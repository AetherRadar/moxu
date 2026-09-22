import { type CSSProperties, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { useLayoutStore, layoutBounds } from '@/stores/layout';
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
  aux,
  statusBar,
  onOpenSettings,
}: {
  titleBar: ReactNode;
  side: ReactNode;
  main: ReactNode;
  aux?: ReactNode;
  statusBar: ReactNode;
  onOpenSettings: () => void;
}): ReactNode {
  const sideVisible = useLayoutStore((state) => state.sideVisible);
  const sideWidth = useLayoutStore((state) => state.sideWidth);
  const setSideWidth = useLayoutStore((state) => state.setSideWidth);
  const auxVisible = useLayoutStore((state) => state.auxVisible);
  const auxWidth = useLayoutStore((state) => state.auxWidth);
  const setAuxWidth = useLayoutStore((state) => state.setAuxWidth);
  const focusMode = useLayoutStore((state) => state.focusMode);

  const showSide = sideVisible && !focusMode;
  const showAux = auxVisible && aux !== undefined && !focusMode;

  const shellStyle = {
    '--side-w': `${sideWidth}px`,
    '--aux-w': `${auxWidth}px`,
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

      <div className="flex min-h-0 flex-1">
        {!focusMode && <ActivityBar onOpenSettings={onOpenSettings} />}

        {showSide && (
          <>
            <aside
              style={{ width: 'var(--side-w)' }}
              className="min-w-0 shrink-0 overflow-hidden border-r border-ink-800 bg-ink-900"
            >
              <ErrorBoundary>{side}</ErrorBoundary>
            </aside>
            <ResizeHandle
              cssVar="--side-w"
              direction="east"
              min={layoutBounds.SIDE_MIN}
              max={layoutBounds.SIDE_MAX}
              defaultWidth={288}
              onCommit={setSideWidth}
              label="调整侧栏宽度"
            />
          </>
        )}

        <main className="flex min-w-0 flex-1 flex-col">
          <ErrorBoundary>{main}</ErrorBoundary>
        </main>

        {showAux && (
          <>
            <ResizeHandle
              cssVar="--aux-w"
              direction="west"
              min={layoutBounds.AUX_MIN}
              max={layoutBounds.AUX_MAX}
              defaultWidth={380}
              onCommit={setAuxWidth}
              label="调整右侧面板宽度"
            />
            <aside
              style={{ width: 'var(--aux-w)' }}
              className="min-w-0 shrink-0 overflow-hidden border-l border-ink-800 bg-ink-900"
            >
              <ErrorBoundary>{aux}</ErrorBoundary>
            </aside>
          </>
        )}
      </div>

      {statusBar}
    </div>
  );
}
