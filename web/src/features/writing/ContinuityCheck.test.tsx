// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { sessionApi } from '@/api';
import type { ContinuityPlan } from '@/api/session';
import { ContinuityCheck } from './ContinuityCheck';

vi.mock('@/api', () => ({
  sessionApi: {
    continuity: vi.fn(),
    syncContinuity: vi.fn(),
    acknowledgeContinuity: vi.fn(),
    cleanRemovedContinuity: vi.fn(),
    analysisJob: vi.fn(),
    cancelAnalysisJob: vi.fn(),
    causalStatus: vi.fn(),
    runCausal: vi.fn(),
    cancelCausal: vi.fn(),
    decideCausal: vi.fn(),
  },
}));
const plan: ContinuityPlan = {
  status: 'needs_sync',
  revision: 'source-a',
  changed_chapters: ['V1C1', 'V1C3'],
  unknown_chapters: [],
  removed_chapters: [],
  affected_chapters: [],
  deltas: [],
};
let client: QueryClient;
const ready = vi.fn();
function mount() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ContinuityCheck projectId="book" chapter="V1C5" onReady={ready} onCancel={vi.fn()} />
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  vi.mocked(sessionApi.continuity).mockResolvedValue(plan);
  vi.mocked(sessionApi.analysisJob).mockResolvedValue({
    job_id: 'fixture',
    status: 'running',
    mode: 'preview',
    results: [],
  });
});
afterEach(() => {
  cleanup();
  client?.clear();
  vi.clearAllMocks();
});

it('generates directly when the fresh check reports no changes', async () => {
  vi.mocked(sessionApi.continuity).mockResolvedValue({ ...plan, status: 'ready', changed_chapters: [] });
  mount();
  await waitFor(() => expect(ready).toHaveBeenCalledTimes(1));
  expect(sessionApi.syncContinuity).not.toHaveBeenCalled();
});

it('waits for consent and synchronizes multiple edits in one task', async () => {
  vi.mocked(sessionApi.syncContinuity).mockResolvedValue({ ...plan, status: 'ready', changed_chapters: [] });
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '同步后继续' }));
  await waitFor(() =>
    expect(sessionApi.syncContinuity).toHaveBeenCalledWith(
      'book',
      'V1C5',
      expect.objectContaining({ revision: 'source-a', max_tokens: 200000, job_id: expect.any(String) }),
    ),
  );
  await waitFor(() => expect(ready).toHaveBeenCalledTimes(1));
});

it('does not generate until factual changes have been acknowledged', async () => {
  const review = {
    ...plan,
    status: 'needs_review' as const,
    changed_chapters: [],
    affected_chapters: ['V1C4'],
  };
  vi.mocked(sessionApi.continuity).mockResolvedValue(review);
  vi.mocked(sessionApi.acknowledgeContinuity).mockResolvedValue({ ...review, status: 'ready' });
  mount();
  const confirm = await screen.findByRole('button', { name: '已核对变化，继续生成' });
  expect(ready).not.toHaveBeenCalled();
  fireEvent.click(confirm);
  await waitFor(() => expect(ready).toHaveBeenCalledTimes(1));
  expect(sessionApi.acknowledgeContinuity).toHaveBeenCalledWith('book', 'V1C5', 'source-a');
});

it('blocks generation on check failure and offers retry', async () => {
  vi.mocked(sessionApi.continuity).mockRejectedValue(new Error('保存失败'));
  mount();
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.getByRole('button', { name: '重新检查' })).toBeTruthy();
  expect(ready).not.toHaveBeenCalled();
});

it('restores the persistent task id for budget-resume', async () => {
  vi.mocked(sessionApi.continuity).mockResolvedValue({ ...plan, status: 'paused', job_id: 'persistent-job' });
  vi.mocked(sessionApi.syncContinuity).mockResolvedValue({
    ...plan,
    status: 'paused',
    job_id: 'persistent-job',
  });
  mount();
  fireEvent.change(await screen.findByRole('spinbutton'), { target: { value: '400000' } });
  fireEvent.click(screen.getByRole('button', { name: '同步后继续' }));
  await waitFor(() =>
    expect(sessionApi.syncContinuity).toHaveBeenCalledWith(
      'book',
      'V1C5',
      expect.objectContaining({ job_id: 'persistent-job', max_tokens: 400000 }),
    ),
  );
  expect(ready).not.toHaveBeenCalled();
});

it('requires explicit cleanup for removed chapters', async () => {
  const blocked = { ...plan, status: 'blocked' as const, changed_chapters: [], removed_chapters: ['V1C1'] };
  vi.mocked(sessionApi.continuity).mockResolvedValue(blocked);
  vi.mocked(sessionApi.cleanRemovedContinuity).mockResolvedValue({ ...blocked, status: 'needs_review' });
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '清理已删除章节的旧事实' }));
  await waitFor(() =>
    expect(sessionApi.cleanRemovedContinuity).toHaveBeenCalledWith('book', 'V1C5', 'source-a'),
  );
  expect(ready).not.toHaveBeenCalled();
});

it('does not bypass an unfinished causal audit even if a response says ready', async () => {
  vi.mocked(sessionApi.continuity).mockResolvedValue({
    ...plan,
    status: 'ready',
    changed_chapters: [],
    causal: {
      status: 'paused',
      revision: 'a',
      fresh: true,
      ready_for_generation: false,
      total_chapters: 4,
      unresolved: 0,
      findings: [],
    },
  });
  mount();
  await screen.findByText('待同步 0 章 · 关联待核对 0 章');
  expect(ready).not.toHaveBeenCalled();
});

it('continues automatically only after a completed causal audit and a fresh continuity check', async () => {
  const causal = {
    status: 'not_started' as const,
    revision: 'a',
    fresh: false,
    ready_for_generation: false,
    total_chapters: 4,
    unresolved: 0,
    findings: [],
  };
  vi.mocked(sessionApi.continuity).mockResolvedValue({
    ...plan,
    status: 'needs_causal',
    changed_chapters: [],
    causal,
  });
  vi.mocked(sessionApi.causalStatus).mockResolvedValue(causal);
  vi.mocked(sessionApi.runCausal).mockImplementation(async () => {
    const completed = { ...causal, status: 'complete' as const, fresh: true, ready_for_generation: true };
    vi.mocked(sessionApi.continuity).mockResolvedValue({
      ...plan,
      status: 'ready',
      changed_chapters: [],
      causal: completed,
    });
    vi.mocked(sessionApi.causalStatus).mockResolvedValue(completed);
    return completed;
  });
  mount();
  const start = await screen.findByRole('button', { name: '开始因果核查' });
  await waitFor(() => expect((start as HTMLButtonElement).disabled).toBe(false));
  expect(ready).not.toHaveBeenCalled();
  fireEvent.click(start);
  await waitFor(() => expect(ready).toHaveBeenCalledTimes(1));
  expect(sessionApi.acknowledgeContinuity).not.toHaveBeenCalled();
});
