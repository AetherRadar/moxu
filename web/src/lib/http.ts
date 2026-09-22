/**
 * 后端 HTTP 访问层。
 *
 * 直接用 fetch，不引 axios：少约 12KB gzip，原生支持 AbortSignal 与流式响应体
 * （流式草稿和长文本改写需要）。
 *
 * 线上传输一律 snake_case（Java 侧 @JsonProperty 已固定），前端类型直接镜像线格式，
 * 不做 camelCase 转换——省掉每个响应体一次深拷贝，章节列表上千条时差别明显。
 */

/** 开发期由 Vite 代理到后端；打包后同源部署。 */
const BASE_URL = '/api';

/** 超时档位。LLM 与批处理接口远慢于普通 CRUD，统一超时会误杀。 */
export const Timeout = {
  /** 普通 CRUD */
  quick: 30_000,
  /** 单次 LLM 调用 */
  llm: 300_000,
  /** 局部改写候选 */
  rewrite: 180_000,
  /** 保存草稿：后端会连带重建绑定 / review / memory pack */
  draftSave: 300_000,
  /** 全书批处理 */
  batch: 1_800_000,
  /** 不设超时，由调用方的 signal 控制 */
  none: 0,
} as const;

export type TimeoutValue = (typeof Timeout)[keyof typeof Timeout] | number;

export type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  /** 查询参数，null/undefined 自动省略 */
  query?: Record<string, QueryValue> | undefined;
  /** JSON 请求体 */
  body?: unknown;
  /** multipart 请求体，与 body 互斥 */
  form?: FormData | undefined;
  timeout?: TimeoutValue;
  signal?: AbortSignal | undefined;
  headers?: Record<string, string> | undefined;
}

/** 后端错误的统一表示。所有失败路径都抛这个，调用方只需 catch 一种。 */
export class ApiError extends Error {
  readonly status: number;
  readonly url: string;
  readonly payload: unknown;

  constructor(message: string, status: number, url: string, payload: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.url = url;
    this.payload = payload;
  }

  /** 资源不存在——很多地方需要静默降级而不是弹错误。 */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** 网络中断 / 超时 / 后端未启动，status 为 0。 */
  get isOffline(): boolean {
    return this.status === 0;
  }
}

/** 用户主动取消（切章、关弹窗）时抛出，调用方通常直接忽略。 */
export class AbortedError extends Error {
  constructor() {
    super('aborted');
    this.name = 'AbortedError';
  }
}

function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const url = `${BASE_URL}${path}`;
  if (!query) return url;
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue;
    search.append(key, String(value));
  }
  const qs = search.toString();
  return qs ? `${url}?${qs}` : url;
}

/** 组合调用方 signal 与超时 signal；两者任一触发即中断。 */
function resolveSignal(timeout: TimeoutValue, external?: AbortSignal): AbortSignal | undefined {
  const timeoutSignal = timeout > 0 ? AbortSignal.timeout(timeout) : undefined;
  if (timeoutSignal && external) return AbortSignal.any([external, timeoutSignal]);
  return timeoutSignal ?? external;
}

/**
 * 从响应体里挖出人类可读的错误信息。
 * Spring 的 ResponseStatusException 走 `message`，业务层自定义的走 `detail` / `error`。
 */
function extractMessage(payload: unknown, fallback: string): string {
  if (typeof payload === 'string' && payload.trim()) return payload.trim();
  if (payload && typeof payload === 'object') {
    const record = payload as Record<string, unknown>;
    for (const key of ['message', 'detail', 'error', 'reason']) {
      const value = record[key];
      if (typeof value === 'string' && value.trim()) return value.trim();
    }
  }
  return fallback;
}

async function readPayload(response: Response): Promise<unknown> {
  const contentType = response.headers.get('content-type') ?? '';
  if (response.status === 204 || response.headers.get('content-length') === '0') return null;
  if (contentType.includes('application/json')) {
    return response.json().catch(() => null);
  }
  const text = await response.text().catch(() => '');
  return text || null;
}

async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const { query, body, form, timeout = Timeout.quick, signal: external, headers } = options;
  const url = buildUrl(path, query);

  const init: RequestInit = {
    method,
    headers: { Accept: 'application/json', ...headers },
  };

  const signal = resolveSignal(timeout, external);
  if (signal) init.signal = signal;

  if (form) {
    // 不要手写 Content-Type，交给浏览器生成 multipart boundary。
    init.body = form;
  } else if (body !== undefined) {
    init.headers = { ...init.headers, 'Content-Type': 'application/json' };
    init.body = JSON.stringify(body);
  }

  let response: Response;
  try {
    response = await fetch(url, init);
  } catch (cause) {
    if (external?.aborted) throw new AbortedError();
    const reason = cause instanceof Error && cause.name === 'TimeoutError' ? '请求超时' : '无法连接后端';
    throw new ApiError(reason, 0, url, cause);
  }

  const payload = await readPayload(response);
  if (!response.ok) {
    throw new ApiError(extractMessage(payload, `HTTP ${response.status}`), response.status, url, payload);
  }
  return payload as T;
}

export const http = {
  get: <T>(path: string, options?: Omit<RequestOptions, 'body' | 'form'>) =>
    request<T>('GET', path, options),
  post: <T>(path: string, options?: RequestOptions) => request<T>('POST', path, options),
  put: <T>(path: string, options?: RequestOptions) => request<T>('PUT', path, options),
  patch: <T>(path: string, options?: RequestOptions) => request<T>('PATCH', path, options),
  delete: <T>(path: string, options?: RequestOptions) => request<T>('DELETE', path, options),
};

/** 把路径片段安全地塞进 URL（章节号形如 V1C2，人物名可能含空格或斜杠）。 */
export function seg(value: string): string {
  return encodeURIComponent(value);
}
