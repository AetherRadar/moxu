import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { sessionApi } from '@/api';
import type { ArchiveReport } from '@/api/types';
import { Button } from '@/components/ui';

const reviewLabels: Record<string, string> = {
  character_state_conflict: '人物位置发生变化，请核对是否交代了移动过程；位置变化本身不代表矛盾。',
  fact_conflict: '事实陈述可能不一致，请结合上下文核对。',
  timeline_conflict: '同一时间的事件或地点可能不一致，请核对时间线。',
};
const labels: Record<string, string> = {
  summary: '章节摘要', canon: '事实与人物', volume: '分卷摘要', checks: '正文检查', memory: '归档记录',
};

export function ArchiveDetails({ report }: { report: ArchiveReport }) {
  const issues = report.review?.conflicts ?? [];
  const pending = report.pending_facts ?? [];
  return (
    <div className="space-y-2 text-sm">
      <p>{report.status === 'complete' ? '定稿已归档' : report.status === 'running' ? '定稿归档中…' : '正文已定稿，部分归档待完成'}</p>
      {report.message && <p>{report.message}</p>}
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-400">
        {Object.entries(report.stages ?? {}).map(([key, value]) => (
          <span key={key}>{labels[key] ?? key}：{value.status === 'complete' ? '完成' : value.status === 'running' && report.status === 'running' ? '处理中' : '待重试'}</span>
        ))}
      </div>
      {Object.entries(report.stages ?? {}).filter(([, value]) => value.message).map(([key, value]) => (
        <p key={key} className="text-xs text-ink-300">{value.message}</p>
      ))}
      {(issues.length > 0 || pending.length > 0) && (
        <details>
          <summary className="cursor-pointer text-xs">{issues.length} 项一致性提示 · {pending.length} 条事实待核实</summary>
          <ul className="mt-2 max-h-52 space-y-2 overflow-auto pl-4 text-xs">
            {issues.map((issue, index) => <li key={`issue-${index}`}>{issue.message ?? reviewLabels[issue.type] ?? '发现一项待核对的内容'}{issue.evidence && <blockquote className="mt-1 text-ink-400">“{issue.evidence}”</blockquote>}</li>)}
            {pending.map((fact, index) => <li key={`fact-${index}`}>{fact.statement}<span className="block text-ink-400">{fact.review_reason}；未加入正典。</span></li>)}
          </ul>
        </details>
      )}
      <p className="text-2xs text-ink-500">检查范围：事实启发式与连续页码数量；未覆盖全部情节逻辑。</p>
    </div>
  );
}

export function ArchiveStatus({ projectId, chapter }: { projectId: string; chapter: string }) {
  const client = useQueryClient();
  const queryKey = ['projects', projectId, 'archive', chapter];
  const query = useQuery({
    queryKey,
    queryFn: () => sessionApi.archiveStatus(projectId, chapter),
    refetchInterval: (query) => query.state.data?.status === 'running' ? 3000 : false,
  });
  const retry = useMutation({
    onMutate: () => {
      client.setQueryData<ArchiveReport>(queryKey, (current) => current ? { ...current, status: 'running' } : current);
    },
    mutationFn: () => sessionApi.retryArchive(projectId, chapter),
    onSuccess: (result) => {
      if (result.success === false) toast.error(result.error ?? '归档未完成');
      else if (result.archive?.status === 'complete') toast.success('归档已完成');
      else toast.warning('部分归档仍未完成，可以再次重试');
      void query.refetch();
    },
    onError: () => { toast.error('归档请求中断，请刷新状态后重试'); void query.refetch(); },
  });
  if (!query.data || query.data.status === 'not_run') return null;
  return (
    <aside className="max-h-64 shrink-0 overflow-auto border-b border-amber-200/70 bg-amber-50/60 px-5 py-3" aria-label="定稿归档状态">
      <ArchiveDetails report={query.data} />
      {query.data.status !== 'complete' && (
        <Button tone="outline" size="sm" className="mt-2" loading={retry.isPending || query.data.status === 'running'} onClick={() => retry.mutate()}>
          重试未完成的归档
        </Button>
      )}
    </aside>
  );
}
