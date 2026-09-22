import { useState, type ReactNode } from 'react';
import { toast } from 'sonner';
import type { Fact } from '@/api/types';
import { Button, Dialog, Input, Select, Textarea } from '@/components/ui';
import { useChapterTree } from '@/features/chapters/queries';
import { useSaveFact } from '@/features/cards/queries';
import { formatChapterLabel } from '@/lib/chapter-id';

/** 新建/编辑事实。手工录入走 manual 端点，origin 由后端标记。 */
export function FactEditDialog({
  projectId,
  fact,
  onClose,
}: {
  projectId: string;
  fact: Fact | null;
  onClose: () => void;
}): ReactNode {
  const tree = useChapterTree(projectId);
  const save = useSaveFact(projectId);
  const [draft, setDraft] = useState({
    title: fact?.title ?? '',
    statement: fact?.statement ?? '',
    content: fact?.content ?? '',
    introduced_in: fact?.introduced_in ?? '',
    confidence: fact?.confidence ?? 1.0,
    source: fact?.source ?? '',
  });

  const submit = (): void => {
    if (draft.statement.trim() === '') {
      toast.error('事实陈述不能为空');
      return;
    }
    save.mutate(
      {
        id: fact?.id ?? '',
        create: fact === null,
        fact: {
          title: draft.title.trim() || null,
          statement: draft.statement.trim(),
          content: draft.content.trim() || null,
          introduced_in: draft.introduced_in || null,
          confidence: draft.confidence,
          source: draft.source.trim() || null,
        },
      },
      { onSuccess: onClose },
    );
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={fact === null ? '录入事实' : `编辑事实 ${fact.id}`}
      description="事实是作品的正典：人物背景、已发生的事件、设定结论。"
      size="md"
      footer={
        <>
          <Button tone="outline" onClick={onClose}>
            取消
          </Button>
          <Button tone="primary" loading={save.isPending} onClick={submit}>
            保存
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Input
          value={draft.title}
          onChange={(event) => setDraft((d) => ({ ...d, title: event.target.value }))}
          placeholder="标题（可空）"
        />
        <Textarea
          value={draft.statement}
          onChange={(event) => setDraft((d) => ({ ...d, statement: event.target.value }))}
          rows={2}
          placeholder="一句话事实陈述，例如：林晚在第三章得知父亲失踪真相"
        />
        <Textarea
          value={draft.content}
          onChange={(event) => setDraft((d) => ({ ...d, content: event.target.value }))}
          rows={3}
          placeholder="详细内容/上下文（可空）"
        />
        <div className="grid grid-cols-2 gap-3">
          <label className="flex flex-col gap-1">
            <span className="text-2xs text-ink-500">引入章节</span>
            <Select
              value={draft.introduced_in || '__none'}
              onValueChange={(value) =>
                setDraft((d) => ({ ...d, introduced_in: value === '__none' ? '' : value }))
              }
              options={[
                { value: '__none', label: '（无）' },
                ...tree.chapterIds.map((id) => ({ value: id, label: formatChapterLabel(id) })),
              ]}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-2xs text-ink-500">置信度（0~1）</span>
            <Input
              value={String(draft.confidence)}
              inputMode="decimal"
              onChange={(event) => {
                const value = Number.parseFloat(event.target.value);
                if (!Number.isNaN(value)) {
                  setDraft((d) => ({ ...d, confidence: Math.max(0, Math.min(1, value)) }));
                }
              }}
            />
          </label>
        </div>
        <Input
          value={draft.source}
          onChange={(event) => setDraft((d) => ({ ...d, source: event.target.value }))}
          placeholder="来源标注（可空）"
        />
      </div>
    </Dialog>
  );
}
