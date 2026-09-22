import { lazy, useMemo, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  BookMarked,
  ChevronDown,
  ChevronRight,
  FileScan,
  Globe,
  Plus,
  Sparkles,
  Star,
  Trash2,
  User,
} from 'lucide-react';
import { cardsApi } from '@/api';
import type { CharacterCard, WorldCard } from '@/api/types';
import { ApiError } from '@/lib/http';
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
  Textarea,
} from '@/components/ui';
import { LazyOverlay } from '@/components/ui/LazyOverlay';
import {
  useCharacterCards,
  useDeleteCharacterCard,
  useDeleteWorldCard,
  useGenerateCardDescription,
  useSaveCharacterCard,
  useSaveStyleCard,
  useSaveWorldCard,
  useStyleCard,
  useWorldCards,
} from '@/features/cards/queries';
import { cn } from '@/lib/cn';

const ExtractCardsDialog = lazy(() =>
  import('@/features/cards/ExtractCardsDialog').then((m) => ({ default: m.ExtractCardsDialog })),
);

type CardTab = 'characters' | 'world' | 'style';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export function CardsPanel(): ReactNode {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const [tab, setTab] = useState<CardTab>('characters');
  const [extractOpen, setExtractOpen] = useState(false);

  return (
    <Panel
      title="卡片"
      scroll={false}
      actions={
        <Button tone="ghost" size="xs" icon={<FileScan />} onClick={() => setExtractOpen(true)}>
          提取
        </Button>
      }
    >
      <Tabs
        value={tab}
        onValueChange={setTab}
        size="sm"
        items={[
          { value: 'characters', label: '人物', icon: <User /> },
          { value: 'world', label: '世界', icon: <Globe /> },
          { value: 'style', label: '文风', icon: <BookMarked /> },
        ]}
        className="h-full"
      >
        <TabPanel value="characters" className="p-2.5">
          <CharactersTab projectId={projectId} />
        </TabPanel>
        <TabPanel value="world" className="p-2.5">
          <WorldTab projectId={projectId} />
        </TabPanel>
        <TabPanel value="style" className="p-2.5">
          <StyleTab projectId={projectId} />
        </TabPanel>
      </Tabs>

      {extractOpen && (
        <LazyOverlay>
          <ExtractCardsDialog projectId={projectId} onClose={() => setExtractOpen(false)} />
        </LazyOverlay>
      )}
    </Panel>
  );
}

/** 星级选择/展示。 */
function Stars({
  value,
  onChange,
}: {
  value: number;
  onChange?: (stars: number) => void;
}): ReactNode {
  return (
    <span className="inline-flex items-center gap-0.5">
      {[1, 2, 3].map((n) => (
        <button
          key={n}
          type="button"
          disabled={onChange === undefined}
          onClick={() => onChange?.(n)}
          aria-label={`${n} 星`}
          className={cn(
            'text-gamboge-400 disabled:cursor-default [&_svg]:size-3',
            onChange !== undefined && 'hover:scale-110',
          )}
        >
          <Star fill={n <= value ? 'currentColor' : 'none'} className={n <= value ? '' : 'text-ink-700'} />
        </button>
      ))}
    </span>
  );
}

/** CSV 输入 → string[]，保存时调用。 */
function csvToList(text: string): string[] {
  return text
    .split(/[,，、\n]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function CharactersTab({ projectId }: { projectId: string }): ReactNode {
  const cards = useCharacterCards(projectId);
  const remove = useDeleteCharacterCard(projectId);
  const [search, setSearch] = useState('');
  const [openName, setOpenName] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const list = cards.data ?? [];
    const key = search.trim();
    if (key === '') return list;
    return list.filter(
      (card) => card.name.includes(key) || card.aliases.some((alias) => alias.includes(key)),
    );
  }, [cards.data, search]);

  if (cards.isPending) return <SkeletonList rows={5} />;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-1.5">
        <Input
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="搜索人物…"
          className="h-7 flex-1"
        />
        <Button
          tone="ghost"
          size="xs"
          icon={<Plus />}
          onClick={() => {
            setCreating(true);
            setOpenName(null);
          }}
        >
          新建
        </Button>
      </div>

      {creating && (
        <CharacterEditor
          projectId={projectId}
          initial={{ name: '', aliases: [], description: '', stars: 1 }}
          create
          onDone={() => setCreating(false)}
        />
      )}

      {filtered.map((card) => (
        <section key={card.name} className="rounded-md border border-ink-800">
          <button
            type="button"
            aria-expanded={openName === card.name}
            onClick={() => setOpenName(openName === card.name ? null : card.name)}
            className="flex w-full items-center gap-1.5 px-2 py-1.5 text-left hover:bg-ink-850"
          >
            <span className="text-ink-500 [&_svg]:size-3.5">
              {openName === card.name ? <ChevronDown /> : <ChevronRight />}
            </span>
            <span className="min-w-0 flex-1 truncate text-xs text-ink-200">{card.name}</span>
            <Stars value={card.stars} />
          </button>
          {openName === card.name && (
            <CharacterEditor
              projectId={projectId}
              initial={card}
              onDone={() => setOpenName(null)}
              onDelete={() => setDeleting(card.name)}
            />
          )}
        </section>
      ))}

      {filtered.length === 0 && !creating && (
        <EmptyState
          title={search === '' ? '还没有人物卡' : '没搜到'}
          description="新建或用「提取」从大纲/正文里抠。"
        />
      )}

      {deleting !== null && (
        <ConfirmDialog
          open
          onOpenChange={(open) => {
            if (!open) setDeleting(null);
          }}
          title={`删除人物卡「${deleting}」？`}
          description="只删卡片，不动正文和事实。"
          confirmLabel="删除"
          danger
          onConfirm={() => {
            remove.mutate(deleting);
            setDeleting(null);
          }}
        />
      )}
    </div>
  );
}

