import { lazy, useMemo, useRef, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { useVirtualizer } from '@tanstack/react-virtual';
import { ChevronDown, ChevronRight, Landmark, Pencil, Plus, ScrollText, Trash2, Users } from 'lucide-react';
import type { Fact, JsonRecord } from '@/api/types';
import {
  Badge,
  Button,
  ConfirmDialog,
  EmptyState,
  Input,
  Panel,
  Select,
  SkeletonList,
  TabPanel,
  Tabs,
} from '@/components/ui';
import { LazyOverlay } from '@/components/ui/LazyOverlay';
import {
  useCharacterStates,
  useCreateTimelineEvent,
  useDeleteFact,
  useFacts,
  useFactsTree,
  useTimeline,
} from '@/features/cards/queries';
import { formatChapterLabel, formatVolumeLabel } from '@/lib/chapter-id';
import { useChapterTree } from '@/features/chapters/queries';

const FactEditDialog = lazy(() =>
  import('@/features/facts/FactEditDialog').then((m) => ({ default: m.FactEditDialog })),
);
const CharacterStateDialog = lazy(() =>
  import('@/features/facts/CharacterStateDialog').then((m) => ({ default: m.CharacterStateDialog })),
);

type FactsTabKey = 'canon' | 'facts' | 'timeline' | 'states';

export function FactsPanel(): ReactNode {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const [tab, setTab] = useState<FactsTabKey>('canon');

  return (
    <Panel title="正典" scroll={false}>
      <Tabs
        value={tab}
        onValueChange={setTab}
        size="sm"
        items={[
          { value: 'canon', label: '百科', icon: <Landmark /> },
          { value: 'facts', label: '事实' },
          { value: 'timeline', label: '时间线', icon: <ScrollText /> },
          { value: 'states', label: '状态', icon: <Users /> },
        ]}
        className="h-full"
      >
        <TabPanel value="canon" className="p-0">
          <CanonTab projectId={projectId} />
        </TabPanel>
        <TabPanel value="facts" className="p-2.5">
          <FactsTabView projectId={projectId} />
        </TabPanel>
        <TabPanel value="timeline" className="p-2.5">
          <TimelineTab projectId={projectId} />
        </TabPanel>
        <TabPanel value="states" className="p-2.5">
          <StatesTab projectId={projectId} />
        </TabPanel>
      </Tabs>
    </Panel>
  );
}

// ── 百科：卷→章→事实 三级虚拟化树 ──────────────────────────────────

interface TreeChapter {
  id: string;
  title: string;
  summary: string;
  facts: JsonRecord[];
}
interface TreeVolume {
  id: string;
  title: string;
  summary: string;
  chapters: TreeChapter[];
}

type Row =
  | { kind: 'volume'; id: string; volume: TreeVolume }
  | { kind: 'chapter'; id: string; chapter: TreeChapter }
  | { kind: 'fact'; id: string; chapterId: string; fact: JsonRecord };

function CanonTab({ projectId }: { projectId: string }): ReactNode {
  const tree = useFactsTree(projectId);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [search, setSearch] = useState('');
  const scrollRef = useRef<HTMLDivElement>(null);

  const volumes = useMemo<TreeVolume[]>(
    () => (tree.data?.volumes as TreeVolume[] | undefined) ?? [],
    [tree.data],
  );

  const rows = useMemo<Row[]>(() => {
    const key = search.trim().toLowerCase();
    const out: Row[] = [];
    for (const volume of volumes) {
      // 搜索时命中章节/事实才收进结果，卷头跟着命中项走
      const chapters = volume.chapters.filter((chapter) => {
        if (key === '') return true;
        if (chapter.title.toLowerCase().includes(key) || chapter.summary.toLowerCase().includes(key))
          return true;
        return chapter.facts.some((fact) =>
          String(fact.statement ?? fact.title ?? fact.content ?? '')
            .toLowerCase()
            .includes(key),
        );
      });
      if (chapters.length === 0) continue;
      out.push({ kind: 'volume', id: `v:${volume.id}`, volume });
      if (collapsed.has(volume.id)) continue;
      for (const chapter of chapters) {
        out.push({ kind: 'chapter', id: `c:${chapter.id}`, chapter });
        if (collapsed.has(chapter.id)) continue;
        const facts =
          key === ''
            ? chapter.facts
            : chapter.facts.filter((fact) =>
                String(fact.statement ?? fact.title ?? fact.content ?? '')
                  .toLowerCase()
                  .includes(key),
              );
        for (const [index, fact] of facts.entries()) {
          out.push({ kind: 'fact', id: `f:${chapter.id}:${index}`, chapterId: chapter.id, fact });
        }
      }
    }
    return out;
  }, [volumes, collapsed, search]);

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: (index) => (rows[index]?.kind === 'fact' ? 40 : 32),
    overscan: 10,
  });

  const toggle = (id: string): void => {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  if (tree.isPending) return <SkeletonList rows={8} />;
  if (volumes.length === 0) {
    return <EmptyState title="还没有正典内容" description="定稿章节后，分析产物会出现在这里。" />;
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="border-b border-ink-800 p-2">
        <Input
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="搜事实/章节…"
          className="h-7"
        />
      </div>
      <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto">
        <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
          {virtualizer.getVirtualItems().map((item) => {
            const row = rows[item.index];
            if (row === undefined) return null;
            return (
              <div
                key={item.key}
                data-index={item.index}
                ref={virtualizer.measureElement}
                className="absolute top-0 left-0 w-full"
                style={{ transform: `translateY(${item.start}px)` }}
              >
                {row.kind === 'volume' && (
                  <button
                    type="button"
                    onClick={() => toggle(row.volume.id)}
                    className="flex w-full items-center gap-1.5 border-b border-ink-850 bg-ink-900/70 px-2 py-1.5 text-left hover:bg-ink-850"
                  >
                    <span className="text-ink-500 [&_svg]:size-3.5">
                      {collapsed.has(row.volume.id) ? <ChevronRight /> : <ChevronDown />}
                    </span>
                    <span className="truncate text-xs font-medium text-celadon-400">
                      {formatVolumeLabel(row.volume.id, row.volume.title)}
                    </span>
                  </button>
                )}
                {row.kind === 'chapter' && (
                  <button
                    type="button"
                    onClick={() => toggle(row.chapter.id)}
                    className="flex w-full items-center gap-1.5 px-2 py-1.5 pl-6 text-left hover:bg-ink-900"
                  >
                    <span className="text-ink-600 [&_svg]:size-3">
                      {collapsed.has(row.chapter.id) ? <ChevronRight /> : <ChevronDown />}
                    </span>
                    <span className="tnum shrink-0 text-2xs text-ink-500">
                      {formatChapterLabel(row.chapter.id)}
                    </span>
                    <span className="min-w-0 flex-1 truncate text-2xs text-ink-200">{row.chapter.title}</span>
                    {row.chapter.facts.length > 0 && <Badge tone="outline">{row.chapter.facts.length}</Badge>}
                  </button>
                )}
                {row.kind === 'fact' && (
                  <div className="flex items-start gap-2 px-2 py-1.5 pl-12">
                    <span className="mt-1.5 size-1 shrink-0 rounded-full bg-celadon-600" />
                    <div className="min-w-0 flex-1">
                      <p className="text-2xs leading-relaxed text-ink-300">
                        {String(row.fact.statement ?? row.fact.title ?? row.fact.content ?? '')}
                      </p>
                      {row.fact.title !== undefined && row.fact.statement !== undefined && (
                        <p className="text-2xs text-ink-600">{String(row.fact.title)}</p>
                      )}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ── 事实：扁平列表 + CRUD ─────────────────────────────────────────

function FactsTabView({ projectId }: { projectId: string }): ReactNode {
  const facts = useFacts(projectId);
  const remove = useDeleteFact(projectId);
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<Fact | null | 'new'>(null);
  const [deleting, setDeleting] = useState<Fact | null>(null);

  const filtered = useMemo(() => {
    const list = facts.data ?? [];
    const key = search.trim().toLowerCase();
    if (key === '') return list;
    return list.filter((fact) =>
      [fact.statement, fact.title, fact.content, fact.id]
        .filter((v): v is string => typeof v === 'string')
        .some((v) => v.toLowerCase().includes(key)),
    );
  }, [facts.data, search]);

  if (facts.isPending) return <SkeletonList rows={6} />;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-1.5">
        <Input
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="搜事实…"
          className="h-7 flex-1"
        />
        <Button tone="ghost" size="xs" icon={<Plus />} onClick={() => setEditing('new')}>
          录入
        </Button>
      </div>

      {filtered.map((fact) => (
        <section
          key={fact.id}
          className="group rounded-md border border-ink-800 px-2.5 py-2 hover:border-ink-700"
        >
          <div className="flex items-start gap-2">
            <div className="min-w-0 flex-1">
              <p className="text-2xs leading-relaxed text-ink-200">{fact.statement ?? fact.content}</p>
              <div className="mt-1 flex flex-wrap items-center gap-1.5 text-2xs text-ink-600">
                <span className="tnum">{fact.id}</span>
                {fact.introduced_in !== null && <span>{formatChapterLabel(fact.introduced_in)}引入</span>}
                {fact.confidence < 1 && <Badge tone="warning">置信 {fact.confidence}</Badge>}
                {fact.origin !== null && fact.origin !== '' && <Badge tone="outline">{fact.origin}</Badge>}
              </div>
            </div>
            <div className="flex shrink-0 opacity-0 transition-opacity group-hover:opacity-100">
              <Button tone="ghost" size="xs" icon={<Pencil />} onClick={() => setEditing(fact)}>
                改
              </Button>
              <Button tone="dangerGhost" size="xs" icon={<Trash2 />} onClick={() => setDeleting(fact)}>
                删
              </Button>
            </div>
          </div>
        </section>
      ))}

      {filtered.length === 0 && (
        <EmptyState
          title={search === '' ? '还没有事实' : '没搜到'}
          description="事实大多由章节分析自动产出，也可以手动录入。"
        />
      )}

      {editing !== null && (
        <LazyOverlay>
          <FactEditDialog
            projectId={projectId}
            fact={editing === 'new' ? null : editing}
            onClose={() => setEditing(null)}
          />
        </LazyOverlay>
      )}
      {deleting !== null && (
        <ConfirmDialog
          open
          onOpenChange={(open) => {
            if (!open) setDeleting(null);
          }}
          title={`删除事实 ${deleting.id}？`}
          description={deleting.statement ?? ''}
          confirmLabel="删除"
          danger
          onConfirm={() => {
            remove.mutate(deleting.id);
            setDeleting(null);
          }}
        />
      )}
    </div>
  );
}

// ── 时间线 ─────────────────────────────────────────────────────────

function TimelineTab({ projectId }: { projectId: string }): ReactNode {
  const timeline = useTimeline(projectId);
  const create = useCreateTimelineEvent(projectId);
  const tree = useChapterTree(projectId);
  const [form, setForm] = useState({ time: '', event: '', source: '' });
  const [formOpen, setFormOpen] = useState(false);

  if (timeline.isPending) return <SkeletonList rows={6} />;
  const events = timeline.data ?? [];

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex justify-end">
        <Button tone="ghost" size="xs" icon={<Plus />} onClick={() => setFormOpen((v) => !v)}>
          添加事件
        </Button>
      </div>

      {formOpen && (
        <div className="flex flex-col gap-2 rounded-md border border-ink-800 bg-ink-900/40 p-2.5">
          <Input
            value={form.time}
            onChange={(event) => setForm((f) => ({ ...f, time: event.target.value }))}
            placeholder="时间点（如：第三年秋 / 第 12 日）"
            className="h-7"
          />
          <Input
            value={form.event}
            onChange={(event) => setForm((f) => ({ ...f, event: event.target.value }))}
            placeholder="发生了什么"
            className="h-7"
          />
          <div className="flex items-center gap-2">
            <Select
              value={form.source || '__none'}
              onValueChange={(value) => setForm((f) => ({ ...f, source: value === '__none' ? '' : value }))}
              options={[
                { value: '__none', label: '（无章节关联）' },
                ...tree.chapterIds.map((id) => ({ value: id, label: formatChapterLabel(id) })),
              ]}
              className="flex-1"
            />
            <Button
              tone="primary"
              size="xs"
              loading={create.isPending}
              disabled={form.event.trim() === ''}
              onClick={() =>
                create.mutate(
                  {
                    time: form.time.trim() || null,
                    event: form.event.trim(),
                    source: form.source || null,
                  },
                  {
                    onSuccess: () => {
                      setForm({ time: '', event: '', source: '' });
                      setFormOpen(false);
                    },
                  },
                )
              }
            >
              添加
            </Button>
          </div>
        </div>
      )}

      {events.map((event, index) => {
        const time = String(event.time ?? event.date ?? '');
        const text = String(event.event ?? event.description ?? event.title ?? '');
        const source = typeof event.source === 'string' ? event.source : '';
        return (
          <section key={String(event.id ?? index)} className="flex gap-2.5 px-1 py-1.5">
            <div className="flex w-16 shrink-0 flex-col items-end">
              <span className="text-2xs text-gamboge-400">{time !== '' ? time : '—'}</span>
              {source !== '' && (
                <span className="tnum text-2xs text-ink-600">{formatChapterLabel(source)}</span>
              )}
            </div>
            <div className="w-px shrink-0 self-stretch bg-ink-800" />
            <p className="min-w-0 flex-1 text-2xs leading-relaxed text-ink-300">{text}</p>
          </section>
        );
      })}

      {events.length === 0 && (
        <EmptyState title="时间线还是空的" description="章节分析会自动补事件，也可以手动添加。" />
      )}
    </div>
  );
}

// ── 人物状态 ───────────────────────────────────────────────────────

function StatesTab({ projectId }: { projectId: string }): ReactNode {
  const states = useCharacterStates(projectId);
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<JsonRecord | null>(null);

  const entries = useMemo(() => {
    const list = states.data ?? [];
    const key = search.trim().toLowerCase();
    if (key === '') return list;
    return list.filter((item) => JSON.stringify(item).toLowerCase().includes(key));
  }, [states.data, search]);

  // 状态是 append-only 历史：编辑入口永远落在该人物最新一条上
  const latestByName = useMemo(() => {
    const map = new Map<string, JsonRecord>();
    for (const item of states.data ?? []) {
      const name = String(item.character ?? item.name ?? '');
      if (name !== '') map.set(name, item);
    }
    return map;
  }, [states.data]);

  if (states.isPending) return <SkeletonList rows={6} />;

  return (
    <div className="flex flex-col gap-1.5">
      <Input
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        placeholder="搜人物状态…"
        className="h-7"
      />
      {entries.map((entry, index) => {
        const name = String(entry.character ?? entry.name ?? '（未名）');
        const fields = Object.entries(entry).filter(
          ([key, value]) =>
            !['character', 'name'].includes(key) &&
            (typeof value === 'string' || typeof value === 'number') &&
            String(value) !== '',
        );
        return (
          <section key={`${name}-${index}`} className="rounded-md border border-ink-800 px-2.5 py-2">
            <div className="flex items-center gap-2">
              <p className="min-w-0 flex-1 text-xs font-medium text-ink-200">{name}</p>
              <Button
                tone="ghost"
                size="xs"
                icon={<Pencil />}
                onClick={() => setEditing(latestByName.get(name) ?? entry)}
              >
                编辑
              </Button>
            </div>
            <dl className="mt-1 flex flex-col gap-0.5">
              {fields.slice(0, 6).map(([key, value]) => (
                <div key={key} className="flex gap-2 text-2xs">
                  <dt className="w-20 shrink-0 text-ink-600">{key}</dt>
                  <dd className="min-w-0 flex-1 text-ink-300">{String(value)}</dd>
                </div>
              ))}
            </dl>
          </section>
        );
      })}
      {entries.length === 0 && (
        <EmptyState title="还没有人物状态" description="写完章节并完成分析后自动产生。" />
      )}
      {editing !== null && (
        <LazyOverlay>
          <CharacterStateDialog projectId={projectId} entry={editing} onClose={() => setEditing(null)} />
        </LazyOverlay>
      )}
    </div>
  );
}
