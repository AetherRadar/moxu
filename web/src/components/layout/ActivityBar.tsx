import { type ReactNode } from 'react';
import {
  Bot,
  BookOpen,
  FolderTree,
  Landmark,
  Search,
  Settings,
  SunMoon,
  Users,
} from 'lucide-react';
import { cn } from '@/lib/cn';
import { Tooltip } from '@/components/ui';
import { useLayoutStore, type SideView } from '@/stores/layout';

interface Entry {
  view: SideView;
  icon: ReactNode;
  label: string;
  shortcut: string;
}

const ENTRIES: Entry[] = [
  { view: 'explorer', icon: <FolderTree />, label: '章节', shortcut: 'Ctrl+1' },
  { view: 'outline', icon: <BookOpen />, label: '大纲', shortcut: 'Ctrl+2' },
  { view: 'cards', icon: <Users />, label: '设定', shortcut: 'Ctrl+3' },
  { view: 'facts', icon: <Landmark />, label: '正典', shortcut: 'Ctrl+4' },
  { view: 'search', icon: <Search />, label: '检索', shortcut: 'Ctrl+5' },
  { view: 'agents', icon: <Bot />, label: '智能体', shortcut: 'Ctrl+6' },
];

/**
 * 最左侧图标栏。
 * 订阅时只取 sideView / sideVisible 两个字段，切章或流式生成都不会让它重渲染。
 */
export function ActivityBar({ onOpenSettings }: { onOpenSettings: () => void }): ReactNode {
  const sideView = useLayoutStore((state) => state.sideView);
  const sideVisible = useLayoutStore((state) => state.sideVisible);
  const activateSideView = useLayoutStore((state) => state.activateSideView);
  const toggleTheme = useLayoutStore((state) => state.toggleTheme);

  return (
    <nav
      aria-label="主导航"
      className="flex w-11 shrink-0 flex-col items-center border-r border-ink-800 bg-ink-975 py-1.5"
    >
      {ENTRIES.map((entry) => {
        const active = sideVisible && sideView === entry.view;
        return (
          <Tooltip key={entry.view} content={entry.label} side="right" shortcut={entry.shortcut}>
            <button
              type="button"
              aria-label={entry.label}
              aria-pressed={active}
              onClick={() => activateSideView(entry.view)}
              className={cn(
                'relative flex size-9 items-center justify-center transition-colors duration-150',
                '[&_svg]:size-[18px]',
                active ? 'text-celadon-300' : 'text-ink-500 hover:text-ink-200',
                // 激活指示条放在左边缘，与面板同侧
                'before:absolute before:inset-y-1.5 before:left-0 before:w-0.5 before:rounded-r',
                active ? 'before:bg-celadon-500' : 'before:bg-transparent',
              )}
            >
              {entry.icon}
            </button>
          </Tooltip>
        );
      })}

      <div className="flex-1" />

      <Tooltip content="切换深浅色" side="right">
        <button
          type="button"
          aria-label="切换深浅色"
          onClick={toggleTheme}
          className="flex size-9 items-center justify-center text-ink-500 transition-colors hover:text-ink-200 [&_svg]:size-[18px]"
        >
          <SunMoon />
        </button>
      </Tooltip>
      <Tooltip content="设置" side="right" shortcut="Ctrl+,">
        <button
          type="button"
          aria-label="设置"
          onClick={onOpenSettings}
          className="flex size-9 items-center justify-center text-ink-500 transition-colors hover:text-ink-200 [&_svg]:size-[18px]"
        >
          <Settings />
        </button>
      </Tooltip>
    </nav>
  );
}

export const SIDE_VIEWS = ENTRIES.map((entry) => entry.view);
