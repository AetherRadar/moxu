import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Activity, Bot, CircleStop, Layers, ListChecks, MemoryStick, RefreshCw } from 'lucide-react';
import type { JsonRecord } from '@/api/types';
import { projectScope, retrievalApi } from '@/api';
import { ApiError } from '@/lib/http';
import { subscribeWs, wsPath, type WsEnvelope } from '@/lib/ws';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, ConfirmDialog, EmptyState, Panel, SkeletonList } from '@/components/ui';
import {
  useBindings,
  useCancelSession,
  useMemoryPack,
  useRebuildBindings,
  useRelationships,
  useSessionStatus,
} from '@/features/agents/queries';
import { useWorkspaceStore } from '@/stores/workspace';

const STATUS_LABEL: Record<
  string,
  { label: string; tone: 'accent' | 'success' | 'warning' | 'danger' | 'neutral' }
> = {
  idle: { label: '空闲', tone: 'neutral' },
  generating_brief: { label: '出简报', tone: 'warning' },
  waiting_user_input: { label: '等回答', tone: 'accent' },
  writing_draft: { label: '写稿中', tone: 'warning' },
  editing: { label: '修订中', tone: 'warning' },
  waiting_feedback: { label: '等反馈', tone: 'accent' },
  analyzing: { label: '分析中', tone: 'warning' },
  completed: { label: '已完成', tone: 'success' },
  error: { label: '出错', tone: 'danger' },
};

interface TraceEvent {
  id: number;
  time: string;
  agent: string;
  status: string;
  stage: string;
  message: string;
  round: number | null;
}

