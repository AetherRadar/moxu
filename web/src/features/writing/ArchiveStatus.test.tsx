// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { sessionApi } from '@/api';
import type { ArchiveReport } from '@/api/types';
import { ArchiveDetails, ArchiveStatus } from './ArchiveStatus';

vi.mock('@/api', () => ({ sessionApi: { archiveStatus: vi.fn(), retryArchive: vi.fn() } }));
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn(), warning: vi.fn() } }));
afterEach(() => { cleanup(); vi.clearAllMocks(); });
const partial: ArchiveReport = {
  status: 'partial',
  stages: { summary: { status: 'complete' }, canon: { status: 'failed', message: '事实抽取未完成，不能据此判断无冲突' } },
  pending_facts: [{ statement: '有人故意制造骗保假象', review_reason: '原文仅为推测' }],
  review: { status: 'partial', conflicts: [{ type: 'page_count_mismatch', message: '页码之间应有3页', evidence: '143到147之间缺了一页' }] },
};

describe('chapter archive status', () => {
  it('distinguishes saved prose from incomplete archiving and shows evidence', () => {
    render(<ArchiveDetails report={partial} />);
    expect(screen.getByText('正文已定稿，部分归档待完成')).toBeTruthy();
    expect(screen.getByText('事实抽取未完成，不能据此判断无冲突')).toBeTruthy();
    expect(screen.getByText('1 项一致性提示 · 1 条事实待核实')).toBeTruthy();
    expect(screen.getByText('“143到147之间缺了一页”')).toBeTruthy();
    expect(screen.getByText('原文仅为推测；未加入正典。')).toBeTruthy();
  });
  it('loads persisted failure after remount and refreshes it after retry', async () => {
    vi.mocked(sessionApi.archiveStatus).mockResolvedValueOnce(partial).mockResolvedValue({ status: 'complete' });
    vi.mocked(sessionApi.retryArchive).mockResolvedValue({ success: true, archive: { status: 'complete' } });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><ArchiveStatus projectId="book" chapter="V1C2" /></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: '重试未完成的归档' }));
    await waitFor(() => expect(sessionApi.retryArchive).toHaveBeenCalledWith('book', 'V1C2'));
    expect(await screen.findByText('定稿已归档')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '重试未完成的归档' })).toBeNull();
    client.clear();
  });
});
