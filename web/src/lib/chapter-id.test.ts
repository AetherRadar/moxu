import { describe, expect, it } from 'vitest';
import {
  compareChapterIds,
  compareVolumeIds,
  formatChapterLabel,
  formatVolumeLabel,
  nextChapterId,
} from './chapter-id';

describe('中文序数', () => {
  it('处理零位与万亿分组', () => {
    const cases: [number, string][] = [
      [0, '零'],
      [1, '一'],
      [9, '九'],
      [10, '十'],
      [11, '十一'],
      [20, '二十'],
      [101, '一百零一'],
      [110, '一百一十'],
      [1001, '一千零一'],
      [1010, '一千零一十'],
      [10000, '一万'],
      [10001, '一万零一'],
      [10010, '一万零一十'],
      [11000, '一万一千'],
      [100000, '十万'],
      [100000001, '一亿零一'],
      [100010001, '一亿零一万零一'],
    ];
    for (const [number, label] of cases) {
      expect(formatChapterLabel(`V1C${number}`)).toBe(`第${label}章`);
      expect(formatVolumeLabel(`V${number}`)).toBe(`第${label}卷`);
    }
  });
});

describe('章节显示名', () => {
  it('兼容大小写、空白、补零与历史缺卷号', () => {
    expect(formatChapterLabel(' v02c0012 ')).toBe('第十二章');
    expect(formatChapterLabel('C12', 'en-GB', { includeVolume: true })).toBe('Chapter 12');
  });

  it('按语言与 includeVolume 切换', () => {
    expect(formatChapterLabel('V2C12', 'zh-CN', { includeVolume: true })).toBe('第二卷 · 第十二章');
    expect(formatChapterLabel('V2C12', 'en-US')).toBe('Chapter 12');
    expect(formatChapterLabel('V2C12', 'en', { includeVolume: true })).toBe('Volume 2 · Chapter 12');
  });

  it('渲染番外与幕间后缀', () => {
    expect(formatChapterLabel('V1C2E3')).toBe('第二章 · 番外三');
    expect(formatChapterLabel('V1C2I10')).toBe('第二章 · 幕间十');
    expect(formatChapterLabel('V1C2E3', 'en-US')).toBe('Chapter 2 · Extra 3');
    expect(formatChapterLabel('V1C2I10', 'en-US', { includeVolume: true })).toBe(
      'Volume 1 · Chapter 2 · Interlude 10',
    );
  });

  it('非法 ID 不会漏给用户', () => {
    const invalid = [undefined, null, '', 'nonsense', 'V1', 'V1C2E', 'V1C2E1garbage', 'V1C9007199254740992'];
    for (const id of invalid) {
      expect(formatChapterLabel(id)).toBe('章节');
      expect(formatChapterLabel(id, 'en-US')).toBe('Chapter');
    }
    expect(formatVolumeLabel(null)).toBe('分卷');
    expect(formatVolumeLabel('unknown', '', 'en-US')).toBe('Volume');
    expect(formatVolumeLabel('unknown', 'A custom title')).toBe('A custom title');
  });
});

describe('分卷显示名', () => {
  it('默认卷名不与序数重复', () => {
    const titles = ['', 'V1', 'Volume 1', 'Volume1', 'volume 01', '第一卷', '第1卷', 'Default Volume', '默认分卷'];
    for (const title of titles) {
      expect(formatVolumeLabel('V1', title)).toBe('第一卷');
      expect(formatVolumeLabel('V1', title, 'en-US')).toBe('Volume 1');
    }
  });

  it('自定义卷名保留，已有前缀被归一', () => {
    for (const title of ['启程', '第一卷 · 启程', 'Volume 1: 启程', 'V1 — 启程']) {
      expect(formatVolumeLabel('V1', title)).toBe('第一卷 · 启程');
      expect(formatVolumeLabel('V1', title, 'en-US')).toBe('Volume 1 · 启程');
    }
    expect(formatVolumeLabel('V1', 'Volume 10')).toBe('第一卷 · Volume 10');
    expect(formatVolumeLabel('V2', 'The First Voyage', 'en-US')).toBe('Volume 2 · The First Voyage');
    // 幂等：把格式化结果再喂回去不该叠加前缀
    const once = formatVolumeLabel('V2', 'The First Voyage');
    expect(formatVolumeLabel('V2', once)).toBe(once);
  });
});

describe('章节 ID 分配', () => {
  it('按最大序号而非数量分配，且各卷独立', () => {
    const chapters = ['V1C1', 'V1C3', 'V2C99', 'V10C100'];
    expect(nextChapterId(chapters)).toBe('V1C4');
    expect(nextChapterId(chapters, 'V2')).toBe('V2C100');
    expect(nextChapterId(chapters, 'V3')).toBe('V3C1');
    // 带 E/I 后缀的父章号也要计入
    expect(nextChapterId(['V1C1', 'V1C5E2', 'V1C7I1'])).toBe('V1C8');
  });

  it('番外与幕间各自独立编号，且必须挂在正文章节下', () => {
    const chapters = ['V1C1', 'V1C1E1', 'V1C1E3', 'V1C1I2', 'V1C1I7', 'V1C10E99', 'V2C1E99'];
    expect(nextChapterId(chapters, 'V1', 'extra', 'V1C1')).toBe('V1C1E4');
    expect(nextChapterId(chapters, 'V1', 'interlude', 'V1C1')).toBe('V1C1I8');
    expect(nextChapterId(chapters, 'V1', 'extra', 'V1C2')).toBe('V1C2E1');
    // 父章不在目标卷 / 缺父章 / 父章本身是番外，都拒绝
    expect(nextChapterId(chapters, 'V1', 'extra', 'V2C1')).toBe('');
    expect(nextChapterId(chapters, 'V1', 'extra', '')).toBe('');
    expect(nextChapterId(chapters, 'V1', 'extra', 'V1C1E1')).toBe('');
  });

  it('接受记录形式与历史 ID，且连续分配不产生重复', () => {
    const chapters = [
      { id: 'C1', title: 'Keep me' },
      { chapter: 'v1c03' },
      { chapter_id: 'V1C2' },
      { id: 'V1C1E4' },
    ];
    expect(nextChapterId(chapters)).toBe('V1C4');
    expect(nextChapterId(chapters, 'V1', 'extra', 'V1C1')).toBe('V1C1E5');

    const growing = ['V1C2E1', 'V1C2E7'];
    for (let i = 0; i < 20; i += 1) {
      const next = nextChapterId(growing, 'V1', 'extra', 'V1C2');
      expect(growing).not.toContain(next);
      growing.push(next);
    }
    expect(growing.at(-1)).toBe('V1C2E27');
  });

  it('非法输入与溢出一律失败关闭', () => {
    expect(nextChapterId([], 'invalid')).toBe('');
    expect(nextChapterId(['V1C9007199254740991'])).toBe('');
  });
});

describe('排序', () => {
  it('卷号按自然序而非字典序', () => {
    expect(['V10', 'V2', 'V1'].sort(compareVolumeIds)).toEqual(['V1', 'V2', 'V10']);
  });

  it('章节按卷→章→正文先于幕间与番外→子序号', () => {
    const ids = ['V1C2E1', 'V1C10', 'V1C2', 'V2C1', 'V1C2I1', 'C1', 'V1C2E2'];
    expect([...ids].sort(compareChapterIds)).toEqual([
      'C1',
      'V1C2',
      'V1C2I1',
      'V1C2E1',
      'V1C2E2',
      'V1C10',
      'V2C1',
    ]);
  });
});