export function AgentsPanel(): ReactNode {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const client = useQueryClient();
  const chapter = useWorkspaceStore((state) => state.chapter);

  const status = useSessionStatus(projectId);
  const cancel = useCancelSession(projectId);
  const bindings = useBindings(projectId, chapter);
  const memoryPack = useMemoryPack(projectId, chapter);
  const relationships = useRelationships(projectId);
  const rebuildBindings = useRebuildBindings(projectId);

  // 全量重建走 rebuild-batch：整本书顺序跑，可能要几分钟
  const rebuildAll = useMutation({
    mutationFn: () => retrievalApi.rebuildBindingsBatch(projectId, {}, undefined),
    onSuccess: (data) => {
      const results = Array.isArray(data.results) ? (data.results as JsonRecord[]) : [];
      const failed = results.filter((item) => item.success !== true).length;
      void client.invalidateQueries({ queryKey: projectScope(projectId) });
      if (failed > 0) toast.error(`全部重建完成，${failed} 章失败`);
      else toast.success(`已重建 ${results.length} 章绑定`);
    },
    onError: (error) => toast.error(error instanceof ApiError ? error.message : '全部重建失败'),
  });

  const [events, setEvents] = useState<TraceEvent[]>([]);
  const idRef = useRef(0);
  const [confirmCancel, setConfirmCancel] = useState(false);

  // 全局链路事件流：连接即回放最近 100 条
  useEffect(() => {
    idRef.current = 0;
    setEvents([]);
    return subscribeWs(wsPath.trace(), {
      onMessage: (batch: WsEnvelope[]) => {
        setEvents((current) => {
          const next = [...current];
          for (const envelope of batch) {
            const message =
              typeof envelope.message === 'string'
                ? envelope.message
                : typeof envelope.stage === 'string'
                  ? envelope.stage
                  : (envelope.type ?? '');
            next.push({
              id: idRef.current++,
              time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
              agent: String(envelope.agent ?? envelope.type ?? 'system'),
              status: String(envelope.status ?? ''),
              stage: String(envelope.stage ?? ''),
              message,
              round: typeof envelope.round === 'number' ? envelope.round : null,
            });
          }
          return next.slice(-200);
        });
      },
    });
  }, [projectId]);

  const statusInfo: { label: string; tone: 'accent' | 'success' | 'warning' | 'danger' | 'neutral' } =
    STATUS_LABEL[String(status.data?.status ?? 'idle')] ?? { label: '空闲', tone: 'neutral' };
  const sessionChapter = typeof status.data?.chapter === 'string' ? status.data.chapter : '';

  const binding = (bindings.data?.binding as JsonRecord | undefined) ?? null;
  const bindingCharacters = (binding?.characters as string[] | undefined) ?? [];
  const bindingWorld = (binding?.world_entities as string[] | undefined) ?? [];
  const bindingRules = (binding?.world_rules as string[] | undefined) ?? [];

  const pack = memoryPack.data ?? null;
  const packExists = pack?.exists === true;

  const relStats = relationships.data?.stats as Record<string, unknown> | undefined;
  const relBuilt = relationships.data?.built === true;

  const busy = status.data?.status !== undefined && status.data.status !== 'idle';

  return (
    <Panel title="编排" scroll={false}>
      <div className="flex h-full min-h-0 flex-col gap-3 overflow-y-auto p-2.5">
        {/* ── 会话状态 ── */}
        <section className="rounded-md border border-ink-800 px-3 py-2.5">
          <div className="flex items-center gap-2">
            <Bot className="size-4 text-celadon-500" />
            <span className="text-xs font-medium text-ink-100">写作会话</span>
            <Badge tone={statusInfo.tone}>{statusInfo.label}</Badge>
            {busy && (
              <Button
                tone="dangerGhost"
                size="xs"
                icon={<CircleStop />}
                className="ml-auto"
                onClick={() => setConfirmCancel(true)}
              >
                取消
              </Button>
            )}
          </div>
          {(sessionChapter !== '' || (status.data?.iteration ?? 0) > 0) && (
            <p className="mt-1.5 text-2xs text-ink-500">
              {sessionChapter !== '' && `${formatChapterLabel(sessionChapter)}`}
              {(status.data?.iteration ?? 0) > 0 && ` · 第 ${status.data?.iteration} 轮修订`}
            </p>
          )}
          {status.isPending && <SkeletonList rows={1} />}
        </section>

        {/* ── 当前章上下文 ── */}
        <section className="rounded-md border border-ink-800 px-3 py-2.5">
          <div className="flex items-center gap-2">
            <ListChecks className="size-4 text-celadon-500" />
            <span className="text-xs font-medium text-ink-100">
              章节绑定{chapter !== null && ` · ${formatChapterLabel(chapter)}`}
            </span>
            {bindings.data?.stale === true && <Badge tone="warning">已过期</Badge>}
            {bindings.data?.rebuilding === true && <Badge tone="warning">重建中</Badge>}
            {chapter !== null && (
              <Button
                tone="ghost"
                size="xs"
                icon={<RefreshCw />}
                loading={rebuildBindings.isPending}
                onClick={() =>
                  rebuildBindings.mutate(chapter, {
                    onError: (error) => toast.error(error instanceof ApiError ? error.message : '重建失败'),
                  })
                }
              >
                重建
              </Button>
            )}
            <Button
              tone="ghost"
              size="xs"
              icon={<Layers />}
              className={chapter === null ? 'ml-auto' : ''}
              loading={rebuildAll.isPending}
              onClick={() => rebuildAll.mutate()}
            >
              全部重建
            </Button>
          </div>
          {chapter === null ? (
            <p className="mt-1.5 text-2xs text-ink-600">打开一章后显示它的实体绑定。</p>
          ) : bindings.isPending ? (
            <SkeletonList rows={2} className="mt-1.5" />
          ) : binding === null ? (
            <p className="mt-1.5 text-2xs text-ink-600">还没建过绑定。</p>
          ) : (
            <div className="mt-1.5 flex flex-col gap-1 text-2xs">
              <BindingRow label="人物" items={bindingCharacters} />
              <BindingRow label="世界" items={bindingWorld} />
              <BindingRow label="规则" items={bindingRules} />
            </div>
          )}
        </section>

        {/* ── 记忆包 ── */}
        <section className="rounded-md border border-ink-800 px-3 py-2.5">
          <div className="flex items-center gap-2">
            <MemoryStick className="size-4 text-celadon-500" />
            <span className="text-xs font-medium text-ink-100">记忆包</span>
            {packExists ? <Badge tone="success">已建</Badge> : <Badge tone="neutral">未建</Badge>}
            {memoryPack.isPending && <SkeletonList rows={1} className="flex-1" />}
          </div>
          {packExists && (
            <div className="mt-1.5 grid grid-cols-2 gap-x-3 gap-y-1 text-2xs text-ink-400">
              <span>
                卡片快照：人 {String((pack?.card_snapshot as JsonRecord)?.characters ?? 0)} / 世{' '}
                {String((pack?.card_snapshot as JsonRecord)?.world ?? 0)}
              </span>
              <span>反问 {String(pack?.questions_count ?? 0)} 条</span>
              <span className="col-span-2">
                来源 {String(pack?.source ?? '—')}
                {typeof pack?.built_at === 'string' && pack.built_at !== '' && ` · ${pack.built_at}`}
              </span>
              {String(pack?.research_stop_reason ?? '') !== '' && (
                <span className="col-span-2 text-gamboge-400">
                  研究终止：{String(pack?.research_stop_reason)}
                </span>
              )}
            </div>
          )}
        </section>

        {/* ── 关系索引 ── */}
        <section className="rounded-md border border-ink-800 px-3 py-2.5">
          <div className="flex items-center gap-2">
            <Activity className="size-4 text-celadon-500" />
            <span className="text-xs font-medium text-ink-100">关系索引</span>
            {relBuilt ? <Badge tone="success">已建</Badge> : <Badge tone="neutral">未建</Badge>}
          </div>
          {relStats !== undefined && (
            <p className="mt-1.5 text-2xs text-ink-400">
              别名 {String(relStats.characters_indexed ?? 0)} · 章节提及{' '}
              {String(relStats.chapters_indexed ?? 0)} · 关系对 {String(relStats.relation_pairs ?? 0)}
            </p>
          )}
        </section>

        {/* ── 链路时间线 ── */}
        <section className="flex min-h-0 flex-1 flex-col rounded-md border border-ink-800">
          <p className="border-b border-ink-800 px-3 py-2 text-xs font-medium text-ink-100">链路时间线</p>
          <TraceList events={events} />
        </section>
      </div>

      {confirmCancel && (
        <ConfirmDialog
          open
          onOpenChange={(open) => {
            if (!open) setConfirmCancel(false);
          }}
          title="取消当前会话？"
          description="正在跑的写作/分析会被叫停；已落盘的草稿不动。"
          confirmLabel="取消会话"
          danger
          loading={cancel.isPending}
          onConfirm={() => {
            cancel.mutate();
            setConfirmCancel(false);
          }}
        />
      )}
    </Panel>
  );
}

