import { useEffect, useState } from 'react';
import { subscribeWs, wsPath, type WsStatus } from '@/lib/ws';

/**
 * 只订阅连接状态，不订阅消息。
 * 状态栏用它显示后端是否在线，不需要为此把每条 trace 消息都推到 React。
 */
export function useTraceStatus(): WsStatus {
  const [status, setStatus] = useState<WsStatus>('closed');
  useEffect(() => subscribeWs(wsPath.trace(), { onStatus: setStatus }), []);
  return status;
}
