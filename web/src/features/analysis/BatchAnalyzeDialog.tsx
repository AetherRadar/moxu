import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CircleStop, FileScan, RotateCcw } from 'lucide-react';
import { projectScope, sessionApi } from '@/api';
import type { JsonRecord } from '@/api/types';
import { AbortedError, ApiError } from '@/lib/http';
import { subscribeWs, wsPath, type WsEnvelope } from '@/lib/ws';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, Dialog, EmptyState } from '@/components/ui';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export interface BatchChapter {
  id: string;
  title: string;
}

// SYNC_THRESHOLD: at most a few chapters go through analyze-sync (analyze + overwrite-save
// in one call); anything larger runs analyze-batch first so results can be reviewed
// before save-analysis-batch writes them.
const SYNC_THRESHOLD = 3;

type RowState = 'pending' | 'done' | 'failed';

interface BatchResult {
  chapter: string;
  success: boolean;
  error?: string;
  analysis?: JsonRecord;
}

/**
 * Batch chapter analysis (docs/TODO_token_efficiency.md): one analyze-batch request walks
 * the volume/book server-side (30min timeout). The dialog stays interactive: the trace WS
 * feed shows live agent activity, 取消 aborts the fetch (the backend call ends with the
 * dropped connection), and failed chapters can be retried without redoing the rest.
 */
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
  const abortRef = useRef<AbortController | null>(null);

  const [rows, setRows] = useState<Map<string, RowState>>(
    () => new Map(chapters.map((chapter) => [chapter.id, 'pending'])),
  );
  const [results, setResults] = useState<BatchResult[] | null>(null);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [activity, setActivity] = useState('');
  // 打开即开跑：初值就是 batch，mount effect 只负责发起外部请求
  const [running, setRunning] = useState<'idle' | 'batch' | 'saving'>('batch');

  // Live activity line: batch analysis is one long request, so per-chapter granularity
  // only lands with the response; the trace feed is the in-flight signal.
  useEffect(() => {
    return subscribeWs(wsPath.trace(), {
      onMessage: (batch: WsEnvelope[]) => {
        const last = batch[batch.length - 1];
        const message = last?.message ?? last?.stage;
        if (typeof message === 'string' && message !== '') setActivity(message);
      },
    });
  }, [projectId]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const markRows = (list: BatchResult[]): void => {
    setRows((current) => {
      const next = new Map(current);
      for (const item of list) next.set(item.chapter, item.success ? 'done' : 'failed');
      return next;
    });
  };
  const runBatch = useMutation({
    mutationFn: (ids: string[]) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      return sessionApi.analyzeBatch(projectId, { chapters: ids }, controller.signal);
    },
    onSuccess: (data) => {
      const list = (Array.isArray(data.results) ? data.results : []) as BatchResult[];
      markRows(list);
      setResults((current) => {
        const merged = new Map((current ?? []).map((item) => [item.chapter, item]));
        for (const item of list) merged.set(item.chapter, item);
        return [...merged.values()];
      });
      setPicked((current) => {
        const next = new Set(current);
        for (const item of list) if (item.success) next.add(item.chapter);
        return next;
      });
      setRunning('idle');
      const failed = list.filter((item) => !item.success).length;
      if (failed > 0) toast.error(failed + ' 章分析失败，可单独重试');
    },
    onError: (error) => {
      setRunning('idle');
      if (error instanceof AbortedError) return;
      toast.error(reason(error, '批量分析中断，可重试'));
    },
  });

  const runSync = useMutation({
    mutationFn: (ids: string[]) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      return sessionApi.analyzeSync(projectId, { chapters: ids }, controller.signal);
    },
    onSuccess: (data) => {
      const list = (Array.isArray(data.results) ? data.results : []) as BatchResult[];
      markRows(list);
      setResults(list.map((item) => ({ ...item, analysis: undefined })));
      setRunning('idle');
      void client.invalidateQueries({ queryKey: projectScope(projectId) });
      const failed = list.filter((item) => !item.success).length;
      if (failed > 0) toast.error(failed + ' 章同步分析失败');
      else toast.success('已同步分析并入库 ' + list.length + ' 章');
    },
    onError: (error) => {
      setRunning('idle');
      if (error instanceof AbortedError) return;
      toast.error(reason(error, '同步分析中断，可重试'));
    },
  });

  const save = useMutation({
    mutationFn: () => {
      const items = (results ?? [])
        .filter((item) => item.success && picked.has(item.chapter) && item.analysis !== undefined)
        .map((item) => ({ chapter: item.chapter, analysis: item.analysis }));
      return sessionApi.saveAnalysisBatch(projectId, { items, overwrite: true });
    },
    onSuccess: (data) => {
      if (data.success === false) {
        toast.error(String(data.error ?? '入库失败'));
        setRunning('idle');
        return;
      }
      void client.invalidateQueries({ queryKey: projectScope(projectId) });
      toast.success('已归档 ' + picked.size + ' 章分析');
      onClose();
    },
    onError: (error) => {
      setRunning('idle');
      toast.error(reason(error, '入库失败'));
    },
  });

  const ids = useMemo(() => chapters.map((chapter) => chapter.id), [chapters]);
  const useSync = ids.length <= SYNC_THRESHOLD;

  const retryFailed = (): void => {
    const failed = ids.filter((id) => rows.get(id) === 'failed');
    if (failed.length === 0) return;
    setRunning('batch');
    runBatch.mutate(failed);
  };

  const doneCount = [...rows.values()].filter((state) => state === 'done').length;
  const failedCount = [...rows.values()].filter((state) => state === 'failed').length;
  const busy = running !== 'idle';
  // Start on open; closing mid-run aborts the in-flight request.
  useEffect(() => {
    if (useSync) runSync.mutate(ids);
    else runBatch.mutate(ids);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const cancel = (): void => {
    abortRef.current?.abort();
    setRunning('idle');
    toast('已取消，后端已收到的章节不受影响');
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={'批量分析 · ' + scopeLabel}
      description={
        useSync
          ? ids.length + ' 章走同步分析：逐章分析并直接覆盖入库。'
          : ids.length + ' 章先批量分析，勾选确认后再统一入库（覆盖写）。'
      }
      size="lg"
      dismissible={!busy}
      footer={
        busy ? (
          <Button tone="dangerGhost" icon={<CircleStop />} onClick={cancel}>
            取消
          </Button>
        ) : (
          <>
            {failedCount > 0 && !useSync && (
              <Button tone="outline" icon={<RotateCcw />} onClick={retryFailed}>
                重试失败的 {failedCount} 章
              </Button>
            )}
            <Button tone="outline" onClick={onClose}>
              关闭
            </Button>
            {!useSync && results !== null && (
              <Button
                tone="primary"
                icon={<FileScan />}
                loading={save.isPending}
                disabled={picked.size === 0}
                onClick={() => {
                  setRunning('saving');
                  save.mutate();
                }}
              >
                归档选中的 {picked.size} 章
              </Button>
            )}
          </>
        )
      }
    >
      <div className="flex flex-col gap-2.5">
        <div className="flex items-center gap-2 text-2xs text-ink-500">
          <Badge tone={busy ? 'warning' : failedCount > 0 ? 'danger' : 'success'}>
            {busy ? '进行中' : failedCount > 0 ? '有失败' : '完成'}
          </Badge>
          <span className="tnum">
            {doneCount}/{ids.length} 完成{failedCount > 0 ? ' · ' + failedCount + ' 失败' : ''}
          </span>
        </div>
        {busy && activity !== '' && <p className="truncate text-2xs text-ink-600">{activity}</p>}
        <div className="flex max-h-[46vh] flex-col gap-1 overflow-y-auto">
          {chapters.map((chapter) => (
            <BatchRow
              key={chapter.id}
              chapter={chapter}
              state={rows.get(chapter.id) ?? 'pending'}
              error={results?.find((item) => item.chapter === chapter.id)?.error}
              selectable={!useSync && results !== null}
              checked={picked.has(chapter.id)}
              onToggle={() =>
                setPicked((current) => {
                  const next = new Set(current);
                  if (next.has(chapter.id)) next.delete(chapter.id);
                  else next.add(chapter.id);
                  return next;
                })
              }
            />
          ))}
          {chapters.length === 0 && (
            <EmptyState title="没有可分析的章节" description="这个范围里还没有带正文的章节。" />
          )}
        </div>
        {busy && (
          <p className="text-2xs leading-relaxed text-ink-600">
            后端逐章顺序处理，整卷可能跑几分钟到半小时；对话框可关，取消只中断等待，不影响已完成的章节。
          </p>
        )}
      </div>
    </Dialog>
  );
}

function BatchRow({
  chapter,
  state,
  error,
  selectable,
  checked,
  onToggle,
}: {
  chapter: BatchChapter;
  state: RowState;
  error?: string | undefined;
  selectable: boolean;
  checked: boolean;
  onToggle: () => void;
}): ReactNode {
  return (
    <div className="flex items-center gap-2 rounded border border-ink-800 px-2.5 py-1.5">
      {selectable && state === 'done' ? (
        <input type="checkbox" checked={checked} onChange={onToggle} className="accent-celadon-500" />
      ) : null}
      <span className="tnum shrink-0 text-2xs text-ink-500">{formatChapterLabel(chapter.id)}</span>
      <span className="min-w-0 flex-1 truncate text-xs text-ink-300">
        {chapter.title === '' ? '未命名' : chapter.title}
      </span>
      {state === 'pending' && <Badge tone="neutral">等待</Badge>}
      {state === 'done' && <Badge tone="success">完成</Badge>}
      {state === 'failed' && (
        <span className="truncate text-2xs text-cinnabar-400" title={error ?? ''}>
          {error ?? '失败'}
        </span>
      )}
    </div>
  );
}
