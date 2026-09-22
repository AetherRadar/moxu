import { type ReactNode } from 'react';
import { Logo } from './Logo';

/**
 * 顶栏。
 * 只负责排版，内容由使用方通过插槽传入——项目切换器、面包屑和写作工具栏
 * 各属于不同业务域，塞在这里会让顶栏跟着它们一起重渲染。
 */
export function TitleBar({
  breadcrumb,
  center,
  actions,
}: {
  breadcrumb?: ReactNode;
  center?: ReactNode;
  actions?: ReactNode;
}): ReactNode {
  return (
    <header className="flex h-9 shrink-0 items-center gap-2 border-b border-ink-800 bg-ink-975 px-2">
      <span className="flex shrink-0 items-center gap-1.5 pr-1">
        <Logo />
        <span className="font-serif text-sm tracking-wide text-ink-200">墨序</span>
      </span>

      <div className="flex min-w-0 flex-1 items-center gap-1.5">{breadcrumb}</div>

      {center !== undefined && <div className="shrink-0">{center}</div>}

      <div className="flex shrink-0 items-center gap-1">{actions}</div>
    </header>
  );
}

/** 面包屑分隔符，单独导出以便各域自行拼接层级。 */
export function Crumb({
  children,
  onClick,
  muted = false,
}: {
  children: ReactNode;
  onClick?: () => void;
  muted?: boolean;
}): ReactNode {
  const className = muted
    ? 'truncate text-xs text-ink-500'
    : 'truncate text-xs text-ink-300 transition-colors hover:text-ink-100';
  if (!onClick) return <span className={className}>{children}</span>;
  return (
    <button type="button" onClick={onClick} className={className}>
      {children}
    </button>
  );
}

export function CrumbSeparator(): ReactNode {
  return (
    <span aria-hidden className="shrink-0 text-ink-700">
      /
    </span>
  );
}
