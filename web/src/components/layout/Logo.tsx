import { type ReactNode } from 'react';

/**
 * 应用标记。
 * 三道渐短的横画加一道竖画，取"墨"的笔意与"序"的层次；纯几何绘制，无外部资源。
 */
export function Logo({ className, size = 18 }: { className?: string; size?: number }): ReactNode {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      className={className}
      role="img"
      aria-label="墨序"
      fill="none"
    >
      <rect x="1.5" y="1.5" width="21" height="21" rx="6" className="fill-ink-800" />
      <g strokeLinecap="round" className="stroke-celadon-400" strokeWidth="1.9">
        <path d="M6.5 7.5h11" />
        <path d="M6.5 12h8" opacity="0.8" />
        <path d="M6.5 16.5h5" opacity="0.55" />
      </g>
      <path d="M18.2 11.2v5.6" strokeLinecap="round" strokeWidth="1.9" className="stroke-cinnabar-500" />
    </svg>
  );
}
