import { lazy, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Library, Maximize2, Minimize2 } from 'lucide-react';
import { formatChapterLabel } from '@/lib/chapter-id';
import { projectsApi, qk } from '@/api';
import { AppShell } from '@/components/layout/AppShell';
import { Crumb, CrumbSeparator, TitleBar } from '@/components/layout/TitleBar';
import { StatusBar, StatusItem } from '@/components/layout/StatusBar';
import { LazyOverlay } from '@/components/ui/LazyOverlay';
import { useShortcuts } from '@/hooks/useShortcuts';
import { SidePanelHost } from '@/panels/registry';
import { useChapterTree } from '@/features/chapters/queries';
import { useLayoutStore } from '@/stores/layout';
import { useWorkspaceStore } from '@/stores/workspace';
import { Button } from '@/components/ui';

const WritingArea = lazy(() =>
  import('@/features/writing/WritingArea').then((m) => ({ default: m.WritingArea })),
);
const SettingsDialog = lazy(() =>
  import('@/features/settings/SettingsDialog').then((m) => ({ default: m.SettingsDialog })),
);

/**
 * 工作区：一个项目打开后的主界面。
 * 这里只负责组装外壳与全局快捷键，具体内容由各域面板/页面填充。
 */
export function WorkspacePage(): ReactNode {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const [settingsOpen, setSettingsOpen] = useState(false);

  const sideView = useLayoutStore((state) => state.sideView);
  const activateSideView = useLayoutStore((state) => state.activateSideView);
  const toggleSide = useLayoutStore((state) => state.toggleSide);
  const toggleFocusMode = useLayoutStore((state) => state.toggleFocusMode);
  const focusMode = useLayoutStore((state) => state.focusMode);

  const chapter = useWorkspaceStore((state) => state.chapter);
  const openChapter = useWorkspaceStore((state) => state.openChapter);
  const openProject = useWorkspaceStore((state) => state.openProject);
  const closeProject = useWorkspaceStore((state) => state.closeProject);

  // URL 里的 projectId 同步进 store：openChapter 写 lastChapter 依赖它，
  // 不同步的话刷新后回不到上次打开的章节
  useEffect(() => {
    openProject(projectId);
    return () => closeProject();
  }, [projectId, openProject, closeProject]);

  const project = useQuery({
    queryKey: qk.project(projectId),
    queryFn: () => projectsApi.get(projectId),
    enabled: projectId !== '',
  });
  const chapterTree = useChapterTree(projectId);
  const chapterCount =
    chapterTree.isPending || chapterTree.isError
      ? (project.data?.chapter_count ?? 0)
      : chapterTree.chapterIds.length;
  const totalWordCount =
    chapterTree.isPending || chapterTree.isError
      ? (project.data?.total_word_count ?? 0)
      : chapterTree.volumes.reduce((total, volume) => total + volume.wordCount, 0);

  const bindings = useMemo(
    () => ({
      'ctrl+1': () => activateSideView('explorer'),
      'ctrl+2': () => activateSideView('outline'),
      'ctrl+3': () => activateSideView('cards'),
      'ctrl+4': () => activateSideView('facts'),
      'ctrl+5': () => activateSideView('search'),
      'ctrl+6': () => activateSideView('agents'),
      'ctrl+shift+b': () => toggleSide(),
      'ctrl+shift+f': () => toggleFocusMode(),
      'ctrl+,': () => setSettingsOpen(true),
    }),
    [activateSideView, toggleSide, toggleFocusMode],
  );
  useShortcuts(bindings);

  return (
    <>
      <AppShell
        onOpenSettings={() => setSettingsOpen(true)}
        titleBar={
          <TitleBar
            breadcrumb={
              <>
                <Crumb onClick={() => navigate('/')}>项目</Crumb>
                <CrumbSeparator />
                <Crumb
                  onClick={() => {
                    openChapter(null);
                    if (focusMode) toggleFocusMode();
                  }}
                  muted={chapter === null}
                >
                  {project.data?.name ?? projectId}
                </Crumb>
                {chapter !== null && (
                  <>
                    <CrumbSeparator />
                    <Crumb muted>{formatChapterLabel(chapter)}</Crumb>
                  </>
                )}
              </>
            }
            actions={
              <>
                {chapter && (
                  <Button
                    tone={focusMode ? 'default' : 'ghost'}
                    size="sm"
                    icon={focusMode ? <Minimize2 /> : <Maximize2 />}
                    onClick={toggleFocusMode}
                    aria-pressed={focusMode}
                    aria-label={focusMode ? '退出专注' : '专注写作'}
                    title="专注写作（Ctrl / ⌘ + Shift + F）"
                  >
                    <span className="hidden sm:inline">{focusMode ? '退出专注' : '专注写作'}</span>
                  </Button>
                )}
                <Button
                  tone="ghost"
                  size="sm"
                  icon={<Library />}
                  onClick={() => navigate('/')}
                  aria-label="作品库"
                >
                  <span className="hidden sm:inline">作品库</span>
                </Button>
              </>
            }
          />
        }
        side={<SidePanelHost view={sideView} />}
        main={
          <LazyOverlay>
            <WritingArea />
          </LazyOverlay>
        }
        statusBar={
          <StatusBar
            left={
              project.data && (
                <>
                  <StatusItem>{chapterCount} 章</StatusItem>
                  <StatusItem>
                    <span className="tnum">{totalWordCount.toLocaleString('zh-CN')}</span> 字
                  </StatusItem>
                </>
              )
            }
          />
        }
      />
      {settingsOpen && (
        <LazyOverlay>
          <SettingsDialog projectId={projectId} onClose={() => setSettingsOpen(false)} />
        </LazyOverlay>
      )}
    </>
  );
}
