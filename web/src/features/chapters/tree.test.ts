import { describe, expect, it } from 'vitest';
import type { ChapterSummary, Volume } from '@/api/types';
import { buildChapterTree, filterTree, flattenTree, neighbourChapter, ORPHAN_VOLUME_TITLE } from './tree';

function volume(id: string, title: string, order: number): Volume {
  return {
    id,
    project_id: 'p',
    title,
    summary: null,
    order,
    created_at: null,
    updated_at: null,
  };
}

function summary(chapter: string, fields: Partial<ChapterSummary> = {}): ChapterSummary {
  return {
    chapter,
    volume_id: null,
    order_index: null,
    title: '',
    word_count: 0,
    key_events: [],
    new_facts: [],
    character_state_changes: [],
    open_loops: [],
    brief_summary: '',
    ...fields,
  };
}

describe('buildChapterTree', () => {
  it('合并 drafts 与 summaries：无摘要的新章也要出现', () => {
    const tree = buildChapterTree(
      [volume('V1', '启程', 1)],
      ['V1C1', 'V1C2'],
      [summary('V1C1', { volume_id: 'V1', title: '开场', word_count: 3200, order_index: 0 })],
    );
    expect(tree).toHaveLength(1);
    expect(tree[0]?.chapters.map((c) => [c.id, c.title, c.analyzed])).toEqual([
      ['V1C1', '开场', true],
      ['V1C2', '', false],
    ]);
  });

  it('只有摘要而 drafts 目录已丢的章节不会被藏掉', () => {
    const tree = buildChapterTree([volume('V1', '', 1)], [], [summary('V1C3', { volume_id: 'V1' })]);
    expect(tree[0]?.chapters.map((c) => c.id)).toEqual(['V1C3']);
  });

  it('摘要里的 volume_id 优先于 ID 前缀，支持手工并卷', () => {
    const tree = buildChapterTree(
      [volume('V1', '', 1), volume('V2', '', 2)],
      ['V1C5'],
      [summary('V1C5', { volume_id: 'V2' })],
    );
    expect(tree.find((v) => v.id === 'V1')?.chapters).toEqual([]);
    expect(tree.find((v) => v.id === 'V2')?.chapters.map((c) => c.id)).toEqual(['V1C5']);
  });

  it('未登记的卷仍然成组显示，不吞掉章节', () => {
    const tree = buildChapterTree([], ['V7C1'], []);
    expect(tree[0]?.id).toBe('V7');
    expect(tree[0]?.title).toBe(ORPHAN_VOLUME_TITLE);
  });

  it('orderIndex 齐全时按它排序，缺一个就整卷退回 ID 自然序', () => {
    const complete = buildChapterTree(
      [volume('V1', '', 1)],
      ['V1C1', 'V1C2', 'V1C3'],
      [
        summary('V1C1', { volume_id: 'V1', order_index: 2 }),
        summary('V1C2', { volume_id: 'V1', order_index: 0 }),
        summary('V1C3', { volume_id: 'V1', order_index: 1 }),
      ],
    );
    expect(complete[0]?.chapters.map((c) => c.id)).toEqual(['V1C2', 'V1C3', 'V1C1']);

    const partial = buildChapterTree(
      [volume('V1', '', 1)],
      ['V1C1', 'V1C2', 'V1C10'],
      [summary('V1C10', { volume_id: 'V1', order_index: 0 })],
    );
    expect(partial[0]?.chapters.map((c) => c.id)).toEqual(['V1C1', 'V1C2', 'V1C10']);
  });

  it('卷按 order 字段排序，未登记的排在后面', () => {
    const tree = buildChapterTree(
      [volume('V2', '', 1), volume('V1', '', 2)],
      ['V9C1'],
      [],
    );
    expect(tree.map((v) => v.id)).toEqual(['V2', 'V1', 'V9']);
  });

  it('卷字数是其章节字数之和', () => {
    const tree = buildChapterTree(
      [volume('V1', '', 1)],
      ['V1C1', 'V1C2'],
      [
        summary('V1C1', { volume_id: 'V1', word_count: 1000, order_index: 0 }),
        summary('V1C2', { volume_id: 'V1', word_count: 2500, order_index: 1 }),
      ],
    );
    expect(tree[0]?.wordCount).toBe(3500);
  });
});

describe('flattenTree', () => {
  const tree = buildChapterTree([volume('V1', '', 1), volume('V2', '', 2)], ['V1C1', 'V2C1'], []);

  it('折叠的卷不产出章节行', () => {
    expect(flattenTree(tree, new Set()).map((row) => row.kind)).toEqual(['volume', 'volume']);
  });

  it('展开的卷在其后插入章节行', () => {
    const rows = flattenTree(tree, new Set(['V1']));
    expect(rows.map((row) => row.kind)).toEqual(['volume', 'chapter', 'volume']);
  });
});

describe('filterTree', () => {
  const tree = buildChapterTree(
    [volume('V1', '启程', 1)],
    ['V1C1', 'V1C2'],
    [
      summary('V1C1', { volume_id: 'V1', title: '雪夜', order_index: 0 }),
      summary('V1C2', { volume_id: 'V1', title: '登船', order_index: 1 }),
    ],
  );

  it('命中章节标题时只留该章，并保留其所在卷', () => {
    const filtered = filterTree(tree, '雪');
    expect(filtered).toHaveLength(1);
    expect(filtered[0]?.chapters.map((c) => c.id)).toEqual(['V1C1']);
  });

  it('命中章节 ID 也算', () => {
    expect(filterTree(tree, 'v1c2')[0]?.chapters.map((c) => c.id)).toEqual(['V1C2']);
  });

  it('只命中卷名时保留空卷，提示这一卷存在', () => {
    const filtered = filterTree(tree, '启程');
    expect(filtered[0]?.chapters).toEqual([]);
  });

  it('空关键词原样返回', () => {
    expect(filterTree(tree, '  ')).toHaveLength(1);
  });
});

describe('neighbourChapter', () => {
  const tree = buildChapterTree([volume('V1', '', 1), volume('V2', '', 2)], ['V1C1', 'V1C2', 'V2C1'], []);

  it('跨卷连续前后翻章', () => {
    expect(neighbourChapter(tree, 'V1C2', 1)).toBe('V2C1');
    expect(neighbourChapter(tree, 'V2C1', -1)).toBe('V1C2');
  });

  it('首尾与未知章节返回 null', () => {
    expect(neighbourChapter(tree, 'V1C1', -1)).toBeNull();
    expect(neighbourChapter(tree, 'V2C1', 1)).toBeNull();
    expect(neighbourChapter(tree, 'V9C9', 1)).toBeNull();
  });
});
