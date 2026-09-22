// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useWorkspaceStore } from '@/stores/workspace';
import type { ChapterTreeResult } from './queries';
import { CreateChapterDialog } from './CreateChapterDialog';

const { createVolume, createChapter } = vi.hoisted(() => ({ createVolume: vi.fn(), createChapter: vi.fn() }));
vi.mock('./queries', () => ({
  useCreateVolume: () => ({ mutateAsync: createVolume }),
  useCreateChapter: () => ({ mutateAsync: createChapter }),
}));
beforeEach(() => {
  vi.resetAllMocks();
  useWorkspaceStore.setState({ projectId: 'book', chapter: null, expandedVolumes: [] });
  createVolume.mockResolvedValue({ id: 'V1' });
  createChapter.mockResolvedValue('V1C1');
});
afterEach(cleanup);
const empty: ChapterTreeResult = {
  volumes: [],
  chapterIds: [],
  isPending: false,
  isError: false,
  refetch: () => {},
};

it('locks creation and dismissal while a request is pending', async () => {
  let finish!: (value: string) => void;
  createChapter.mockReturnValue(
    new Promise<string>((resolve) => {
      finish = resolve;
    }),
  );
  const close = vi.fn();
  render(<CreateChapterDialog projectId="book" tree={empty} defaultVolumeId={undefined} onClose={close} />);
  const submit = screen.getByRole('button', { name: '创建并写作' });
  fireEvent.click(submit);
  fireEvent.submit(submit.closest('form')!);
  await waitFor(() => expect(createChapter).toHaveBeenCalledOnce());
  expect((submit as HTMLButtonElement).disabled).toBe(true);
  fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
  expect(close).not.toHaveBeenCalled();
  await act(async () => finish('V1C1'));
  expect(close).toHaveBeenCalledOnce();
  expect(useWorkspaceStore.getState().chapter).toBe('V1C1');
});

it('keeps the title and reuses the newly created volume when retrying a chapter failure', async () => {
  createChapter.mockRejectedValueOnce(new Error('offline'));
  render(
    <CreateChapterDialog projectId="book" tree={empty} defaultVolumeId={undefined} onClose={() => {}} />,
  );
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '雨夜' } });
  fireEvent.click(screen.getByRole('button', { name: '创建并写作' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect((screen.getByRole('textbox') as HTMLInputElement).value).toBe('雨夜');
  fireEvent.click(screen.getByRole('button', { name: '创建并写作' }));
  await waitFor(() => expect(useWorkspaceStore.getState().chapter).toBe('V1C1'));
  expect(createVolume).toHaveBeenCalledOnce();
  expect(createChapter).toHaveBeenLastCalledWith({ volumeId: 'V1', title: '雨夜' });
});

it('creates in the explicitly selected volume and expands it', async () => {
  const tree = {
    ...empty,
    volumes: ['V1', 'V2'].map((id) => ({ id, title: id, chapters: [], wordCount: 0 })),
  };
  createChapter.mockResolvedValue('V2C1');
  render(<CreateChapterDialog projectId="book" tree={tree} defaultVolumeId="V1" onClose={() => {}} />);
  fireEvent.change(screen.getByRole('combobox'), { target: { value: 'V2' } });
  fireEvent.click(screen.getByRole('button', { name: '创建并写作' }));
  await waitFor(() => expect(useWorkspaceStore.getState().chapter).toBe('V2C1'));
  expect(useWorkspaceStore.getState().expandedVolumes).toContain('V2');
  expect(createVolume).not.toHaveBeenCalled();
  expect(createChapter).toHaveBeenCalledWith({ volumeId: 'V2', title: '' });
});
