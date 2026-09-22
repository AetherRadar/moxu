import { type CSSProperties, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { useLayoutStore, layoutBounds } from '@/stores/layout';
import { X } from 'lucide-react';
import { IconButton } from '@/components/ui/Button';
import { ActivityBar } from './ActivityBar';
import { ResizeHandle } from './ResizeHandle';

/**
 * IDE 外壳。
 *
 * 栏宽走 CSS 变量而不是 React state：拖拽时 ResizeHandle 直接改变量，
 * 这棵树一次都不用重渲染。store 里的宽度只在松手时同步，用于刷新后恢复。
 *
 * 三块内容各自包一层错误边界——某个面板炸了不该把正文一起带走。
 */
export function AppShell({
  titleBar,
  side,
  main,
  statusBar,
  onOpenSettings,
}: {
  titleBar: ReactNode;
  side: ReactNode;
  main: ReactNode;
  statusBar: ReactNode;
  onOpenSettings: () => void;
}): ReactNode {
  const sideVisible = useLayoutStore((state) => state.sideVisible);
  const toggleSide = useLayoutStore((state) => state.toggleSide);
  const sideWidth = useLayoutStore((state) => state.sideWidth);
  const setSideWidth = useLayoutStore((state) => state.setSideWidth);
  const focusMode = useLayoutStore((state) => state.focusMode);

  const showSide = sideVisible && !focusMode;

  const shellStyle = {
    '--side-w': `${sideWidth}px`,
  } as CSSProperties;

  return (
    <div
      data-app-shell
      style={shellStyle}
      className={cn(
        'flex h-full flex-col bg-ink-950',
        // 拖拽期间禁止选中和 hover 干扰，光标锁定为列调整
        '[&[data-resizing]]:cursor-col-resize [&[data-resizing]_*]:pointer-events-none [&[data-resizing]]:select-none',
      )}
    >
      {titleBar}

      <div className="relative flex min-h-0 flex-1">
        {!focusMode && <ActivityBar onOpenSettings={onOpenSettings} />}

        {showSide && (
          <>
            <aside
              style={{ width: 'min(var(--side-w), calc(100vw - 4.5rem))' }}
              className="absolute inset-y-0 left-[4.5rem] z-20 min-w-0 shrink-0 overflow-hidden border-r border-ink-800 bg-sidebar shadow-panel md:static md:shadow-none sm:left-20"
            >
              <div className="flex h-full min-h-0 flex-col">
                <div className="flex justify-end border-b border-ink-800 p-1 md:hidden">
                  <IconButton label="收起侧栏" size="lg" onClick={toggleSide}>
                    <X />
                  </IconButton>
                </div>
                <div className="min-h-0 flex-1">
                  <ErrorBoundary>{side}</ErrorBoundary>
                </div>
              </div>
            </aside>
            <div className="hidden md:contents">
              <ResizeHandle
                cssVar="--side-w"
                direction="east"
                min={layoutBounds.SIDE_MIN}
                max={layoutBounds.SIDE_MAX}
                defaultWidth={288}
                onCommit={setSideWidth}
                label="调整侧栏宽度"
              />
            </div>
          </>
        )}

        <main className="flex min-w-0 flex-1 flex-col">
          <ErrorBoundary>{main}</ErrorBoundary>
        </main>
      </div>

      {statusBar}
    </div>
  );
}
