import { useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { AlertTriangle, CheckCircle2, FileScan } from 'lucide-react';
import { cardsApi, draftsApi, outlineApi, qk } from '@/api';
import type { JsonRecord, ProjectOutline } from '@/api/types';
import { ApiError } from '@/lib/http';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, Dialog, EmptyState, Textarea } from '@/components/ui';
import { useChapterTree } from '@/features/chapters/queries';
import { useSaveCharacterCard, useSaveStyleCard, useSaveWorldCard, useStyleCard } from '@/features/cards/queries';

type Module = 'character' | 'world' | 'style';

interface Source {
  id: string;
  label: string;
  content: string;
}

interface Candidate {
  card_type: string;
  name: string;
  description: string;
  aliases: string[];
  stars: number;
  category: string;
  rules: string[];
  source_ids: string[];
  evidence: string;
  evidence_verified: boolean;
  uncertainty: string;
}

const MODULE_LABEL: Record<Module, string> = {
  character: '人物',
  world: '世界',
  style: '文风',
};

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/**
 * 从材料提取卡片候选。
 * 材料 = 总纲 + 分卷纲 + 勾选章节正文 + 补充说明；
 * 每个模块独立跑（后端 3 并发上限 + 独立失败），结果逐张勾选入库。
 */
export function ExtractCardsDialog({
  projectId,
  onClose,
}: {
  projectId: string;
  onClose: () => void;
}): ReactNode {
  const client = useQueryClient();
  const tree = useChapterTree(projectId);
  const style = useStyleCard(projectId);
  const saveCharacter = useSaveCharacterCard(projectId);
  const saveWorld = useSaveWorldCard(projectId);
  const saveStyle = useSaveStyleCard(projectId);

  const [modules, setModules] = useState<Set<Module>>(new Set(['character', 'world']));
  const [pickedChapters, setPickedChapters] = useState<Set<string>>(new Set());
  const [note, setNote] = useState('');
  const [running, setRunning] = useState(false);
  const [results, setResults] = useState<Record<Module, Candidate[]>>({
    character: [],
    world: [],
    style: [],
  });
  const [errors, setErrors] = useState<Partial<Record<Module, string>>>({});
  const [adopted, setAdopted] = useState<Set<string>>(new Set());

  const allChapters = useMemo(() => tree.volumes.flatMap((v) => v.chapters), [tree.volumes]);
  const totalCards = results.character.length + results.world.length + results.style.length;
  const ran = running || totalCards > 0 || Object.keys(errors).length > 0;

  const toggleModule = (module: Module): void => {
    setModules((current) => {
      const next = new Set(current);
      if (next.has(module)) next.delete(module);
      else next.add(module);
      return next;
    });
  };

  const toggleChapter = (id: string): void => {
    setPickedChapters((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const run = async (): Promise<void> => {
    setRunning(true);
    setErrors({});
    setResults({ character: [], world: [], style: [] });

    // ── 收集材料 ──
    const sources: Source[] = [];
    try {
      const outline: ProjectOutline = await outlineApi.get(projectId);
      const master = outline.master.content ?? '';
      if (master.trim() !== '') {
        sources.push({ id: 'outline:master', label: '总纲', content: master });
      }
      for (const volume of outline.volumes) {
        const content = volume.content ?? '';
        if (content.trim() !== '') {
          sources.push({
            id: `outline:${volume.volume_id}`,
            label: `分卷纲 ${volume.volume_id} ${volume.title}`,
            content,
          });
        }
      }
    } catch {
      // 大纲读不到不阻塞，章节/补充照样能提
    }

    const chapterIds = [...pickedChapters];
    const contents = await Promise.allSettled(
      chapterIds.map((id) => draftsApi.latest(projectId, id)),
    );
    contents.forEach((outcome, index) => {
      if (outcome.status === 'fulfilled' && outcome.value.content.trim() !== '') {
        const id = chapterIds[index] ?? '';
        const node = allChapters.find((c) => c.id === id);
        sources.push({
          id: `chapter:${id}`,
          label: `${formatChapterLabel(id)} ${node?.title ?? ''}`.trim(),
          content: outcome.value.content,
        });
      }
    });

    if (note.trim() !== '') {
      sources.push({ id: 'note:extra', label: '作者补充', content: note.trim() });
    }

    if (sources.length === 0) {
      setRunning(false);
      toast.error('没有可用材料：勾选几章、写点大纲或补充说明');
      return;
    }

    // ── 逐模块跑，失败互不影响 ──
    const moduleErrors: Partial<Record<Module, string>> = {};
    const moduleResults: Record<Module, Candidate[]> = { character: [], world: [], style: [] };
    await Promise.all(
      [...modules].map(async (module) => {
        try {
          const data = (await cardsApi.extractCandidates(projectId, module, { sources })) as JsonRecord;
          moduleResults[module] = (data.cards as Candidate[]) ?? [];
        } catch (error) {
          moduleErrors[module] = reason(error, '提取失败');
        }
      }),
    );
    setResults(moduleResults);
    setErrors(moduleErrors);
    setRunning(false);
  };

  const adopt = (module: Module, card: Candidate): void => {
    const key = `${module}:${card.name}`;
    if (module === 'character') {
      saveCharacter.mutate({
        name: card.name,
        create: true,
        card: { name: card.name, aliases: card.aliases, description: card.description, stars: card.stars },
      });
    } else if (module === 'world') {
      saveWorld.mutate({
        name: card.name,
        create: true,
        card: {
          name: card.name,
          aliases: card.aliases,
          description: card.description,
          category: card.category || 'concept',
          rules: card.rules,
          immutable: null,
          stars: card.stars,
        },
      });
    } else {
      saveStyle.mutate({
        style: card.description,
        representative_excerpts: card.evidence !== '' ? [card.evidence] : [],
        source_chapters: style.data?.source_chapters ?? [],
      });
      void client.invalidateQueries({ queryKey: qk.styleCard(projectId) });
    }
    setAdopted((current) => new Set(current).add(key));
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title="从材料提取卡片"
      description="模型从选定材料抠人物/世界/文风候选，逐张审核入库。"
      size="lg"
      dismissible={!running}
      footer={
        ran ? (
          <Button tone="primary" onClick={onClose}>
            完成
          </Button>
        ) : (
          <>
            <Button tone="outline" onClick={onClose}>
              取消
            </Button>
            <Button
              tone="primary"
              icon={<FileScan />}
              loading={running}
              disabled={modules.size === 0}
              onClick={() => void run()}
            >
              开始提取
            </Button>
          </>
        )
      }
    >
      {!ran ? (
        <div className="flex flex-col gap-3">
          <div className="flex gap-4">
            {(Object.keys(MODULE_LABEL) as Module[]).map((module) => (
              <label key={module} className="flex cursor-pointer items-center gap-1.5 text-xs text-ink-300">
                <input
                  type="checkbox"
                  checked={modules.has(module)}
                  onChange={() => toggleModule(module)}
                  className="accent-celadon-500"
                />
                {MODULE_LABEL[module]}
              </label>
            ))}
          </div>

          <div>
            <p className="mb-1 text-2xs text-ink-500">章节材料（多选，正文纳入提取）：</p>
            <div className="max-h-40 overflow-y-auto rounded-md border border-ink-800 p-1.5">
              {allChapters.map((chapter) => (
                <label
                  key={chapter.id}
                  className="flex cursor-pointer items-center gap-2 rounded px-1.5 py-1 text-2xs text-ink-300 hover:bg-ink-850"
                >
                  <input
                    type="checkbox"
                    checked={pickedChapters.has(chapter.id)}
                    onChange={() => toggleChapter(chapter.id)}
                    className="accent-celadon-500"
                  />
                  <span className="tnum text-ink-500">{formatChapterLabel(chapter.id)}</span>
                  <span className="truncate">{chapter.title}</span>
                </label>
              ))}
              {allChapters.length === 0 && <p className="p-2 text-2xs text-ink-600">还没有章节。</p>}
            </div>
            <p className="mt-1 text-2xs text-ink-600">总纲与分卷纲会自动纳入材料。</p>
          </div>

          <Textarea
            value={note}
            onChange={(event) => setNote(event.target.value)}
            rows={3}
            placeholder="补充说明（可选）：想重点提取什么、纠正什么…"
          />
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {(Object.keys(MODULE_LABEL) as Module[]).map((module) => {
            const error = errors[module];
            const cards = results[module];
            if (!modules.has(module)) return null;
            return (
              <section key={module}>
                <p className="mb-1.5 text-xs font-medium text-ink-200">
                  {MODULE_LABEL[module]}
                  {cards.length > 0 && <span className="ml-1.5 text-ink-500">{cards.length} 张</span>}
                </p>
                {error !== undefined && (
                  <p className="flex items-center gap-1.5 text-2xs text-cinnabar-400">
                    <AlertTriangle className="size-3" />
                    {error}
                  </p>
                )}
                <div className="flex flex-col gap-1.5">
                  {cards.map((card) => {
                    const key = `${module}:${card.name}`;
                    const done = adopted.has(key);
                    return (
                      <div
                        key={key}
                        className="rounded-md border border-ink-800 bg-ink-900/50 px-3 py-2"
                      >
                        <div className="flex items-center gap-2">
                          <span className="min-w-0 flex-1 truncate text-xs font-medium text-ink-100">
                            {card.name}
                          </span>
                          {card.evidence_verified && (
                            <Badge tone="success">有原文依据</Badge>
                          )}
                          {card.category !== '' && <Badge tone="outline">{card.category}</Badge>}
                          <Button
                            tone={done ? 'ghost' : 'primary'}
                            size="xs"
                            disabled={done}
                            icon={done ? <CheckCircle2 /> : undefined}
                            onClick={() => adopt(module, card)}
                          >
                            {done ? '已入库' : '入库'}
                          </Button>
                        </div>
                        <p className="mt-1 text-2xs leading-relaxed text-ink-300">{card.description}</p>
                        {card.uncertainty !== '' && (
                          <p className="mt-1 text-2xs text-gamboge-400">待确认：{card.uncertainty}</p>
                        )}
                        {card.source_ids.length > 0 && (
                          <p className="mt-1 text-2xs text-ink-600">
                            来源：{card.source_ids.join('、')}
                          </p>
                        )}
                      </div>
                    );
                  })}
                </div>
                {error === undefined && cards.length === 0 && !running && (
                  <p className="text-2xs text-ink-600">这个模块没抠到东西。</p>
                )}
              </section>
            );
          })}
          {running && <EmptyState title="提取中…" description="每个模块独立跑，可能要等一会儿。" />}
        </div>
      )}
    </Dialog>
  );
}
