import { useState, type ReactNode } from 'react';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Eraser } from 'lucide-react';
import { draftsApi } from '@/api';
import type { JsonRecord } from '@/api/types';
import { ApiError } from '@/lib/http';
import { Badge, Button, Dialog } from '@/components/ui';
import type { EditorSelection } from '@/features/editor/CodeMirrorEditor';
import { DiffPreview } from '@/features/writing/DiffPreview';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/**
 * 去味润色：把选区或整章过一遍本地/LLM 改写管道（参数取项目级 rewrite-config）。
 * 后端只回改写稿不落盘，确认后由调用方写回编辑器。
 */
export function RewriteDialog({
  projectId,
  getDoc,
  selection,
  onApply,
  onClose,
}: {
  projectId: string;
  getDoc: () => string;
  selection: EditorSelection | null;
  onApply: (text: string) => void;
  onClose: () => void;
}): ReactNode {
  const [original, setOriginal] = useState('');
  const [rewritten, setRewritten] = useState<string | null>(null);
  const [meta, setMeta] = useState<{ engine: string; changed: boolean } | null>(null);

  const rewrite = useMutation({
    mutationFn: () => {
      const text = selection !== null ? selection.text : getDoc();
      setOriginal(text);
      return draftsApi.rewrite(projectId, { text });
    },
    onSuccess: (data: JsonRecord) => {
      if (data.success !== true || typeof data.rewritten !== 'string') {
        toast.error('改写服务没有返回结果');
        return;
      }
      setRewritten(data.rewritten);
      setMeta({ engine: String(data.engine ?? ''), changed: data.changed === true });
    },
    onError: (error) => toast.error(reason(error, '润色失败')),
  });

  const apply = (): void => {
    if (rewritten === null) return;
    if (selection === null) {
      onApply(rewritten);
    } else {
      const doc = getDoc();
      onApply(doc.slice(0, selection.from) + rewritten + doc.slice(selection.to));
    }
    onClose();
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={selection !== null ? '润色选中段落' : '润色整章'}
      description={
        selection !== null
          ? `选区 ${selection.text.length} 字，只改这段`
          : '没有选区，改写整章正文'
      }
      size="lg"
      dismissible={!rewrite.isPending}
      footer={
        rewritten === null ? (
          <>
            <Button tone="outline" onClick={onClose}>
              取消
            </Button>
            <Button tone="primary" icon={<Eraser />} loading={rewrite.isPending} onClick={() => rewrite.mutate()}>
              开始润色
            </Button>
          </>
        ) : (
          <>
            <Button tone="outline" onClick={() => setRewritten(null)}>
              再来一次
            </Button>
            <Button tone="primary" onClick={apply} disabled={meta?.changed === false}>
              应用润色
            </Button>
          </>
        )
      }
    >
      {rewritten === null ? (
        <div className="max-h-40 overflow-y-auto rounded-md border border-ink-800 bg-ink-900/50 p-2.5 font-serif text-xs leading-relaxed text-ink-400">
          {(selection !== null ? selection.text : getDoc()).slice(0, 2000)}
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <div className="flex items-center gap-2 text-2xs text-ink-500">
            {meta !== null && meta.engine !== '' && <Badge tone="accent">{meta.engine}</Badge>}
            {meta?.changed === false && <span>管道判定原文无需改动</span>}
          </div>
          <DiffPreview original={original} revised={rewritten} className="max-h-[50vh] overflow-y-auto" />
        </div>
      )}
    </Dialog>
  );
}
