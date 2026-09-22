import { useMemo, type ReactNode } from 'react';
import diff from 'fast-diff';

const CONTEXT = 24;

type Piece =
  | { kind: 'same' | 'add' | 'del'; text: string }
  | { kind: 'gap'; count: number };

/**
 * 内联字符级 diff。
 * 中文正文逐字比对天然可读（不像英文得按词切），
 * 未改动的长段折叠成「… 略过 N 字 …」，差异处保留前后 CONTEXT 字符上下文。
 */
export function DiffPreview({
  original,
  revised,
  className,
}: {
  original: string;
  revised: string;
  className?: string;
}): ReactNode {
  const { pieces, stats } = useMemo(() => {
    const out: Piece[] = [];
    let added = 0;
    let removed = 0;
    for (const [op, text] of diff(original, revised)) {
      if (op === 1) {
        added += text.length;
        out.push({ kind: 'add', text });
      } else if (op === -1) {
        removed += text.length;
        out.push({ kind: 'del', text });
      } else if (text.length <= CONTEXT * 2) {
        out.push({ kind: 'same', text });
      } else {
        out.push({ kind: 'same', text: text.slice(0, CONTEXT) });
        out.push({ kind: 'gap', count: text.length - CONTEXT * 2 });
        out.push({ kind: 'same', text: text.slice(-CONTEXT) });
      }
    }
    return { pieces: out, stats: { added, removed } };
  }, [original, revised]);

  return (
    <div className={className}>
      <p className="mb-1.5 text-2xs text-ink-500">
        <span className="text-malachite-400">+{stats.added} 字</span>
        {'　'}
        <span className="text-cinnabar-400">-{stats.removed} 字</span>
        <span className="ml-2 text-ink-600">未变的长段已折叠</span>
      </p>
      <div className="whitespace-pre-wrap rounded-md border border-ink-800 bg-ink-900/50 p-3 font-serif text-sm leading-[1.9] text-ink-300">
        {pieces.map((piece, index) => {
          if (piece.kind === 'gap') {
            return (
              <span key={index} className="mx-1 select-none text-2xs text-ink-600">
                […略过 {piece.count} 字…]
              </span>
            );
          }
          if (piece.kind === 'add') {
            return (
              <span key={index} className="bg-malachite-500/15 text-malachite-400">
                {piece.text}
              </span>
            );
          }
          if (piece.kind === 'del') {
            return (
              <span key={index} className="bg-cinnabar-500/15 text-cinnabar-400 line-through">
                {piece.text}
              </span>
            );
          }
          return <span key={index}>{piece.text}</span>;
        })}
      </div>
    </div>
  );
}
