import { type ReactNode } from 'react';
import { Maximize2, Minimize2 } from 'lucide-react';
import { cn } from '@/lib/cn';
import { Dot, Tooltip } from '@/components/ui';
import { useTraceStatus } from '@/hooks/useWsStatus';
import { useLayoutStore } from '@/stores/layout';

/**
 * 底部状态栏。
 * 高度固定 22px，是唯一常驻显示后端连通性与字数的地方。
 */
export function StatusBar({
  left,
  center,
  right,
}: {
  left?: ReactNode;
  center?: ReactNode;
  right?: ReactNode;
}): ReactNode {
  const status = useTraceStatus();
  const focusMode = useLayoutStore((state) => state.focusMode);
  const toggleFocusMode = useLayoutStore((state) => state.toggleFocusMode);

  const connection =
    status === 'open'
      ? { tone: 'success' as const, text: 'backend', pulse: false }
      : status === 'connecting'
        ? { tone: 'warning' as const, text: '重连中', pulse: true }
        : { tone: 'danger' as const, text: '后端未连接', pulse: false };

  return (
    <footer className="flex h-[22px] shrink-0 items-center gap-3 border-t border-ink-800 bg-ink-975 px-2 text-2xs text-ink-500">
      <span className="flex items-center gap-1.5">
        <Dot tone={connection.tone} pulse={connection.pulse} />
        <span className={cn(status !== 'open' && 'text-ink-300')}>{connection.text}</span>
      </span>

      {left}

      <div className="flex-1" />

      {center}
      {right}

      <Tooltip content={focusMode ? '退出沉浸' : '沉浸写作'} shortcut="Ctrl+Shift+F" side="top">
        <button
          type="button"
          aria-label={focusMode ? '退出沉浸' : '沉浸写作'}
          onClick={toggleFocusMode}
          className={cn(
            'flex size-4 items-center justify-center transition-colors [&_svg]:size-3',
            focusMode ? 'text-celadon-400' : 'hover:text-ink-200',
          )}
        >
          {focusMode ? <Minimize2 /> : <Maximize2 />}
        </button>
      </Tooltip>
    </footer>
  );
}

/** 状态栏里的只读信息块。 */
export function StatusItem({
  icon,
  children,
  tone = 'default',
}: {
  icon?: ReactNode;
  children: ReactNode;
  tone?: 'default' | 'active' | 'warning' | 'danger';
}): ReactNode {
  return (
    <span
      className={cn(
        'flex items-center gap-1 [&_svg]:size-3',
        tone === 'active' && 'text-celadon-300',
        tone === 'warning' && 'text-gamboge-300',
        tone === 'danger' && 'text-cinnabar-400',
      )}
    >
      {icon}
      {children}
    </span>
  );
}