function BindingRow({ label, items }: { label: string; items: string[] }): ReactNode {
  if (items.length === 0) return null;
  return (
    <p className="flex gap-1.5">
      <span className="w-8 shrink-0 text-ink-600">{label}</span>
      <span className="min-w-0 flex-1 text-ink-300">{items.join('、')}</span>
    </p>
  );
}

function TraceList({ events }: { events: TraceEvent[] }): ReactNode {
  const endRef = useRef<HTMLDivElement>(null);
  const [follow, setFollow] = useState(true);

  useEffect(() => {
    if (follow) endRef.current?.scrollIntoView({ block: 'end' });
  }, [events, follow]);

  const visible = useMemo(() => events.slice(-120), [events]);

  if (visible.length === 0) {
    return <EmptyState title="还没有链路事件" description="AI 会话跑起来后这里会滚动事件。" />;
  }

  return (
    <div
      className="min-h-0 flex-1 overflow-y-auto p-2"
      onScroll={(event) => {
        const el = event.currentTarget;
        setFollow(el.scrollHeight - el.scrollTop - el.clientHeight < 40);
      }}
    >
      {visible.map((event) => (
        <div key={event.id} className="flex gap-2 py-1">
          <span className="tnum w-14 shrink-0 text-2xs text-ink-600">{event.time}</span>
          <div className="min-w-0 flex-1">
            <span className="mr-1.5 rounded bg-ink-800 px-1 text-2xs text-ink-400">{event.agent}</span>
            {event.status !== '' && <span className="mr-1.5 text-2xs text-celadon-500">{event.status}</span>}
            {event.round !== null && <span className="mr-1.5 text-2xs text-ink-600">R{event.round}</span>}
            <span className="text-2xs leading-relaxed text-ink-300">{event.message}</span>
          </div>
        </div>
      ))}
      <div ref={endRef} />
    </div>
  );
}
