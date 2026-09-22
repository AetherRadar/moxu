// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { FactsPanel } from './FactsPanel';

/**
 * 正典树是虚拟化长列表：jsdom 没有真实布局，measure 全是 0，
 * 把 useVirtualizer stub 成"返回全部行"来测试展开/折叠/搜索逻辑。
 */
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count }: { count: number }) => ({
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({
        index,
        key: index,
        start: index * 32,
        size: 32,
      })),
    getTotalSize: () => count * 32,
    measureElement: () => undefined,
  }),
}));

const TREE = {
  volumes: [
    {
      id: 'V1',
      title: '起卷',
      summary: '',
      chapters: [
        {
          id: 'V1C1',
          title: '序章',
          summary: '下山',
          facts: [{ statement: '女主前世是剑修', title: '身世' }],
        },
        {
          id: 'V1C2',
          title: '入城',
          summary: '',
          facts: [{ statement: '男主在城门摆摊' }],
        },
      ],
    },
  ],
};

vi.mock('@/features/cards/queries', () => ({
  useFactsTree: () => ({ isPending: false, data: TREE }),
  useFacts: () => ({ isPending: false, data: [] }),
  useTimeline: () => ({ isPending: false, data: { events: [] } }),
  useCharacterStates: () => ({ isPending: false, data: {} }),
  useCreateTimelineEvent: () => ({ mutate: vi.fn() }),
  useUpdateTimelineEvent: () => ({ mutate: vi.fn() }),
  useDeleteTimelineEvent: () => ({ mutate: vi.fn() }),
  useDeleteFact: () => ({ mutate: vi.fn() }),
}));

vi.mock('@/features/chapters/queries', () => ({
  useChapterTree: () => ({ volumes: [], isPending: false }),
}));

function renderPanel(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/projects/p1']}>
        <Routes>
          <Route path="/projects/:projectId" element={<FactsPanel />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FactsPanel 正典树', () => {
  it('renders volume/chapter/fact rows', () => {
    renderPanel();
    expect(screen.getByText('序章')).toBeTruthy();
    expect(screen.getByText('女主前世是剑修')).toBeTruthy();
    expect(screen.getByText('男主在城门摆摊')).toBeTruthy();
  });

  it('collapses a chapter to hide its facts', () => {
    renderPanel();
    fireEvent.click(screen.getByText('序章'));
    expect(screen.queryByText('女主前世是剑修')).toBeNull();
    // 另一章不受影响
    expect(screen.getByText('男主在城门摆摊')).toBeTruthy();
    // 再点展开
    fireEvent.click(screen.getByText('序章'));
    expect(screen.getByText('女主前世是剑修')).toBeTruthy();
  });

  it('collapsing a volume hides its whole subtree', () => {
    renderPanel();
    fireEvent.click(screen.getByText('第一卷 · 起卷'));
    expect(screen.queryByText('序章')).toBeNull();
    expect(screen.queryByText('女主前世是剑修')).toBeNull();
  });

  it('search filters facts and keeps only matching branches', () => {
    renderPanel();
    fireEvent.change(screen.getByPlaceholderText('搜事实/章节…'), {
      target: { value: '剑修' },
    });
    expect(screen.getByText('女主前世是剑修')).toBeTruthy();
    expect(screen.queryByText('入城')).toBeNull();
  });
});
