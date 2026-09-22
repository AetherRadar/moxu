import { useEffect, useState, type ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import * as Tooltip from '@radix-ui/react-tooltip';
import { Toaster } from 'sonner';
import { createQueryClient } from '@/lib/query-client';

/** 应用固定深色：配色 token 只有一套暗色阶，不让系统偏好突然反色。 */
function useDarkTheme(): void {
  useEffect(() => {
    const root = document.documentElement;
    root.classList.add('dark');
    root.style.colorScheme = 'dark';
  }, []);
}

export function AppProviders({ children }: { children: ReactNode }): ReactNode {
  // QueryClient 必须跨渲染稳定，否则每次重渲染都会丢掉整个缓存。
  // useState 懒初始化：不像 useRef(init) 那样每次渲染都白造一个 client。
  const [client] = useState(() => createQueryClient());
  useDarkTheme();

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
