import { lazy, useDeferredValue, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowUpRight,
  ArrowRight,
  BookMarked,
  FolderPlus,
  Moon,
  Pencil,
  RefreshCw,
  Search,
  Settings,
  Sun,
  Trash2,
  X,
} from 'lucide-react';
import { Button, IconButton } from '@/components/ui/Button';
import { EmptyState, SkeletonList } from '@/components/ui/Feedback';
import { Input } from '@/components/ui/Input';
import { LazyOverlay } from '@/components/ui/LazyOverlay';
import { Logo } from '@/components/layout/Logo';
import {
  useCreateProject,
  useDeleteProject,
  useProjects,
  useRenameProject,
} from '@/features/projects/queries';
import type { Project } from '@/api/types';
import { useLayoutStore } from '@/stores/layout';
import { useWorkspaceStore } from '@/stores/workspace';
import { ApiError } from '@/lib/http';

// 破坏性确认和设置仍懒加载；创建表单直接放在书架里，便于连续建作。
const ConfirmDialog = lazy(() =>
  import('@/components/ui/ConfirmDialog').then((m) => ({ default: m.ConfirmDialog })),
);
const SettingsDialog = lazy(() =>
  import('@/features/settings/SettingsDialog').then((m) => ({ default: m.SettingsDialog })),
);

