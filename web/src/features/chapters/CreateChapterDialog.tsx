import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Plus } from 'lucide-react';
import { toast } from 'sonner';
import { Button, Dialog, Input } from '@/components/ui';
import { ApiError } from '@/lib/http';
import { formatVolumeLabel } from '@/lib/chapter-id';
import { useWorkspaceStore } from '@/stores/workspace';
import { useCreateChapter, useCreateVolume, type ChapterTreeResult } from './queries';

export function CreateChapterDialog({
  projectId,
  tree,
  defaultVolumeId,
  onClose,
}: {
  projectId: string;
  tree: ChapterTreeResult;
  defaultVolumeId: string | undefined;
  onClose: () => void;
}): ReactNode {
  const [volumeId, setVolumeId] = useState(defaultVolumeId ?? '');
  const [title, setTitle] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const inFlight = useRef(false);
  // 第一卷成功而建章失败时，重试沿用已创建的卷。
  const createdVolume = useRef<string | null>(null);
  const createVolume = useCreateVolume(projectId);
  const createChapter = useCreateChapter(projectId, tree.chapterIds);
  const openChapter = useWorkspaceStore((state) => state.openChapter);
  const setVolumeExpanded = useWorkspaceStore((state) => state.setVolumeExpanded);
  const close = (): void => {
    if (!inFlight.current) onClose();
  };

  const submit = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    if (inFlight.current || tree.isPending || tree.isError) return;
    inFlight.current = true;
    setPending(true);
    setError('');
    try {
      let targetId = volumeId || createdVolume.current || tree.volumes.at(-1)?.id;
      if (!targetId) {
        const volume = await createVolume.mutateAsync({ title: '第一卷', order: 1 });
        createdVolume.current = volume.id;
        setVolumeId(volume.id);
        targetId = volume.id;
      }
      const chapter = await createChapter.mutateAsync({ volumeId: targetId, title });
      setVolumeExpanded(targetId, true);
      openChapter(chapter);
      toast.success('新章节已准备好');
      onClose();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '创建失败，请重试。已填写的内容会保留。');
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) close();
      }}
      dismissible={!pending}
      title="添一页新的故事"
      description="选好分卷，章节会接在该卷末尾，编号自动安排。"
      size="sm"
    >
      <form onSubmit={(event) => void submit(event)} className="grid gap-5" aria-busy={pending}>
        <label className="grid gap-2 text-xs text-ink-300">
          章节标题 <span className="text-2xs text-ink-500">可以留空，想到合适的名字再填</span>
          <Input
            autoFocus
            value={title}
            maxLength={120}
            disabled={pending}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="例如：雨夜来客"
          />
        </label>
        {tree.volumes.length > 0 ? (
          <label className="grid gap-2 text-xs text-ink-300">
            所属分卷
            <select
              value={volumeId || tree.volumes.at(-1)?.id}
              disabled={pending}
              onChange={(event) => setVolumeId(event.target.value)}
              className="h-10 w-full rounded-md border border-ink-700 bg-paper px-3 text-ink-100"
            >
              {tree.volumes.map((volume) => (
                <option key={volume.id} value={volume.id}>
                  {formatVolumeLabel(volume.id, volume.title)} · {volume.chapters.length} 章
                </option>
              ))}
            </select>
          </label>
        ) : (
          <p className="rounded-md bg-ink-850 p-3 text-xs leading-6 text-ink-400">
            将为你建立「第一卷」，之后可以随时改名或添加分卷。
          </p>
        )}
        {error && (
          <p role="alert" className="text-xs leading-6 text-cinnabar-400">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2 border-t border-ink-800 pt-4">
          <Button tone="ghost" disabled={pending} onClick={close}>
            取消
          </Button>
          <Button
            type="submit"
            tone="primary"
            icon={<Plus />}
            loading={pending}
            disabled={tree.isPending || tree.isError}
          >
            创建并写作
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
