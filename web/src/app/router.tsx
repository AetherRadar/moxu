import { lazy, Suspense, type ReactNode } from 'react';
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router-dom';
import { SkeletonList } from '@/components/ui';
import { ErrorBoundary } from '@/components/ErrorBoundary';

/**
 * 路由。
 * 两个顶层页面各自成 chunk：项目列表不需要加载工作区的编辑器和面板代码。
 */

const ProjectListPage = lazy(() =>
  import('@/pages/ProjectListPage').then((m) => ({ default: m.ProjectListPage })),
);
const WorkspacePage = lazy(() => import('@/pages/WorkspacePage').then((m) => ({ default: m.WorkspacePage })));

function Lazy({ children }: { children: ReactNode }): ReactNode {
  return (
    <ErrorBoundary>
      <Suspense fallback={<SkeletonList rows={10} className="mx-auto max-w-3xl pt-16" />}>
        {children}
      </Suspense>
    </ErrorBoundary>
  );
}

const router = createBrowserRouter([
  {
    path: '/',
    element: (
      <Lazy>
        <ProjectListPage />
      </Lazy>
    ),
  },
  {
    // chapter 作为可选段，切章只换 URL 不重挂工作区
    path: '/p/:projectId',
    element: (
      <Lazy>
        <WorkspacePage />
      </Lazy>
    ),
  },
  { path: '*', element: <Navigate to="/" replace /> },
]);

export function AppRouter(): ReactNode {
  return <RouterProvider router={router} />;
}
