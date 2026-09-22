import { type ReactNode } from 'react';
import * as RadixSelect from '@radix-ui/react-select';
import { Check, ChevronDown } from 'lucide-react';
import { cn } from '@/lib/cn';

export interface SelectOption<T extends string> {
  value: T;
  label: ReactNode;
  hint?: string;
  disabled?: boolean;
}

/**
 * 下拉选择。
 * 不用原生 <select>：需要在选项里放副标题（模型名 + 供应商）和禁用态样式。
 */
export function Select<T extends string>({
  value,
  onValueChange,
  options,
  placeholder = '请选择',
  id,
  disabled,
  className,
}: {
  value: T | undefined;
  onValueChange: (value: T) => void;
  options: SelectOption<T>[];
  placeholder?: string;
  id?: string;
  disabled?: boolean;
  className?: string;
}): ReactNode {
  return (
    <RadixSelect.Root value={value} onValueChange={onValueChange} disabled={disabled}>
      <RadixSelect.Trigger
        id={id}
        className={cn(
          'flex h-8 w-full items-center justify-between gap-2 rounded-md border border-ink-700',
          'bg-ink-950/60 px-2.5 text-xs text-ink-100 transition-colors',
          'hover:border-ink-600 focus:border-celadon-600 focus:outline-none',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'data-[placeholder]:text-ink-600',
          className,
        )}
      >
        <RadixSelect.Value placeholder={placeholder} />
        <RadixSelect.Icon>
          <ChevronDown className="size-3.5 text-ink-500" />
        </RadixSelect.Icon>
      </RadixSelect.Trigger>

      <RadixSelect.Portal>
        <RadixSelect.Content
          position="popper"
          sideOffset={4}
          className="animate-rise z-50 max-h-72 min-w-[var(--radix-select-trigger-width)] overflow-hidden rounded-md border border-ink-700 bg-ink-850 shadow-float"
        >
          <RadixSelect.Viewport className="p-1">
            {options.map((option) => (
              <RadixSelect.Item
                key={option.value}
                value={option.value}
                disabled={option.disabled}
                className={cn(
                  'relative flex cursor-default flex-col rounded py-1.5 pr-2 pl-7 text-xs text-ink-200',
                  'outline-none select-none',
                  'data-[highlighted]:bg-ink-700 data-[highlighted]:text-ink-50',
                  'data-[disabled]:pointer-events-none data-[disabled]:opacity-40',
                )}
              >
                <RadixSelect.ItemIndicator className="absolute top-2 left-2">
                  <Check className="size-3 text-celadon-400" />
                </RadixSelect.ItemIndicator>
                <RadixSelect.ItemText>{option.label}</RadixSelect.ItemText>
                {option.hint !== undefined && (
                  <span className="mt-0.5 truncate text-2xs text-ink-500">{option.hint}</span>
                )}
              </RadixSelect.Item>
            ))}
          </RadixSelect.Viewport>
        </RadixSelect.Content>
      </RadixSelect.Portal>
    </RadixSelect.Root>
  );
}
