// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { draftsApi, sessionApi } from '@/api';
import { SessionDialog } from './SessionDialog';

vi.mock('@/api', () => ({
  projectScope: (id: string) => ['project', id],
  draftsApi: { checkContinuity: vi.fn() },
  sessionApi: { start: vi.fn(), feedback: vi.fn(), streamDraft: vi.fn(), cancel: vi.fn() },
}));
vi.mock('@/lib/ws', () => ({ subscribeWs: () => () => undefined, wsPath: { session: () => '/ws' } }));
vi.mock('./ContinuityCheck', () => ({
  ContinuityCheck: ({ onReady }: { onReady: () => void }) => <button onClick={onReady}>前文已同步</button>,
}));
const adopted = vi.fn();
const closed = vi.fn();
let client: QueryClient;
const content = '屏幕时间是十三点零四分。';
const report = {
  revision: 'confirmed-revision', source_chapter: 'V1C1', source_hash: 'a', draft_hash: 'b',
  coverage: 'local_rules_only' as const, truncated: false,
  issues: [{ type: 'unverified_clock', severity: 'warning', evidence: '十三点零四分', offset: 5, message: '精确时间待核对。' }],
};
beforeEach(() => {
  vi.mocked(sessionApi.start).mockResolvedValue({
    success: true, status: 'waiting_feedback',
    draft_v1: { chapter: 'V1C2', version: 'current', content, word_count: content.length,
      pending_confirmations: [], created_at: '2026-09-24T11:00:00' },
  });
  vi.mocked(draftsApi.checkContinuity).mockResolvedValue(report);
  vi.mocked(sessionApi.feedback).mockResolvedValue({ success: true, status: 'completed' });
  vi.mocked(sessionApi.cancel).mockResolvedValue({});
});
afterEach(() => { cleanup(); client?.clear(); vi.clearAllMocks(); });
async function draft() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}>
    <SessionDialog projectId="book" chapter="V1C2" chapterTitle="续章" currentContent=""
      onAdopt={adopted} onClose={closed} />
  </QueryClientProvider>);
  fireEvent.click(screen.getByRole('button', { name: '开始写章' }));
  fireEvent.click(screen.getByRole('button', { name: '前文已同步' }));
  await screen.findByRole('button', { name: '存为本章草稿' });
}

it('requires review before adopting and then adopts the unchanged preview', async () => {
  await draft();
  fireEvent.click(screen.getByRole('button', { name: '存为本章草稿' }));
  const confirm = await screen.findByRole('button', { name: '已核对，继续' });
  expect(adopted).not.toHaveBeenCalled();
  fireEvent.click(confirm);
  await waitFor(() => expect(adopted).toHaveBeenCalledWith(content));
  expect(closed).toHaveBeenCalledTimes(1);
});

it('sends the reviewed revision to the finalization endpoint', async () => {
  await draft();
  fireEvent.click(screen.getByRole('button', { name: '定稿归档' }));
  const confirm = await screen.findByRole('button', { name: '已核对，继续' });
  expect(sessionApi.feedback).not.toHaveBeenCalled();
  fireEvent.click(confirm);
  await waitFor(() => expect(sessionApi.feedback).toHaveBeenCalledWith('book',
    expect.objectContaining({ chapter: 'V1C2', action: 'confirm', continuity_revision: report.revision })));
});

it('does not adopt a partial stream after a transport failure', async () => {
  await draft();
  vi.mocked(sessionApi.streamDraft).mockImplementation(async (_project, _chapter, onChunk) => {
    onChunk('只生成了一半的正文。');
    throw new Error('connection closed');
  });
  fireEvent.click(screen.getByRole('button', { name: '重写' }));
  fireEvent.click(screen.getByRole('button', { name: '前文已同步' }));
  await screen.findByText('流式生成未完成，请重新生成后再采纳。');
  expect((screen.getByRole('button', { name: '存为本章草稿' }) as HTMLButtonElement).disabled).toBe(true);
  expect(draftsApi.checkContinuity).not.toHaveBeenCalled();
  expect(adopted).not.toHaveBeenCalled();
});

it('checks completed streaming previews too, without calling finalization', async () => {
  await draft();
  vi.mocked(sessionApi.streamDraft).mockImplementation(async (_project, _chapter, onChunk) => {
    onChunk('新的流式正文13:04。');
  });
  fireEvent.click(screen.getByRole('button', { name: '重写' }));
  fireEvent.click(screen.getByRole('button', { name: '前文已同步' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '存为本章草稿' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: '存为本章草稿' }));
  fireEvent.click(await screen.findByRole('button', { name: '已核对，继续' }));
  await waitFor(() => expect(adopted).toHaveBeenCalledWith('新的流式正文13:04。'));
  expect(sessionApi.feedback).not.toHaveBeenCalled();
});
