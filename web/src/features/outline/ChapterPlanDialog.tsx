import { useState, type ReactNode } from 'react';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Flag, Goal, ListChecks, Scissors, Trash2 } from 'lucide-react';
import { outlineApi } from '@/api';
import type { PlannedChapter } from '@/api/types';
import { ApiError } from '@/lib/http';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, Dialog, Input } from '@/components/ui';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/**
 * 章节规划预览：plan-chapters / exploration 的产物在应用前过一遍人工筛。
 * 可以删掉规划里不想要的章，也可以对某一章再做 AI 拆分。
 */
export function ChapterPlanDialog({
  projectId,
  volumeId,
  title,
  initialChapters,
  applying,
  onApply,
  onClose,
}: {
  projectId: string;
  volumeId: string;
  title: string;
  initialChapters: PlannedChapter[];
  applying: boolean;
  onApply: (chapters: PlannedChapter[]) => void;
  onClose: () => void;
}): ReactNode {
  const [chapters, setChapters] = useState<PlannedChapter[]>(initialChapters);
  const [splitTarget, setSplitTarget] = useState<number | null>(null);
  const [instruction, setInstruction] = useState('');

  const split = useMutation({
    mutationFn: ({ chapter, instruction: hint }: { chapter: PlannedChapter; instruction: string }) =>
      outlineApi.splitPlannedChapter(projectId, volumeId, {
        instruction: hint,
        chapter_id: chapter.chapter_id,
        chapter,
      }),
    onSuccess: (result, { chapter }) => {
      if (result.chapters.length === 0) {
        toast.error('模型没有给出可用的拆分结果');
        return;
      }
      setChapters((current) => {
        const index = current.findIndex((item) => item.chapter_id === chapter.chapter_id);
        if (index === -1) return current;
        return [...current.slice(0, index), ...result.chapters, ...current.slice(index + 1)];
      });
      setSplitTarget(null);
      setInstruction('');
      toast.success(`已拆为 ${result.chapters.length} 章`);
    },
    onError: (error) => toast.error(reason(error, '拆分失败')),
  });

  const remove = (chapterId: string): void => {
    setChapters((current) => current.filter((item) => item.chapter_id !== chapterId));
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={title}
      description={`${chapters.length} 章将写入 ${volumeId}；应用后章节、摘要和章纲一并建好。`}
      size="lg"
      dismissible={!applying && !split.isPending}
      footer={
        <>
          <Button tone="outline" onClick={onClose} disabled={applying}>
            取消
          </Button>
          <Button
            tone="primary"
            onClick={() => onApply(chapters)}
            loading={applying}
            disabled={chapters.length === 0}
          >
            应用 {chapters.length} 章规划
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-2">
        {chapters.map((chapter, index) => (
          <section
            key={`${chapter.chapter_id}-${index}`}
            className="rounded-md border border-ink-800 bg-ink-900/60 px-3 py-2.5"
          >
            <header className="flex items-center gap-2">
              <span className="tnum shrink-0 text-2xs text-celadon-400">
                {formatChapterLabel(chapter.chapter_id)}
              </span>
              <span className="truncate text-xs font-medium text-ink-100">{chapter.title}</span>
              <span className="ml-auto flex shrink-0 gap-0.5">
                <Button
                  tone="ghost"
                  size="xs"
                  icon={<Scissors />}
                  onClick={() => {
                    setSplitTarget(splitTarget === index ? null : index);
                    setInstruction('');
                  }}
                >
                  拆分
                </Button>
                <Button tone="dangerGhost" size="xs" icon={<Trash2 />} onClick={() => remove(chapter.chapter_id)}>
                  移除
                </Button>
              </span>
            </header>

            {chapter.summary !== '' && (
              <p className="mt-1.5 text-2xs leading-relaxed text-ink-300">{chapter.summary}</p>
            )}

            <div className="mt-2 flex flex-col gap-1 text-2xs">
              {chapter.goal !== '' && (
                <p className="flex items-start gap-1.5 text-ink-400">
                  <Goal className="mt-0.5 size-3 shrink-0 text-celadon-500" />
                  {chapter.goal}
                </p>
              )}
              {chapter.key_beats.length > 0 && (
                <p className="flex items-start gap-1.5 text-ink-400">
                  <ListChecks className="mt-0.5 size-3 shrink-0 text-celadon-500" />
                  {chapter.key_beats.join(' → ')}
                </p>
              )}
              {chapter.ending_hook !== '' && (
                <p className="flex items-start gap-1.5 text-gamboge-300/90">
                  <Flag className="mt-0.5 size-3 shrink-0" />
                  钩子：{chapter.ending_hook}
                </p>
              )}
              {chapter.must_not_include.length > 0 && (
                <div className="flex flex-wrap gap-1 pt-0.5">
                  {chapter.must_not_include.map((item) => (
                    <Badge key={item} tone="danger">
                      勿写：{item}
                    </Badge>
                  ))}
                </div>
              )}
            </div>

            {splitTarget === index && (
              <div className="mt-2 flex items-center gap-2 border-t border-ink-800 pt-2">
                <Input
                  value={instruction}
                  onChange={(event) => setInstruction(event.target.value)}
                  placeholder="怎么拆？例如：把对峙戏单独成章"
                  className="h-7 flex-1"
                  autoFocus
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && instruction.trim() !== '') {
                      split.mutate({ chapter, instruction: instruction.trim() });
                    }
                  }}
                />
                <Button
                  tone="primary"
                  size="sm"
                  loading={split.isPending}
                  disabled={instruction.trim() === ''}
                  onClick={() => split.mutate({ chapter, instruction: instruction.trim() })}
                >
                  拆分
                </Button>
              </div>
            )}
          </section>
        ))}

        {chapters.length === 0 && (
          <p className="py-8 text-center text-xs text-ink-500">规划里的章都被移除了。</p>
        )}
      </div>
    </Dialog>
  );
}
