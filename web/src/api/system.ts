import { http } from '@/lib/http';

export const systemApi = {
  /** 启动画面用它判断后端是否就绪，超时短一点以便快速重试。 */
  health: () => http.get<Record<string, string>>('/health', { timeout: 5_000 }),
};
