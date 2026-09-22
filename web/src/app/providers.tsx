import { useEffect, useState, type ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import * as Tooltip from '@radix-ui/react-tooltip';
import { Toaster } from 'sonner';
import { createQueryClient } from '@/lib/query-client';
import { useLayoutStore } from '@/stores/layout';

/** 主题 class 落到 <html>：Tailwind 的 dark 变体和 CSS 变量翻转都依赖它。 */
function useThemeClass(): void {
  const theme = useLayoutStore((state) => state.theme);
  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('dark', theme === 'dark');
    root.classList.toggle('light', theme === 'light');
    root.style.colorScheme = theme;
  }, [theme]);
}

export function AppProviders({ children }: { children: ReactNode }): ReactNode {
  // QueryClient 必须跨渲染稳定，否则每次重渲染都会丢掉整个缓存。
  // useState 懒初始化：不像 useRef(init) 那样每次渲染都白造一个 client。
  const [client] = useState(() => createQueryClient());
  useThemeClass();

  return (
    <QueryClientProvider client={client}>
      {/* delayDuration 调大一点：写作时鼠标经过工具栏不该一路弹提示 */}
      <Tooltip.Provider delayDuration={450} skipDelayDuration={200}>
        {children}
        <Toaster
          position="bottom-right"
          closeButton
          toastOptions={{
            classNames: {
              toast:
                'bg-ink-850! border-ink-700! text-ink-100! shadow-float! rounded-panel! text-xs! font-sans!',
              description: 'text-ink-400!',
              actionButton: 'bg-celadon-600! text-ink-950!',
            },
          }}
        />
      </Tooltip.Provider>
    </QueryClientProvider>
  );
}
