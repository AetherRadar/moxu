import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCheck, CircleStop, RefreshCw, RotateCcw, Search } from 'lucide-react';
import { sessionApi } from '@/api';
import type { CausalFinding, CausalReport } from '@/api/session';
import { Button, Input } from '@/components/ui';
import { awaitDraftWrites } from '@/lib/draft-write-barrier';

const states: Record<CausalReport['status'], string> = {
  not_started: '尚未核查',
  stale: '正文已变化',
  running: '核查中',
  paused: '已暂停',
  partial: '未完成',
  complete: '核查完成',
};
const classifications = {
  supported_conflict: '有证据的疑似冲突',
  explained: '已有解释',
  insufficient_evidence: '证据不足',
};

export function CausalAuditPanel({
  projectId,
  chapter,
  initial,
  onChanged,
}: {
  projectId: string;
  chapter: string;
  initial: CausalReport;
  onChanged: () => void;
}) {
  const client = useQueryClient();
  const key = ['causal-audit', projectId, chapter];
  const [tokens, setTokens] = useState(String(initial.max_tokens ?? 200000));
  const [minutes, setMinutes] = useState(String(initial.max_minutes ?? 30));
  const [filter, setFilter] = useState('open');
  const query = useQuery({
    queryKey: key,
    queryFn: async () => {
      await awaitDraftWrites(projectId);
      return sessionApi.causalStatus(projectId, chapter);
    },
    initialData: initial,
    staleTime: 0,
    retry: false,
    refetchInterval: (q) => (q.state.data?.status === 'running' || q.state.data?.active ? 1500 : false),
  });
  const report = query.data;
  const notified = useRef('');
  useEffect(() => {
    if (report.ready_for_generation && notified.current !== report.revision) {
      notified.current = report.revision;
      onChanged();
    }
  }, [report.ready_for_generation, report.revision, onChanged]);
  const receive = (value: CausalReport) => {
    client.setQueryData(key, value);
    if (value.status !== 'running') onChanged();
  };
  const run = useMutation({
    mutationFn: async () => {
      await awaitDraftWrites(projectId);
      return sessionApi.runCausal(projectId, chapter, {
        revision: report.revision,
        max_tokens: Number(tokens),
        max_minutes: Number(minutes),
      });
    },
    onMutate: () => {
      client.setQueryData(key, { ...report, status: 'running', ready_for_generation: false });
    },
    onSuccess: receive,
    onError: () => {
      void query.refetch();
      onChanged();
    },
  });
  const cancel = useMutation({
    mutationFn: () => sessionApi.cancelCausal(projectId, chapter),
    onSuccess: receive,
  });
  const decision = useMutation({
    mutationFn: async ({ id, resolution, note }: { id: string; resolution: string; note: string }) => {
      await awaitDraftWrites(projectId);
      return sessionApi.decideCausal(projectId, chapter, {
        revision: report.revision,
        finding_id: id,
        resolution,
        note,
      });
    },
    onSuccess: receive,
    onError: () => {
      void query.refetch();
      onChanged();
    },
  });
  const busy = run.isPending || report.status === 'running' || Boolean(report.active) || decision.isPending;
  const error = query.error || run.error || cancel.error || decision.error;
  const findings = report.findings.filter(
    (f) => filter === 'all' || (f.classification !== 'explained' && !report.decisions?.[f.id]),
  );
  const validBudget =
    tokens.trim() !== '' &&
    minutes.trim() !== '' &&
    Number.isInteger(Number(tokens)) &&
    Number(tokens) >= 0 &&
    Number(tokens) <= 1000000000 &&
    Number.isInteger(Number(minutes)) &&
    Number(minutes) >= 1 &&
    Number(minutes) <= 1440;
  return (
    <section aria-label="因果核查" className="min-w-0 space-y-3 border-t border-ink-700 pt-3">
      <div className="flex flex-wrap justify-between gap-2 text-sm">
        <h4 className="font-medium">全书因果核查</h4>
        <span role="status">
          {states[report.status]} · 待处理 {report.unresolved}
        </span>
      </div>
      <p className="text-xs text-ink-400">
        范围：已有正文 {report.total_chapters} 章。首次建索引会产生模型费用。
      </p>
      {report.status === 'complete' && (
        <p className="text-xs text-ink-400">核查完成不代表所有隐含因果均已识别。</p>
      )}
      {error && (
        <p role="alert" className="break-words text-sm text-red-400">
          {error instanceof Error ? error.message : '核查未完成，请重试。'}
        </p>
      )}
      {(report.error || report.pause_reason) && (
        <p role="alert" className="text-xs">
          {report.error ||
            ({ token_budget: 'Token 预算已用尽', time_budget: '时间预算已用尽', cancelled: '已停止后续调用' }[
              report.pause_reason!
            ] ??
              '任务已暂停')}
        </p>
      )}
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
        <div>
          <dt className="inline">已索引 </dt>
          <dd className="inline">
            {report.completed_chapters ?? 0}/{report.total_chapters} 章
          </dd>
        </div>
        <div>
          <dt className="inline">疑点复核 </dt>
          <dd className="inline">
            {report.checked_candidates ?? 0}/{report.total_candidates ?? 0}
          </dd>
        </div>
        <div>
          <dt className="inline">事件 / 依赖 </dt>
          <dd className="inline">
            {report.event_count ?? 0} / {report.dependency_count ?? 0}
          </dd>
        </div>
        <div>
          <dt className="inline">受影响 / 复用事件 </dt>
          <dd className="inline">{report.impacted_events ?? 0} / {report.reused_events ?? 0}</dd>
        </div>
        <div>
          <dt className="inline">复用片段 </dt>
          <dd className="inline">{report.cached_segments ?? 0}</dd>
        </div>
        <div>
          <dt className="inline">已计费或预留 Token </dt>
          <dd className="inline">{report.metrics?.budget_tokens ?? 0}</dd>
        </div>
      </dl>
      {report.status === 'running' && (
        <progress
          aria-label="章节索引进度"
          className="w-full"
          max={Math.max(1, report.total_chapters)}
          value={report.completed_chapters ?? 0}
        />
      )}
      {report.status !== 'complete' && (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <label className="text-xs">
            累计 Token 预算（0 不限）
            <Input
              type="number"
              min={0}
              max={1000000000}
              value={tokens}
              disabled={busy}
              onChange={(e) => setTokens(e.target.value)}
            />
          </label>
          <label className="text-xs">
            累计时间预算（分钟）
            <Input
              type="number"
              min={1}
              max={1440}
              value={minutes}
              disabled={busy}
              onChange={(e) => setMinutes(e.target.value)}
            />
          </label>
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        {report.status !== 'complete' && report.status !== 'running' && (
          <Button
            icon={<Search />}
            disabled={busy || !validBudget || query.isFetching || query.isError}
            onClick={() => run.mutate()}
          >
            {['paused', 'partial'].includes(report.status) ? '恢复因果核查' : '开始因果核查'}
          </Button>
        )}
        {(run.isPending || report.status === 'running' || report.active) && (
          <Button
            tone="dangerGhost"
            icon={<CircleStop />}
            loading={cancel.isPending}
            onClick={() => cancel.mutate()}
          >
            停止后续核查
          </Button>
        )}
        <Button
          tone="outline"
          icon={<RefreshCw />}
          disabled={decision.isPending}
          onClick={() => {
            void query.refetch();
            onChanged();
          }}
        >
          刷新报告
        </Button>
      </div>
      {report.findings.length > 0 && (
        <>
          <label className="flex items-center gap-2 text-xs">
            问题范围
            <select
              aria-label="问题范围"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              className="min-w-0 rounded border border-ink-700 bg-ink-900 p-1"
            >
              <option value="open">待处理</option>
              <option value="all">全部</option>
            </select>
          </label>
          <div className="max-h-96 overflow-y-auto">
            {findings.map((finding) => (
              <Finding
                key={`${report.revision}-${finding.id}`}
                finding={finding}
                resolution={report.decisions?.[finding.id]}
                disabled={
                  busy || query.isFetching || query.isError || !report.fresh || report.status !== 'complete'
                }
                onDecide={(resolution, note) => decision.mutate({ id: finding.id, resolution, note })}
              />
            ))}
          </div>
        </>
      )}
    </section>
  );
}

function Finding({
  finding,
  resolution,
  disabled,
  onDecide,
}: {
  finding: CausalFinding;
  resolution?: { resolution: 'accepted_risk' | 'false_positive'; note: string };
  disabled: boolean;
  onDecide: (resolution: string, note: string) => void;
}) {
  const [note, setNote] = useState(resolution?.note ?? '');
  return (
    <article className="min-w-0 space-y-2 break-words border-b border-ink-700 py-3 text-xs">
      <h5 className="font-medium">
        {finding.chapter} · {classifications[finding.classification]}
      </h5>
      <p>{finding.explanation}</p>
      {finding.uncertainties?.map((text, i) => (
        <p key={i}>{text}</p>
      ))}
      {[finding.prior, finding.current]
        .filter((e) => e !== undefined && e !== null)
        .map((e, i) => (
          <blockquote key={i} className="whitespace-pre-wrap border-l-2 border-ink-600 pl-2">
            <span className="text-ink-400">
              {e.chapter} · 字符 {e.start}–{e.end}
            </span>
            <p>{e.quote}</p>
          </blockquote>
        ))}
      {finding.dependency_path && finding.dependency_path.length > 0 && (
        <details>
          <summary className="cursor-pointer">上游关联事件</summary>
          <ol className="space-y-1 py-2">
            {finding.dependency_path.map((n) => (
              <li key={n.id}>
                {n.chapter} · {n.label}
                <p>{n.quote}</p>
              </li>
            ))}
          </ol>
        </details>
      )}
      {resolution && (
        <p>
          {resolution.resolution === 'false_positive' ? '已标记误报' : '已接受风险'}：{resolution.note}
        </p>
      )}
      {finding.classification !== 'explained' && (
        <>
          {!resolution && (
            <label className="block">
              核对依据
              <Input
                value={note}
                maxLength={1000}
                disabled={disabled}
                onChange={(e) => setNote(e.target.value)}
              />
            </label>
          )}
          <div className="flex flex-wrap gap-2">
            {resolution ? (
              <Button
                tone="outline"
                icon={<RotateCcw />}
                disabled={disabled}
                onClick={() => onDecide('reopen', '')}
              >
                重新核对
              </Button>
            ) : (
              <>
                <Button
                  tone="outline"
                  icon={<CheckCheck />}
                  disabled={disabled}
                  onClick={() => onDecide('accepted_risk', note)}
                >
                  接受此项风险
                </Button>
                <Button
                  tone="outline"
                  icon={<CheckCheck />}
                  disabled={disabled || !note.trim()}
                  onClick={() => onDecide('false_positive', note)}
                >
                  标记为误报
                </Button>
              </>
            )}
          </div>
        </>
      )}
    </article>
  );
}
