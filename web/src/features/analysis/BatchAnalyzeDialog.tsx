import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CircleStop, FileScan, Play, RotateCcw } from 'lucide-react';
import { projectScope, sessionApi } from '@/api';
import type { AnalysisJob } from '@/api/session';
import { ApiError } from '@/lib/http';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, Dialog } from '@/components/ui';

export interface BatchChapter {
  id: string;
  title: string;
}
interface SavedJob {
  id: string;
  combined: boolean;
  sync: boolean;
  bookSummary: boolean;
  tokens?: number;
  minutes?: number;
  calls?: number;
}
function readJob(key: string): SavedJob | null {
  try {
    const data = JSON.parse(localStorage.getItem(key) ?? 'null') as SavedJob | null;
    return data &&
      typeof data.id === 'string' &&
      /^[0-9a-f-]{36}$/.test(data.id) &&
      typeof data.combined === 'boolean' &&
      typeof data.sync === 'boolean' &&
      typeof data.bookSummary === 'boolean'
      ? data
      : null;
  } catch {
    return null;
  }
}
const pauseLabels: Record<string, string> = {
  token_budget: 'Token 预算不足',
  time_budget: '已到时限',
  chapter_call_budget: '已到单章调用上限',
  cancelled: '已取消',
  interrupted: '任务中断',
};

