import { useState, type ReactNode } from 'react';
import { toast } from 'sonner';
import { Sparkles } from 'lucide-react';
import { useMutation } from '@tanstack/react-query';
import { draftsApi } from '@/api';
import { Button, Dialog, Field, IconButton, Input, Tooltip } from '@/components/ui';
import { formatChapterLabel } from '@/lib/chapter-id';
import { ApiError } from '@/lib/http';
import type { ChapterNode } from './tree';
import { useRenameChapter } from './queries';

/** 后端返回的字段名随实现浮动，挑第一个像标题的字符串。 */
function pickTitle(payload: unknown): string {
  if (typeof payload !== 'object' || payload === null) return '';
  const record = payload as Record<string, unknown>;
  for (const key of ['title', 'chapter_title', 'result', 'text']) {
    const value = record[key];
    if (typeof value === 'string' && value.trim() !== '') return value.trim();
  }
  return '';
}

export function ChapterRenameDialog({
  projectId,
  chapter,
  onClose,
}: {
  projectId: string;
  chapter: ChapterNode;
  onClose: () => void;
}): ReactNode {
  const [title, setTitle] = useState(chapter.title);
  const rename = useRenameChapter(projectId);

  const suggest = useMutation({
    mutationFn: () => draftsApi.generateTitle(projectId, chapter.id),
    onSuccess: (payload) => {
      const suggested = pickTitle(payload);
      if (suggested === '') {
        toast.error('模型没有给出可用的标题');
        return;
      }
      setTitle(suggested);
    },
    onError: (error) =>
      toast.error(error instanceof ApiError ? error.message : '生成标题失败'),
  });

  const trimmed = title.trim();
  const submit = (): void => {
    if (trimmed === chapter.title) {
      onClose();
      return;
    }
    rename.mutate({ chapter: chapter.id, title: trimmed }, { onSuccess: onClose });
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={`重命名 ${formatChapterLabel(chapter.id, 'zh-CN', { includeVolume: true })}`}
      size="sm"
      dismissible={!rename.isPending}
      footer={
        <>
          <Button tone="outline" onClick={onClose} disabled={rename.isPending}>
            取消
          </Button>
          <Button tone="primary" onClick={submit} loading={rename.isPending}>
            保存
          </Button>
        </>
      }
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <Field
          label="章节标题"
          hint="留空则在树里显示为「未命名」。"
          action={
            <Tooltip content="按正文让模型起一个">
              <IconButton
                label="生成标题"
                size="xs"
                onClick={() => suggest.mutate()}
                disabled={suggest.isPending}
              >
                <Sparkles className={suggest.isPending ? 'animate-pulse' : undefined} />
              </IconButton>
            </Tooltip>
          }
        >
          {(props) => (
            <Input
              {...props}
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="例如：雪夜来客"
              autoFocus
              maxLength={120}
            />
          )}
        </Field>
      </form>
    </Dialog>
  );
}
