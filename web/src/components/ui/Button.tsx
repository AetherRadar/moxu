import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/cn';
import { variants } from './variants';

const buttonClass = variants({
  base: cn(
    'relative inline-flex shrink-0 items-center justify-center gap-1.5 rounded-md font-medium [&_svg]:size-4 [&_svg]:shrink-0',
    'whitespace-nowrap transition-[background-color,border-color,color,opacity] duration-150',
    'disabled:pointer-events-none disabled:opacity-45',
    // 按下时轻微下沉，给一点物理反馈
    'active:translate-y-px',
  ),
  variants: {
    tone: {
      /** 主操作：生成、保存、确认 */
      primary: 'bg-action text-action-foreground hover:bg-action-hover shadow-sm',
      /** 次要操作，最常用 */
      default: 'bg-ink-800 text-ink-100 hover:bg-ink-700 border border-ink-700',
      /** 弱化：工具栏、面板内联操作 */
      ghost: 'text-ink-300 hover:bg-ink-800 hover:text-ink-100',
      /** 只有边框，用于对话框取消 */
      outline: 'border border-ink-700 text-ink-200 hover:border-ink-600 hover:bg-ink-850',
      /** 破坏性操作 */
      danger: 'bg-cinnabar-600 text-white hover:bg-cinnabar-500',
      dangerGhost: 'text-cinnabar-400 hover:bg-cinnabar-900/40 hover:text-cinnabar-300',
      /** 安全确认型操作（采用/完成） */
      success: 'bg-malachite-500 text-white hover:bg-malachite-400',
    },
    size: {
      xs: 'h-6 px-2 text-2xs',
      sm: 'h-7 px-2.5 text-xs',
      md: 'h-8 px-3 text-xs',
      lg: 'h-10 px-4 text-sm',
    },
  },
  defaults: { tone: 'default', size: 'md' },
});

type Tone = 'primary' | 'default' | 'ghost' | 'outline' | 'danger' | 'dangerGhost' | 'success';
type Size = 'xs' | 'sm' | 'md' | 'lg';

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  tone?: Tone;
  size?: Size;
  /** 显示转圈并禁用点击，但保留按钮宽度避免布局跳动 */
  loading?: boolean;
  icon?: ReactNode;
  children?: ReactNode;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { tone, size, loading = false, icon, className, children, disabled, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled === true || loading}
      aria-busy={loading || undefined}
      className={buttonClass({ tone, size }, className)}
      {...rest}
    >
      {/* 保留文字尺寸与无障碍名称，加载时只隐藏视觉内容。 */}
      <span className={cn('inline-flex items-center gap-1.5', loading && 'opacity-0')}>
        {icon}
        {children}
      </span>
      {loading && <Loader2 aria-hidden className="absolute size-3.5 animate-spin" />}
    </button>
  );
});

const iconButtonClass = variants({
  base: cn(
    'inline-flex shrink-0 items-center justify-center rounded-md transition-colors duration-150',
    'disabled:pointer-events-none disabled:opacity-40',
  ),
  variants: {
    tone: {
      ghost: 'text-ink-400 hover:bg-ink-800 hover:text-ink-100',
      subtle: 'text-ink-500 hover:text-ink-200',
      danger: 'text-ink-400 hover:bg-cinnabar-900/40 hover:text-cinnabar-300',
      active: 'bg-ink-800 text-celadon-300',
    },
    size: {
      xs: 'size-5 [&_svg]:size-3',
      sm: 'size-6 [&_svg]:size-3.5',
      md: 'size-7 [&_svg]:size-4',
      lg: 'size-9 [&_svg]:size-[18px]',
    },
  },
  defaults: { tone: 'ghost', size: 'sm' },
});

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  tone?: 'ghost' | 'subtle' | 'danger' | 'active';
  size?: Size;
  /** 图标按钮没有可见文字，必须给无障碍名称 */
  label: string;
}

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { tone, size, label, className, type = 'button', ...rest },
  ref,
) {
  return (
    // 不写 title：交给外层 Tooltip，否则原生气泡会和 Radix 的叠成双份
    <button
      ref={ref}
      type={type}
      aria-label={label}
      className={iconButtonClass({ tone, size }, className)}
      {...rest}
    />
  );
});
