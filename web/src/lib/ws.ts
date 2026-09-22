/**
 * WebSocket 连接池。
 *
 * 后端有两个端点：
 *   /ws/trace              全局链路事件，连上会回放最近 100 条
 *   /ws/{projectId}/session  单项目的会话进度
 *
 * 设计要点：
 * 1. 同一 URL 只开一条连接，多个面板共享订阅。原来每个面板各开一条，
 *    5 个面板就是 5 条连接 + 5 份回放，既费带宽又重复渲染。
 * 2. 消息按动画帧合批后一次性投递。trace 在生成期间每秒能来几十条，
 *    逐条 setState 会把主线程打满。
 * 3. 指数退避重连带抖动；页面隐藏时不重连，可见时立刻补连。
 */

export interface WsEnvelope {
  type?: string;
  payload?: Record<string, unknown>;
  message?: string;
  project_id?: string;
  chapter?: string;
  status?: string;
  stage?: string;
  round?: number;
  chunk?: string;
  timestamp?: string | number;
  [key: string]: unknown;
}

export type WsStatus = 'connecting' | 'open' | 'closed';

type MessageListener = (batch: WsEnvelope[]) => void;
type StatusListener = (status: WsStatus) => void;

const HEARTBEAT_INTERVAL = 25_000;
const MAX_BACKOFF = 15_000;

interface Channel {
  url: string;
  socket: WebSocket | null;
  status: WsStatus;
  attempt: number;
  heartbeat: number;
  retry: number;
  frame: number;
  queue: WsEnvelope[];
  messageListeners: Set<MessageListener>;
  statusListeners: Set<StatusListener>;
}

const channels = new Map<string, Channel>();

function absoluteUrl(path: string): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}${path}`;
}

function setStatus(channel: Channel, status: WsStatus): void {
  if (channel.status === status) return;
  channel.status = status;
  for (const listener of channel.statusListeners) listener(status);
}

function flush(channel: Channel): void {
  channel.frame = 0;
  if (channel.queue.length === 0) return;
  const batch = channel.queue;
  channel.queue = [];
  for (const listener of channel.messageListeners) listener(batch);
}

function scheduleFlush(channel: Channel): void {
  if (channel.frame !== 0) return;
  channel.frame = requestAnimationFrame(() => flush(channel));
}

function clearTimers(channel: Channel): void {
  if (channel.heartbeat !== 0) {
    clearInterval(channel.heartbeat);
    channel.heartbeat = 0;
  }
  if (channel.retry !== 0) {
    clearTimeout(channel.retry);
    channel.retry = 0;
  }
}

function scheduleReconnect(channel: Channel): void {
  if (channel.messageListeners.size === 0 && channel.statusListeners.size === 0) return;
  if (channel.retry !== 0) return;

  // 2^n 秒上限 15 秒，叠加 0~40% 抖动，避免多标签页同时重连打到后端
  const base = Math.min(MAX_BACKOFF, 500 * 2 ** channel.attempt);
  const delay = base * (1 + Math.random() * 0.4);
  channel.attempt += 1;
  channel.retry = window.setTimeout(() => {
    channel.retry = 0;
    if (document.hidden) return; // 可见性回调里会补连
    connect(channel);
  }, delay);
}

function connect(channel: Channel): void {
  if (channel.socket !== null) return;
  setStatus(channel, 'connecting');

  const socket = new WebSocket(absoluteUrl(channel.url));
  channel.socket = socket;

  socket.onopen = (): void => {
    channel.attempt = 0;
    setStatus(channel, 'open');
    channel.heartbeat = window.setInterval(() => {
      if (socket.readyState === WebSocket.OPEN) socket.send('ping');
    }, HEARTBEAT_INTERVAL);
  };

  socket.onmessage = (event: MessageEvent<string>): void => {
    // 心跳回包不进队列，避免唤醒所有订阅者
    if (event.data === 'pong') return;
    let parsed: WsEnvelope;
    try {
      parsed = JSON.parse(event.data) as WsEnvelope;
    } catch {
      return;
    }
    if (parsed.type === 'pong') return;
    channel.queue.push(parsed);
    scheduleFlush(channel);
  };

  socket.onerror = (): void => {
    // onclose 紧随其后，重连逻辑统一放在那里
  };

  socket.onclose = (): void => {
    channel.socket = null;
    clearTimers(channel);
    setStatus(channel, 'closed');
    scheduleReconnect(channel);
  };
}

function teardown(channel: Channel): void {
  clearTimers(channel);
  if (channel.frame !== 0) {
    cancelAnimationFrame(channel.frame);
    channel.frame = 0;
  }
  const socket = channel.socket;
  channel.socket = null;
  if (socket) {
    socket.onclose = null;
    socket.onmessage = null;
    socket.onerror = null;
    socket.onopen = null;
    socket.close();
  }
  channels.delete(channel.url);
}

function acquire(url: string): Channel {
  let channel = channels.get(url);
  if (!channel) {
    channel = {
      url,
      socket: null,
      status: 'closed',
      attempt: 0,
      heartbeat: 0,
      retry: 0,
      frame: 0,
      queue: [],
      messageListeners: new Set(),
      statusListeners: new Set(),
    };
    channels.set(url, channel);
  }
  return channel;
}

export interface WsSubscription {
  onMessage?: MessageListener | undefined;
  onStatus?: StatusListener | undefined;
}

/** 订阅一个端点，返回退订函数。最后一个订阅者退订时连接关闭。 */
export function subscribeWs(url: string, handlers: WsSubscription): () => void {
  const channel = acquire(url);
  if (handlers.onMessage) channel.messageListeners.add(handlers.onMessage);
  if (handlers.onStatus) {
    channel.statusListeners.add(handlers.onStatus);
    handlers.onStatus(channel.status);
  }
  connect(channel);

  return (): void => {
    if (handlers.onMessage) channel.messageListeners.delete(handlers.onMessage);
    if (handlers.onStatus) channel.statusListeners.delete(handlers.onStatus);
    if (channel.messageListeners.size === 0 && channel.statusListeners.size === 0) {
      teardown(channel);
    }
  };
}

export const wsPath = {
  trace: (): string => '/ws/trace',
  session: (projectId: string): string => `/ws/${encodeURIComponent(projectId)}/session`,
};

// 从后台切回前台时立即补连，不等退避计时器
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) return;
    for (const channel of channels.values()) {
      if (channel.socket === null) {
        channel.attempt = 0;
        if (channel.retry !== 0) {
          clearTimeout(channel.retry);
          channel.retry = 0;
        }
        connect(channel);
      }
    }
  });
}
