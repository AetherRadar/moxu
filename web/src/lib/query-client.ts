import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './http';

/**
 * 这是一个本地单用户应用：数据只会被自己的操作改变，没有其他客户端在并发写。
 * 所以默认关掉焦点重取、把 staleTime 拉长，靠 mutation 后精确失效来保证新鲜度。
 * 上游那套"每次进面板都重新拉一遍"的做法在有几千章的项目上会反复拉大列表。
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 10 * 60_000,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
        retry: (failureCount, error) => {
          // 4xx 是确定性失败，重试只会拖慢报错
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
          return failureCount < 2;
        },
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
      },
      mutations: {
        retry: false,
      },
    },
  });
}
