/**
 * SSE 读取。
 *
 * 不用 EventSource：它无法取消而不触发自动重连，对"生成一次草稿"这种一次性流是错的行为；
 * 也拿不到非 2xx 的响应体。这里用 fetch + ReadableStream 手工解帧。
 *
 * 关键性能点：逐 token 调用 setState 会让 React 在长章节上每秒重渲染上百次。
 * 这里把到达的片段按动画帧合批，一帧最多回调一次，渲染次数降到 60/s 以内且文本不丢。
 */

import { ApiError } from './http';

export interface SseFrame {
  /** SSE `event:` 字段，Spring 的 Flux<String> 默认不带，为空串 */
  event: string;
  /** 多个 data: 行按换行拼接后的内容 */
  data: string;
}

export interface TextStreamOptions {
  /** 已含 /api 前缀的完整路径 */
  url: string;
  signal?: AbortSignal | undefined;
  /** 每帧至多调用一次，text 是本帧内累积的新片段 */
  onChunk: (text: string) => void;
  /** 收到非纯文本帧时回调（例如后端未来加 event: 类型） */
  onFrame?: ((frame: SseFrame) => void) | undefined;
}

/** SSE 用于表示"流正常结束"的约定标记。 */
const DONE_MARKER = '[DONE]';

/**
 * 按动画帧合批的文本累积器。
 * 页面不可见时 rAF 不触发，退化为定时器，避免后台标签页丢流。
 */
function createFrameBatcher(flush: (text: string) => void): {
  push: (text: string) => void;
  finish: () => void;
} {
  let pending = '';
  let handle = 0;
  let mode: 'raf' | 'timer' = 'raf';

  const emit = (): void => {
    handle = 0;
    if (!pending) return;
    const text = pending;
    pending = '';
    flush(text);
  };

  const schedule = (): void => {
    if (handle !== 0) return;
    if (document.hidden) {
      mode = 'timer';
      handle = window.setTimeout(emit, 120);
    } else {
      mode = 'raf';
      handle = requestAnimationFrame(emit);
    }
  };

  return {
    push(text: string): void {
      if (!text) return;
      pending += text;
      schedule();
    },
    finish(): void {
      if (handle !== 0) {
        if (mode === 'raf') cancelAnimationFrame(handle);
        else clearTimeout(handle);
        handle = 0;
      }
      emit();
    },
  };
}

/** 解析单个 SSE 事件块（已按 \n\n 切分）。 */
function parseFrame(block: string): SseFrame | null {
  let event = '';
  const dataLines: string[] = [];

  for (const rawLine of block.split('\n')) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
    if (!line || line.startsWith(':')) continue; // 空行与注释/心跳
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    // 规范规定 ": " 后的单个空格要去掉，其余空格保留
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'data') dataLines.push(value);
    else if (field === 'event') event = value;
  }

  if (dataLines.length === 0 && !event) return null;
  return { event, data: dataLines.join('\n') };
}

/**
 * 打开一条文本流，promise 在流结束时 resolve。
 * 调用方 abort 时静默 resolve（用户切章属正常操作，不该当错误上报）。
 */
export async function openTextStream(options: TextStreamOptions): Promise<void> {
  const { url, signal, onChunk, onFrame } = options;

  const response = await fetch(url, {
    headers: { Accept: 'text/event-stream' },
    // 流式响应必须关掉缓存，某些代理会缓冲整条流
    cache: 'no-store',
    ...(signal ? { signal } : {}),
  }).catch((cause: unknown) => {
    if (signal?.aborted) return null;
    throw new ApiError('无法建立流式连接', 0, url, cause);
  });

  if (response === null) return; // 已取消
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new ApiError(text || `HTTP ${response.status}`, response.status, url, text);
  }
  if (!response.body) throw new ApiError('响应没有可读流', 0, url, null);

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const batcher = createFrameBatcher(onChunk);
  let buffer = '';

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // 事件之间以空行分隔；兼容 \r\n\r\n
      let boundary = findBoundary(buffer);
      while (boundary !== null) {
        const block = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary.length);

        const frame = parseFrame(block);
        if (frame) {
          if (frame.data === DONE_MARKER) {
            batcher.finish();
            return;
          }
          if (frame.event && onFrame) onFrame(frame);
          else batcher.push(frame.data);
        }
        boundary = findBoundary(buffer);
      }
    }

    // 流结束时把残留的最后一块也解出来（后端未必以空行收尾）
    const tail = parseFrame(buffer + decoder.decode());
    if (tail && tail.data !== DONE_MARKER) batcher.push(tail.data);
  } catch (cause) {
    if (signal?.aborted) return;
    throw cause instanceof ApiError ? cause : new ApiError('流式读取中断', 0, url, cause);
  } finally {
    batcher.finish();
    reader.cancel().catch(() => undefined);
  }
}

function findBoundary(buffer: string): { index: number; length: number } | null {
  const lf = buffer.indexOf('\n\n');
  const crlf = buffer.indexOf('\r\n\r\n');
  if (lf === -1 && crlf === -1) return null;
  if (crlf !== -1 && (lf === -1 || crlf < lf)) return { index: crlf, length: 4 };
  return { index: lf, length: 2 };
}
