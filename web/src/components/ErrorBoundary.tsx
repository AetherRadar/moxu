import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  /** 传入随路由变化的值，切页面时自动复位，不然一次崩溃会把整块区域永久卡住 */
  resetKey?: unknown;
  fallback?: ((error: Error, reset: () => void) => ReactNode) | undefined;
}

interface State {
  error: Error | null;
}

/**
 * 错误边界。React 至今只能用 class 组件实现这个。
 * 放在路由层和每个大面板外层，一个面板炸掉不至于整个应用白屏。
 */
export class ErrorBoundary extends Component<Props, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('[ErrorBoundary]', error, info.componentStack);
  }

  override componentDidUpdate(prev: Props): void {
    if (this.state.error !== null && prev.resetKey !== this.props.resetKey) {
      this.setState({ error: null });
    }
  }

  private readonly reset = (): void => this.setState({ error: null });

  override render(): ReactNode {
    const { error } = this.state;
    if (error === null) return this.props.children;
    if (this.props.fallback) return this.props.fallback(error, this.reset);
    return <DefaultFallback error={error} onReset={this.reset} />;
  }
}

function DefaultFallback({ error, onReset }: { error: Error; onReset: () => void }): ReactNode {
  return (
    <div className="flex h-full min-h-40 flex-col items-center justify-center gap-3 p-8 text-center">
      <p className="text-ink-200 text-sm font-medium">出错了</p>
      <p className="text-ink-400 max-w-md text-xs leading-relaxed">
        界面崩了。这通常是前端的 bug，不会影响已保存的稿件。
      </p>
      <pre className="text-cinnabar-300/80 bg-ink-900 border-ink-800 max-w-full overflow-auto rounded-md border px-3 py-2 text-left font-mono text-2xs">
        {error.message}
      </pre>
      <button
        type="button"
        onClick={onReset}
        className="border-ink-700 text-ink-200 hover:border-celadon-600 hover:text-celadon-300 rounded-md border px-3 py-1.5 text-xs transition-colors"
      >
        重试
      </button>
    </div>
  );
}
