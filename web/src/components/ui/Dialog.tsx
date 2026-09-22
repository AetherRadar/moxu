import { type ReactNode } from 'react';
import * as RadixDialog from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { IconButton } from './Button';

/**
 * 对话框。用 Radix 而不是手写：焦点捕获、Esc 关闭、滚动锁定、
 * aria-modal 和返回焦点这几件事手写很难做对，本项目有十几个弹窗。
 */

export type DialogSize = 'sm' | 'md' | 'lg' | 'xl' | 'full';

const sizeClass: Record<DialogSize, string> = {
  sm: 'max-w-sm',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
  xl: 'max-w-4xl',
  // 导入预览、差异对比这类需要尽量铺满
  full: 'max-w-[min(1200px,calc(100vw-4rem))] h-[calc(100vh-5rem)]',
};

export interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  size?: DialogSize;
  /** 底部操作区 */
  footer?: ReactNode;
  /** 标题栏右侧（关闭按钮左边）的附加内容 */
  toolbar?: ReactNode;
  /** 生成中等场景下禁止点遮罩或 Esc 关闭 */
  dismissible?: boolean;
  children: ReactNode;
  contentClassName?: string;
}

export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  size = 'md',
  footer,
  toolbar,
  dismissible = true,
  children,
  contentClassName,
}: DialogProps): ReactNode {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="animate-fade-in fixed inset-0 z-50 bg-ink-975/70 backdrop-blur-[2px]" />
        <RadixDialog.Content
          onPointerDownOutside={dismissible ? undefined : (event) => event.preventDefault()}
          onEscapeKeyDown={dismissible ? undefined : (event) => event.preventDefault()}
          className={cn(
            'animate-rise fixed top-1/2 left-1/2 z-50 flex w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2',
            'flex-col overflow-hidden rounded-panel border border-ink-700 bg-ink-900 shadow-float',
            'max-h-[calc(100vh-4rem)]',
            sizeClass[size],
            contentClassName,
          )}
        >
          <header className="flex items-start gap-3 border-b border-ink-800 px-4 py-3">
            <div className="min-w-0 flex-1">
              <RadixDialog.Title className="truncate text-sm font-medium text-ink-100">
                {title}
              </RadixDialog.Title>
              {description !== undefined && (
                <RadixDialog.Description className="mt-0.5 text-2xs leading-relaxed text-ink-400">
                  {description}
                </RadixDialog.Description>
              )}
            </div>
            {toolbar}
            {dismissible && (
              <RadixDialog.Close asChild>
                <IconButton label="关闭" size="sm">
                  <X />
                </IconButton>
              </RadixDialog.Close>
            )}
          </header>

          {/* 只有正文滚动，头尾固定 */}
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3.5">{children}</div>

          {footer !== undefined && (
            <footer className="flex items-center justify-end gap-2 border-t border-ink-800 bg-ink-850/60 px-4 py-3">
              {footer}
            </footer>
          )}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}

export const DialogClose = RadixDialog.Close;