export function BatchAnalyzeDialog({
  projectId,
  chapters,
  scopeLabel,
  onClose,
}: {
  projectId: string;
  chapters: BatchChapter[];
  scopeLabel: string;
  onClose: () => void;
}): ReactNode {
  const client = useQueryClient();
  const storageKey = `moxu-analysis:${projectId}:${chapters
    .map((ch) => ch.id)
    .sort()
    .join(',')}`;
  const [saved, setSaved] = useState(() => readJob(storageKey));
  const [combined, setCombined] = useState(saved?.combined ?? false);
  const [sync, setSync] = useState(saved?.sync ?? false);
  const [bookSummary, setBookSummary] = useState(saved?.bookSummary ?? false);
  const [tokens, setTokens] = useState(saved?.tokens ?? 200000);
  const [minutes, setMinutes] = useState(saved?.minutes ?? 30);
  const [calls, setCalls] = useState(saved?.calls ?? 6);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const abort = useRef<AbortController | null>(null);
  useEffect(() => () => abort.current?.abort(), []);
  const queryKey = ['analysis-job', projectId, saved?.id];
  const query = useQuery({
    queryKey,
    queryFn: () => sessionApi.analysisJob(projectId, saved!.id),
    enabled: saved !== null,
    retry: false,
    refetchInterval: (q) => (q.state.data?.status === 'running' ? 1500 : false),
  });
  const job = query.data;
  const resultRows = new Map((job?.results ?? []).map((row) => [row.chapter, row]));
  const run = useMutation({
    mutationFn: (next: SavedJob) => {
      const controller = new AbortController();
      abort.current = controller;
      const body = {
        job_id: next.id,
        chapters: chapters.map((ch) => ch.id),
        combined: next.combined,
        bulk_mode: true,
        book_summary: next.bookSummary,
        max_tokens: tokens,
        max_minutes: minutes,
        max_calls_per_chapter: calls,
        force: false,
      };
      return next.sync
        ? sessionApi.analyzeSync(projectId, body, controller.signal)
        : sessionApi.analyzeBatch(projectId, body, controller.signal);
    },
    onSuccess: (data, next) => {
      client.setQueryData(['analysis-job', projectId, next.id], data);
      setPicked(new Set(data.results.filter((row) => row.success && row.analysis).map((row) => row.chapter)));
      if (next.sync) void client.invalidateQueries({ queryKey: projectScope(projectId) });
      if (data.status === 'complete') toast.success('分析完成');
      else if (data.status === 'paused') toast(pauseLabels[data.pause_reason ?? ''] ?? '任务已暂停');
      else toast.warning('部分步骤未完成，可继续重试');
    },
    onError: (error, next) => {
      toast.error(error instanceof ApiError ? error.message : '请求中断，已完成结果保留在任务中');
      client.setQueryData<AnalysisJob>(['analysis-job', projectId, next.id], (current) =>
        current ? { ...current, status: 'paused', pause_reason: 'interrupted' } : current,
      );
      void query.refetch();
    },
  });
  const cancel = useMutation({
    mutationFn: () => sessionApi.cancelAnalysisJob(projectId, saved!.id),
    onSuccess: () => {
      toast('已请求停止后续调用');
      void query.refetch();
    },
    onError: () => toast.error('取消请求失败，请重试'),
  });
  const save = useMutation({
    mutationFn: () =>
      sessionApi.saveAnalysisBatch(projectId, {
        overwrite: true,
        items: (job?.results ?? [])
          .filter((row) => picked.has(row.chapter) && row.analysis)
          .map((row) => ({ chapter: row.chapter, analysis: row.analysis })),
      }),
    onSuccess: (result) => {
      void client.invalidateQueries({ queryKey: projectScope(projectId) });
      if (result.success === false) toast.error(String(result.error ?? '部分入库失败，可重试'));
      else {
        toast.success('已归档选中的分析');
        onClose();
      }
    },
    onError: (error) => toast.error(error instanceof ApiError ? error.message : '入库失败'),
  });
  const busy = run.isPending || job?.status === 'running';
  const resumable = saved !== null && (job?.status === 'paused' || job?.status === 'partial');
  const sameMode = saved?.combined === combined && saved.sync === sync && saved.bookSummary === bookSummary;
  const validBudget =
    Number.isInteger(tokens) &&
    tokens >= 0 &&
    tokens <= 1000000000 &&
    Number.isInteger(minutes) &&
    minutes >= 1 &&
    minutes <= 1440 &&
    Number.isInteger(calls) &&
    calls >= 1 &&
    calls <= 20;
  const start = (resume: boolean): void => {
    const next: SavedJob = {
      ...(resume && saved ? saved : { id: crypto.randomUUID(), combined, sync, bookSummary }),
      tokens,
      minutes,
      calls,
    };
    setSaved(next);
    localStorage.setItem(storageKey, JSON.stringify(next));
    setPicked(new Set());
    client.setQueryData<AnalysisJob>(['analysis-job', projectId, next.id], {
      job_id: next.id,
      status: 'running',
      mode: next.sync ? 'sync' : 'preview',
      results: [],
    });
    run.mutate(next);
  };
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={`批量分析 · ${scopeLabel}`}
      size="lg"
      dismissible={!busy && !save.isPending}
      footer={
        busy ? (
          <Button
            tone="dangerGhost"
            icon={<CircleStop />}
            loading={cancel.isPending}
            onClick={() => cancel.mutate()}
          >
            停止后续调用
          </Button>
        ) : (
          <div className="flex flex-wrap justify-end gap-2">
            <Button tone="outline" disabled={save.isPending} onClick={onClose}>
              关闭
            </Button>
            {resumable && (
              <Button
                tone="outline"
                icon={<RotateCcw />}
                disabled={!validBudget || !sameMode || save.isPending}
                title="继续任务保留原分析模式"
                onClick={() => start(true)}
              >
                继续任务
              </Button>
            )}
            <Button
              tone="primary"
              icon={<Play />}
              disabled={!validBudget || chapters.length === 0 || save.isPending}
              onClick={() => start(false)}
            >
              {job ? '重新检查' : '开始分析'}
            </Button>
            {job?.mode === 'preview' && (
              <Button
                tone="primary"
                icon={<FileScan />}
                loading={save.isPending}
                disabled={picked.size === 0}
                onClick={() => save.mutate()}
              >
                归档选中 {picked.size} 章
              </Button>
            )}
          </div>
        )
      }
    >
      <div className="flex flex-col gap-4">
        <fieldset disabled={busy || save.isPending} className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <label className="grid gap-1 text-xs">
            Token 预算（0 不限）
            <input
              aria-label="Token 预算"
              type="number"
              min={0}
              max={1000000000}
              step={1000}
              value={tokens}
              onChange={(e) => setTokens(Number(e.target.value))}
              className="min-w-0 rounded border border-ink-700 bg-ink-900 p-2"
            />
          </label>
          <label className="grid gap-1 text-xs">
            累计时限（分钟）
            <input
              aria-label="累计时限"
              type="number"
              min={1}
              max={1440}
              value={minutes}
              onChange={(e) => setMinutes(Number(e.target.value))}
              className="min-w-0 rounded border border-ink-700 bg-ink-900 p-2"
            />
          </label>
          <label className="grid gap-1 text-xs">
            单章调用上限
            <input
              aria-label="单章调用上限"
              type="number"
              min={1}
              max={20}
              value={calls}
              onChange={(e) => setCalls(Number(e.target.value))}
              className="min-w-0 rounded border border-ink-700 bg-ink-900 p-2"
            />
          </label>
          <label className="flex items-center gap-2 text-xs">
            <input type="checkbox" checked={combined} onChange={(e) => setCombined(e.target.checked)} />
            合并抽取（试验）
          </label>
          <label className="flex items-center gap-2 text-xs">
            <input type="checkbox" checked={sync} onChange={(e) => setSync(e.target.checked)} />
            分析后覆盖入库
          </label>
          <label className="flex items-center gap-2 text-xs">
            <input type="checkbox" checked={bookSummary} onChange={(e) => setBookSummary(e.target.checked)} />
            生成范围梗概
          </label>
        </fieldset>
        {job && (
          <div className="flex flex-wrap items-center gap-2 text-xs text-ink-400">
            <Badge tone={busy ? 'warning' : job.status === 'complete' ? 'success' : 'neutral'}>
              {busy
                ? '进行中'
                : job.status === 'complete'
                  ? '完成'
                  : job.status === 'paused'
                    ? '已暂停'
                    : '部分完成'}
            </Badge>
            <span>
              {job.results.filter((row) => row.success).length}/{chapters.length} 章
            </span>
            {job.metrics && (
              <span>
                预算记账 {job.metrics.budget_tokens.toLocaleString()} token · 本次{' '}
                {job.metrics.calls_this_run} 次调用
              </span>
            )}
            {job.pause_reason && <span>{pauseLabels[job.pause_reason] ?? job.pause_reason}</span>}
            {job.reduce_error && <span role="alert">{job.reduce_error}</span>}
          </div>
        )}
        <div className="max-h-[38vh] space-y-1 overflow-y-auto">
          {chapters.map((chapter) => {
            const row = resultRows.get(chapter.id);
            return (
              <div
                key={chapter.id}
                className="flex min-w-0 items-center gap-2 border-b border-ink-800 py-2 text-xs"
              >
                {row?.success && row.analysis && (
                  <input
                    type="checkbox"
                    aria-label={`选择 ${chapter.title || chapter.id}`}
                    disabled={busy || save.isPending}
                    checked={picked.has(chapter.id)}
                    onChange={() =>
                      setPicked((current) => {
                        const next = new Set(current);
                        if (next.has(chapter.id)) next.delete(chapter.id);
                        else next.add(chapter.id);
                        return next;
                      })
                    }
                  />
                )}
                <span className="shrink-0 text-ink-500">{formatChapterLabel(chapter.id)}</span>
                <span className="min-w-0 flex-1 truncate">{chapter.title || '未命名'}</span>
                <span className="max-w-[45%] text-right text-ink-400">
                  {!row ? '等待' : row.success ? (row.skipped ? '已复用' : '完成') : row.error || '失败'}
                </span>
              </div>
            );
          })}
        </div>
        {job?.synopsis?.brief_summary && (
          <section className="border-t border-ink-800 pt-3">
            <h3 className="mb-2 text-sm">范围梗概 · 待核对</h3>
            <p className="whitespace-pre-wrap text-sm leading-7">{job.synopsis.brief_summary}</p>
          </section>
        )}
      </div>
    </Dialog>
  );
}
