import { lazy, Suspense, type ComponentType, type ReactNode } from 'react';
import { SkeletonList } from '@/components/ui';
import type { SideView } from '@/stores/layout';

/**
 * 侧边面板注册表。
 *
 * 每个面板独立懒加载：只打开过"章节"面板的用户不必下载事实百科和智能体面板的代码。
 * 上游是把全部面板静态 import 进同一个 bundle 的。
 */

const panels: Record<SideView, ComponentType> = {
  explorer: lazy(() => import('./ExplorerPanel').then((m) => ({ default: m.ExplorerPanel }))),
  outline: lazy(() => import('./OutlinePanel').then((m) => ({ default: m.OutlinePanel }))),
  cards: lazy(() => import('./CardsPanel').then((m) => ({ default: m.CardsPanel }))),
  facts: lazy(() => import('./FactsPanel').then((m) => ({ default: m.FactsPanel }))),
  search: lazy(() => import('./SearchPanel').then((m) => ({ default: m.SearchPanel }))),
  agents: lazy(() => import('./AgentsPanel').then((m) => ({ default: m.AgentsPanel }))),
};

export function SidePanelHost({ view }: { view: SideView }): ReactNode {
  const Panel = panels[view];
  return (
    <Suspense fallback={<SkeletonList rows={8} />}>
      <Panel />
    </Suspense>
  );
}
