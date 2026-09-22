import { lazy, useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { useVirtualizer } from '@tanstack/react-virtual';
import {
  ArrowDown,
  ArrowUp,
  BookPlus,
  ChevronDown,
  ChevronRight,
  Ellipsis,
  FilePlus2,
  FileText,
  FolderPlus,
  Pencil,
  RefreshCw,
  Search,
  Trash2,
  Upload,
} from 'lucide-react';
import {
  Button,
  EmptyState,
  IconButton,
  Input,
  Menu,
  MenuContent,
  MenuItem,
  MenuLabel,
  MenuSeparator,
  MenuTrigger,
  Panel,
  SkeletonList,
  Tooltip,
} from '@/components/ui';
import { LazyOverlay } from '@/components/ui/LazyOverlay';
import { cn } from '@/lib/cn';
import { useShortcuts } from '@/hooks/useShortcuts';
import { formatChapterLabel, formatVolumeLabel, parseChapterId } from '@/lib/chapter-id';
import {
  useChapterTree,
  useCreateChapter,
  useCreateVolume,
  useDeleteChapter,
  useDeleteVolume,
  useReorderChapters,
} from '@/features/chapters/queries';
import {
  filterTree,
  flattenTree,
  type ChapterNode,
  type TreeRow,
  type VolumeNode,
} from '@/features/chapters/tree';
import { useWorkspaceStore } from '@/stores/workspace';

const ConfirmDialog = lazy(() =>
  import('@/components/ui/ConfirmDialog').then((m) => ({ default: m.ConfirmDialog })),
);
const ChapterRenameDialog = lazy(() =>
  import('@/features/chapters/ChapterRenameDialog').then((m) => ({ default: m.ChapterRenameDialog })),
);
const VolumeRenameDialog = lazy(() =>
  import('@/features/chapters/VolumeRenameDialog').then((m) => ({ default: m.VolumeRenameDialog })),
);
const BookImportDialog = lazy(() =>
  import('@/features/imports/BookImportDialog').then((m) => ({ default: m.BookImportDialog })),
);

const ROW_HEIGHT = 26;

/**
 * 卷与章节树。
 *
 * 用虚拟列表而非全量渲染：长篇动辄上千章，一次性挂上千个 DOM 节点会让
 * 打开侧栏这个动作本身卡住半秒。这里只渲染可视区 + 上下各 8 行。
 */
export function ExplorerPanel(): ReactNode {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const scrollRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const [keyword, setKeyword] = useState('');
  const [searching, setSearching] = useState(false);
  const [renamingChapter, setRenamingChapter] = useState<ChapterNode | null>(null);
  const [renamingVolume, setRenamingVolume] = useState<VolumeNode | null>(null);
  const [deletingChapter, setDeletingChapter] = useState<ChapterNode | null>(null);
  const [deletingVolume, setDeletingVolume] = useState<VolumeNode | null>(null);
  const [importOpen, setImportOpen] = useState(false);

  const tree = useChapterTree(projectId);
  const chapter = useWorkspaceStore((state) => state.chapter);
  const openChapter = useWorkspaceStore((state) => state.openChapter);
  const expandedList = useWorkspaceStore((state) => state.expandedVolumes);
  const toggleVolume = useWorkspaceStore((state) => state.toggleVolume);
  const expandVolumes = useWorkspaceStore((state) => state.expandVolumes);

  const createChapter = useCreateChapter(projectId, tree.chapterIds);
  const createVolume = useCreateVolume(projectId);
  const deleteChapter = useDeleteChapter(projectId);
  const deleteVolume = useDeleteVolume(projectId);
  const reorder = useReorderChapters(projectId);

  const openSearch = useCallback(() => {
    setSearching(true);
    // 等搜索框挂上 DOM 再聚焦
    requestAnimationFrame(() => searchRef.current?.focus());
  }, []);
  useShortcuts({ 'ctrl+p': openSearch });

  // 搜索时强制展开全部，否则命中结果藏在折叠的卷里看不见
  const visible = useMemo(() => filterTree(tree.volumes, keyword), [tree.volumes, keyword]);
  const expanded = useMemo(() => {
    if (keyword.trim() !== '') return new Set(visible.map((volume) => volume.id));
    // 只有一卷时默认展开，省一次点击
    if (expandedList.length === 0 && tree.volumes.length === 1 && tree.volumes[0]) {
      return new Set([tree.volumes[0].id]);
    }
    return new Set(expandedList);
  }, [keyword, visible, expandedList, tree.volumes]);

  const rows = useMemo(() => flattenTree(visible, expanded), [visible, expanded]);

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 8,
    getItemKey: (index) => {
      const row = rows[index];
      return row === undefined ? index : row.kind === 'volume' ? `v:${row.volume.id}` : `c:${row.chapter.id}`;
    },
  });

  const addChapter = useCallback(
    (volumeId: string) => {
      createChapter.mutate(
        { volumeId },
        {
          onSuccess: (id) => {
            expandVolumes([volumeId]);
            openChapter(id);
          },
        },
      );
    },
    [createChapter, expandVolumes, openChapter],
  );

  /** 番外/幕间挂在某个正文章节下，序号由后端 ID 规则分配。 */
  const addSubChapter = useCallback(
    (parent: ChapterNode, kind: 'extra' | 'interlude') => {
      createChapter.mutate(
        { volumeId: parent.volumeId, kind, insertAfter: parent.id },
        { onSuccess: (id) => openChapter(id) },
      );
    },
    [createChapter, openChapter],
  );

  /** 卷内上移/下移：把该卷全量有序 ID 交给后端，由后端落 order_index。 */
  const moveChapter = useCallback(
    (target: ChapterNode, offset: -1 | 1) => {
      const volume = tree.volumes.find((item) => item.id === target.volumeId);
      if (volume === undefined) return;
      const index = volume.chapters.findIndex((item) => item.id === target.id);
      const swap = index + offset;
      if (index < 0 || swap < 0 || swap >= volume.chapters.length) return;
      const order = volume.chapters.map((item) => item.id);
      [order[index], order[swap]] = [order[swap] as string, order[index] as string];
      reorder.mutate({ volumeId: volume.id, order });
    },
    [tree.volumes, reorder],
  );

  const addVolume = useCallback(() => {
    const order = tree.volumes.length + 1;
    createVolume.mutate({ title: '', order });
  }, [createVolume, tree.volumes.length]);

  const totalChapters = tree.volumes.reduce((sum, volume) => sum + volume.chapters.length, 0);

  return (
    <Panel
      title="章节"
      scroll={false}
      actions={
        <>
          <Tooltip content="搜索章节" shortcut="Ctrl+P">
            <IconButton
              label="搜索章节"
              tone={searching ? 'active' : 'ghost'}
              onClick={() => {
                if (searching) {
                  setSearching(false);
                  setKeyword('');
                } else {
                  openSearch();
                }
              }}
            >
              <Search />
            </IconButton>
          </Tooltip>
          <Tooltip content="新建分卷">
            <IconButton label="新建分卷" onClick={addVolume} disabled={createVolume.isPending}>
              <FolderPlus />
            </IconButton>
          </Tooltip>
          <Tooltip content="导入书籍">
            <IconButton label="导入书籍" onClick={() => setImportOpen(true)}>
              <Upload />
            </IconButton>
          </Tooltip>
          <Tooltip content="刷新">
            <IconButton label="刷新" onClick={tree.refetch}>
              <RefreshCw />
            </IconButton>
          </Tooltip>
        </>
      }
      footer={
        <span className="flex items-center gap-2 px-1 text-2xs text-ink-500">
          <span className="tnum">{tree.volumes.length}</span> 卷
          <span className="tnum">{totalChapters}</span> 章
        </span>
      }
    >
      {searching && (
        <div className="border-b border-ink-850 p-2">
          <Input
            ref={searchRef}
            icon={<Search />}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="章节标题或编号"
            className="h-7"
            onKeyDown={(event) => {
              if (event.key !== 'Escape') return;
              setKeyword('');
              setSearching(false);
            }}
          />
        </div>
      )}

      {tree.isPending && <SkeletonList rows={10} />}

      {!tree.isPending && tree.isError && (
        <EmptyState title="读不到章节" description="确认后端已启动。" />
      )}

      {!tree.isPending && !tree.isError && rows.length === 0 && (
        <EmptyState
          icon={<FileText />}
          title={keyword === '' ? '还没有章节' : '没有匹配的章节'}
          description={keyword === '' ? '新建一卷，然后添加第一章；或者直接导入整本书。' : undefined}
          action={
            keyword === '' ? (
              <Button tone="outline" size="sm" icon={<Upload />} onClick={() => setImportOpen(true)}>
                导入书籍
              </Button>
            ) : undefined
          }
        />
      )}

      {rows.length > 0 && (
        <div ref={scrollRef} className="h-full overflow-y-auto">
          <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
            {virtualizer.getVirtualItems().map((item) => {
              const row = rows[item.index];
              if (row === undefined) return null;
              return (
                <div
                  key={item.key}
                  // translateY 而非 top：合成器处理，滚动时不触发重排
                  style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    right: 0,
                    height: item.size,
                    transform: `translateY(${item.start}px)`,
                  }}
                >
                  {row.kind === 'volume' ? (
                    <VolumeRow
                      row={row}
                      onToggle={() => toggleVolume(row.volume.id)}
                      onAddChapter={() => addChapter(row.volume.id)}
                      onRename={() => setRenamingVolume(row.volume)}
                      onDelete={() => setDeletingVolume(row.volume)}
                    />
                  ) : (
                    <ChapterRow
                      chapter={row.chapter}
                      selected={chapter === row.chapter.id}
                      // 上移/下移要拿未过滤树里的真实位置：搜索时 row.volume 只是子集
                      index={
                        tree.volumes
                          .find((item) => item.id === row.volume.id)
                          ?.chapters.indexOf(row.chapter) ?? -1
                      }
                      count={
                        tree.volumes.find((item) => item.id === row.volume.id)?.chapters.length ?? 0
                      }
                      onOpen={() => openChapter(row.chapter.id)}
                      onRename={() => setRenamingChapter(row.chapter)}
                      onAddSub={(kind) => addSubChapter(row.chapter, kind)}
                      onMove={(offset) => moveChapter(row.chapter, offset)}
                      onDelete={() => setDeletingChapter(row.chapter)}
                    />
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {renamingChapter !== null && (
        <LazyOverlay>
          <ChapterRenameDialog
            projectId={projectId}
            chapter={renamingChapter}
            onClose={() => setRenamingChapter(null)}
          />
        </LazyOverlay>
      )}

      {renamingVolume !== null && (
        <LazyOverlay>
          <VolumeRenameDialog
            projectId={projectId}
            volume={renamingVolume}
            onClose={() => setRenamingVolume(null)}
          />
        </LazyOverlay>
      )}

      {deletingChapter !== null && (
        <LazyOverlay>
          <ConfirmDialog
            open
            onOpenChange={(open) => {
              if (!open) setDeletingChapter(null);
            }}
            title={`删除 ${formatChapterLabel(deletingChapter.id)}`}
            description="该章的正文、历史版本与摘要都会被删除。"
            confirmLabel="删除"
            danger
            loading={deleteChapter.isPending}
            onConfirm={() =>
              deleteChapter.mutate(deletingChapter.id, {
                onSuccess: () => {
                  if (chapter === deletingChapter.id) openChapter(null);
                  setDeletingChapter(null);
                },
              })
            }
          />
        </LazyOverlay>
      )}

      {importOpen && (
        <LazyOverlay>
          <BookImportDialog projectId={projectId} onClose={() => setImportOpen(false)} />
        </LazyOverlay>
      )}

      {deletingVolume !== null && (
        <LazyOverlay>
          <ConfirmDialog
            open
            onOpenChange={(open) => {
              if (!open) setDeletingVolume(null);
            }}
            title={`删除 ${formatVolumeLabel(deletingVolume.id, deletingVolume.title)}`}
            description={`这一卷下有 ${deletingVolume.chapters.length} 章，会一并处理。`}
            confirmLabel="删除分卷"
            danger
            requirePhrase={deletingVolume.chapters.length > 0 ? deletingVolume.id : undefined}
            loading={deleteVolume.isPending}
            onConfirm={() =>
              deleteVolume.mutate(deletingVolume.id, { onSuccess: () => setDeletingVolume(null) })
            }
          />
        </LazyOverlay>
      )}
    </Panel>
  );
}

function VolumeRow({
  row,
  onToggle,
  onAddChapter,
  onRename,
  onDelete,
}: {
  row: Extract<TreeRow, { kind: 'volume' }>;
  onToggle: () => void;
  onAddChapter: () => void;
  onRename: () => void;
  onDelete: () => void;
}): ReactNode {
  const { volume, expanded } = row;
  return (
    <div className="group flex h-[26px] items-center gap-1 pr-1 pl-1.5">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        className="flex min-w-0 flex-1 items-center gap-1 text-left"
      >
        <span className="text-ink-500 [&_svg]:size-3.5">
          {expanded ? <ChevronDown /> : <ChevronRight />}
        </span>
        <span className="truncate text-xs font-medium text-ink-200">
          {formatVolumeLabel(volume.id, volume.title)}
        </span>
        <span className="tnum shrink-0 text-2xs text-ink-600">{volume.chapters.length}</span>
      </button>

      <div className="flex shrink-0 gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        <Tooltip content="在此卷新建章节" side="top">
          <IconButton label="在此卷新建章节" size="xs" onClick={onAddChapter}>
            <FilePlus2 />
          </IconButton>
        </Tooltip>
        <Tooltip content="重命名分卷" side="top">
          <IconButton label="重命名分卷" size="xs" onClick={onRename}>
            <Pencil />
          </IconButton>
        </Tooltip>
        <Tooltip content="删除分卷" side="top">
          <IconButton label="删除分卷" size="xs" tone="danger" onClick={onDelete}>
            <Trash2 />
          </IconButton>
        </Tooltip>
      </div>
    </div>
  );
}

function ChapterRow({
  chapter,
  selected,
  index,
  count,
  onOpen,
  onRename,
  onAddSub,
  onMove,
  onDelete,
}: {
  chapter: ChapterNode;
  selected: boolean;
  index: number;
  count: number;
  onOpen: () => void;
  onRename: () => void;
  onAddSub: (kind: 'extra' | 'interlude') => void;
  onMove: (offset: -1 | 1) => void;
  onDelete: () => void;
}): ReactNode {
  // 受控打开：左键点 ⋯ 或右键点行都能拉起同一个菜单
  const [menuOpen, setMenuOpen] = useState(false);
  const isNormal = parseChapterId(chapter.id)?.kind === 'normal';

  return (
    <Menu open={menuOpen} onOpenChange={setMenuOpen}>
      <div
        onContextMenu={(event) => {
          event.preventDefault();
          setMenuOpen(true);
        }}
        className={cn(
          'group flex h-[26px] items-center gap-1 pr-1 pl-6',
          selected ? 'bg-celadon-900/50' : 'hover:bg-ink-850',
        )}
      >
        <button
          type="button"
          onClick={onOpen}
          className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
        >
          <span className={cn('tnum shrink-0 text-2xs', selected ? 'text-celadon-400' : 'text-ink-600')}>
            {formatChapterLabel(chapter.id)}
          </span>
          <span
            className={cn(
              'truncate text-xs',
              selected ? 'text-celadon-100' : 'text-ink-300 group-hover:text-ink-100',
              chapter.title === '' && 'italic text-ink-600',
            )}
          >
            {chapter.title === '' ? '未命名' : chapter.title}
          </span>
          {chapter.wordCount > 0 && (
            <span className="tnum ml-auto shrink-0 pl-1 text-2xs text-ink-600">
              {Math.round(chapter.wordCount / 100) / 10}k
            </span>
          )}
          {/* 没有摘要说明还没归档分析，用一个小点提示，不占文字空间 */}
          {!chapter.analyzed && (
            <span
              aria-label="尚未归档"
              className="size-1 shrink-0 rounded-full bg-gamboge-500/70"
            />
          )}
        </button>

        <MenuTrigger asChild>
          <IconButton
            label="章节操作"
            size="xs"
            className="opacity-0 transition-opacity group-hover:opacity-100 focus:opacity-100 data-[state=open]:opacity-100"
          >
            <Ellipsis />
          </IconButton>
        </MenuTrigger>
      </div>

      <MenuContent align="start">
        <MenuLabel>{formatChapterLabel(chapter.id, 'zh-CN', { includeVolume: true })}</MenuLabel>
        <MenuItem icon={<FileText />} onSelect={onOpen}>
          打开
        </MenuItem>
        <MenuItem icon={<Pencil />} onSelect={onRename}>
          重命名
        </MenuItem>
        {isNormal && (
          <>
            <MenuSeparator />
            <MenuItem icon={<BookPlus />} onSelect={() => onAddSub('extra')}>
              新建番外
            </MenuItem>
            <MenuItem icon={<BookPlus />} onSelect={() => onAddSub('interlude')}>
              新建幕间
            </MenuItem>
          </>
        )}
        <MenuSeparator />
        <MenuItem icon={<ArrowUp />} disabled={index <= 0} onSelect={() => onMove(-1)}>
          上移
        </MenuItem>
        <MenuItem icon={<ArrowDown />} disabled={index >= count - 1} onSelect={() => onMove(1)}>
          下移
        </MenuItem>
        <MenuSeparator />
        <MenuItem icon={<Trash2 />} danger onSelect={onDelete}>
          删除章节
        </MenuItem>
      </MenuContent>
    </Menu>
  );
}
