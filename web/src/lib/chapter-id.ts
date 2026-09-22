/**
 * 章节标识与显示名。
 *
 * 存储 ID 形如 `V1C2`，番外/幕间在其后追加 `E{n}` / `I{n}`；历史数据里存在
 * 缺卷号的 `C2`，视作第一卷。
 *
 * 这里的格式化函数只产出给人看的文字，**任何情况下都不要拿返回值当 ID 用**——
 * 只有 nextChapterId 会产出存储 ID。
 */

export type ChapterKind = 'normal' | 'extra' | 'interlude';

interface ParsedChapterId {
  volume: number | null;
  chapter: number;
  kind: ChapterKind;
  /** 番外/幕间的序号 */
  sub: number | null;
}

const ID_PATTERN = /^(?:V(\d+))?C(\d+)(?:([EI])(\d+))?$/;
const VOLUME_PATTERN = /^V(\d+)$/;

const normalize = (value: unknown): string => String(value ?? '').trim().toUpperCase();

const isEnglish = (locale: string): boolean => /^en(?:[-_]|$)/i.test(locale);

/** 章节号来自文件名，可能被手工改坏；超出安全整数范围一律当非法。 */
const isSafeIndex = (raw: string): boolean => {
  const value = Number(raw);
  return Number.isSafeInteger(value) && value >= 0;
};

export function parseChapterId(id: unknown): ParsedChapterId | null {
  const match = ID_PATTERN.exec(normalize(id));
  if (!match) return null;
  const [, volume, chapter, suffix, sub] = match;
  if (chapter === undefined || !isSafeIndex(chapter)) return null;
  if (volume !== undefined && !isSafeIndex(volume)) return null;
  if (sub !== undefined && !isSafeIndex(sub)) return null;
  return {
    volume: volume === undefined ? null : Number(volume),
    chapter: Number(chapter),
    kind: suffix === 'E' ? 'extra' : suffix === 'I' ? 'interlude' : 'normal',
    sub: sub === undefined ? null : Number(sub),
  };
}

/** 中文数字，支持零位与万/亿/兆分组。 */
export function chineseNumber(value: number): string {
  const digits = '零一二三四五六七八九';
  const units = ['', '十', '百', '千'];
  const groups = ['', '万', '亿', '兆'];
  const zero = digits[0] as string;

  let remaining = Math.trunc(value);
  if (remaining === 0) return zero;

  let result = '';
  let group = 0;
  while (remaining > 0) {
    const part = remaining % 10000;
    if (part !== 0) {
      let text = '';
      let pendingZero = false;
      for (let place = 3; place >= 0; place -= 1) {
        const digit = Math.floor(part / 10 ** place) % 10;
        if (digit !== 0) {
          if (pendingZero) text += zero;
          text += (digits[digit] as string) + (units[place] as string);
          pendingZero = false;
        } else if (text !== '') {
          pendingZero = true;
        }
      }
      result = text + (groups[group] as string) + result;
      // 高位组存在而本组不足四位时要补一个"零"，如 一万零一
      if (part < 1000 && Math.floor(remaining / 10000) > 0) result = zero + result;
    } else if (result !== '' && !result.startsWith(zero)) {
      result = zero + result;
    }
    remaining = Math.floor(remaining / 10000);
    group += 1;
  }
  // 十一 而非 一十一
  return result.replace(/^一十/, '十');
}

/**
 * `V2C12` → 第十二章 / Chapter 12。
 * includeVolume 时前置卷名。非法 ID 返回中性文字，绝不把原始 ID 漏给用户。
 */
export function formatChapterLabel(
  id: unknown,
  locale = 'zh-CN',
  options: { includeVolume?: boolean } = {},
): string {
  const parsed = parseChapterId(id);
  const english = isEnglish(locale);
  if (!parsed) return english ? 'Chapter' : '章节';

  let label = english ? `Chapter ${parsed.chapter}` : `第${chineseNumber(parsed.chapter)}章`;

  if (parsed.kind !== 'normal' && parsed.sub !== null) {
    if (english) {
      label += ` · ${parsed.kind === 'extra' ? 'Extra' : 'Interlude'} ${parsed.sub}`;
    } else {
      label += ` · ${parsed.kind === 'extra' ? '番外' : '幕间'}${chineseNumber(parsed.sub)}`;
    }
  }

  if (options.includeVolume === true && parsed.volume !== null) {
    return `${formatVolumeLabel(`V${parsed.volume}`, '', locale)} · ${label}`;
  }
  return label;
}

const DEFAULT_VOLUME_TITLE = /^(?:default volume|默认分卷)$/i;

