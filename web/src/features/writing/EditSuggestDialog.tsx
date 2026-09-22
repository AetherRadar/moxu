import { useState, type ReactNode } from 'react';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Wand2 } from 'lucide-react';
import { sessionApi } from '@/api';
import type { EditSuggestResponse } from '@/api/types';
import { ApiError } from '@/lib/http';
import { Badge, Button, Dialog, Select, Textarea } from '@/components/ui';
import type { EditorSelection } from '@/features/editor/CodeMirrorEditor';
import { DiffPreview } from '@/features/writing/DiffPreview';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/**
 * 局部/全文修订建议。
 * 有选区 → 传 selection_start/end 精准修订；没选区 → 全文修订。
 * 后端只回修订稿不落盘，确认后才写回编辑器。
 */
export function EditSuggestDialog({
  projectId,
  chapter,
  getDoc,
  selection,
  onApply,
  onClose,
}: {
  projectId: string;
  chapter: string;
  getDoc: () => string;
  selection: EditorSelection | null;
  onApply: (text: string) => void;
  onClose: () => void;
}): ReactNode {
  const [instruction, setInstruction] = useState('');
  const [contextMode, setContextMode] = useState<'quick' | 'full'>('quick');
  const [result, setResult] = useState<EditSuggestResponse | null>(null);
  const [original, setOriginal] = useState('');

  const suggest = useMutation({
    mutationFn: () => {
      const content = getDoc();
      setOriginal(content);
      return sessionApi.editSuggest(projectId, {
        chapter,
        content,
        instruction: instruction.trim(),
        context_mode: contextMode,
        ...(selection !== null
          ? {
              selection_text: selection.text,
              selection_start: selection.from,
              selection_end: selection.to,
            }
          : {}),
      });
    },
    onSuccess: (data) => {
      if (!data.success) {
        toast.error(data.error ?? '没有产生可用的修改');
        setResult(null);
        return;
      }
      setResult(data);
    },
    onError: (error) => toast.error(reason(error, '修订失败')),
  });

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={selection !== null ? '修订选中段落' : '修订整章'}
      description={
        selection !== null
          ? `选区 ${selection.text.length} 字，模型只改这段并回全稿`
          : '没有选区，按指令修订整章'
      }
      size="lg"
      dismissible={!suggest.isPending}
      footer={
        result === null ? (
          <>
            <Button tone="outline" onClick={onClose}>
              取消
            </Button>
            <Button
              tone="primary"
              icon={<Wand2 />}
              loading={suggest.isPending}
              disabled={instruction.trim() === ''}
              onClick={() => suggest.mutate()}
            >
              生成修订
            </Button>
          </>
        ) : (
          <>
            <Button
              tone="outline"
              onClick={() => {
                setResult(null);
              }}
            >
              再改一版
            </Button>
            <Button
              tone="primary"
              onClick={() => {
                if (result.revised_content !== undefined) onApply(result.revised_content);
                onClose();
              }}
            >
              应用修订
            </Button>
          </>
        )
      }
    >
      {result === null ? (
        <div className="flex flex-col gap-3">
          {selection !== null && (
            <div className="max-h-24 overflow-y-auto rounded-md border border-ink-800 bg-ink-900/50 p-2.5 font-serif text-xs leading-relaxed text-ink-400">
              {selection.text}
            </div>
          )}
          <Textarea
            value={instruction}
            onChange={(event) => setInstruction(event.target.value)}
            rows={3}
            autoFocus
            placeholder="怎么改？例如：压缩对话，把动作描写提前；或：这段语气太硬，软一点"
          />
          <div className="flex items-center gap-2">
            <span className="text-2xs text-ink-500">上下文</span>
            <Select
              value={contextMode}
              onValueChange={(value) => setContextMode(value as 'quick' | 'full')}
              options={[
                { value: 'quick', label: '快速', hint: '用已有记忆包' },
                { value: 'full', label: '完整', hint: '重建记忆包，更慢更准' },
              ]}
              className="w-40"
            />
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {result.change_stats !== undefined && (
            <div className="flex items-center gap-2 text-2xs text-ink-500">
              <Badge tone="accent">{result.change_stats.changed_lines} 行改动</Badge>
              <span>
                {result.change_stats.original_lines} → {result.change_stats.revised_lines} 行
              </span>
            </div>
          )}
          <DiffPreview
            original={original}
            revised={result.revised_content ?? original}
            className="max-h-[50vh] overflow-y-auto"
          />
        </div>
      )}
    </Dialog>
  );
}