/** 相对时间，避免一堆绝对时间戳干扰扫读。 */
function relativeTime(iso: string | null): string {
  if (iso === null) return '';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const minutes = Math.round((Date.now() - then) / 60_000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days} 天前`;
  return new Date(iso).toLocaleDateString('zh-CN');
}

export function ProjectListPage(): ReactNode {
  const navigate = useNavigate();
  const openProject = useWorkspaceStore((state) => state.openProject);
  const projects = useProjects();
  const create = useCreateProject();
  const remove = useDeleteProject();

  const theme = useLayoutStore((state) => state.theme);
  const toggleTheme = useLayoutStore((state) => state.toggleTheme);
  const [keyword, setKeyword] = useState('');
  const deferredKeyword = useDeferredValue(keyword);
  const [createOpen, setCreateOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [renaming, setRenaming] = useState<Project | null>(null);
  const [deleting, setDeleting] = useState<Project | null>(null);
  const createInFlight = useRef(false);
  const [createError, setCreateError] = useState('');
  const [createName, setCreateName] = useState('');
  const [description, setDescription] = useState('');
  const [language, setLanguage] = useState<'zh' | 'en'>('zh');

  const closeCreate = (): void => {
    if (createInFlight.current) return;
    setCreateError('');
    setCreateOpen(false);
    setCreateName('');
    setDescription('');
    setLanguage('zh');
  };

  // 排序只随服务端数据变化；输入搜索时不再重复 O(n log n) 排序。
  const sorted = useMemo(
    () => [...(projects.data ?? [])].sort((a, b) => (b.updated_at ?? '').localeCompare(a.updated_at ?? '')),
    [projects.data],
  );
  const filtered = useMemo(() => {
    const needle = deferredKeyword.trim().toLowerCase();
    return needle === ''
      ? sorted
      : sorted.filter(
          (item) =>
            item.name.toLowerCase().includes(needle) ||
            (item.description ?? '').toLowerCase().includes(needle),
        );
  }, [sorted, deferredKeyword]);
  const totalWords = useMemo(
    () => sorted.reduce((sum, project) => sum + project.total_word_count, 0),
    [sorted],
  );
  const latest = sorted[0];

  const enter = (projectId: string): void => {
    openProject(projectId);
    navigate(`/p/${encodeURIComponent(projectId)}`);
  };

  const submitCreate = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const name = createName.trim();
    if (name === '' || createInFlight.current) return;
    createInFlight.current = true;
    setCreateError('');
    create.mutate(
      { name, description: description.trim(), language },
      {
        onSuccess: (project) => {
          createInFlight.current = false;
          closeCreate();
          enter(project.id);
        },
        onError: (error) =>
          setCreateError(error instanceof ApiError ? error.message : '创建失败，请重试。你的输入已保留。'),
        onSettled: () => {
          createInFlight.current = false;
        },
      },
    );
  };

  return (
    <div className="library-page h-full overflow-y-auto">
      <div className="mx-auto flex min-h-full max-w-6xl flex-col gap-7 px-5 py-6 sm:px-10 sm:py-8">
        <header className="flex items-center justify-between gap-4 border-b border-ink-800 pb-6">
          <div className="flex items-center gap-3">
            <Logo size={36} />
            <div>
              <p className="font-serif text-2xl tracking-[0.15em] text-ink-100">墨序</p>
              <p className="mt-1 text-[10px] tracking-[0.24em] text-ink-400">MOXU · 写作工作台</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <IconButton
              label={theme === 'dark' ? '切换浅色主题' : '切换深色主题'}
              size="lg"
              onClick={toggleTheme}
            >
              {theme === 'dark' ? <Sun /> : <Moon />}
            </IconButton>
            <IconButton label="设置" size="lg" onClick={() => setSettingsOpen(true)}>
              <Settings />
            </IconButton>
          </div>
        </header>

        <section className="library-intro grid gap-7 py-2 sm:py-3 md:grid-cols-[minmax(0,1fr)_20rem] md:items-center">
          <div>
            <p className="mb-3 text-xs tracking-[0.15em] text-ink-400">创作空间</p>
            <h1 className="font-serif text-3xl leading-tight tracking-wide sm:text-4xl">我的作品</h1>
            <p className="mt-3 text-sm leading-7 text-ink-400">从上次停笔的地方，继续写。</p>
            <Button
              tone="primary"
              size="lg"
              icon={<FolderPlus className="size-4" />}
              onClick={() => setCreateOpen(true)}
              aria-expanded={createOpen}
              aria-controls="create-project-panel"
              className="mt-6"
            >
              新建作品
            </Button>
          </div>
          {latest && projects.isSuccess && (
            <button
              type="button"
              onClick={() => enter(latest.id)}
              className="continue-writing group flex min-h-32 items-center gap-4 rounded-lg border p-5 text-left transition-[border-color,background-color]"
            >
              <span className="flex size-11 shrink-0 items-center justify-center rounded-md bg-celadon-900/70 text-celadon-300">
                <BookMarked className="size-6" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-xs text-celadon-300">
                  最近编辑 · {relativeTime(latest.updated_at) || '等待落笔'}
                </span>
                <span className="mt-2 block truncate font-serif text-lg text-ink-100">{latest.name}</span>
                <span className="mt-2 flex items-center gap-1 text-xs text-ink-400">
                  继续写作{' '}
                  <ArrowUpRight className="size-3.5 transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5" />
                </span>
              </span>
            </button>
          )}
        </section>

        {createOpen && (
          <section
            id="create-project-panel"
            className="create-project-panel rounded-xl border border-ink-800 bg-ink-900 p-5 shadow-panel sm:p-6"
            aria-label="新建作品"
          >
            <div className="flex items-start gap-4">
              <span className="hidden size-10 shrink-0 items-center justify-center rounded-lg bg-celadon-900 text-celadon-300 sm:flex">
                <FolderPlus className="size-5" />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <h2 className="font-serif text-xl text-ink-100">开始一本新书</h2>
                    <p className="mt-1 text-xs leading-5 text-ink-400">
                      先留下一个方向，卷、章节和设定可以边写边补。
                    </p>
                  </div>
                  <IconButton
                    label="关闭新建作品"
                    size="md"
                    onClick={closeCreate}
                    disabled={create.isPending}
                  >
                    <X />
                  </IconButton>
                </div>
                <form
                  className="mt-5 grid gap-4"
                  onSubmit={submitCreate}
                  aria-busy={create.isPending}
                  onKeyDown={(event) => {
                    if (event.key === 'Escape') {
                      event.preventDefault();
                      closeCreate();
                    }
                  }}
                >
                  <label className="grid gap-1.5">
                    <span className="text-xs font-medium text-ink-300">书名</span>
                    <Input
                      disabled={create.isPending}
                      value={createName}
                      onChange={(event) => setCreateName(event.target.value)}
                      placeholder="例如：山海旧梦"
                      autoFocus
                      maxLength={80}
                    />
                  </label>
                  <label className="grid gap-1.5">
                    <span className="text-xs font-medium text-ink-300">
                      一句话简介 <span className="font-normal text-ink-500">（可选）</span>
                    </span>
                    <Input
                      disabled={create.isPending}
                      value={description}
                      onChange={(event) => setDescription(event.target.value)}
                      placeholder="题材、主角与核心冲突"
                      maxLength={500}
                    />
                  </label>
                  {createError && (
                    <p role="alert" className="text-xs leading-6 text-cinnabar-400">
                      {createError}
                    </p>
                  )}
                  <div className="flex flex-wrap items-end justify-between gap-4">
                    <fieldset disabled={create.isPending} className="grid gap-1.5">
                      <legend className="text-xs font-medium text-ink-300">写作语言</legend>
                      <div className="flex gap-1 rounded-md border border-ink-800 bg-ink-950 p-1">
                        {(['zh', 'en'] as const).map((value) => (
                          <button
                            key={value}
                            type="button"
                            aria-pressed={language === value}
                            onClick={() => setLanguage(value)}
                            className={`rounded px-3 py-1.5 text-xs transition-colors ${language === value ? 'bg-ink-800 text-ink-100' : 'text-ink-500 hover:text-ink-200'}`}
                          >
                            {value === 'zh' ? '中文' : 'English'}
                          </button>
                        ))}
                      </div>
                    </fieldset>
                    <div className="flex gap-2">
                      <Button tone="outline" type="button" onClick={closeCreate} disabled={create.isPending}>
                        取消
                      </Button>
                      <Button
                        tone="primary"
                        type="submit"
                        loading={create.isPending}
                        disabled={createName.trim() === ''}
                        icon={<ArrowUpRight />}
                      >
                        创建并开始
                      </Button>
                    </div>
                  </div>
                </form>
              </div>
            </div>
          </section>
        )}

        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h2 className="font-serif text-xl">
              全部作品{' '}
              <span className="ml-2 font-sans text-xs text-ink-400">
                {projects.isSuccess ? `${sorted.length} 部作品` : ''}
              </span>
            </h2>
            <p className="mt-2 text-xs text-ink-400">
              {projects.isSuccess
                ? `共 ${totalWords.toLocaleString('zh-CN')} 字 · 按最近编辑排序`
                : '每一个世界，都值得被记录'}
            </p>
          </div>
          <div className="flex w-full items-center gap-2 sm:w-auto">
            <div className="min-w-0 flex-1 sm:flex-none">
              <Input
                aria-label="搜索作品"
                icon={<Search />}
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                placeholder="搜索书名或简介…"
                className="h-10 w-full sm:w-64"
              />
            </div>
            <IconButton
              label="刷新作品列表"
              size="lg"
              onClick={() => void projects.refetch()}
              disabled={projects.isFetching}
            >
              <RefreshCw className={projects.isFetching ? 'animate-spin' : undefined} />
            </IconButton>
          </div>
        </div>

        {projects.isPending && <SkeletonList rows={6} className="gap-3" />}

        {projects.isError && (
          <EmptyState
            title="读不到项目列表"
            description="确认后端已启动，然后重试。"
            action={
              <Button onClick={() => void projects.refetch()} loading={projects.isFetching}>
                重试
              </Button>
            }
          />
        )}

        {projects.isSuccess && filtered.length === 0 && (
          <EmptyState
            icon={<BookMarked />}
            title={deferredKeyword.trim() === '' ? '还没有项目' : '没有匹配的项目'}
            description={
              deferredKeyword.trim() === '' ? '新建一个项目，或先导入一本已有的书稿。' : '换个关键词试试。'
            }
            action={
              deferredKeyword.trim() === '' ? (
                <Button tone="primary" icon={<FolderPlus />} onClick={() => setCreateOpen(true)}>
                  新建作品
                </Button>
              ) : undefined
            }
          />
        )}

        {filtered.length > 0 && (
          <ul
            aria-label="作品列表"
            aria-busy={keyword !== deferredKeyword}
            className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3"
          >
            {filtered.map((project) => (
              <li key={project.id}>
                <ProjectCard
                  project={project}
                  onOpen={() => enter(project.id)}
                  onRename={() => setRenaming(project)}
                  onDelete={() => setDeleting(project)}
                />
              </li>
            ))}
          </ul>
        )}
        <footer className="mt-auto border-t border-ink-800 pt-6 text-xs tracking-wide text-ink-400">
          墨序 MOXU <span className="mx-2 text-ink-700">/</span> 专注于下一段文字。
        </footer>
      </div>

      {settingsOpen && (
        <LazyOverlay>
          <SettingsDialog onClose={() => setSettingsOpen(false)} />
        </LazyOverlay>
      )}

      {renaming !== null && (
        <LazyOverlay>
          <RenameDialog project={renaming} onClose={() => setRenaming(null)} />
        </LazyOverlay>
      )}

      {deleting !== null && (
        <LazyOverlay>
          <ConfirmDialog
            open
            onOpenChange={(open) => {
              if (!open) setDeleting(null);
            }}
            title={`删除《${deleting.name}》`}
            description="项目目录下的全部稿件、卡片、大纲与正典都会被删除。"
            confirmLabel="永久删除"
            danger
            requirePhrase={deleting.name}
            loading={remove.isPending}
            onConfirm={() => remove.mutate(deleting.id, { onSuccess: () => setDeleting(null) })}
          />
        </LazyOverlay>
      )}
    </div>
  );
}

function ProjectCard({
  project,
  onOpen,
  onRename,
  onDelete,
}: {
  project: Project;
  onOpen: () => void;
  onRename: () => void;
  onDelete: () => void;
}): ReactNode {
  return (
    <div
      data-color={[...project.id].reduce((sum, char) => sum + (char.codePointAt(0) ?? 0), 0) % 4}
      className="project-card group relative flex h-full flex-col overflow-hidden rounded-lg border border-ink-800 bg-ink-900 transition-colors hover:border-celadon-700"
    >
      {/* 整卡可点，菜单按钮浮在上层并阻止冒泡 */}
      <button
        type="button"
        onClick={onOpen}
        className="flex flex-1 flex-col items-start px-5 pt-5 pb-6 text-left"
      >
        <span
          aria-hidden
          className="book-monogram mb-7 flex size-10 items-center justify-center rounded-sm font-serif text-lg"
        >
          {project.name.slice(0, 1)}
        </span>
        <span className="line-clamp-1 font-serif text-xl text-ink-100 group-hover:text-celadon-300">
          {project.name}
        </span>
        <span className="mt-3 line-clamp-2 min-h-10 text-xs leading-5 text-ink-400">
          {project.description || '故事的轮廓，等待你慢慢勾勒。'}
        </span>
      </button>

      <div className="flex items-center gap-3 border-t border-ink-800 px-5 py-3 text-xs text-ink-400">
        <span className="tnum">{project.chapter_count} 章</span>
        <span className="tnum">{project.total_word_count.toLocaleString('zh-CN')} 字</span>
        <span className="flex-1" />
        <span className="rounded-full bg-ink-850 px-2 py-0.5 text-2xs text-ink-500">
          {project.language === 'en' ? 'English' : '中文'}
        </span>
        <span className="flex items-center gap-1 text-ink-500">
          {relativeTime(project.updated_at)}
          <ArrowRight className="size-3.5 opacity-0 transition-opacity group-hover:opacity-100" />
        </span>
      </div>

      {/* 只有两个操作，直接并排放比藏进菜单少一次点击 */}
      <div className="project-actions absolute top-5 right-4 flex gap-1 opacity-100 transition-opacity sm:opacity-0 sm:group-hover:opacity-100 focus-within:opacity-100">
        <IconButton label="重命名" size="lg" onClick={onRename}>
          <Pencil />
        </IconButton>
        <IconButton label="删除项目" size="lg" tone="danger" onClick={onDelete}>
          <Trash2 />
        </IconButton>
      </div>
    </div>
  );
}

function RenameDialog({ project, onClose }: { project: Project; onClose: () => void }): ReactNode {
  const [name, setName] = useState(project.name);
  const rename = useRenameProject(project.id);
  const trimmed = name.trim();

  return (
    <ConfirmDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title="重命名项目"
      confirmLabel="保存"
      loading={rename.isPending}
      onConfirm={() => {
        if (trimmed === '' || trimmed === project.name) {
          onClose();
          return;
        }
        rename.mutate(trimmed, { onSuccess: onClose });
      }}
      description={
        <Input
          value={name}
          onChange={(event) => setName(event.target.value)}
          autoFocus
          maxLength={80}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && trimmed !== '') {
              event.preventDefault();
              rename.mutate(trimmed, { onSuccess: onClose });
            }
          }}
        />
      }
    />
  );
}
