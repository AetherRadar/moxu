import { useState, type ReactNode } from 'react';
import { RotateCcw } from 'lucide-react';
import { Badge, Button, Dialog, EmptyState, SkeletonList } from '@/components/ui';
import { useDraftVersion, useDraftVersions } from '@/features/writing/queries';

/** 版本历史：AI 每次写稿/改稿落一个版本，可回看、可恢复。 */
export function VersionsDialog({
  projectId,
  chapter,
  onRestore,
  onClose,
}: {
  projectId: string;
  chapter: string;
  onRestore: (text: string) => void;
  onClose: () => void;
}): ReactNode {
  const versions = useDraftVersions(projectId, chapter, true);
  const [picked, setPicked] = useState<string | null>(null);
  const content = useDraftVersion(projectId, chapter, picked);

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title="版本历史"
      description="AI 写作和修订每轮落一个版本；恢复会把内容写回编辑器并立即保存。"
      size="lg"
      footer={
        <>
          <Button tone="outline" onClick={onClose}>
            关闭
          </Button>
          <Button
            tone="primary"
            icon={<RotateCcw />}
            disabled={picked === null || content.data?.content === undefined}
            onClick={() => {
              if (content.data?.content !== undefined) onRestore(content.data.content);
              onClose();
            }}
          >
            恢复此版本
          </Button>
        </>
      }
    >
      {versions.isPending ? (
        <SkeletonList rows={4} />
      ) : (versions.data?.length ?? 0) === 0 ? (
        <EmptyState
          title="还没有版本"
          description="版本由 AI 写章/反馈修订产生；手敲的正文在「当前草稿」里。"
        />
      ) : (
        <div className="flex min-h-0 gap-3">
          <div className="flex w-28 shrink-0 flex-col gap-1 overflow-y-auto">
            {(versions.data ?? []).map((version) => (
              <button
                key={version}
                type="button"
                onClick={() => setPicked(version)}
                className={
                  picked === version
                    ? 'rounded border border-celadon-600 bg-celadon-500/10 px-2 py-1.5 text-left text-xs text-celadon-300'
                    : 'rounded border border-ink-800 px-2 py-1.5 text-left text-xs text-ink-300 hover:border-ink-600'
                }
              >
                {version}
              </button>
            ))}
          </div>
          <div className="min-w-0 flex-1">
            {picked === null ? (
              <p className="py-10 text-center text-2xs text-ink-600">选个版本看内容</p>
            ) : content.isPending ? (
              <SkeletonList rows={6} />
            ) : (
              <>
                <div className="mb-2 flex items-center gap-2 text-2xs text-ink-500">
                  <Badge tone="accent">{picked}</Badge>
                  <span className="tnum">
                    {(content.data?.content ?? '').length.toLocaleString()} 字
                  </span>
                  {content.data?.created_at !== null && content.data?.created_at !== undefined && (
                    <span>{content.data.created_at.replace('T', ' ').slice(0, 16)}</span>
                  )}
                </div>
                <div className="prose-chapter max-h-[46vh] overflow-y-auto rounded-md border border-ink-800 bg-ink-900/50 p-4 text-ink-200">
                  {(content.data?.content ?? '')
                    .split(/\n{2,}/)
                    .map((paragraph, index) => (
                      <p key={index}>{paragraph}</p>
                    ))}
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </Dialog>
  );
}
