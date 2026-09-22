import { type ReactNode } from 'react';
import * as RadixMenu from '@radix-ui/react-dropdown-menu';
import { Check, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/cn';

/** 下拉菜单。样式集中在这里，各处只写内容，避免每个菜单各调一遍间距。 */

const contentClass = cn(
  'z-50 min-w-44 overflow-hidden rounded-md border border-ink-700 bg-ink-850 p-1 shadow-float',
  'data-[state=open]:animate-rise',
);

const itemClass = cn(
  'relative flex cursor-default items-center gap-2 rounded px-2 py-1.5 text-xs text-ink-200',
  'outline-none select-none',
  'data-[highlighted]:bg-ink-700 data-[highlighted]:text-ink-50',
  'data-[disabled]:pointer-events-none data-[disabled]:opacity-40',
  '[&_svg]:size-3.5 [&_svg]:shrink-0 [&_svg]:text-ink-400 data-[highlighted]:[&_svg]:text-ink-200',
);

export const Menu = RadixMenu.Root;
export const MenuTrigger = RadixMenu.Trigger;

export function MenuContent({
  children,
  align = 'start',
  sideOffset = 6,
  className,
}: {
  children: ReactNode;
  align?: 'start' | 'center' | 'end';
  sideOffset?: number;
  className?: string;
}): ReactNode {
  return (
    <RadixMenu.Portal>
      <RadixMenu.Content align={align} sideOffset={sideOffset} className={cn(contentClass, className)}>
        {children}
      </RadixMenu.Content>
    </RadixMenu.Portal>
  );
}

export function MenuItem({
  children,
  onSelect,
  disabled,
  danger = false,
  shortcut,
  icon,
}: {
  children: ReactNode;
  onSelect?: () => void;
  disabled?: boolean;
  danger?: boolean;
  shortcut?: string;
  icon?: ReactNode;
}): ReactNode {
  return (
    <RadixMenu.Item
      disabled={disabled}
      onSelect={onSelect}
      className={cn(
        itemClass,
        danger && 'text-cinnabar-400 data-[highlighted]:bg-cinnabar-900/50 data-[highlighted]:text-cinnabar-200',
      )}
    >
      {icon}
      <span className="flex-1 truncate">{children}</span>
      {shortcut !== undefined && <kbd className="text-ink-500 font-mono text-2xs">{shortcut}</kbd>}
    </RadixMenu.Item>
  );
}

export function MenuCheckItem({
  children,
  checked,
  onCheckedChange,
}: {
  children: ReactNode;
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
}): ReactNode {
  return (
    <RadixMenu.CheckboxItem
      checked={checked}
      onCheckedChange={onCheckedChange}
      // 阻止勾选后菜单关闭，方便连续切换多个开关
      onSelect={(event) => event.preventDefault()}
      className={cn(itemClass, 'pl-7')}
    >
      <RadixMenu.ItemIndicator className="absolute left-2">
        <Check />
      </RadixMenu.ItemIndicator>
      <span className="flex-1 truncate">{children}</span>
    </RadixMenu.CheckboxItem>
  );
}

export function MenuSub({ label, children }: { label: ReactNode; children: ReactNode }): ReactNode {
  return (
    <RadixMenu.Sub>
      <RadixMenu.SubTrigger className={itemClass}>
        <span className="flex-1 truncate">{label}</span>
        <ChevronRight />
      </RadixMenu.SubTrigger>
      <RadixMenu.Portal>
        <RadixMenu.SubContent sideOffset={4} className={contentClass}>
          {children}
        </RadixMenu.SubContent>
      </RadixMenu.Portal>
    </RadixMenu.Sub>
  );
}

export function MenuLabel({ children }: { children: ReactNode }): ReactNode {
  return (
    <RadixMenu.Label className="text-ink-500 px-2 pt-1.5 pb-1 text-2xs font-medium tracking-wide">
      {children}
    </RadixMenu.Label>
  );
}

export function MenuSeparator(): ReactNode {
  return <RadixMenu.Separator className="bg-ink-700 my-1 h-px" />;
}
