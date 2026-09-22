import { useEffect } from 'react';

/**
 * 全局快捷键。
 *
 * 在 capture 阶段监听，并在输入控件内主动放行普通按键——
 * 否则在正文里打字会触发面板切换。带修饰键的组合仍然生效。
 */

export type ShortcutHandler = (event: KeyboardEvent) => void;

function isTextEntry(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
}

/** 把事件归一成 `ctrl+shift+k` 这样的字符串，Mac 上 Meta 与 Ctrl 等价。 */
function describe(event: KeyboardEvent): string {
  const parts: string[] = [];
  if (event.ctrlKey || event.metaKey) parts.push('ctrl');
  if (event.shiftKey) parts.push('shift');
  if (event.altKey) parts.push('alt');
  parts.push(event.key.length === 1 ? event.key.toLowerCase() : event.key);
  return parts.join('+');
}

export function useShortcuts(bindings: Record<string, ShortcutHandler>, enabled = true): void {
  useEffect(() => {
    if (!enabled) return;
    const handle = (event: KeyboardEvent): void => {
      if (event.isComposing) return; // 中文输入法组字期间不拦
      const combo = describe(event);
      const handler = bindings[combo];
      if (!handler) return;
      // 无修饰键的单键快捷方式在输入框里让给输入
      if (!event.ctrlKey && !event.metaKey && !event.altKey && isTextEntry(event.target)) return;
      event.preventDefault();
      handler(event);
    };
    window.addEventListener('keydown', handle, { capture: true });
    return () => window.removeEventListener('keydown', handle, { capture: true });
    // bindings 每次渲染都是新对象，但内部闭包需要最新的 state，
    // 所以这里刻意不做深比较，重新绑定的成本只是一次 addEventListener。
  }, [bindings, enabled]);
}
