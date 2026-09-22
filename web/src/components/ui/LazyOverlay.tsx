import { Suspense, type ReactNode } from 'react';

/**
 * 弹层的懒加载外壳。
 *
 * 对话框依赖 Radix，而 Radix 的共享运行时接近 37KB(gz)。
 * 首屏（项目列表）本来一个弹窗都不开，没理由为此付这份代价。
 * 用法：`{open && <LazyOverlay><SomeDialog … /></LazyOverlay>}`
 * ——条件渲染负责不加载，Suspense 负责加载期间不闪。
 */
export function LazyOverlay({ children }: { children: ReactNode }): ReactNode {
  return <Suspense fallback={null}>{children}</Suspense>;
}
