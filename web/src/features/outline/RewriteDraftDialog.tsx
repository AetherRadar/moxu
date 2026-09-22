import { useState, type ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Sparkles } from 'lucide-react';
import { draftsApi, outlineApi, qk } from '@/api';
import type { JsonRecord } from '@/api/types';
import { ApiError } from '@/lib/http';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, Dialog, EmptyState, Input, Select, SkeletonList, Textarea } from '@/components/ui';
import { useDraft } from '@/features/writing/queries';
import { DiffPreview } from '@/features/writing/DiffPreview';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

type Mode = 'draft' | 'scene';

/**
 * 按纲重构正文。
 *
 * 两种粒度都走 outline 的重写端点，模型只回修订稿不落盘；前端用 DiffPreview
 * 展示差异，确认后才通过 drafts/update-content 落盘（会连带刷新 review 与记忆包）。
 * 对比基准是当前最新正文，后端读的是 final.md，两者不一致时以正文为准。
 */
export function RewriteDraftDialog({
  projectId,
  chapterId,
  hasOutline,
  onClose,
}: {
  projectId: string;
  chapterId: string;
  hasOutline: boolean;
  onClose: () => void;
}): ReactNode {
  const client = useQueryClient();
  const draft = useDraft(projectId, chapterId);

  const [mode, setMode] = useState<Mode>('draft');
  const [instruction, setInstruction] = useState('');
  const [sceneIndex, setSceneIndex] = useState('1');
  const [revised, setRevised] = useState<string | null>(null);
  const [original, setOriginal] = useState('');

  const rewrite = useMutation({
    mutationFn: () => {
      const body: JsonRecord = {};
      if (instruction.trim() !== '') body.instruction = instruction.trim();
      const base = draft.data?.content ?? '';
      setOriginal(base);
      if (mode === 'scene') {
        body.scene_index = Number.parseInt(sceneIndex, 10) || 0;
        return outlineApi.rewriteScene(projectId, chapterId, body);
      }
      return outlineApi.rewriteDraft(projectId, chapterId, body);
    },
    onSuccess: (data) => {
      if (data.success === false) {
        toast.error(String(data.error ?? '没有产生可用的重构稿'));
        setRevised(null);
        return;
      }
      setRevised(typeof data.revised_content === 'string' ? data.revised_content : null);
    },
    onError: (error) => toast.error(reason(error, '按纲重构失败')),
  });

  const apply = useMutation({
    mutationFn: (content: string) => draftsApi.updateContent(projectId, chapterId, { content }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.draft(projectId, chapterId) });
      void client.invalidateQueries({ queryKey: qk.draftReview(projectId, chapterId) });
      void client.invalidateQueries({ queryKey: qk.chapterSummaries(projectId) });
      toast.success('已按纲重构并保存');
      onClose();
    },
    onError: (error) => toast.error(reason(error, '保存失败')),
  });
  const busy = rewrite.isPending || apply.isPending;

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={'按纲重构 · ' + formatChapterLabel(chapterId)}
      description="模型按章纲重写正文，只回修订稿；确认差异后才落盘。"
      size="lg"
      dismissible={!busy}
      footer={
        revised === null ? (
          <>
            <Button tone="outline" onClick={onClose} disabled={rewrite.isPending}>
              取消
            </Button>
            <Button
              tone="primary"
              icon={<Sparkles />}
              loading={rewrite.isPending}
              disabled={!hasOutline}
              onClick={() => rewrite.mutate()}
            >
              生成重构稿
            </Button>
          </>
        ) : (
          <>
            <Button tone="outline" onClick={() => setRevised(null)} disabled={apply.isPending}>
              再改一版
            </Button>
            <Button tone="primary" loading={apply.isPending} onClick={() => apply.mutate(revised)}>
              应用并落盘
            </Button>
          </>
        )
      }
    >
      {!hasOutline ? (
        <EmptyState
          title="这一章还没有章纲"
          description="先在下方写一段可执行章纲（必须保留 / 必增强 / 结尾落点），再回来重构。"
        />
      ) : draft.isPending ? (
        <SkeletonList rows={6} />
      ) : revised === null ? (
        <div className="flex flex-col gap-3">
          <div className="flex items-center gap-2">
            <span className="text-2xs text-ink-500">粒度</span>
            <Select
              value={mode}
              onValueChange={(value) => setMode(value as Mode)}
              options={[
                { value: 'draft', label: '整章', hint: '按章纲重排全章结构与场景顺序' },
                { value: 'scene', label: '单个场景', hint: '只重写章纲里的第 N 个场景' },
              ]}
              className="w-44"
            />
            {mode === 'scene' && (
              <Input
                value={sceneIndex}
                onChange={(event) => setSceneIndex(event.target.value)}
                inputMode="numeric"
                className="h-8 w-20"
                aria-label="场景序号"
              />
            )}
          </div>
          <Textarea
            value={instruction}
            onChange={(event) => setInstruction(event.target.value)}
            rows={3}
            placeholder="补充要求（可空）：例如保留对话、把战斗压缩到半页；场景模式建议写清这个场景的落点"
          />
          <p className="text-2xs leading-relaxed text-ink-600">
            章纲取已保存的版本；重构稿不会自动覆盖正文，看到 diff 后再决定。
          </p>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-2 text-2xs text-ink-500">
            <Badge tone="accent">{revised.length.toLocaleString('zh-CN')} 字</Badge>
            <span>与当前正文对比</span>
          </div>
          <DiffPreview original={original} revised={revised} className="max-h-[50vh] overflow-y-auto" />
        </div>
      )}
    </Dialog>
  );
}
