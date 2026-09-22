// @vitest-environment jsdom
import { forwardRef, useImperativeHandle, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/http';
import { useWorkspaceStore } from '@/stores/workspace';
import { WritingArea } from './WritingArea';
import type { UseQueryResult, UseMutationResult } from '@tanstack/react-query';
import type { Draft } from '@/api/types';

/**
 * 回归：draft 从 pending 转到 loaded 时不能崩。
 * 历史上 setDoc 的 useEffect 写在 isPending/isError 早退之后，
 * 那次渲染多一个 hook，React 直接抛 #310 卸载整棵树。
 */

interface DraftQueryShape {
  isPending: boolean;
  isError?: boolean;
  error?: unknown;
  data?: Draft;
  refetch?: () => void;
}

let draftResult: DraftQueryShape;

vi.mock('@/features/writing/queries', () => ({
  useDraft: () => draftResult as unknown as UseQueryResult<Draft>,
  useAutosaveDraft: () =>
    ({ mutate: vi.fn(), isPending: false }) as unknown as UseMutationResult<unknown>,
}));

vi.mock('@/features/chapters/queries', () => ({
  useChapterTree: () => ({
    volumes: [
      {
        id: 'V1',
        title: '第一卷',
        chapters: [{ id: 'V1C1', title: '序章', wordCount: 12, analyzed: true }],
      },
    ],
    isPending: false,
    refetch: vi.fn(),
  }),
}));

// CodeMirror 在 jsdom 里跑不全（measure/scroll API 缺失），stub 成命令式句柄
vi.mock('@/features/editor/CodeMirrorEditor', () => {
  let doc = '';
  const CodeMirrorEditor = forwardRef(function StubEditor(
    _props: unknown,
    ref: React.Ref<unknown>,
  ): ReactNode {
    useImperativeHandle(ref, () => ({
      getDoc: () => doc,
      setDoc: (text: string) => {
        doc = text;
      },
      append: (text: string) => {
        doc += text;
      },
      selection: () => ({ from: 0, to: 0, text: '' }),
      replaceRange: () => undefined,
      focus: () => undefined,
    }));
    return <div data-testid="cm-stub" />;
  });
  return { CodeMirrorEditor };
});

// draftsApi.final / autosave 走真实 http 会出网，直接替换整个 api 面
vi.mock('@/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api')>();
  return {
    ...original,
    draftsApi: {
      ...original.draftsApi,
      final: () =>
        Promise.reject(new ApiError('not found', 404, '/drafts/x/final', undefined)),
      autosave: () => Promise.resolve({}),
    },
  };
});

function renderEditor(): ReturnType<typeof render> {
  useWorkspaceStore.setState({ projectId: 'p1', chapter: 'V1C1' });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/projects/p1']}>
        <Routes>
          <Route path="/projects/:projectId" element={<WritingArea />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WritingArea', () => {
  it('survives the pending → loaded transition without hook-order crash', async () => {
    draftResult = { isPending: true };
    const view = renderEditor();
    expect(screen.queryByTestId('cm-stub')).toBeNull();

    draftResult = {
      isPending: false,
      data: { chapter: 'V1C1', content: '第一章正文', version: 'v3' } as Draft,
    };
    view.rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/projects/p1']}>
          <Routes>
            <Route path="/projects/:projectId" element={<WritingArea />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // 渲染期 setState 调整后应出现编辑器与标题输入框
    expect(await screen.findByTestId('cm-stub')).toBeTruthy();
    expect(screen.getByLabelText('章节标题')).toBeTruthy();
  });

  it('renders an empty editor for a 404 (new chapter) without crashing', async () => {
    draftResult = {
      isPending: false,
      isError: true,
      error: new ApiError('not found', 404, '/drafts/V1C1', undefined),
    };
    renderEditor();
    expect(await screen.findByTestId('cm-stub')).toBeTruthy();
  });
});
