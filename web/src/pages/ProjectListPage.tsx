import { lazy, useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { BookMarked, FolderPlus, Pencil, Search, Settings, Trash2 } from 'lucide-react';
import { Button, EmptyState, IconButton, Input, SkeletonList } from '@/components/ui';
import { LazyOverlay } from '@/components/ui/LazyOverlay';
import { Logo } from '@/components/layout/Logo';
import { useDeleteProject, useProjects, useRenameProject } from '@/features/projects/queries';
import type { Project } from '@/api/types';
import { useWorkspaceStore } from '@/stores/workspace';

// 弹窗全部懒加载：列表页不开弹窗就不下载 Radix
const ProjectCreateDialog = lazy(() =>
  import('@/features/projects/ProjectCreateDialog').then((m) => ({ default: m.ProjectCreateDialog })),
);
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
  const remove = useDeleteProject();

  const [keyword, setKeyword] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [renaming, setRenaming] = useState<Project | null>(null);
  const [deleting, setDeleting] = useState<Project | null>(null);

  const filtered = useMemo(() => {
    const list = projects.data ?? [];
    const needle = keyword.trim().toLowerCase();
    const matched =
      needle === ''
        ? list
        : list.filter(
            (item) =>
              item.name.toLowerCase().includes(needle) ||
              (item.description ?? '').toLowerCase().includes(needle),
          );
    // 最近改动的排前面，这是打开项目时最常想找的
    return [...matched].sort((a, b) => (b.updated_at ?? '').localeCompare(a.updated_at ?? ''));
  }, [projects.data, keyword]);

  const enter = (projectId: string): void => {
    openProject(projectId);
    navigate(`/p/${projectId}`);
  };

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto flex min-h-full max-w-5xl flex-col gap-6 px-6 py-10">
        <header className="flex items-end justify-between gap-4">
          <div className="flex items-center gap-3">
            <Logo size={34} />
            <div>
              <h1 className="font-serif text-2xl tracking-wide text-ink-100">墨序</h1>
              <p className="mt-0.5 text-2xs text-ink-500">长篇小说创作工作台</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <IconButton label="设置" size="md" onClick={() => setSettingsOpen(true)}>
              <Settings />
            </IconButton>
            <Button tone="primary" size="lg" icon={<FolderPlus />} onClick={() => setCreateOpen(true)}>
              新建项目
            </Button>
          </div>
        </header>

        {(projects.data?.length ?? 0) > 6 && (
          <Input
            icon={<Search />}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜索书名或简介"
            className="h-9 max-w-sm"
          />
        )}

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
            title={keyword === '' ? '还没有项目' : '没有匹配的项目'}
            description={keyword === '' ? '新建一个项目，或先导入一本已有的书稿。' : '换个关键词试试。'}
            action={
              keyword === '' ? (
                <Button tone="primary" icon={<FolderPlus />} onClick={() => setCreateOpen(true)}>
                  新建项目
                </Button>
              ) : undefined
            }
          />
        )}

        {filtered.length > 0 && (
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
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
      </div>

      {createOpen && (
        <LazyOverlay>
          <ProjectCreateDialog open onOpenChange={setCreateOpen} onCreated={enter} />
        </LazyOverlay>
      )}

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
    <div className="group relative flex h-full flex-col rounded-panel border border-ink-800 bg-ink-900 transition-colors hover:border-ink-700">
      {/* 整卡可点，菜单按钮浮在上层并阻止冒泡 */}
      <button
        type="button"
        onClick={onOpen}
        className="flex flex-1 flex-col items-start gap-2 px-4 pt-4 pb-3 text-left"
      >
        <span className="line-clamp-1 font-serif text-base text-ink-100 group-hover:text-celadon-200">
          {project.name}
        </span>
        <span className="line-clamp-2 min-h-8 text-2xs leading-relaxed text-ink-500">
          {project.description ?? '暂无简介'}
        </span>
      </button>

      <div className="flex items-center gap-3 border-t border-ink-850 px-4 py-2 text-2xs text-ink-500">
        <span className="tnum">{project.chapter_count} 章</span>
        <span className="tnum">{project.total_word_count.toLocaleString('zh-CN')} 字</span>
        <span className="flex-1" />
        <span>{relativeTime(project.updated_at)}</span>
      </div>

      {/* 只有两个操作，直接并排放比藏进菜单少一次点击 */}
      <div className="absolute top-2.5 right-2.5 flex gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        <IconButton label="重命名" size="sm" onClick={onRename}>
          <Pencil />
        </IconButton>
        <IconButton label="删除项目" size="sm" tone="danger" onClick={onDelete}>
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
