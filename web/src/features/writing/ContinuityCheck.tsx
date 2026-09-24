import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { RefreshCw, CheckCheck, CircleStop } from 'lucide-react';
import { sessionApi } from '@/api';
import type { ContinuityPlan } from '@/api/session';
import { Button, Input } from '@/components/ui';
import { awaitDraftWrites } from '@/lib/draft-write-barrier';
import { CausalAuditPanel } from './CausalAuditPanel';

export function ContinuityCheck({
  projectId,
  chapter,
  onReady,
  onCancel,
}: {
  projectId: string;
  chapter: string;
  onReady: () => void;
  onCancel: () => void;
}) {
  const [plan, setPlan] = useState<ContinuityPlan | null>(null);
  const [updatedAt, setUpdatedAt] = useState(0);
  const [tokens, setTokens] = useState('200000');
  const query = useQuery({
    queryKey: ['continuity', projectId, chapter],
    queryFn: async () => {
      await awaitDraftWrites(projectId);
      return sessionApi.continuity(projectId, chapter);
    },
    staleTime: 0,
    retry: false,
    refetchInterval: (q) => (q.state.data?.status === 'running' || plan?.status === 'running' ? 1500 : false),
  });
  const current = query.data && (!plan || query.dataUpdatedAt > updatedAt) ? query.data : plan;
  const accept = (value: ContinuityPlan) => {
    setPlan(value);
    setUpdatedAt(Date.now());
  };
  const sync = useMutation({
    mutationFn: async (next: ContinuityPlan) => {
      await awaitDraftWrites(projectId);
      return sessionApi.syncContinuity(projectId, chapter, {
        revision: next.revision,
        job_id: next.job_id,
        max_tokens: Number(tokens),
        max_minutes: 30,
      });
    },
    onSuccess: accept,
    onError: () => {
      void query.refetch();
    },
  });
  const confirm = useMutation({
    mutationFn: () => sessionApi.acknowledgeContinuity(projectId, chapter, current!.revision),
    onSuccess: accept,
    onError: () => {
      void query.refetch();
    },
  });
  const job = useQuery({
    queryKey: ['continuity-progress', projectId, current?.job_id],
    queryFn: () => sessionApi.analysisJob(projectId, current!.job_id!),
    enabled: Boolean(current?.job_id) && (sync.isPending || current?.status === 'running'),
    refetchInterval: 1500,
    retry: false,
  });
  const cleanRemoved = useMutation({
    mutationFn: () => sessionApi.cleanRemovedContinuity(projectId, chapter, current!.revision),
    onSuccess: accept,
    onError: () => {
      void query.refetch();
    },
  });
  const cancel = useMutation({
    mutationFn: () => sessionApi.cancelAnalysisJob(projectId, current!.job_id!),
  });
  const ready =
    current?.status === 'ready' &&
    current.causal?.ready_for_generation !== false &&
    !query.isFetching &&
    !query.isError;
  useEffect(() => {
    if (ready) onReady();
  }, [ready, onReady]);
  const busy = sync.isPending || confirm.isPending || cleanRemoved.isPending || current?.status === 'running';
  const error = query.error || sync.error || confirm.error || cleanRemoved.error || cancel.error;
  return (
    <section className="flex min-w-0 flex-col gap-3 border-b border-ink-700 pb-4" aria-label="前文同步">
      <h3 className="text-sm font-medium">前文检查</h3>
      {query.isPending && <p role="status">正在检查前文版本…</p>}
      {error && (
        <p role="alert" className="break-words text-sm text-red-400">
          {error instanceof Error ? error.message : '检查或同步未完成，请重新检查后重试。'}
        </p>
      )}
      {!current && query.isError && (
        <Button
          tone="outline"
          icon={<RefreshCw />}
          onClick={() => {
            void query.refetch();
          }}
        >
          重新检查
        </Button>
      )}
      {current && (
        <>
          <p className="text-sm">
            待同步 {current.changed_chapters.length} 章 · 关联待核对 {current.affected_chapters.length} 章
          </p>
          {current.unknown_chapters.length > 0 && (
            <p className="text-xs text-ink-400">缺少入库版本记录：{current.unknown_chapters.length} 章</p>
          )}
          {current.message && (
            <p role="alert" className="text-sm">
              {current.message}
            </p>
          )}
          {current.removed_chapters.length > 0 && (
            <p className="break-words text-sm">
              待清理：{current.removed_chapters.join('、')}
              。清理会移除这些章节的旧事实、时间线、人物状态和摘要，不修改正文。
            </p>
          )}
          <div className="max-h-64 overflow-y-auto break-words text-xs">
            <p>{current.changed_chapters.join('、')}</p>
            {current.deltas.map((delta, index) => (
              <details key={`${delta.chapter}-${index}`} className="border-b border-ink-700 py-2">
                <summary>
                  {delta.chapter} 事实变化 · 关联 {delta.related_chapters.length} 章
                </summary>
                <p className="py-2">
                  关联章节：{delta.related_chapters.join('、') || '未匹配到明确关联，仍需核对隐含影响'}
                </p>
                {delta.coverage === 'unknown_conservative' && <p>影响范围不确定，已扩大待核对范围。</p>}
                <pre className="whitespace-pre-wrap break-all">
                  旧：{JSON.stringify(delta.before, null, 2)}
                  {'\n'}新：{JSON.stringify(delta.after, null, 2)}
                </pre>
                {delta.conflict_check && (
                  <pre className="whitespace-pre-wrap break-all">
                    启发式冲突检查：{JSON.stringify(delta.conflict_check, null, 2)}
                  </pre>
                )}
              </details>
            ))}
          </div>
          {(sync.isPending || current.status === 'running') && (
            <p role="status" className="text-xs">
              同步中 {job.data?.results.filter((row) => row.success).length ?? 0}/
              {current.changed_chapters.length}
            </p>
          )}
          {['needs_sync', 'paused', 'partial'].includes(current.status) && (
            <label className="text-xs">
              累计 Token 预算（0 不限）
              <Input
                type="number"
                min={0}
                max={1000000000}
                value={tokens}
                disabled={busy}
                onChange={(event) => setTokens(event.target.value)}
              />
            </label>
          )}
          <div className="flex flex-wrap gap-2">
            {!busy && (
              <Button tone="outline" onClick={onCancel}>
                返回
              </Button>
            )}
            {!busy && (
              <Button
                tone="outline"
                icon={<RefreshCw />}
                onClick={() => {
                  setPlan(null);
                  void query.refetch();
                }}
              >
                重新检查
              </Button>
            )}
            {['needs_sync', 'paused', 'partial'].includes(current.status) && (
              <Button
                icon={<RefreshCw />}
                loading={sync.isPending}
                disabled={
                  busy ||
                  !Number.isInteger(Number(tokens)) ||
                  Number(tokens) < 0 ||
                  Number(tokens) > 1000000000
                }
                onClick={() => {
                  const next: ContinuityPlan = {
                    ...current,
                    status: 'running',
                    job_id: current.job_id ?? crypto.randomUUID(),
                  };
                  accept(next);
                  sync.mutate(next);
                }}
              >
                同步后继续
              </Button>
            )}
            {current.status === 'needs_review' && (
              <Button
                tone="primary"
                icon={<CheckCheck />}
                loading={confirm.isPending}
                onClick={() => confirm.mutate()}
              >
                已核对变化，继续生成
              </Button>
            )}
            {current.status === 'blocked' && current.removed_chapters.length > 0 && (
              <Button
                tone="danger"
                disabled={busy}
                loading={cleanRemoved.isPending}
                onClick={() => cleanRemoved.mutate()}
              >
                清理已删除章节的旧事实
              </Button>
            )}
            {busy && current.job_id && (
              <Button
                tone="dangerGhost"
                icon={<CircleStop />}
                loading={cancel.isPending}
                onClick={() => cancel.mutate()}
              >
                停止后续调用
              </Button>
            )}
          </div>
          {current.causal && ['needs_causal', 'needs_review'].includes(current.status) && (
            <CausalAuditPanel
              key={`${projectId}-${chapter}`}
              projectId={projectId}
              chapter={chapter}
              initial={current.causal}
              onChanged={() => {
                setPlan(null);
                void query.refetch();
              }}
            />
          )}
        </>
      )}
    </section>
  );
}
