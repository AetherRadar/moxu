// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionApi } from '@/api';
import type { AnalysisJob } from '@/api/session';
import { BatchAnalyzeDialog } from './BatchAnalyzeDialog';

vi.mock('@/api', () => ({
  projectScope: (id: string) => ['projects', id],
  sessionApi: {
    analyzeBatch: vi.fn(),
    analyzeSync: vi.fn(),
    analysisJob: vi.fn(),
    cancelAnalysisJob: vi.fn(),
    saveAnalysisBatch: vi.fn(),
  },
}));
vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn(), success: vi.fn(), warning: vi.fn() }),
}));
const id = '11111111-1111-4111-8111-111111111111';
const chapters = [{ id: 'V1C1', title: 'First' }];
const paused: AnalysisJob = {
  job_id: id,
  mode: 'preview',
  status: 'paused',
  pause_reason: 'token_budget',
  results: [],
};
let client: QueryClient;
function mount() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <BatchAnalyzeDialog projectId="book" chapters={chapters} scopeLabel="First volume" onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  localStorage.clear();
  vi.spyOn(crypto, 'randomUUID').mockReturnValue(id);
  vi.mocked(sessionApi.analysisJob).mockResolvedValue(paused);
  vi.mocked(sessionApi.analyzeBatch).mockResolvedValue(paused);
});
afterEach(() => {
  cleanup();
  client?.clear();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe('durable chapter analysis', () => {
  it('waits for explicit start and defaults to preview even for one chapter', async () => {
    mount();
    expect(sessionApi.analyzeBatch).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Token 预算'), { target: { value: '10000' } });
    fireEvent.click(screen.getByRole('button', { name: '开始分析' }));
    await waitFor(() =>
      expect(sessionApi.analyzeBatch).toHaveBeenCalledWith(
        'book',
        expect.objectContaining({
          job_id: id,
          chapters: ['V1C1'],
          max_tokens: 10000,
          combined: false,
        }),
        expect.any(AbortSignal),
      ),
    );
    expect(sessionApi.analyzeSync).not.toHaveBeenCalled();
    expect(await screen.findByText('Token 预算不足')).toBeTruthy();
  });
  it('restores a paused job after remount and resumes its id with a larger budget', async () => {
    localStorage.setItem(
      'moxu-analysis:book:V1C1',
      JSON.stringify({
        id,
        combined: true,
        sync: false,
        bookSummary: false,
        tokens: 12000,
        minutes: 12,
        calls: 4,
      }),
    );
    mount();
    await screen.findByRole('button', { name: '继续任务' });
    expect((screen.getByLabelText('Token 预算') as HTMLInputElement).value).toBe('12000');
    expect((screen.getByLabelText('累计时限') as HTMLInputElement).value).toBe('12');
    expect((screen.getByLabelText('单章调用上限') as HTMLInputElement).value).toBe('4');
    fireEvent.change(screen.getByLabelText('Token 预算'), { target: { value: '900000' } });
    fireEvent.click(screen.getByRole('button', { name: '继续任务' }));
    await waitFor(() =>
      expect(sessionApi.analyzeBatch).toHaveBeenCalledWith(
        'book',
        expect.objectContaining({
          job_id: id,
          combined: true,
          max_tokens: 900000,
          max_minutes: 12,
          max_calls_per_chapter: 4,
        }),
        expect.any(AbortSignal),
      ),
    );
  });
  it('requests server cancellation instead of only abandoning the HTTP request', async () => {
    vi.mocked(sessionApi.analyzeBatch).mockImplementation(() => new Promise(() => undefined));
    vi.mocked(sessionApi.analysisJob).mockResolvedValue({ ...paused, status: 'running' });
    vi.mocked(sessionApi.cancelAnalysisJob).mockResolvedValue(paused);
    mount();
    fireEvent.click(screen.getByRole('button', { name: '开始分析' }));
    fireEvent.click(await screen.findByRole('button', { name: '停止后续调用' }));
    await waitFor(() => expect(sessionApi.cancelAnalysisJob).toHaveBeenCalledWith('book', id));
  });
  it('keeps a failed start retryable and rejects invalid budgets', async () => {
    vi.mocked(sessionApi.analyzeBatch).mockRejectedValue(new Error('offline'));
    vi.mocked(sessionApi.analysisJob).mockRejectedValue(new Error('not found'));
    mount();
    fireEvent.change(screen.getByLabelText('累计时限'), { target: { value: '-1' } });
    expect((screen.getByRole('button', { name: '开始分析' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('累计时限'), { target: { value: '30' } });
    fireEvent.click(screen.getByRole('button', { name: '开始分析' }));
    await waitFor(() =>
      expect((screen.getByRole('button', { name: '重新检查' }) as HTMLButtonElement).disabled).toBe(false),
    );
  });
  it('saves only selected recovered candidates', async () => {
    const completed: AnalysisJob = {
      ...paused,
      status: 'complete',
      results: [{ chapter: 'V1C1', success: true, skipped: true, analysis: { summary: {} } }],
    };
    vi.mocked(sessionApi.analyzeBatch).mockResolvedValue(completed);
    vi.mocked(sessionApi.analysisJob).mockResolvedValue(completed);
    vi.mocked(sessionApi.saveAnalysisBatch).mockResolvedValue({ success: true });
    mount();
    fireEvent.click(screen.getByRole('button', { name: '开始分析' }));
    fireEvent.click(await screen.findByRole('button', { name: '归档选中 1 章' }));
    await waitFor(() =>
      expect(sessionApi.saveAnalysisBatch).toHaveBeenCalledWith('book', {
        overwrite: true,
        items: [{ chapter: 'V1C1', analysis: { summary: {} } }],
      }),
    );
  });
});
