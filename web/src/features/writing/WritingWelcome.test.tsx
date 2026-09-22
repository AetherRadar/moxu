// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useLayoutStore } from '@/stores/layout';
import { useWorkspaceStore } from '@/stores/workspace';
import type { ChapterTreeResult } from '@/features/chapters/queries';
import { WritingWelcome } from './WritingWelcome';

let tree: ChapterTreeResult;
vi.mock('@/features/chapters/queries', () => ({ useChapterTree: () => tree }));
vi.mock('@/features/imports/BookImportDialog', () => ({
  BookImportDialog: () => <div role="dialog">导入书稿窗口</div>,
}));

beforeEach(() => {
  tree = {
    volumes: [
      {
        id: 'V1',
        title: '第一卷',
        wordCount: 10,
        chapters: ['V1C1', 'V1C2'].map((id) => ({
          id,
          volumeId: 'V1',
          title: id,
          wordCount: 5,
          analyzed: true,
          orderIndex: null,
        })),
      },
    ],
    chapterIds: ['V1C1', 'V1C2'],
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  };
  useWorkspaceStore.setState({ projectId: 'book', chapter: null, lastChapter: { book: 'V1C1' } });
  useLayoutStore.setState({ sideView: 'outline', sideVisible: true });
});
afterEach(cleanup);
function show() {
  return render(
    <MemoryRouter initialEntries={['/p/book']}>
      <Routes>
        <Route path="/p/:projectId" element={<WritingWelcome projectName="测试作品" />} />
      </Routes>
    </MemoryRouter>,
  );
}

it('continues the last opened chapter instead of the final chapter', () => {
  show();
  fireEvent.click(screen.getByRole('button', { name: '继续写作' }));
  expect(useWorkspaceStore.getState().chapter).toBe('V1C1');
});

it('falls back to the final chapter when the remembered chapter no longer exists', () => {
  useWorkspaceStore.setState({ lastChapter: { book: 'V1C9' } });
  show();
  fireEvent.click(screen.getByRole('button', { name: '继续写作' }));
  expect(useWorkspaceStore.getState().chapter).toBe('V1C2');
});

it('keeps outline open when its welcome action is clicked repeatedly', () => {
  show();
  const button = screen.getByRole('button', { name: /规划故事大纲/ });
  fireEvent.click(button);
  fireEvent.click(button);
  expect(useLayoutStore.getState().sideVisible).toBe(true);
  expect(useLayoutStore.getState().sideView).toBe('outline');
});

it('opens book import directly', async () => {
  show();
  fireEvent.click(screen.getByRole('button', { name: /导入已有书稿/ }));
  expect(await screen.findByRole('dialog')).toBeTruthy();
});

it('does not allow chapter creation from an incomplete tree and offers retry on error', () => {
  tree.isPending = true;
  const view = show();
  expect(screen.getByRole('status')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /创建第一章|新建章节/ })).toBeNull();
  view.unmount();
  tree.isPending = false;
  tree.isError = true;
  show();
  fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
  expect(tree.refetch).toHaveBeenCalledOnce();
  expect(screen.queryByRole('button', { name: /创建第一章|新建章节/ })).toBeNull();
});

it('restores a chapter after the persisted project is reopened', () => {
  useWorkspaceStore.getState().openProject('book');
  expect(useWorkspaceStore.getState().chapter).toBe('V1C1');
});
