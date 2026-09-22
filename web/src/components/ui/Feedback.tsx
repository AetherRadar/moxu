import { type ReactNode } from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/cn';

/** 骨架条。 */
export function Skeleton({
  className,
  width,
}: {
  className?: string;
  width?: string;
}): ReactNode {
  return <div aria-hidden className={cn('skeleton rounded', className)} style={width ? { width } : undefined} />;
}

/** 列表骨架。宽度用固定的不等长序列模拟真实文本，比一排等宽灰条自然。 */
export function SkeletonList({ rows = 6, className }: { rows?: number; className?: string }): ReactNode {
  const widths = ['86%', '72%', '94%', '64%', '80%', '70%', '90%', '58%'];
  return (
    <div className={cn('flex flex-col gap-2 p-2', className)} aria-busy>
      {Array.from({ length: rows }, (_, index) => (
        <Skeleton key={index} className="h-4" width={widths[index % widths.length]} />
      ))}
    </div>
  );
}

export function Spinner({ className }: { className?: string }): ReactNode {
  return <Loader2 aria-hidden className={cn('size-3.5 animate-spin text-ink-400', className)} />;
}

/** 生成中的行内指示：转圈 + 阶段文字，用于工具栏和状态栏。 */
export function ActivityHint({ text, className }: { text: ReactNode; className?: string }): ReactNode {
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-2xs text-gamboge-300', className)}>
      <Spinner className="text-gamboge-400" />
      {text}
    </span>
  );
}

export function EmptyState({
  icon,
  title,
  description,
  action,
  className,
}: {
  icon?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
}): ReactNode {
  return (
    <div
      className={cn(
        'flex h-full min-h-32 flex-col items-center justify-center gap-2 px-6 py-10 text-center',
        className,
      )}
    >
      {icon !== undefined && <span className="text-ink-600 [&_svg]:size-7">{icon}</span>}
      <p className="text-xs font-medium text-ink-300">{title}</p>
      {description !== undefined && (
        <p className="max-w-xs text-2xs leading-relaxed text-ink-500">{description}</p>
      )}
      {action !== undefined && <div className="mt-1.5">{action}</div>}
    </div>
  );
}
