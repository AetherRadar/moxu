import { compareChapterIds, compareVolumeIds, volumeIdOf } from '@/lib/chapter-id';
import type { ChapterSummary, Volume } from '@/api/types';

/**
 * 章节树的纯函数构建。
 *
 * 两个数据源必须合并：`drafts` 目录决定章节是否存在，`summaries` 目录提供标题、
 * 字数与自定义顺序。只读 summaries 会漏掉尚未生成摘要的新章；只读 drafts 则没有标题。
 * 放在纯函数里也方便单测，不用拉起组件。
 */

export interface ChapterNode {
  id: string;
  volumeId: string;
  title: string;
  wordCount: number;
  /** 有摘要说明这章已经被归档分析过 */
  analyzed: boolean;
  orderIndex: number | null;
}

export interface VolumeNode {
  id: string;
  title: string;
  chapters: ChapterNode[];
  wordCount: number;
}

/** 卷在 volumes 里没有登记，但章节 ID 指向了它——数据不一致时不能把章节藏掉。 */
export const ORPHAN_VOLUME_TITLE = '未归卷';

export function buildChapterTree(
  volumes: readonly Volume[],
  chapterIds: readonly string[],
  summaries: readonly ChapterSummary[],
): VolumeNode[] {
  const summaryById = new Map<string, ChapterSummary>();
  for (const summary of summaries) {
    if (summary.chapter) summaryById.set(summary.chapter.toUpperCase(), summary);
  }

  // 以 drafts 为准，再补上只有摘要、目录却已丢失的章节，避免数据看起来"消失"
  const ids = new Set<string>();
  for (const id of chapterIds) ids.add(id.toUpperCase());
  for (const key of summaryById.keys()) ids.add(key);

  const byVolume = new Map<string, ChapterNode[]>();
  for (const id of ids) {
    const summary = summaryById.get(id);
    // summary 里记录的卷优先：手工并卷后 ID 前缀可能与实际归属不一致
    const volumeId = (summary?.volume_id ?? volumeIdOf(id)).toUpperCase();
    const node: ChapterNode = {
      id,
      volumeId,
      title: summary?.title?.trim() ?? '',
      wordCount: summary?.word_count ?? 0,
      analyzed: summary !== undefined,
      orderIndex: summary?.order_index ?? null,
    };
    const bucket = byVolume.get(volumeId);
    if (bucket) bucket.push(node);
    else byVolume.set(volumeId, [node]);
  }

  const known = new Map<string, Volume>();
  for (const volume of volumes) known.set(volume.id.toUpperCase(), volume);

  const volumeIds = new Set<string>([...known.keys(), ...byVolume.keys()]);

  const nodes: VolumeNode[] = [];
  for (const volumeId of volumeIds) {
    const chapters = byVolume.get(volumeId) ?? [];
    sortChapters(chapters);
    nodes.push({
      id: volumeId,
      title: known.get(volumeId)?.title ?? (byVolume.has(volumeId) ? ORPHAN_VOLUME_TITLE : ''),
      chapters,
      wordCount: chapters.reduce((total, chapter) => total + chapter.wordCount, 0),
    });
  }

  nodes.sort((a, b) => {
    const left = known.get(a.id)?.order;
    const right = known.get(b.id)?.order;
    // 登记过的卷按 order 字段，未登记的按卷号自然序排在后面
    if (left !== undefined && right !== undefined && left !== right) return left - right;
    if (left !== undefined && right === undefined) return -1;
    if (left === undefined && right !== undefined) return 1;
    return compareVolumeIds(a.id, b.id);
  });

  return nodes;
}

/**
 * 卷内排序：只要有任一章缺 orderIndex，就整卷退回按 ID 自然序——
 * 混用两种依据会让拖拽排序的结果看起来随机。
 */
function sortChapters(chapters: ChapterNode[]): void {
  const complete = chapters.every((chapter) => chapter.orderIndex !== null);
  if (complete) chapters.sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0));
  else chapters.sort((a, b) => compareChapterIds(a.id, b.id));
}

/** 扁平化成虚拟列表需要的行序列。 */
export type TreeRow =
  | { kind: 'volume'; volume: VolumeNode; expanded: boolean }
  | { kind: 'chapter'; chapter: ChapterNode; volume: VolumeNode };

export function flattenTree(volumes: readonly VolumeNode[], expanded: ReadonlySet<string>): TreeRow[] {
  const rows: TreeRow[] = [];
  for (const volume of volumes) {
    const isExpanded = expanded.has(volume.id);
    rows.push({ kind: 'volume', volume, expanded: isExpanded });
    if (!isExpanded) continue;
    for (const chapter of volume.chapters) rows.push({ kind: 'chapter', chapter, volume });
  }
  return rows;
}

/** 关键词过滤：命中章节标题或 ID 即保留，并连带保留其所在卷。 */
export function filterTree(volumes: readonly VolumeNode[], keyword: string): VolumeNode[] {
  const needle = keyword.trim().toLowerCase();
  if (needle === '') return [...volumes];
  const result: VolumeNode[] = [];
  for (const volume of volumes) {
    const chapters = volume.chapters.filter(
      (chapter) =>
        chapter.title.toLowerCase().includes(needle) || chapter.id.toLowerCase().includes(needle),
    );
    if (chapters.length > 0) result.push({ ...volume, chapters });
    else if (volume.title.toLowerCase().includes(needle)) result.push({ ...volume, chapters: [] });
  }
  return result;
}

/** 相邻章节，供上一章/下一章跳转与删除后的落点选择。 */
export function flatChapterIds(volumes: readonly VolumeNode[]): string[] {
  return volumes.flatMap((volume) => volume.chapters.map((chapter) => chapter.id));
}

export function neighbourChapter(
  volumes: readonly VolumeNode[],
  current: string,
  offset: 1 | -1,
): string | null {
  const ids = flatChapterIds(volumes);
  const index = ids.indexOf(current);
  if (index === -1) return null;
  return ids[index + offset] ?? null;
}
