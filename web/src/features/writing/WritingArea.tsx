import { lazy, useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { History, FileText, PenLine, Save, Sparkles, Wand2 } from 'lucide-react';
import { draftsApi, qk } from '@/api';
import { ApiError } from '@/lib/http';
import { formatChapterLabel, formatVolumeLabel } from '@/lib/chapter-id';
import { Badge, Button, EmptyState, SkeletonList } from '@/components/ui';
import { LazyOverlay } from '@/components/ui/LazyOverlay';
import {
  CodeMirrorEditor,
  type EditorHandle,
  type EditorSelection,
} from '@/features/editor/CodeMirrorEditor';
import { useChapterTree } from '@/features/chapters/queries';
import { useDraft, useAutosaveDraft } from '@/features/writing/queries';
import { useWorkspaceStore } from '@/stores/workspace';
import { cn } from '@/lib/cn';

const SessionDialog = lazy(() =>
  import('@/features/writing/SessionDialog').then((m) => ({ default: m.SessionDialog })),
);
const EditSuggestDialog = lazy(() =>
  import('@/features/writing/EditSuggestDialog').then((m) => ({ default: m.EditSuggestDialog })),
);
const VersionsDialog = lazy(() =>
  import('@/features/writing/VersionsDialog').then((m) => ({ default: m.VersionsDialog })),
);
const SceneBriefDialog = lazy(() =>
  import('@/features/writing/SceneBriefDialog').then((m) => ({ default: m.SceneBriefDialog })),
);

const AUTOSAVE_DELAY = 1600;

type SaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';

export function WritingArea(): ReactNode {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const chapter = useWorkspaceStore((state) => state.chapter);

  if (chapter === null || chapter === '') {
    return (
      <div className="flex h-full items-center justify-center">
        <EmptyState
          icon={<PenLine />}
          title="选一章开始写"
          description="左侧章节树里挑一章；没有章就先去大纲面板规划，或新建一章。"
        />
      </div>
    );
  }
  return <ChapterEditor key={chapter} projectId={projectId} chapter={chapter} />;
}

function ChapterEditor({ projectId, chapter }: { projectId: string; chapter: string }): ReactNode {
  const client = useQueryClient();
  const draft = useDraft(projectId, chapter);
  const tree = useChapterTree(projectId);
  const autosave = useAutosaveDraft(projectId, chapter);

  // 终稿徽标：final.md 只由会话定稿写入，404 属正常状态，不重试
  const finalDraft = useQuery({
    queryKey: qk.draftFinal(projectId, chapter),
    queryFn: () => draftsApi.final(projectId, chapter),
    retry: false,
  });

  const editorRef = useRef<EditorHandle>(null);
  const abortRef = useRef<AbortController | null>(null);
  const timerRef = useRef<number>(0);
  const dirtyRef = useRef(false);
  const titleRef = useRef('');

  const chapterNode = tree.volumes.flatMap((v) => v.chapters).find((c) => c.id === chapter);
  const volume = tree.volumes.find((v) => v.chapters.some((c) => c.id === chapter));

  const [doc, setDoc] = useState('');
  const [title, setTitle] = useState(chapterNode?.title ?? '');
  const titleDirty = useRef(false);
  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const [selection, setSelection] = useState<EditorSelection>({ from: 0, to: 0, text: '' });
  const [dialog, setDialog] = useState<'session' | 'suggest' | 'versions' | 'brief' | null>(null);

  titleRef.current = title;

  // 章节树可能比编辑器晚到；用户没动过标题才跟着外部数据走
  useEffect(() => {
    if (!titleDirty.current && chapterNode?.title !== undefined && chapterNode.title !== title) {
      setTitle(chapterNode.title);
      titleRef.current = chapterNode.title;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chapterNode?.title]);

  const markSaved = (): void => {
    dirtyRef.current = false;
    setSaveState('saved');
    setSavedAt(new Date());
  };

  /** 全量保存：正文 + 标题一起，走后端的 content 端点（会刷新 review/记忆包）。 */
  const save = useMutation({
    mutationFn: (content: string) =>
      draftsApi.updateContent(projectId, chapter, { content, title: titleRef.current }),
    onSuccess: () => {
      markSaved();
      void client.invalidateQueries({ queryKey: qk.chapterSummaries(projectId) });
      toast.success('已保存');
    },
    onError: (error) => {
      setSaveState('error');
      toast.error(error instanceof ApiError ? error.message : '保存失败');
    },
  });

  const scheduleAutosave = useCallback(
    (content: string) => {
      window.clearTimeout(timerRef.current);
      timerRef.current = window.setTimeout(() => {
        abortRef.current?.abort();
        const controller = new AbortController();
        abortRef.current = controller;
        setSaveState('saving');
        autosave.mutate(
          { body: { content, title: titleRef.current }, signal: controller.signal },
          {
            onSuccess: markSaved,
            onError: (error) => {
              if (!controller.signal.aborted) setSaveState('error');
              void error;
            },
          },
        );
      }, AUTOSAVE_DELAY);
    },
    [autosave],
  );

  const handleDocChange = useCallback(
    (next: string) => {
      setDoc(next);
      dirtyRef.current = true;
      setSaveState('dirty');
      scheduleAutosave(next);
    },
    [scheduleAutosave],
  );

  const handleTitleChange = (value: string): void => {
    setTitle(value);
    titleDirty.current = true;
    titleRef.current = value;
    dirtyRef.current = true;
    setSaveState('dirty');
    if (editorRef.current !== null) scheduleAutosave(editorRef.current.getDoc());
  };

  const flush = useCallback((): void => {
    window.clearTimeout(timerRef.current);
    abortRef.current?.abort();
    const content = editorRef.current?.getDoc();
    if (content === undefined || !dirtyRef.current) return;
    setSaveState('saving');
    save.mutate(content);
  }, [save]);

  // Ctrl/Cmd+S 手动保存；切走/关页前把脏内容冲掉
  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        flush();
      }
    };
    const onUnload = (): void => {
      if (!dirtyRef.current || editorRef.current === null) return;
      // 同步 XHR 已死路一条，用 keepalive fetch 尽力而为
      void draftsApi.autosave(projectId, chapter, {
        content: editorRef.current.getDoc(),
        title: titleRef.current,
      });
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('beforeunload', onUnload);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('beforeunload', onUnload);
      window.clearTimeout(timerRef.current);
      abortRef.current?.abort();
      // 切章节时未保存的内容也冲掉
      if (dirtyRef.current && editorRef.current !== null) {
        void draftsApi
          .autosave(projectId, chapter, {
            content: editorRef.current.getDoc(),
            title: titleRef.current,
          })
          .catch(() => undefined);
      }
    };
  }, [projectId, chapter, flush]);

  /** AI 草稿/版本恢复统一从这里进编辑器，并立刻走一次全量保存。 */
  const applyText = useCallback(
    (text: string): void => {
      editorRef.current?.setDoc(text);
      dirtyRef.current = true;
      save.mutate(text);
    },
    [save],
  );

  if (draft.isPending) {
    return (
      <div className="flex h-full flex-col p-6">
        <SkeletonList rows={8} />
      </div>
    );
  }

  const isNew = draft.isError && draft.error instanceof ApiError && draft.error.status === 404;
  if (draft.isError && !isNew) {
    return (
      <div className="flex h-full items-center justify-center">
        <EmptyState
          title="草稿读不出来"
          description={draft.error instanceof ApiError ? draft.error.message : '后端返回异常'}
          action={
            <Button tone="outline" size="sm" onClick={() => draft.refetch()}>
              重试
            </Button>
          }
        />
      </div>
    );
  }
  const initialDoc = isNew ? '' : (draft.data?.content ?? '');

  // 加载完成的正文同步进本地计数；之后每次改动由 onDocChange 接管
  useEffect(() => setDoc(initialDoc), [initialDoc]);

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* ── 章节头 ─────────────────────────────────────── */}
      <header className="flex items-center gap-3 border-b border-ink-800 px-4 py-2">
        <div className="flex min-w-0 flex-1 items-baseline gap-2.5">
          <span className="tnum shrink-0 text-2xs text-celadon-500">
            {volume !== undefined ? `${formatVolumeLabel(volume.id, volume.title)} · ` : ''}
            {formatChapterLabel(chapter)}
          </span>
          <input
            value={title}
            onChange={(event) => handleTitleChange(event.target.value)}
            placeholder="（未命名）"
            aria-label="章节标题"
            className="min-w-0 flex-1 border-0 bg-transparent font-serif text-base font-medium text-ink-50 outline-none placeholder:text-ink-600"
          />
        </div>

        <div className="flex shrink-0 items-center gap-1.5">
          {finalDraft.data !== undefined && <Badge tone="success">已定稿</Badge>}
          <SaveIndicator state={save.isPending ? 'saving' : saveState} savedAt={savedAt} />
          <span className="tnum text-2xs text-ink-500">{doc.length.toLocaleString()} 字</span>
          <div className="mx-1 h-4 w-px bg-ink-800" />
          <Button tone="ghost" size="sm" icon={<History />} onClick={() => setDialog('versions')}>
            版本
          </Button>
          <Button tone="ghost" size="sm" icon={<FileText />} onClick={() => setDialog('brief')}>
            简报
          </Button>
          <Button tone="ghost" size="sm" icon={<Wand2 />} onClick={() => setDialog('suggest')}>
            修订
          </Button>
          <Button tone="primary" size="sm" icon={<Sparkles />} onClick={() => setDialog('session')}>
            AI 写章
          </Button>
          <Button
            tone="outline"
            size="sm"
            icon={<Save />}
            loading={save.isPending}
            disabled={saveState !== 'dirty'}
            onClick={flush}
          >
            保存
          </Button>
        </div>
      </header>

      {/* ── 编辑器 ─────────────────────────────────────── */}
      <div className="min-h-0 flex-1">
        <CodeMirrorEditor
          ref={editorRef}
          docId={chapter}
          initialDoc={initialDoc}
          placeholder="从这里开始写…"
          onDocChange={handleDocChange}
          onSelectionChange={setSelection}
        />
      </div>

      {/* ── 对话框（懒加载）────────────────────────────── */}
      {dialog === 'session' && (
        <LazyOverlay>
          <SessionDialog
            projectId={projectId}
            chapter={chapter}
            chapterTitle={title}
            currentContent={doc}
            onAdopt={applyText}
            onClose={() => setDialog(null)}
          />
        </LazyOverlay>
      )}
      {dialog === 'suggest' && (
        <LazyOverlay>
          <EditSuggestDialog
            projectId={projectId}
            chapter={chapter}
            getDoc={() => editorRef.current?.getDoc() ?? ''}
            selection={selection.from === selection.to ? null : selection}
            onApply={applyText}
            onClose={() => setDialog(null)}
          />
        </LazyOverlay>
      )}
      {dialog === 'versions' && (
        <LazyOverlay>
          <VersionsDialog
            projectId={projectId}
            chapter={chapter}
            onRestore={applyText}
            onClose={() => setDialog(null)}
          />
        </LazyOverlay>
      )}
      {dialog === 'brief' && (
        <LazyOverlay>
          <SceneBriefDialog projectId={projectId} chapter={chapter} onClose={() => setDialog(null)} />
        </LazyOverlay>
      )}
    </div>
  );
}

function SaveIndicator({ state, savedAt }: { state: SaveState; savedAt: Date | null }): ReactNode {
  if (state === 'saving') return <Badge tone="warning">保存中…</Badge>;
  if (state === 'error') return <Badge tone="danger">保存失败</Badge>;
  if (state === 'dirty') return <Badge tone="outline">未保存</Badge>;
  if (state === 'saved' && savedAt !== null) {
    return (
      <Badge tone="success" className={cn('tnum')}>
        已存 {savedAt.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
      </Badge>
    );
  }
  return null;
}
