import { useState, type ReactNode } from 'react';
import { Button } from './Button';
import { Dialog } from './Dialog';
import { Input } from './Input';

/**
 * 确认对话框。
 *
 * 破坏性操作可以要求输入确认词（项目名/章节号）才放行——
 * 删项目会连带删掉整个 data 目录下的稿件，一个"确定"按钮太轻了。
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = '确定',
  danger = false,
  requirePhrase,
  loading = false,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel?: string;
  danger?: boolean;
  /** 需要用户逐字输入的确认词 */
  requirePhrase?: string | undefined;
  loading?: boolean;
  onConfirm: () => void;
}): ReactNode {
  const [typed, setTyped] = useState('');
  const locked = requirePhrase !== undefined && typed.trim() !== requirePhrase;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) setTyped('');
        onOpenChange(next);
      }}
      title={title}
      size="sm"
      dismissible={!loading}
      footer={
        <>
          <Button tone="outline" onClick={() => onOpenChange(false)} disabled={loading}>
            取消
          </Button>
          <Button
            tone={danger ? 'danger' : 'primary'}
            onClick={onConfirm}
            disabled={locked}
            loading={loading}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-2.5">
        {description !== undefined && (
          <p className="text-xs leading-relaxed text-ink-300">{description}</p>
        )}
        {requirePhrase !== undefined && (
          <>
            <p className="text-2xs leading-relaxed text-ink-500">
              此操作不可撤销。请输入 <span className="font-mono text-cinnabar-300">{requirePhrase}</span>{' '}
              以确认。
            </p>
            <Input
              value={typed}
              onChange={(event) => setTyped(event.target.value)}
              placeholder={requirePhrase}
              autoFocus
              spellCheck={false}
            />
          </>
        )}
      </div>
    </Dialog>
  );
}
