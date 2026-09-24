// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { draftsApi } from '@/api';
import type { DraftContinuityReport } from '@/api/drafts';
import { DraftContinuityReview } from './DraftContinuityReview';

vi.mock('@/api', () => ({ draftsApi: { checkContinuity: vi.fn(), checkSemantics: vi.fn() } }));
const ready = vi.fn();
const cancel = vi.fn();
const report: DraftContinuityReport = {
  revision: 'revision-a', source_chapter: 'V1C1', source_hash: 'source-a', draft_hash: 'draft-a',
  coverage: 'local_rules_only', truncated: false,
  issues: [{
    type: 'unverified_clock', severity: 'warning', evidence: '十三点零四分', offset: 3,
    message: '前章正文未出现这一精确时刻；请核对。',
  }],
};
let client: QueryClient;
function mount() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}>
    <DraftContinuityReview projectId="book" chapter="V1C2" content="这是一份草稿。"
      onReady={ready} onCancel={cancel} />
  </QueryClientProvider>);
}
beforeEach(() => { vi.mocked(draftsApi.checkContinuity).mockResolvedValue(report); });
afterEach(() => { cleanup(); client?.clear(); vi.clearAllMocks(); });

it('does not silently approve a warning and rechecks on confirmation', async () => {
  mount();
  const button = await screen.findByRole('button', { name: '已核对，继续' });
  expect(ready).not.toHaveBeenCalled();
  expect(screen.getByText('十三点零四分')).toBeTruthy();
  fireEvent.click(button);
  await waitFor(() => expect(ready).toHaveBeenCalledWith('revision-a'));
  expect(draftsApi.checkContinuity).toHaveBeenCalledTimes(2);
});

it('automatically continues once when the local review has no findings', async () => {
  vi.mocked(draftsApi.checkContinuity).mockResolvedValue({ ...report, issues: [] });
  mount();
  await waitFor(() => expect(ready).toHaveBeenCalledTimes(1));
});

it('does not reuse approval after source changes', async () => {
  mount();
  const button = await screen.findByRole('button', { name: '已核对，继续' });
  vi.mocked(draftsApi.checkContinuity).mockResolvedValue({ ...report, revision: 'revision-b' });
  fireEvent.click(button);
  await screen.findByText('前文已变化，请重新核对以下提示。');
  expect(ready).not.toHaveBeenCalled();
  await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(button);
  await waitFor(() => expect(ready).toHaveBeenCalledWith('revision-b'));
});

it('blocks approval if the initial check fails and permits retry', async () => {
  vi.mocked(draftsApi.checkContinuity).mockRejectedValueOnce(new Error('offline'));
  mount();
  const retry = await screen.findByRole('button', { name: '重新检查' });
  expect(ready).not.toHaveBeenCalled();
  fireEvent.click(retry);
  await screen.findByRole('button', { name: '已核对，继续' });
  expect(ready).not.toHaveBeenCalled();
});

it('does not approve if the confirmation recheck fails', async () => {
  mount();
  const button = await screen.findByRole('button', { name: '已核对，继续' });
  vi.mocked(draftsApi.checkContinuity).mockRejectedValue(new Error('source unreadable'));
  fireEvent.click(button);
  await screen.findByRole('button', { name: '重新检查' });
  expect(ready).not.toHaveBeenCalled();
});

it('ignores a confirmation response arriving after the review closes', async () => {
  const view = mount();
  const button = await screen.findByRole('button', { name: '已核对，继续' });
  let resolve!: (value: DraftContinuityReport) => void;
  vi.mocked(draftsApi.checkContinuity).mockImplementation(() => new Promise((done) => { resolve = done; }));
  fireEvent.click(button);
  await waitFor(() => expect(resolve).toBeDefined());
  view.unmount();
  resolve(report);
  await new Promise((done) => setTimeout(done, 10));
  expect(ready).not.toHaveBeenCalled();
});

it('makes truncated checks visible instead of implying that only thirty issues exist', async () => {
  vi.mocked(draftsApi.checkContinuity).mockResolvedValue({ ...report, truncated: true });
  mount();
  await screen.findByText('仅显示前 30 项，仍有其他待核实项。');
  fireEvent.click(screen.getByRole('button', { name: '返回草稿' }));
  expect(cancel).toHaveBeenCalledTimes(1);
  expect(ready).not.toHaveBeenCalled();
});

it('does not render an empty source-evidence heading', async () => {
  vi.mocked(draftsApi.checkContinuity).mockResolvedValue({ ...report, reference_excerpts: '\n  ' });
  mount();
  await screen.findByRole('button', { name: '已核对，继续' });
  expect(screen.queryByText(/前文原句/)).toBeNull();
});

it('only starts paid semantic review after an explicit click and sends the budget and revision', async () => {
  vi.mocked(draftsApi.checkContinuity).mockResolvedValue({ ...report, semantic_status: 'unchecked' });
  vi.mocked(draftsApi.checkSemantics).mockResolvedValue({
    ...report, revision: 'semantic-b', semantic_status: 'complete', coverage: 'local_and_semantic', issues: [],
  });
  mount();
  const button = await screen.findByRole('button', { name: '语义核查' });
  expect(draftsApi.checkSemantics).not.toHaveBeenCalled();
  expect(ready).not.toHaveBeenCalled();
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Token 上限' }), { target: { value: '30000' } });
  fireEvent.click(button);
  await waitFor(() => expect(draftsApi.checkSemantics).toHaveBeenCalledWith(
    'book', 'V1C2', '这是一份草稿。', 'revision-a', 30000,
  ));
  await waitFor(() => expect(ready).toHaveBeenCalledWith('semantic-b'));
});

it('requires explicit human confirmation after a partial semantic result', async () => {
  vi.mocked(draftsApi.checkContinuity).mockResolvedValue({ ...report, semantic_status: 'unchecked' });
  vi.mocked(draftsApi.checkSemantics).mockResolvedValue({
    ...report, revision: 'partial-b', semantic_status: 'partial',
  });
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '语义核查' }));
  await screen.findByText('语义核查未完成');
  expect(ready).not.toHaveBeenCalled();
  vi.mocked(draftsApi.checkContinuity).mockResolvedValue({
    ...report, revision: 'partial-b', semantic_status: 'partial',
  });
  fireEvent.click(screen.getByRole('button', { name: '人工确认，继续' }));
  await waitFor(() => expect(ready).toHaveBeenCalledWith('partial-b'));
});

it('does not approve when semantic review fails and disables invalid budgets', async () => {
  vi.mocked(draftsApi.checkContinuity).mockResolvedValue({ ...report, semantic_status: 'unchecked' });
  vi.mocked(draftsApi.checkSemantics).mockRejectedValue(new Error('前文因果提取未同步'));
  mount();
  const button = await screen.findByRole('button', { name: '语义核查' });
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Token 上限' }), { target: { value: '0' } });
  expect((button as HTMLButtonElement).disabled).toBe(true);
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Token 上限' }), { target: { value: '60000' } });
  fireEvent.click(button);
  await screen.findByText('前文因果提取未同步');
  expect(ready).not.toHaveBeenCalled();
});
