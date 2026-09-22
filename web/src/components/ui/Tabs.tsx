import { type ReactNode } from 'react';
import * as RadixTabs from '@radix-ui/react-tabs';
import { cn } from '@/lib/cn';

export interface TabItem<T extends string> {
  value: T;
  label: ReactNode;
  /** 右上角计数，例如事实数、问题数 */
  count?: number;
  icon?: ReactNode;
}

/**
 * 标签页。
 * 默认 `unmountOnExit` 行为：未激活的页签不渲染，避免六个面板同时挂载
 * 各自的查询和虚拟列表。需要保留滚动位置的地方再单独处理。
 */
export function Tabs<T extends string>({
  value,
  onValueChange,
  items,
  children,
  className,
  size = 'md',
}: {
  value: T;
  onValueChange: (value: T) => void;
  items: TabItem<T>[];
  children: ReactNode;
  className?: string;
  size?: 'sm' | 'md';
}): ReactNode {
  return (
    <RadixTabs.Root
      value={value}
      onValueChange={(next) => onValueChange(next as T)}
      className={cn('flex min-h-0 flex-col', className)}
    >
      <RadixTabs.List className="flex shrink-0 items-center gap-0.5 border-b border-ink-800 px-1">
        {items.map((item) => (
          <RadixTabs.Trigger
            key={item.value}
            value={item.value}
            className={cn(
              'relative flex items-center gap-1.5 rounded-t px-2.5 font-medium transition-colors',
              size === 'sm' ? 'h-7 text-2xs' : 'h-8 text-xs',
              'text-ink-400 hover:text-ink-200',
              'data-[state=active]:text-celadon-300',
              // 下划线用伪元素，切换时不影响布局高度
              'after:absolute after:inset-x-1.5 after:-bottom-px after:h-px after:bg-transparent',
              'data-[state=active]:after:bg-celadon-500',
              '[&_svg]:size-3.5',
            )}
          >
            {item.icon}
            {item.label}
            {item.count !== undefined && item.count > 0 && (
              <span className="tnum rounded bg-ink-800 px-1 text-2xs text-ink-400">{item.count}</span>
            )}
          </RadixTabs.Trigger>
        ))}
      </RadixTabs.List>
      {children}
    </RadixTabs.Root>
  );
}

export function TabPanel({
  value,
  children,
  className,
}: {
  value: string;
  children: ReactNode;
  className?: string;
}): ReactNode {
  return (
    <RadixTabs.Content
      value={value}
      className={cn('min-h-0 flex-1 overflow-y-auto focus:outline-none', className)}
    >
      {children}
    </RadixTabs.Content>
  );
}