function CharacterEditor({
  projectId,
  initial,
  create = false,
  onDone,
  onDelete,
}: {
  projectId: string;
  initial: CharacterCard;
  create?: boolean;
  onDone: () => void;
  onDelete?: () => void;
}): ReactNode {
  const save = useSaveCharacterCard(projectId);
  const generate = useGenerateCardDescription(projectId);
  const [card, setCard] = useState({
    name: initial.name,
    aliases: initial.aliases.join('，'),
    description: initial.description ?? '',
    stars: initial.stars,
  });

  return (
    <div className="flex flex-col gap-2 border-t border-ink-800 p-2.5">
      <div className="flex items-center gap-2">
        <Input
          value={card.name}
          onChange={(event) => setCard((c) => ({ ...c, name: event.target.value }))}
          placeholder="名字"
          className="h-7 flex-1"
          disabled={!create}
        />
        <Stars value={card.stars} onChange={(stars) => setCard((c) => ({ ...c, stars }))} />
      </div>
      <Input
        value={card.aliases}
        onChange={(event) => setCard((c) => ({ ...c, aliases: event.target.value }))}
        placeholder="别名（逗号分隔）"
        className="h-7"
      />
      <Textarea
        value={card.description}
        onChange={(event) => setCard((c) => ({ ...c, description: event.target.value }))}
        rows={4}
        autoGrow
        placeholder="人物描述：身份、目标、性格、关系…"
      />
      <div className="flex items-center gap-1.5">
        <Button
          tone="ghost"
          size="xs"
          icon={<Sparkles />}
          loading={generate.isPending}
          disabled={card.name.trim() === ''}
          onClick={() =>
            generate.mutate(
              {
                card_type: 'character',
                name: card.name.trim(),
                note: card.description.trim() || undefined,
              },
              {
                onSuccess: (data) => {
                  const text = String((data as Record<string, unknown>).description ?? '');
                  if (text !== '') setCard((c) => ({ ...c, description: text }));
                },
              },
            )
          }
        >
          AI 补写
        </Button>
        <span className="flex-1" />
        {onDelete !== undefined && (
          <Button tone="dangerGhost" size="xs" icon={<Trash2 />} onClick={onDelete}>
            删除
          </Button>
        )}
        <Button tone="ghost" size="xs" onClick={onDone}>
          收起
        </Button>
        <Button
          tone="primary"
          size="xs"
          loading={save.isPending}
          disabled={card.name.trim() === ''}
          onClick={() =>
            save.mutate(
              {
                name: initial.name,
                create,
                card: {
                  name: card.name.trim(),
                  aliases: csvToList(card.aliases),
                  description: card.description,
                  stars: card.stars,
                },
              },
              { onSuccess: onDone },
            )
          }
        >
          保存
        </Button>
      </div>
    </div>
  );
}

const WORLD_CATEGORIES = [
  { value: 'location', label: '地点' },
  { value: 'organization', label: '组织' },
  { value: 'race', label: '种族' },
  { value: 'item', label: '物品' },
  { value: 'power', label: '力量体系' },
  { value: 'rule', label: '规则' },
  { value: 'concept', label: '概念' },
];

