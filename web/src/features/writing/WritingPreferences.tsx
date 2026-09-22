import type { ReactNode } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { RotateCcw, Type, X } from 'lucide-react';
import { Button, IconButton } from '@/components/ui/Button';
import { useWritingPreferences } from '@/stores/writing-preferences';

export function WritingPreferences(): ReactNode {
  const appearance = useWritingPreferences((state) => state.appearance);
  const update = useWritingPreferences((state) => state.update);
  const reset = useWritingPreferences((state) => state.reset);

  return (
    <Popover.Root>
      <Popover.Trigger asChild>
        <Button tone="ghost" size="sm" icon={<Type />}>
          排版
        </Button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          aria-label="正文排版"
          sideOffset={10}
          collisionPadding={16}
          align="end"
          className="z-40 w-72 rounded-xl border border-ink-700 bg-ink-900 p-5 text-ink-100 shadow-float"
        >
          <div className="mb-5 flex items-center justify-between">
            <h2 className="font-serif text-lg">舒服地写，慢慢地读</h2>
            <Popover.Close asChild>
              <IconButton label="关闭排版设置">
                <X />
              </IconButton>
            </Popover.Close>
          </div>
          <div className="grid gap-5">
            <fieldset>
              <legend className="mb-2 text-xs text-ink-400">正文字体</legend>
              <div className="grid grid-cols-2 gap-2">
                {(['serif', 'sans'] as const).map((font) => (
                  <Button
                    key={font}
                    tone={appearance.font === font ? 'default' : 'outline'}
                    aria-pressed={appearance.font === font}
                    onClick={() => update({ font })}
                    className={font === 'serif' ? 'font-serif' : 'font-sans'}
                  >
                    {font === 'serif' ? '宋体 · 书卷' : '黑体 · 清晰'}
                  </Button>
                ))}
              </div>
            </fieldset>
            <label className="grid gap-3 text-xs text-ink-400">
              <span className="flex justify-between">
                字号 <span className="tnum">{appearance.fontSize} px</span>
              </span>
              <input
                aria-label="正文字号"
                type="range"
                min="14"
                max="26"
                step="1"
                value={appearance.fontSize}
                onChange={(event) => update({ fontSize: Number(event.target.value) })}
                className="w-full accent-celadon-500"
              />
            </label>
            <label className="grid gap-3 text-xs text-ink-400">
              <span className="flex justify-between">
                行距 <span className="tnum">{appearance.lineHeight.toFixed(1)} 倍</span>
              </span>
              <input
                aria-label="正文行距"
                type="range"
                min="1.6"
                max="2.6"
                step="0.1"
                value={appearance.lineHeight}
                onChange={(event) => update({ lineHeight: Number(event.target.value) })}
                className="w-full accent-celadon-500"
              />
            </label>
            <fieldset>
              <legend className="mb-2 text-xs text-ink-400">纸面宽度</legend>
              <div className="grid grid-cols-3 gap-2">
                {(['compact', 'comfortable', 'wide'] as const).map((width, index) => (
                  <Button
                    key={width}
                    size="sm"
                    tone={appearance.width === width ? 'default' : 'outline'}
                    aria-pressed={appearance.width === width}
                    onClick={() => update({ width })}
                  >
                    {['窄幅', '适中', '宽幅'][index]}
                  </Button>
                ))}
              </div>
            </fieldset>
          </div>
          <div className="mt-5 flex items-center justify-between border-t border-ink-800 pt-3">
            <span className="text-2xs text-ink-500">自动记住你的偏好</span>
            <Button size="xs" tone="ghost" icon={<RotateCcw />} onClick={reset}>
              恢复默认
            </Button>
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
