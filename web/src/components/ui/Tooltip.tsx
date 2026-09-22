import { type ReactElement, type ReactNode } from 'react';
import * as RadixTooltip from '@radix-ui/react-tooltip';

/** 提示气泡。Provider 在 AppProviders 里统一配置延迟。 */
export function Tooltip({
  content,
  children,
  side = 'bottom',
  shortcut,
}: {
  content: ReactNode;
  children: ReactElement;
  side?: 'top' | 'right' | 'bottom' | 'left';
  shortcut?: string;
}): ReactNode {
  if (content === null || content === undefined || content === '') return children;
  return (
    <RadixTooltip.Root>
      <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content
          side={side}
          sideOffset={6}
          collisionPadding={8}
          className="animate-fade-in z-50 flex items-center gap-2 rounded border border-ink-700 bg-ink-850 px-2 py-1 text-2xs text-ink-200 shadow-float"
        >
          {content}
          {shortcut !== undefined && <kbd className="text-ink-500 font-mono">{shortcut}</kbd>}
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  );
}
