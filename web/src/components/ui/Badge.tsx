import { type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { variants } from './variants';

const badgeClass = variants({
  base: 'inline-flex shrink-0 items-center gap-1 rounded font-medium [&_svg]:size-3',
  variants: {
    tone: {
      neutral: 'bg-ink-800 text-ink-300',
      accent: 'bg-celadon-900/60 text-celadon-300',
      success: 'bg-malachite-900/60 text-malachite-400',
      warning: 'bg-gamboge-900/60 text-gamboge-300',
      danger: 'bg-cinnabar-900/60 text-cinnabar-300',
      outline: 'border border-ink-700 text-ink-400',
    },
    size: {
      sm: 'px-1.5 py-px text-2xs',
      md: 'px-2 py-0.5 text-xs',
    },
  },
  defaults: { tone: 'neutral', size: 'sm' },
});

export type BadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'outline';

export function Badge({
  children,
  tone,
  size,
  className,
}: {
  children: ReactNode;
  tone?: BadgeTone;
  size?: 'sm' | 'md';
  className?: string;
}): ReactNode {
  return <span className={badgeClass({ tone, size }, className)}>{children}</span>;
}

/** 状态圆点，比文字徽章更省横向空间，用于列表行尾。 */
export function Dot({ tone = 'neutral', pulse = false }: { tone?: BadgeTone; pulse?: boolean }): ReactNode {
  const color: Record<BadgeTone, string> = {
    neutral: 'bg-ink-500',
    accent: 'bg-celadon-500',
    success: 'bg-malachite-500',
    warning: 'bg-gamboge-500',
    danger: 'bg-cinnabar-500',
    outline: 'bg-ink-600',
  };
  return (
    <span aria-hidden className="relative inline-flex size-1.5 shrink-0">
      {pulse && <span className={cn('absolute inset-0 animate-ping rounded-full opacity-60', color[tone])} />}
      <span className={cn('relative size-1.5 rounded-full', color[tone])} />
    </span>
  );
}
