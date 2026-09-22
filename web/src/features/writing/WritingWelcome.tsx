import { lazy, useMemo, useState, type ReactNode } from 'react';
import { ArrowRight, BookOpen, Feather, ListTree, Plus, Upload, Users } from 'lucide-react';
import { useParams } from 'react-router-dom';
import { Button, EmptyState, SkeletonList } from '@/components/ui';
import { LazyOverlay } from '@/components/ui/LazyOverlay';
import { formatChapterLabel } from '@/lib/chapter-id';
import { useChapterTree } from '@/features/chapters/queries';
import { useLayoutStore } from '@/stores/layout';
import { useWorkspaceStore } from '@/stores/workspace';

const BookImportDialog = lazy(() =>
  import('@/features/imports/BookImportDialog').then((m) => ({ default: m.BookImportDialog })),
);
const CreateChapterDialog = lazy(() =>
  import('@/features/chapters/CreateChapterDialog').then((m) => ({ default: m.CreateChapterDialog })),
);

export function WritingWelcome({ projectName }: { projectName?: string }): ReactNode {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const tree = useChapterTree(projectId);
  const openChapter = useWorkspaceStore((state) => state.openChapter);
  const setSideView = useLayoutStore((state) => state.setSideView);
  const lastChapter = useWorkspaceStore((state) => state.lastChapter[projectId]);
  const [dialog, setDialog] = useState<'import' | 'chapter' | null>(null);

  const endingChapters = useMemo(
    () =>
      tree.volumes
        .flatMap((volume) => volume.chapters)
        .slice(-6)
        .reverse(),
    [tree.volumes],
  );
  const hasChapters = endingChapters.length > 0;

  const resumeChapter =
    tree.volumes.flatMap((volume) => volume.chapters).find((item) => item.id === lastChapter) ??
    endingChapters[0];

  if (tree.isError)
    return (
      <div className="flex h-full items-center justify-center bg-paper p-6">
        <EmptyState
          title="章节暂时没有载入"
          description="请重试后继续写作，你的书稿不会受到影响。"
          action={<Button onClick={tree.refetch}>重新加载</Button>}
        />
      </div>
    );
  if (tree.isPending)
    return (
      <div role="status" aria-label="正在载入工作台" className="h-full bg-paper p-10">
        <SkeletonList rows={6} />
      </div>
    );

  return (
    <div className="writing-welcome h-full overflow-y-auto bg-paper">
      <section className="mx-auto flex min-h-full max-w-3xl flex-col px-6 py-12 sm:px-12 sm:py-16">
        <div className="flex items-center gap-2 text-xs tracking-[0.16em] text-ink-500 uppercase">
          <span className="size-1.5 rounded-full bg-celadon-500" /> 创作工作台
        </div>
        <div className="mt-7 flex items-start justify-between gap-8">
          <div>
            <p className="text-sm text-ink-500">正在创作</p>
            <h1 className="mt-2 font-serif text-4xl leading-tight tracking-wide text-ink-100 sm:text-5xl">
              {projectName || '我的作品'}
            </h1>
            <p className="mt-5 max-w-xl text-sm leading-7 text-ink-400">
              {hasChapters
                ? '回到故事里，接着写下一页。'
                : '作品已经准备好了。先建一章，再决定自己写、列提纲，还是请助手帮忙。'}
            </p>
          </div>
          <BookOpen className="mt-1 hidden size-16 text-celadon-600/45 sm:block" strokeWidth={1} />
        </div>

        {!hasChapters && (
          <ol className="mt-10 flex flex-wrap gap-x-6 gap-y-3 border-y border-ink-800 py-4 text-xs text-ink-500">
            <li className="flex items-center gap-2 text-celadon-600">
              <span className="flex size-5 items-center justify-center rounded-full bg-celadon-900/35">
                ✓
              </span>
              作品已就绪
            </li>
            <li className="flex items-center gap-2">
              <span className="flex size-5 items-center justify-center rounded-full border border-celadon-600 text-celadon-600">
                2
              </span>
              创建第一章
            </li>
            <li className="flex items-center gap-2">
              <span className="flex size-5 items-center justify-center rounded-full border border-ink-700">
                3
              </span>
              选择开始方式
            </li>
          </ol>
        )}

        <div className="mt-10 rounded-lg border border-ink-800 border-l-4 border-l-celadon-500 bg-ink-900 p-5 sm:p-6">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div className="flex min-w-0 items-start gap-4">
              <Feather className="mt-0.5 size-6 shrink-0 text-celadon-500" />
              <div className="min-w-0 flex-1">
                <p className="text-xs tracking-[0.14em] text-ink-500 uppercase">
                  {hasChapters ? '接着写' : '下一步'}
                </p>
                <h2 className="mt-2 font-serif text-xl text-ink-100">
                  {hasChapters
                    ? resumeChapter?.title || formatChapterLabel(resumeChapter?.id ?? '')
                    : '给故事一个开头'}
                </h2>
                <p className="mt-2 text-xs leading-6 text-ink-400">
                  {hasChapters
                    ? `${formatChapterLabel(resumeChapter?.id ?? '')} · ${resumeChapter?.id === lastChapter ? '上次停笔的地方' : '从最后一章开始'}`
                    : '标题可以稍后再想，章节顺序由墨序安排。'}
                </p>
              </div>
            </div>
            <Button
              className="self-start"
              tone="primary"
              size="sm"
              icon={hasChapters ? <ArrowRight /> : <Plus />}
              onClick={
                hasChapters ? () => openChapter(resumeChapter?.id ?? null) : () => setDialog('chapter')
              }
            >
              {hasChapters ? '继续写作' : '创建第一章'}
            </Button>
          </div>
        </div>

        {hasChapters && (
          <div className="mt-10">
            <div className="flex items-center justify-between border-b border-ink-800 pb-3">
              <span className="text-xs font-medium text-ink-400">篇章速览 · 倒序</span>
              <Button tone="ghost" size="xs" onClick={() => setDialog('chapter')} icon={<Plus />}>
                新建章节
              </Button>
            </div>
            <div className="divide-y divide-ink-800">
              {endingChapters.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => openChapter(item.id)}
                  className="group flex w-full items-center gap-3 py-3 text-left text-sm text-ink-300 hover:text-ink-100"
                >
                  <span className="tnum w-16 shrink-0 text-xs text-ink-500">
                    {formatChapterLabel(item.id)}
                  </span>
                  <span className="min-w-0 flex-1 truncate">{item.title || '未命名章节'}</span>
                  <ArrowRight className="size-4 text-ink-600 transition-transform group-hover:translate-x-1" />
                </button>
              ))}
            </div>
          </div>
        )}

        <div className="mt-12 grid gap-3 sm:grid-cols-3">
          <WelcomeOption
            tone="outline"
            icon={<ListTree />}
            title="规划故事大纲"
            hint="梳理事件与人物目标"
            onClick={() => setSideView('outline')}
          />
          <WelcomeOption
            tone="cards"
            icon={<Users />}
            title="补充人物与世界"
            hint="写作中再慢慢完善"
            onClick={() => setSideView('cards')}
          />
          <WelcomeOption
            tone="import"
            icon={<Upload />}
            title="导入已有书稿"
            hint="支持 TXT、EPUB 与粘贴"
            onClick={() => setDialog('import')}
          />
        </div>
        <p className="mt-auto pt-12 text-center text-xs text-ink-500">
          这些都不是开始写作的前提，想到哪里就写到哪里。
        </p>
      </section>
      {dialog === 'import' && (
        <LazyOverlay>
          <BookImportDialog projectId={projectId} onClose={() => setDialog(null)} />
        </LazyOverlay>
      )}
      {dialog === 'chapter' && (
        <LazyOverlay>
          <CreateChapterDialog
            projectId={projectId}
            tree={tree}
            defaultVolumeId={resumeChapter?.volumeId ?? tree.volumes.at(-1)?.id}
            onClose={() => setDialog(null)}
          />
        </LazyOverlay>
      )}
    </div>
  );
}

function WelcomeOption({
  tone,
  icon,
  title,
  hint,
  onClick,
}: {
  tone: 'outline' | 'cards' | 'import';
  icon: ReactNode;
  title: string;
  hint: string;
  onClick: () => void;
}): ReactNode {
  return (
    <button
      type="button"
      data-tone={tone}
      onClick={onClick}
      className="welcome-option flex items-center gap-3 rounded-md border px-3 py-3 text-left transition-[filter,transform] hover:-translate-y-px"
    >
      <span className="welcome-option-icon text-celadon-500 [&_svg]:size-5">{icon}</span>
      <span className="min-w-0">
        <span className="block text-xs text-ink-200">{title}</span>
        <span className="mt-1 block text-2xs text-ink-500">{hint}</span>
      </span>
    </button>
  );
}
