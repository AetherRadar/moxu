import { type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { draftsApi, qk } from '@/api';
import { ApiError } from '@/lib/http';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, Dialog, EmptyState, SkeletonList } from '@/components/ui';

/**
 * 终稿查看。
 *
 * 会话里 confirm 之后后端才会写 final.md，这里只读展示；没有终稿时给出口径
 * 说明，而不是一个裸错误。
 */
export function FinalDraftDialog({
  projectId,
  chapter,
  onClose,
}: {
  projectId: string;
  chapter: string;
  onClose: () => void;
}): ReactNode {
  const final = useQuery({
    queryKey: qk.draftFinal(projectId, chapter),
    queryFn: () => draftsApi.final(projectId, chapter),
    retry: false,
  });

  const missing = final.isError && final.error instanceof ApiError && final.error.isNotFound;
  const content = typeof final.data?.content === 'string' ? final.data.content : '';

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={'终稿 · ' + formatChapterLabel(chapter)}
      description="AI 写章会话里确认定稿后落下的版本，只读。"
      size="lg"
      footer={
        <Button tone="outline" onClick={onClose}>
          关闭
        </Button>
      }
    >
      {final.isPending ? (
        <SkeletonList rows={6} />
      ) : missing ? (
        <EmptyState
          title="这一章还没有终稿"
          description="在 AI 写章会话里按确认定稿后才会生成，普通保存不会。"
        />
      ) : final.isError ? (
        <EmptyState title="读不到终稿" description="确认后端已启动后重试。" />
      ) : (
        <div className="flex flex-col gap-2">
          <Badge tone="success">{content.length.toLocaleString('zh-CN')} 字</Badge>
          <div className="prose-chapter max-h-[52vh] overflow-y-auto rounded-md border border-ink-800 bg-ink-900/50 p-4 text-ink-200">
            {content.split(/\n{2,}/).map((paragraph, index) => (
              <p key={index}>{paragraph}</p>
            ))}
          </div>
        </div>
      )}
    </Dialog>
  );
}
