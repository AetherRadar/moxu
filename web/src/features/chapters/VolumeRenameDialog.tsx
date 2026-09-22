import { useState, type ReactNode } from 'react';
import { Button, Dialog, Field, Input } from '@/components/ui';
import { formatVolumeLabel } from '@/lib/chapter-id';
import type { VolumeNode } from './tree';
import { useUpdateVolume } from './queries';

export function VolumeRenameDialog({
  projectId,
  volume,
  onClose,
}: {
  projectId: string;
  volume: VolumeNode;
  onClose: () => void;
}): ReactNode {
  const [title, setTitle] = useState(volume.title);
  const update = useUpdateVolume(projectId);

  const submit = (): void => {
    const trimmed = title.trim();
    if (trimmed === volume.title) {
      onClose();
      return;
    }
    update.mutate(
      { volumeId: volume.id, body: { title: trimmed } },
      { onSuccess: onClose },
    );
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={`重命名 ${formatVolumeLabel(volume.id)}`}
      size="sm"
      dismissible={!update.isPending}
      footer={
        <>
          <Button tone="outline" onClick={onClose} disabled={update.isPending}>
            取消
          </Button>
          <Button tone="primary" onClick={submit} loading={update.isPending}>
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
        <Field label="分卷标题" hint="留空则只显示卷号，如「第二卷」。">
          {(props) => (
            <Input
              {...props}
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="例如：风起云涌"
              autoFocus
              maxLength={80}
            />
          )}
        </Field>
      </form>
    </Dialog>
  );
}
