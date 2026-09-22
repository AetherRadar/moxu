import { type ReactNode } from 'react';
import * as RadixSwitch from '@radix-ui/react-switch';
import * as RadixSlider from '@radix-ui/react-slider';
import { cn } from '@/lib/cn';

/** 开关。配置面板里有十几个布尔项，统一到这里。 */
export function Switch({
  checked,
  onCheckedChange,
  label,
  hint,
  disabled,
}: {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  label: ReactNode;
  hint?: ReactNode;
  disabled?: boolean;
}): ReactNode {
  return (
    <label
      className={cn(
        'flex items-start justify-between gap-3 py-1.5',
        disabled === true ? 'opacity-50' : 'cursor-pointer',
      )}
    >
      <span className="min-w-0">
        <span className="block text-xs text-ink-200">{label}</span>
        {hint !== undefined && <span className="mt-0.5 block text-2xs leading-relaxed text-ink-500">{hint}</span>}
      </span>
      <RadixSwitch.Root
        checked={checked}
        onCheckedChange={onCheckedChange}
        disabled={disabled}
        className={cn(
          'relative mt-px h-4 w-7 shrink-0 rounded-full transition-colors duration-150',
          'bg-ink-700 data-[state=checked]:bg-celadon-600',
        )}
      >
        <RadixSwitch.Thumb className="block size-3 translate-x-0.5 rounded-full bg-ink-100 transition-transform duration-150 will-change-transform data-[state=checked]:translate-x-3.5 data-[state=checked]:bg-ink-950" />
      </RadixSwitch.Root>
    </label>
  );
}

/** 数值滑块，右侧固定宽度显示当前值（等宽数字，拖动时不抖）。 */
export function Slider({
  value,
  onValueChange,
  onValueCommit,
  min = 0,
  max = 100,
  step = 1,
  label,
  hint,
  format,
  disabled,
}: {
  value: number;
  onValueChange: (value: number) => void;
  /** 松手才落库，拖动过程只更新本地状态 */
  onValueCommit?: (value: number) => void;
  min?: number;
  max?: number;
  step?: number;
  label: ReactNode;
  hint?: ReactNode;
  format?: (value: number) => string;
  disabled?: boolean;
}): ReactNode {
  return (
    <div className={cn('py-1.5', disabled === true && 'opacity-50')}>
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-xs text-ink-200">{label}</span>
        <span className="tnum text-2xs text-ink-400">{format ? format(value) : value}</span>
      </div>
      <RadixSlider.Root
        value={[value]}
        min={min}
        max={max}
        step={step}
        disabled={disabled}
        onValueChange={([next]) => {
          if (next !== undefined) onValueChange(next);
        }}
        onValueCommit={([next]) => {
          if (next !== undefined) onValueCommit?.(next);
        }}
        className="relative mt-2 flex h-3 w-full touch-none items-center select-none"
      >
        <RadixSlider.Track className="relative h-0.5 w-full grow rounded-full bg-ink-700">
          <RadixSlider.Range className="absolute h-full rounded-full bg-celadon-600" />
        </RadixSlider.Track>
        <RadixSlider.Thumb
          aria-label={typeof label === 'string' ? label : undefined}
          className="block size-3 rounded-full border-2 border-celadon-500 bg-ink-950 transition-transform hover:scale-110"
        />
      </RadixSlider.Root>
      {hint !== undefined && <p className="mt-1.5 text-2xs leading-relaxed text-ink-500">{hint}</p>}
    </div>
  );
}