function WorldTab({ projectId }: { projectId: string }): ReactNode {
  const cards = useWorldCards(projectId);
  const remove = useDeleteWorldCard(projectId);
  const [search, setSearch] = useState('');
  const [openName, setOpenName] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const list = cards.data ?? [];
    const key = search.trim();
    if (key === '') return list;
    return list.filter(
      (card) => card.name.includes(key) || card.aliases.some((alias) => alias.includes(key)),
    );
  }, [cards.data, search]);

  if (cards.isPending) return <SkeletonList rows={5} />;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-1.5">
        <Input
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="搜索世界卡…"
          className="h-7 flex-1"
        />
        <Button
          tone="ghost"
          size="xs"
          icon={<Plus />}
          onClick={() => {
            setCreating(true);
            setOpenName(null);
          }}
        >
          新建
        </Button>
      </div>

      {creating && (
        <WorldEditor
          projectId={projectId}
          initial={{
            name: '',
            aliases: [],
            description: '',
            category: 'concept',
            rules: [],
            immutable: null,
            stars: 1,
          }}
          create
          onDone={() => setCreating(false)}
        />
      )}

      {filtered.map((card) => (
        <section key={card.name} className="rounded-md border border-ink-800">
          <button
            type="button"
            aria-expanded={openName === card.name}
            onClick={() => setOpenName(openName === card.name ? null : card.name)}
            className="flex w-full items-center gap-1.5 px-2 py-1.5 text-left hover:bg-ink-850"
          >
            <span className="text-ink-500 [&_svg]:size-3.5">
              {openName === card.name ? <ChevronDown /> : <ChevronRight />}
            </span>
            <span className="min-w-0 flex-1 truncate text-xs text-ink-200">{card.name}</span>
            {card.immutable === true && <Badge tone="warning">不变量</Badge>}
            {card.category !== null && card.category !== '' && (
              <Badge tone="outline">
                {WORLD_CATEGORIES.find((c) => c.value === card.category)?.label ?? card.category}
              </Badge>
            )}
            <Stars value={card.stars} />
          </button>
          {openName === card.name && (
            <WorldEditor
              projectId={projectId}
              initial={card}
              onDone={() => setOpenName(null)}
              onDelete={() => setDeleting(card.name)}
            />
          )}
        </section>
      ))}

      {filtered.length === 0 && !creating && (
        <EmptyState title={search === '' ? '还没有世界卡' : '没搜到'} />
      )}

      {deleting !== null && (
        <ConfirmDialog
          open
          onOpenChange={(open) => {
            if (!open) setDeleting(null);
          }}
          title={`删除世界卡「${deleting}」？`}
          confirmLabel="删除"
          danger
          onConfirm={() => {
            remove.mutate(deleting);
            setDeleting(null);
          }}
        />
      )}
    </div>
  );
}

function WorldEditor({
  projectId,
  initial,
  create = false,
  onDone,
  onDelete,
}: {
  projectId: string;
  initial: WorldCard;
  create?: boolean;
  onDone: () => void;
  onDelete?: () => void;
}): ReactNode {
  const save = useSaveWorldCard(projectId);
  const generate = useGenerateCardDescription(projectId);
  const [card, setCard] = useState({
    name: initial.name,
    aliases: initial.aliases.join('，'),
    description: initial.description ?? '',
    category: initial.category ?? 'concept',
    rules: initial.rules.join('\n'),
    immutable: initial.immutable === true,
    stars: initial.stars,
  });

  return (
    <div className="flex flex-col gap-2 border-t border-ink-800 p-2.5">
      <div className="flex items-center gap-2">
        <Input
          value={card.name}
          onChange={(event) => setCard((c) => ({ ...c, name: event.target.value }))}
          placeholder="名称"
          className="h-7 flex-1"
          disabled={!create}
        />
        <Stars value={card.stars} onChange={(stars) => setCard((c) => ({ ...c, stars }))} />
      </div>
      <div className="flex items-center gap-2">
        <Select
          value={card.category}
          onValueChange={(value) => setCard((c) => ({ ...c, category: value }))}
          options={WORLD_CATEGORIES}
          className="w-36"
        />
        <label className="flex cursor-pointer items-center gap-1.5 text-2xs text-ink-400">
          <input
            type="checkbox"
            checked={card.immutable}
            onChange={(event) => setCard((c) => ({ ...c, immutable: event.target.checked }))}
            className="accent-celadon-500"
          />
          不可违背
        </label>
      </div>
      <Input
        value={card.aliases}
        onChange={(event) => setCard((c) => ({ ...c, aliases: event.target.value }))}
        placeholder="别名（逗号分隔）"
        className="h-7"
      />
      <Textarea
        value={card.description}
        onChange={(event) => setCard((c) => ({ ...c, description: event.target.value }))}
        rows={4}
        autoGrow
        placeholder="设定描述…"
      />
      <Textarea
        value={card.rules}
        onChange={(event) => setCard((c) => ({ ...c, rules: event.target.value }))}
        rows={2}
        autoGrow
        placeholder="规则（每行一条）"
        className="font-mono text-2xs"
      />
      <div className="flex items-center gap-1.5">
        <Button
          tone="ghost"
          size="xs"
          icon={<Sparkles />}
          loading={generate.isPending}
          disabled={card.name.trim() === ''}
          onClick={() =>
            generate.mutate(
              {
                card_type: 'world',
                name: card.name.trim(),
                note: card.description.trim() || undefined,
              },
              {
                onSuccess: (data) => {
                  const text = String((data as Record<string, unknown>).description ?? '');
                  if (text !== '') setCard((c) => ({ ...c, description: text }));
                },
              },
            )
          }
        >
          AI 补写
        </Button>
        <span className="flex-1" />
        {onDelete !== undefined && (
          <Button tone="dangerGhost" size="xs" icon={<Trash2 />} onClick={onDelete}>
            删除
          </Button>
        )}
        <Button tone="ghost" size="xs" onClick={onDone}>
          收起
        </Button>
        <Button
          tone="primary"
          size="xs"
          loading={save.isPending}
          disabled={card.name.trim() === ''}
          onClick={() =>
            save.mutate(
              {
                name: initial.name,
                create,
                card: {
                  name: card.name.trim(),
                  aliases: csvToList(card.aliases),
                  description: card.description,
                  category: card.category,
                  rules: card.rules
                    .split('\n')
                    .map((r) => r.trim())
                    .filter(Boolean),
                  immutable: card.immutable ? true : null,
                  stars: card.stars,
                },
              },
              { onSuccess: onDone },
            )
          }
        >
          保存
        </Button>
      </div>
    </div>
  );
}

