import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { FileScan } from 'lucide-react';
import { projectScope, sessionApi } from '@/api';
import type { JsonRecord } from '@/api/types';
import { AbortedError, ApiError } from '@/lib/http';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, Dialog, EmptyState, SkeletonList } from '@/components/ui';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

interface AnalysisPayload extends JsonRecord {
  summary?: JsonRecord;
  facts?: JsonRecord[];
  timeline_events?: JsonRecord[];
  character_states?: JsonRecord[];
}

function candidateLabel(item: JsonRecord, keys: string[]): string {
  for (const key of keys) {
    const value = item[key];
    if (typeof value === 'string' && value.trim() !== '') return value;
  }
  return JSON.stringify(item);
}

/**
 * Manual chapter analysis: run the archivist pass over current content, review the
 * produced summary/facts/timeline/character-state candidates, then archive the kept
 * subset via save-analysis (overwrite). Same data as the end-of-session auto archive.
 */
export function AnalyzeChapterDialog({
  projectId,
  chapter,
  onClose,
}: {
  projectId: string;
  chapter: string;
  onClose: () => void;
}): ReactNode {
  const client = useQueryClient();
  const abortRef = useRef<AbortController | null>(null);

  const [analysis, setAnalysis] = useState<AnalysisPayload | null>(null);
  const [dropped, setDropped] = useState<Set<string>>(new Set());

  const analyze = useMutation({
    mutationFn: () => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      return sessionApi.analyze(projectId, { chapter }, controller.signal);
    },
    onSuccess: (data) => {
      if (data.success === false) {
        toast.error(String(data.error ?? '分析失败'));
        return;
      }
      setAnalysis((data.analysis as AnalysisPayload | undefined) ?? {});
      setDropped(new Set());
    },
    onError: (error) => {
      if (error instanceof AbortedError) return;
      toast.error(reason(error, '分析失败，请确认本章已有正文'));
    },
  });

  // Run on open; aborting on close drops the request and the backend LLM call ends with it.
  useEffect(() => {
    analyze.mutate();
    return () => abortRef.current?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const facts = useMemo(() => (Array.isArray(analysis?.facts) ? analysis.facts : []), [analysis]);
  const timeline = useMemo(
    () => (Array.isArray(analysis?.timeline_events) ? analysis.timeline_events : []),
    [analysis],
  );
  const states = useMemo(
    () => (Array.isArray(analysis?.character_states) ? analysis.character_states : []),
    [analysis],
  );
  const summary = analysis?.summary;

  const toggle = (key: string): void => {
    setDropped((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const keptCounts = {
    summary: summary !== undefined && summary !== null && !dropped.has('summary') ? 1 : 0,
    facts: facts.filter((_, index) => !dropped.has(`fact:${index}`)).length,
    timeline: timeline.filter((_, index) => !dropped.has(`timeline:${index}`)).length,
    states: states.filter((_, index) => !dropped.has(`state:${index}`)).length,
  };

  const save = useMutation({
    mutationFn: () => {
      const filtered: AnalysisPayload = { ...(analysis ?? {}) };
      if (keptCounts.summary === 0) delete filtered.summary;
      filtered.facts = facts.filter((_, index) => !dropped.has(`fact:${index}`));
      filtered.timeline_events = timeline.filter((_, index) => !dropped.has(`timeline:${index}`));
      filtered.character_states = states.filter((_, index) => !dropped.has(`state:${index}`));
      return sessionApi.saveAnalysis(projectId, {
        chapter,
        analysis: filtered,
        overwrite: true,
      });
    },
    onSuccess: (data) => {
      if (data.success === false) {
        toast.error(String(data.error ?? '入库失败'));
        return;
      }
      void client.invalidateQueries({ queryKey: projectScope(projectId) });
      toast.success(formatChapterLabel(chapter) + ' 分析已归档');
      onClose();
    },
    onError: (error) => toast.error(reason(error, '入库失败')),
  });
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={'分析本章 · ' + formatChapterLabel(chapter)}
      description="模型产出的是候选：勾掉的条目不会入库；确认后覆盖本章既有分析。"
      size="lg"
      dismissible={!analyze.isPending && !save.isPending}
      footer={
        analysis === null ? (
          <Button tone="outline" onClick={onClose} disabled={analyze.isPending}>
            取消
          </Button>
        ) : (
          <>
            <Button
              tone="outline"
              onClick={() => {
                setAnalysis(null);
                analyze.mutate();
              }}
              disabled={save.isPending}
            >
              重新分析
            </Button>
            <Button tone="primary" icon={<FileScan />} loading={save.isPending} onClick={() => save.mutate()}>
              归档选中项（摘要 {keptCounts.summary} · 事实 {keptCounts.facts} · 时间线 {keptCounts.timeline} ·
              状态 {keptCounts.states}）
            </Button>
          </>
        )
      }
    >
      {analyze.isPending ? (
        <div className="flex flex-col gap-2">
          <EmptyState title="分析中…" description="逐章跑摘要、事实、时间线与人物状态，可能要等一会儿。" />
          <SkeletonList rows={4} />
        </div>
      ) : analysis === null ? (
        <EmptyState
          title="没有产出可归档的分析"
          description="确认本章已有正文后重试。"
          action={
            <Button tone="outline" size="sm" onClick={() => analyze.mutate()}>
              重试
            </Button>
          }
        />
      ) : (
        <div className="flex flex-col gap-3">
          {summary !== undefined && summary !== null && (
            <CandidateSection
              title="摘要"
              checked={!dropped.has('summary')}
              onToggle={() => toggle('summary')}
            >
              <p className="text-2xs leading-relaxed text-ink-300">
                {candidateLabel(summary, ['brief_summary', 'title'])}
              </p>
              {Array.isArray(summary.key_events) && summary.key_events.length > 0 && (
                <ul className="mt-1 list-inside list-disc text-2xs text-ink-500">
                  {(summary.key_events as unknown[]).slice(0, 5).map((event, index) => (
                    <li key={index}>{String(event)}</li>
                  ))}
                </ul>
              )}
            </CandidateSection>
          )}
          <CandidateList
            title="事实"
            items={facts}
            labelKeys={['statement', 'title', 'content']}
            prefix="fact"
            dropped={dropped}
            onToggle={toggle}
          />
          <CandidateList
            title="时间线"
            items={timeline}
            labelKeys={['event', 'description', 'title', 'time']}
            prefix="timeline"
            dropped={dropped}
            onToggle={toggle}
          />
          <CandidateList
            title="人物状态"
            items={states}
            labelKeys={['character', 'name']}
            detailKeys={['state', 'location', 'emotional_state']}
            prefix="state"
            dropped={dropped}
            onToggle={toggle}
          />
          {summary === undefined && facts.length === 0 && timeline.length === 0 && states.length === 0 && (
            <EmptyState title="分析结果是空的" description="换个章节或稍后重试。" />
          )}
        </div>
      )}
    </Dialog>
  );
}
function CandidateSection({
  title,
  checked,
  onToggle,
  children,
}: {
  title: string;
  checked: boolean;
  onToggle: () => void;
  children: ReactNode;
}): ReactNode {
  return (
    <section className="rounded-md border border-ink-800 bg-ink-900/50 px-3 py-2">
      <label className="flex cursor-pointer items-center gap-2">
        <input type="checkbox" checked={checked} onChange={onToggle} className="accent-celadon-500" />
        <span className="text-xs font-medium text-ink-200">{title}</span>
        {!checked && <Badge tone="neutral">不入库</Badge>}
      </label>
      <div className={checked ? '' : 'opacity-40'}>{children}</div>
    </section>
  );
}

function CandidateList({
  title,
  items,
  labelKeys,
  detailKeys = [],
  prefix,
  dropped,
  onToggle,
}: {
  title: string;
  items: JsonRecord[];
  labelKeys: string[];
  detailKeys?: string[];
  prefix: string;
  dropped: Set<string>;
  onToggle: (key: string) => void;
}): ReactNode {
  if (items.length === 0) return null;
  return (
    <section className="rounded-md border border-ink-800 bg-ink-900/50 px-3 py-2">
      <p className="mb-1.5 text-xs font-medium text-ink-200">
        {title}
        <span className="ml-1.5 text-ink-500">{items.length} 条</span>
      </p>
      <div className="flex flex-col gap-1">
        {items.map((item, index) => {
          const key = prefix + ':' + index;
          const kept = !dropped.has(key);
          const detail = detailKeys
            .map((field) => item[field])
            .filter((value): value is string => typeof value === 'string' && value.trim() !== '')
            .join(' · ');
          return (
            <label
              key={key}
              className="flex cursor-pointer items-start gap-2 rounded px-1 py-0.5 hover:bg-ink-850"
            >
              <input
                type="checkbox"
                checked={kept}
                onChange={() => onToggle(key)}
                className="mt-0.5 accent-celadon-500"
              />
              <span className={kept ? '' : 'opacity-40'}>
                <span className="block text-2xs leading-relaxed text-ink-300">
                  {candidateLabel(item, labelKeys)}
                </span>
                {detail !== '' && <span className="block text-2xs text-ink-600">{detail}</span>}
              </span>
            </label>
          );
        })}
      </div>
    </section>
  );
}
