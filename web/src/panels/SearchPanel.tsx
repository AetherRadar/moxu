import { useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import { RefreshCw, Search } from 'lucide-react';
import { retrievalApi, type EvidenceSearchResponse } from '@/api';
import type { EvidenceItem } from '@/api/types';
import { ApiError } from '@/lib/http';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, EmptyState, Input, Panel } from '@/components/ui';

const TYPE_OPTIONS = [
  { value: 'character', label: '人物' },
  { value: 'world_entity', label: '世界' },
  { value: 'fact', label: '事实' },
  { value: 'summary', label: '摘要' },
  { value: 'style', label: '文风' },
  { value: 'text_chunk', label: '正文块' },
  { value: 'memory', label: '记忆' },
] as const;

const TYPE_BADGE: Record<string, { label: string; tone: 'accent' | 'success' | 'warning' | 'danger' | 'neutral' }> = {
  character: { label: '人物', tone: 'accent' },
  world: { label: '世界', tone: 'success' },
  world_entity: { label: '世界', tone: 'success' },
  world_rule: { label: '规则', tone: 'warning' },
  fact: { label: '事实', tone: 'accent' },
  summary: { label: '摘要', tone: 'neutral' },
  style: { label: '文风', tone: 'warning' },
  text_chunk: { label: '正文', tone: 'neutral' },
  memory: { label: '记忆', tone: 'danger' },
};

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/** 证据检索：作家循环用的同一套后端检索，拿来当调试和资料浏览口。 */
export function SearchPanel(): ReactNode {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const [query, setQuery] = useState('');
  const [types, setTypes] = useState<Set<string>>(
    new Set(['character', 'world_entity', 'fact', 'summary', 'text_chunk']),
  );
  const [rerank, setRerank] = useState(false);

  const search = useMutation({
    mutationFn: (text: string) =>
      retrievalApi.searchEvidence(projectId, {
        queries: [text],
        types: [...types],
        limit: 16,
        semantic_rerank: rerank,
        rerank_query: rerank ? text : undefined,
      }),
    onError: (error) => toast.error(reason(error, '检索失败')),
  });

  const rebuild = useMutation({
    mutationFn: () => retrievalApi.rebuildEvidence(projectId),
    onSuccess: () => toast.success('索引已重建'),
    onError: (error) => toast.error(reason(error, '重建索引失败')),
  });

  const submit = (event: FormEvent): void => {
    event.preventDefault();
    const text = query.trim();
    if (text === '') return;
    search.mutate(text);
  };

  const items = useMemo<EvidenceItem[]>(() => {
    const data: EvidenceSearchResponse | undefined = search.data;
    return (data?.items ?? data?.results ?? []).slice(0, 60);
  }, [search.data]);

  const stats = search.data?.stats as Record<string, unknown> | undefined;

  const toggleType = (value: string): void => {
    setTypes((current) => {
      const next = new Set(current);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return next;
    });
  };

  return (
    <Panel
      title="检索"
      actions={
        <Button
          tone="ghost"
          size="xs"
          icon={<RefreshCw />}
          loading={rebuild.isPending}
          onClick={() => rebuild.mutate()}
        >
          重建索引
        </Button>
      }
    >
      <form onSubmit={submit} className="mb-2 flex items-center gap-1.5">
        <Input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜正文/事实/卡片…"
          className="h-7 flex-1"
        />
        <Button type="submit" tone="primary" size="sm" icon={<Search />} loading={search.isPending}>
          搜
        </Button>
      </form>

      <div className="mb-2 flex flex-wrap items-center gap-1">
        {TYPE_OPTIONS.map((option) => (
          <button
            key={option.value}
            type="button"
            onClick={() => toggleType(option.value)}
            className={
              types.has(option.value)
                ? 'rounded border border-celadon-600/60 bg-celadon-500/10 px-1.5 py-0.5 text-2xs text-celadon-300'
                : 'rounded border border-ink-800 px-1.5 py-0.5 text-2xs text-ink-500 hover:border-ink-600'
            }
          >
            {option.label}
          </button>
        ))}
        <label className="ml-auto flex cursor-pointer items-center gap-1 text-2xs text-ink-500">
          <input
            type="checkbox"
            checked={rerank}
            onChange={(event) => setRerank(event.target.checked)}
            className="accent-celadon-500"
          />
          语义重排
        </label>
      </div>

      {stats !== undefined && (
        <p className="mb-2 text-2xs text-ink-600">
          命中 {String(stats.total ?? items.length)} 条
          {Array.isArray(stats.top_sources) && stats.top_sources.length > 0 && (
            <> · 主要来源：{(stats.top_sources as unknown[]).map(String).join('、')}</>
          )}
        </p>
      )}

      <div className="flex flex-col gap-1.5">
        {items.map((item, index) => (
          <EvidenceCard key={String(item.id ?? index)} item={item} />
        ))}
      </div>

      {search.isSuccess && items.length === 0 && (
        <EmptyState title="没搜到" description="换个说法，或先重建索引。" />
      )}
      {!search.isSuccess && !search.isPending && (
        <EmptyState
          icon={<Search />}
          title="证据检索"
          description="跟作家循环共用一套检索：卡片、事实、摘要、正文块一起搜。"
        />
      )}
    </Panel>
  );
}

function EvidenceCard({ item }: { item: EvidenceItem }): ReactNode {
  const type = String(item.type ?? 'unknown');
  const badge = TYPE_BADGE[type] ?? { label: type, tone: 'neutral' as const };
  const source = item.source as Record<string, unknown> | undefined;
  const chapter =
    typeof source?.chapter === 'string'
      ? source.chapter
      : typeof item.chapter === 'string'
        ? item.chapter
        : '';
  const score = typeof item.score === 'number' ? item.score : null;
  const text = String(item.text ?? '');

  return (
    <section className="rounded-md border border-ink-800 px-2.5 py-2">
      <div className="mb-1 flex items-center gap-1.5">
        <Badge tone={badge.tone}>{badge.label}</Badge>
        {chapter !== '' && (
          <span className="tnum text-2xs text-ink-500">{formatChapterLabel(chapter)}</span>
        )}
        {score !== null && (
          <span className="tnum ml-auto text-2xs text-ink-600">{score.toFixed(2)}</span>
        )}
      </div>
      <p className="line-clamp-4 text-2xs leading-relaxed text-ink-300">{text}</p>
    </section>
  );
}