/**
 * `V1` + `启程` → 第一卷 · 启程。
 * 标题里已有的卷号前缀会被归一而不是重复，自定义部分保留。
 */
export function formatVolumeLabel(id: unknown, title = '', locale = 'zh-CN'): string {
  const match = VOLUME_PATTERN.exec(normalize(id));
  const name = String(title ?? '').trim();
  const english = isEnglish(locale);

  const raw = match?.[1];
  if (raw === undefined || !isSafeIndex(raw)) return name || (english ? 'Volume' : '分卷');

  const number = Number(raw);
  const chinese = chineseNumber(number);
  const label = english ? `Volume ${number}` : `第${chinese}卷`;
  if (name === '' || DEFAULT_VOLUME_TITLE.test(name)) return label;

  const prefix = new RegExp(
    `^(?:V0*${number}|Volume\\s*0*${number}|第\\s*(?:0*${number}|${chinese})\\s*卷)(?:\\s*[·:：—–-]\\s*|\\s+|$)`,
    'i',
  );
  const custom = name.replace(prefix, '').trim();
  return custom === '' ? label : `${label} · ${custom}`;
}

/** 从字符串或记录里取出章节 ID，并把历史的 `C2` 补成 `V1C2`。 */
function coerceId(item: unknown): string {
  const raw =
    typeof item === 'string'
      ? item
      : ((item as { id?: string; chapter?: string; chapter_id?: string } | null)?.id ??
        (item as { chapter?: string } | null)?.chapter ??
        (item as { chapter_id?: string } | null)?.chapter_id ??
        '');
  const id = normalize(raw);
  return /^C\d+/.test(id) ? `V1${id}` : id;
}

/**
 * 分配下一个章节 ID：取现有最大序号 +1，而不是按数量递增——
 * 删过章之后按数量分配会撞上已存在的 ID。
 *
 * 番外/幕间必须挂在一个正文章节下（insertAfter），否则返回空串表示拒绝。
 */
export function nextChapterId(
  existing: readonly unknown[],
  volumeId = 'V1',
  kind: ChapterKind = 'normal',
  insertAfter = '',
): string {
  const volume = normalize(volumeId);
  if (!VOLUME_PATTERN.test(volume)) return '';

  const parent = normalize(insertAfter);
  if (kind !== 'normal' && !new RegExp(`^${volume}C\\d+$`).test(parent)) return '';

  const prefix = kind === 'normal' ? `${volume}C` : `${parent}${kind === 'extra' ? 'E' : 'I'}`;
  // 正文章节要把带 E/I 后缀的也算进最大值，否则会与番外的父章号冲突
  const pattern =
    kind === 'normal' ? new RegExp(`^${prefix}(\\d+)(?:[EI]\\d+)?$`) : new RegExp(`^${prefix}(\\d+)$`);

  let max = 0;
  for (const item of existing) {
    const match = pattern.exec(coerceId(item));
    if (match?.[1] !== undefined) max = Math.max(max, Number(match[1]));
  }
  return Number.isSafeInteger(max + 1) ? `${prefix}${max + 1}` : '';
}

/** 卷号自然序比较，`V10` 排在 `V9` 之后。 */
export function compareVolumeIds(a: string | null | undefined, b: string | null | undefined): number {
  const left = VOLUME_PATTERN.exec(normalize(a))?.[1];
  const right = VOLUME_PATTERN.exec(normalize(b))?.[1];
  if (left === undefined && right === undefined) return normalize(a).localeCompare(normalize(b));
  if (left === undefined) return 1;
  if (right === undefined) return -1;
  return Number(left) - Number(right);
}

/** 章节自然序比较：卷 → 章 → 正文先于番外/幕间 → 子序号。 */
export function compareChapterIds(a: unknown, b: unknown): number {
  const left = parseChapterId(coerceId(a));
  const right = parseChapterId(coerceId(b));
  if (!left && !right) return coerceId(a).localeCompare(coerceId(b));
  if (!left) return 1;
  if (!right) return -1;

  if ((left.volume ?? 1) !== (right.volume ?? 1)) return (left.volume ?? 1) - (right.volume ?? 1);
  if (left.chapter !== right.chapter) return left.chapter - right.chapter;

  const rank: Record<ChapterKind, number> = { normal: 0, interlude: 1, extra: 2 };
  if (left.kind !== right.kind) return rank[left.kind] - rank[right.kind];
  return (left.sub ?? 0) - (right.sub ?? 0);
}

/** 从章节 ID 推断所属卷；缺卷号的历史数据归第一卷。 */
export function volumeIdOf(chapterId: string): string {
  const parsed = parseChapterId(chapterId);
  return `V${parsed?.volume ?? 1}`;
}