function StyleTab({ projectId }: { projectId: string }): ReactNode {
  const style = useStyleCard(projectId);
  const save = useSaveStyleCard(projectId);
  const [draft, setDraft] = useState<{ style: string; excerpts: string } | null>(null);
  const [extractText, setExtractText] = useState('');

  const extract = useMutation({
    mutationFn: () => cardsApi.extractStyle(projectId, { content: extractText }),
    onSuccess: (data) => {
      const text = String((data as Record<string, unknown>).style ?? '');
      if (text !== '') {
        setDraft((current) => ({
          style: text,
          excerpts: current?.excerpts ?? (style.data?.representative_excerpts ?? []).join('\n'),
        }));
      }
      toast.success('文风已提炼，检查后保存');
    },
    onError: (error) => toast.error(reason(error, '提炼失败')),
  });

  const missing = style.isError && style.error instanceof ApiError && style.error.status === 404;
  if (style.isPending) return <SkeletonList rows={5} />;

  const current = draft ?? {
    style: missing ? '' : (style.data?.style ?? ''),
    excerpts: missing ? '' : (style.data?.representative_excerpts ?? []).join('\n'),
  };

  return (
    <div className="flex flex-col gap-2.5">
      <label className="flex flex-col gap-1">
        <span className="text-2xs text-ink-500">文风描述</span>
        <Textarea
          value={current.style}
          onChange={(event) => setDraft({ ...current, style: event.target.value })}
          rows={5}
          autoGrow
          placeholder="叙事视角、句式特点、用词风格、情感基调、节奏…"
        />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-2xs text-ink-500">代表性段落（每行一段）</span>
        <Textarea
          value={current.excerpts}
          onChange={(event) => setDraft({ ...current, excerpts: event.target.value })}
          rows={4}
          autoGrow
          className="font-serif"
        />
      </label>
      {!missing && (style.data?.source_chapters.length ?? 0) > 0 && (
        <p className="text-2xs text-ink-600">提炼自：{style.data?.source_chapters.join('、')}</p>
      )}
      <Button
        tone="primary"
        size="sm"
        loading={save.isPending}
        onClick={() =>
          save.mutate(
            {
              style: current.style,
              representative_excerpts: current.excerpts
                .split('\n')
                .map((s) => s.trim())
                .filter(Boolean),
              source_chapters: style.data?.source_chapters ?? [],
            },
            { onSuccess: () => setDraft(null) },
          )
        }
      >
        保存文风卡
      </Button>

      <div className="flex flex-col gap-1.5 rounded-md border border-ink-800 bg-ink-900/40 p-2.5">
        <p className="text-2xs text-ink-500">粘贴一段正文，让模型提炼文风：</p>
        <Textarea
          value={extractText}
          onChange={(event) => setExtractText(event.target.value)}
          rows={3}
          placeholder="贴一段代表性正文…"
        />
        <Button
          tone="default"
          size="xs"
          icon={<Sparkles />}
          loading={extract.isPending}
          disabled={extractText.trim() === ''}
          onClick={() => extract.mutate()}
        >
          提炼文风
        </Button>
      </div>
    </div>
  );
}
