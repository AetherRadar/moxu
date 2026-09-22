import {
  useRef,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import { cn } from '@/lib/cn';

/**
 * 分栏拖拽条。
 *
 * 拖动过程中只改外壳元素上的 CSS 变量，完全不过 React——
 * 一次拖拽会产生几百个 pointermove，如果每个都 setState，
 * 整个 IDE 布局树就要 reconcile 几百次，肉眼可见地卡。
 * 松手时才把最终宽度提交到 store 落库。
 *
 * 同时 rAF 节流：一帧内多个 pointermove 只写一次样式，避免强制同步布局。
 */

export interface ResizeHandleProps {
  /** 要改的 CSS 变量名，例如 --side-w */
  cssVar: string;
  /** 向右拖是变宽（左侧栏）还是变窄（右侧栏） */
  direction: 'east' | 'west';
  min: number;
  max: number;
  /** 松手时落库 */
  onCommit: (width: number) => void;
  /** 双击复位到这个宽度 */
  defaultWidth: number;
  label: string;
}

function readVar(shell: HTMLElement, cssVar: string): number {
  const raw = getComputedStyle(shell).getPropertyValue(cssVar);
  return Number.parseFloat(raw) || 0;
}

export function ResizeHandle({
  cssVar,
  direction,
  min,
  max,
  onCommit,
  defaultWidth,
  label,
}: ResizeHandleProps): ReactNode {
  const drag = useRef<{ shell: HTMLElement; startX: number; startWidth: number; width: number } | null>(null);
  const frame = useRef(0);

  const apply = (width: number): void => {
    const state = drag.current;
    if (!state) return;
    state.width = width;
    if (frame.current !== 0) return;
    frame.current = requestAnimationFrame(() => {
      frame.current = 0;
      const current = drag.current;
      if (current) current.shell.style.setProperty(cssVar, `${current.width}px`);
    });
  };

  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>): void => {
    if (event.button !== 0) return;
    const shell = event.currentTarget.closest<HTMLElement>('[data-app-shell]');
    if (!shell) return;

    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = {
      shell,
      startX: event.clientX,
      startWidth: readVar(shell, cssVar),
      width: readVar(shell, cssVar),
    };
    // 拖拽时全局禁用文本选中与鼠标样式抖动
    shell.dataset['resizing'] = 'true';
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>): void => {
    const state = drag.current;
    if (!state) return;
    const delta = event.clientX - state.startX;
    const signed = direction === 'east' ? delta : -delta;
    apply(Math.min(max, Math.max(min, Math.round(state.startWidth + signed))));
  };

  const finish = (event: ReactPointerEvent<HTMLDivElement>): void => {
    const state = drag.current;
    if (!state) return;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    if (frame.current !== 0) {
      cancelAnimationFrame(frame.current);
      frame.current = 0;
    }
    state.shell.style.setProperty(cssVar, `${state.width}px`);
    delete state.shell.dataset['resizing'];
    onCommit(state.width);
    drag.current = null;
  };

  const nudge = (event: ReactKeyboardEvent<HTMLDivElement>): void => {
    const step = event.shiftKey ? 48 : 16;
    const towardsWider = direction === 'east' ? 'ArrowRight' : 'ArrowLeft';
    const towardsNarrower = direction === 'east' ? 'ArrowLeft' : 'ArrowRight';
    if (event.key !== towardsWider && event.key !== towardsNarrower) return;

    const shell = event.currentTarget.closest<HTMLElement>('[data-app-shell]');
    if (!shell) return;
    event.preventDefault();
    const next = Math.min(
      max,
      Math.max(min, readVar(shell, cssVar) + (event.key === towardsWider ? step : -step)),
    );
    shell.style.setProperty(cssVar, `${next}px`);
    onCommit(next);
  };

  const reset = (event: ReactPointerEvent<HTMLDivElement>): void => {
    const shell = event.currentTarget.closest<HTMLElement>('[data-app-shell]');
    if (!shell) return;
    shell.style.setProperty(cssVar, `${defaultWidth}px`);
    onCommit(defaultWidth);
  };

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      tabIndex={0}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={finish}
      onPointerCancel={finish}
      onDoubleClick={reset}
      onKeyDown={nudge}
      className={cn(
        'group relative z-10 w-px shrink-0 cursor-col-resize bg-ink-800',
        // 命中区域比视觉宽度宽，鼠标好抓
        'after:absolute after:inset-y-0 after:-left-1 after:w-3 after:content-[""]',
        'hover:bg-celadon-600 focus-visible:bg-celadon-500',
        'transition-colors duration-100',
      )}
    />
  );
}
