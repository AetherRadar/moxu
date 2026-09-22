import { fileURLToPath } from 'node:url';
import { loadEnv } from 'vite';
// 从 vitest/config 取 defineConfig，这样 test 段有类型
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

/**
 * 手工分块。
 * 编辑器只在写作页下载。Radix 交给自动分块，避免全局 Tooltip
 * 把 Dialog / Select / Menu 等未打开的控件一起带入首屏。
 * 用函数形式而非对象形式：Vite 8 的打包器只接受函数签名。
 */
const VENDOR_CHUNKS: { name: string; test: RegExp }[] = [
  { name: 'editor', test: /node_modules[\\/]@?codemirror|node_modules[\\/]@lezer/ },
  { name: 'query', test: /node_modules[\\/]@tanstack[\\/]/ },
  { name: 'react', test: /node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/ },
];

function manualChunks(moduleId: string): string | undefined {
  for (const chunk of VENDOR_CHUNKS) {
    if (chunk.test.test(moduleId)) return chunk.name;
  }
  return undefined;
}

/** 读取第一个非空环境变量，按顺序回退。 */
function pick(env: Record<string, string>, ...names: string[]): string {
  for (const name of names) {
    const value = env[name]?.trim();
    if (value) return value;
  }
  return '';
}

function toPort(raw: string, fallback: number): number {
  const port = Number.parseInt(raw, 10);
  return Number.isInteger(port) && port > 0 && port < 65536 ? port : fallback;
}

function stripTrailingSlash(raw: string): string {
  return raw.replace(/\/+$/, '');
}

function toWebSocketOrigin(httpOrigin: string): string {
  return httpOrigin.replace(/^http(s?):\/\//, (_match, secure: string) => (secure ? 'wss://' : 'ws://'));
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');

  const devPort = toPort(pick(env, 'VITE_DEV_PORT', 'MOXU_FRONTEND_PORT'), 3000);
  const backendPort = toPort(pick(env, 'VITE_BACKEND_PORT', 'MOXU_BACKEND_PORT'), 8080);
  const httpOrigin = stripTrailingSlash(pick(env, 'VITE_BACKEND_URL')) || `http://127.0.0.1:${backendPort}`;
  const wsOrigin = stripTrailingSlash(pick(env, 'VITE_BACKEND_WS_URL')) || toWebSocketOrigin(httpOrigin);

  // 逗号分隔；留空则只允许 localhost。用于内网域名或隧道访问。
  const allowedHosts = pick(env, 'VITE_ALLOWED_HOSTS')
    .split(',')
    .map((host) => host.trim())
    .filter(Boolean);

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: devPort,
      strictPort: false,
      ...(allowedHosts.length > 0 ? { allowedHosts } : {}),
      proxy: {
        '/api': {
          target: httpOrigin,
          changeOrigin: true,
          rewrite: (path) => path.slice('/api'.length),
        },
        '/ws': {
          target: wsOrigin,
          ws: true,
        },
      },
    },
    build: {
      target: 'es2023',
      sourcemap: mode !== 'production',
      rollupOptions: {
        output: { manualChunks },
      },
    },
    test: {
      // 默认 node：纯逻辑测试不需要 jsdom，起 jsdom 要多花二十多秒。
      // 组件测试在文件头写 `// @vitest-environment jsdom` 单独切换。
      environment: 'node',
      globals: true,
      include: ['src/**/*.test.{ts,tsx}'],
      css: false,
    },
  };
});
