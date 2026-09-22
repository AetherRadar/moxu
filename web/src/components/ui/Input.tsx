import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type TextareaHTMLAttributes,
} from 'react';
import { cn } from '@/lib/cn';

const fieldBase = cn(
  'w-full rounded-md border bg-ink-950/60 text-ink-100 placeholder:text-ink-600',
  'border-ink-700 transition-colors duration-150',
  'hover:border-ink-600 focus:border-celadon-600 focus:outline-none',
  'disabled:cursor-not-allowed disabled:opacity-50',
  'aria-invalid:border-cinnabar-500',
);

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  /** 行内前缀图标 */
  icon?: ReactNode;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, icon, ...rest },
  ref,
) {
  if (!icon) {
    return <input ref={ref} className={cn(fieldBase, 'h-8 px-2.5 text-xs', className)} {...rest} />;
  }
  return (
    <div className="relative">
      <span aria-hidden className="text-ink-500 pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2 [&_svg]:size-3.5">
        {icon}
      </span>
      <input ref={ref} className={cn(fieldBase, 'h-8 pr-2.5 pl-8 text-xs', className)} {...rest} />
    </div>
  );
});

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  /** 随内容长高，上限由 maxRows 控制。用于大纲、目标这类短文本。 */
  autoGrow?: boolean;
  maxRows?: number;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { className, autoGrow = false, maxRows = 14, rows = 3, onInput, ...rest },
  ref,
) {
  return (
    <textarea
      ref={ref}
      rows={rows}
      className={cn(
        fieldBase,
        'resize-y px-2.5 py-2 text-xs leading-relaxed',
        autoGrow && 'resize-none overflow-hidden',
        className,
      )}
      onInput={(event) => {
        if (autoGrow) {
          const el = event.currentTarget;
          // 先归零再读 scrollHeight，否则缩短内容时高度不会回落
          el.style.height = 'auto';
          const lineHeight = Number.parseFloat(getComputedStyle(el).lineHeight) || 18;
          el.style.height = `${Math.min(el.scrollHeight, lineHeight * maxRows)}px`;
        }
        onInput?.(event);
      }}
      {...rest}
    />
  );
});

export interface FieldProps {
  label: string;
  hint?: string | undefined;
  error?: string | undefined;
  /** 标签右侧的附加操作，例如"用模型生成" */
  action?: ReactNode;
  children: (props: { id: string; 'aria-invalid': boolean }) => ReactNode;
}

/** 表单项外壳：统一 label / 说明 / 错误的排版与 a11y 关联。 */
export function Field({ label, hint, error, action, children }: FieldProps): ReactNode {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-2">
        <label htmlFor={id} className="text-ink-300 text-2xs font-medium tracking-wide">
          {label}
        </label>
        {action}
      </div>
      {children({ id, 'aria-invalid': error !== undefined })}
      {error !== undefined ? (
        <p className="text-cinnabar-400 text-2xs">{error}</p>
      ) : hint !== undefined ? (
        <p className="text-ink-500 text-2xs leading-relaxed">{hint}</p>
      ) : null}
    </div>
  );
}
