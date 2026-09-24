// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { sessionApi } from '@/api';
import type { CausalReport } from '@/api/session';
import { CausalAuditPanel } from './CausalAuditPanel';

vi.mock('@/api', () => ({
  sessionApi: { causalStatus: vi.fn(), runCausal: vi.fn(), cancelCausal: vi.fn(), decideCausal: vi.fn() },
}));
const initial: CausalReport = {
  revision: 'source-a',
  status: 'not_started',
  fresh: false,
  ready_for_generation: false,
  total_chapters: 200,
  unresolved: 0,
  findings: [],
};
const reviewed: CausalReport = {
  ...initial,
  status: 'complete',
  fresh: true,
  unresolved: 1,
  findings: [
    {
      id: 'finding-a',
      chapter: 'V1C200',
      classification: 'supported_conflict',
      explanation: '前提与已有状态不符',
      prior: { chapter: 'V1C1', sourceHash: 'a', start: 0, end: 3, quote: '钥匙已丢失' },
      current: { chapter: 'V1C200', sourceHash: 'b', start: 0, end: 3, quote: '他取出了钥匙' },
      dependency_path: [{ id: 'event-a', chapter: 'V1C1', label: '丢失钥匙', quote: '钥匙已丢失' }],
    },
  ],
};
const changed = vi.fn();
let client: QueryClient;
function mount(report = initial) {
  vi.mocked(sessionApi.causalStatus).mockResolvedValue(report);
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CausalAuditPanel projectId="book" chapter="V1C201" initial={report} onChanged={changed} />
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  vi.mocked(sessionApi.runCausal).mockResolvedValue({
    ...initial,
    status: 'paused',
    pause_reason: 'token_budget',
  });
});
afterEach(() => {
  cleanup();
  client?.clear();
  vi.resetAllMocks();
});

it('requires consent before the first paid full-book audit', async () => {
  mount();
  const start = await screen.findByRole('button', { name: '开始因果核查' });
  await waitFor(() => expect((start as HTMLButtonElement).disabled).toBe(false));
  expect(sessionApi.runCausal).not.toHaveBeenCalled();
  fireEvent.click(start);
  await waitFor(() =>
    expect(sessionApi.runCausal).toHaveBeenCalledWith('book', 'V1C201', {
      revision: 'source-a',
      max_tokens: 200000,
      max_minutes: 30,
    }),
  );
  expect(await screen.findByText('Token 预算已用尽')).toBeTruthy();
});

it('resumes using the source revision and increased cumulative budgets', async () => {
  mount({ ...initial, status: 'paused', job_id: 'persisted', max_tokens: 100000, max_minutes: 10 });
  const resume = await screen.findByRole('button', { name: '恢复因果核查' });
  await waitFor(() => expect((resume as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText('累计 Token 预算（0 不限）'), { target: { value: '400000' } });
  fireEvent.change(screen.getByLabelText('累计时间预算（分钟）'), { target: { value: '60' } });
  fireEvent.click(resume);
  await waitFor(() =>
    expect(sessionApi.runCausal).toHaveBeenCalledWith('book', 'V1C201', {
      revision: 'source-a',
      max_tokens: 400000,
      max_minutes: 60,
    }),
  );
});

it('shows literal evidence and requires a note to mark a false positive', async () => {
  mount(reviewed);
  const button = await screen.findByRole('button', { name: '标记为误报' });
  expect((button as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByText('他取出了钥匙')).toBeTruthy();
  expect(screen.getByText('上游关联事件')).toBeTruthy();
  vi.mocked(sessionApi.decideCausal).mockResolvedValue({
    ...reviewed,
    unresolved: 0,
    ready_for_generation: true,
    decisions: { 'finding-a': { resolution: 'false_positive', note: '下一段解释了备用钥匙' } },
  });
  fireEvent.change(screen.getByLabelText('核对依据'), { target: { value: '下一段解释了备用钥匙' } });
  await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(button);
  await waitFor(() =>
    expect(sessionApi.decideCausal).toHaveBeenCalledWith('book', 'V1C201', {
      revision: 'source-a',
      finding_id: 'finding-a',
      resolution: 'false_positive',
      note: '下一段解释了备用钥匙',
    }),
  );
  await waitFor(() => expect(changed).toHaveBeenCalled());
});

it('accepts one risk explicitly without silently acknowledging other findings', async () => {
  mount(reviewed);
  vi.mocked(sessionApi.decideCausal).mockResolvedValue({
    ...reviewed,
    unresolved: 0,
    ready_for_generation: true,
    decisions: { 'finding-a': { resolution: 'accepted_risk', note: '' } },
  });
  const button = await screen.findByRole('button', { name: '接受此项风险' });
  await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(button);
  await waitFor(() =>
    expect(sessionApi.decideCausal).toHaveBeenCalledWith('book', 'V1C201', {
      revision: 'source-a',
      finding_id: 'finding-a',
      resolution: 'accepted_risk',
      note: '',
    }),
  );
});

it('reopens a resolved finding from the full list', async () => {
  mount({
    ...reviewed,
    unresolved: 0,
    ready_for_generation: true,
    decisions: { 'finding-a': { resolution: 'accepted_risk', note: 'intentional' } },
  });
  vi.mocked(sessionApi.decideCausal).mockResolvedValue(reviewed);
  fireEvent.change(screen.getByLabelText('问题范围'), { target: { value: 'all' } });
  const button = await screen.findByRole('button', { name: '重新核对' });
  await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(button);
  await waitFor(() =>
    expect(sessionApi.decideCausal).toHaveBeenCalledWith('book', 'V1C201', {
      revision: 'source-a',
      finding_id: 'finding-a',
      resolution: 'reopen',
      note: '',
    }),
  );
});

it('does not allow decisions on partial reports or after a check error', async () => {
  mount({ ...reviewed, status: 'partial' });
  const button = await screen.findByRole('button', { name: '接受此项风险' });
  expect((button as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(button);
  expect(sessionApi.decideCausal).not.toHaveBeenCalled();
  vi.mocked(sessionApi.causalStatus).mockRejectedValue(new Error('版本校验失败'));
  fireEvent.click(screen.getByRole('button', { name: '刷新报告' }));
  expect(await screen.findByText('版本校验失败')).toBeTruthy();
  expect((screen.getByRole('button', { name: '恢复因果核查' }) as HTMLButtonElement).disabled).toBe(true);
});

it('stops subsequent calls for a running persistent audit', async () => {
  mount({ ...initial, status: 'running', job_id: 'persistent' });
  vi.mocked(sessionApi.cancelCausal).mockResolvedValue({
    ...initial,
    status: 'paused',
    pause_reason: 'cancelled',
  });
  fireEvent.click(await screen.findByRole('button', { name: '停止后续核查' }));
  await waitFor(() => expect(sessionApi.cancelCausal).toHaveBeenCalledWith('book', 'V1C201'));
  expect(await screen.findByText('已停止后续调用')).toBeTruthy();
});
