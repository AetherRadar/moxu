import { type MouseEvent, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * 侧边面板外壳：固定标题栏 + 可滚动正文。
 * 所有面板共用，保证标题高度、字号和内边距一致。
 */
export function Panel({
  title,
  actions,
  children,
  subtitle,
  footer,
  className,
  scroll = true,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  footer?: ReactNode;
  children: ReactNode;
  className?: string;
  /** 内容自己管滚动时（虚拟列表）传 false */
  scroll?: boolean;
}): ReactNode {
  return (
    <section className={cn('flex h-full min-h-0 flex-col', className)}>
      <header className="flex h-10 shrink-0 items-center gap-1 border-b border-ink-800 bg-ink-975/55 pr-2 pl-4">
        <h2 className="flex-1 truncate text-2xs font-semibold tracking-wide text-ink-400 uppercase">
          {title}
        </h2>
        {actions}
      </header>
      {subtitle !== undefined && (
        <p className="shrink-0 border-b border-ink-850 px-3 py-1.5 text-2xs text-ink-500">{subtitle}</p>
      )}
      <div className={cn('min-h-0 flex-1', scroll && 'overflow-y-auto')}>{children}</div>
      {footer !== undefined && (
        <footer className="shrink-0 border-t border-ink-800 px-2 py-1.5">{footer}</footer>
      )}
    </section>
  );
}

/** 面板内的分组标题，用于把长列表切段。 */
export function PanelSection({
  label,
  action,
  children,
}: {
  label: ReactNode;
  action?: ReactNode;
  children: ReactNode;
}): ReactNode {
  return (
    <div className="border-b border-ink-850 last:border-0">
      <div className="flex items-center gap-1 px-3 py-1.5">
        <span className="flex-1 truncate text-2xs font-medium text-ink-500">{label}</span>
        {action}
      </div>
      <div className="pb-1.5">{children}</div>
    </div>
  );
}

/** 列表行。hover 与选中态统一在这里，省得每个列表各写一遍。 */
export function Row({
  selected = false,
  indent = 0,
  children,
  onClick,
  onDoubleClick,
  onContextMenu,
  className,
  as = 'button',
}: {
  selected?: boolean;
  /** 缩进层级，每级 12px */
  indent?: number;
  children: ReactNode;
  onClick?: () => void;
  onDoubleClick?: () => void;
  onContextMenu?: (event: MouseEvent) => void;
  className?: string;
  as?: 'button' | 'div';
}): ReactNode {
  const shared = cn(
    'group flex w-full items-center gap-1.5 py-1 pr-2 text-left text-xs transition-colors',
    selected ? 'bg-celadon-900/50 text-celadon-100' : 'text-ink-300 hover:bg-ink-850 hover:text-ink-100',
    className,
  );
  const style = { paddingLeft: `${8 + indent * 12}px` };

  if (as === 'div') {
    return (
      <div className={shared} style={style} onContextMenu={onContextMenu}>
        {children}
      </div>
    );
  }
  return (
    <button
      type="button"
      className={shared}
      style={style}
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      onContextMenu={onContextMenu}
      aria-current={selected || undefined}
    >
      {children}
    </button>
  );
}
