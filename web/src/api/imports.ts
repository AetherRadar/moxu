import { http, seg, Timeout } from '@/lib/http';
import type {
  BookImportConfirmRequest,
  BookImportOptions,
  BookImportPreview,
  BookImportResult,
  BookImportTextRequest,
} from './types';

const base = (projectId: string): string => `/projects/${seg(projectId)}/imports`;

/** 把导入选项铺进查询参数（文件上传那条走 @RequestParam 而不是 body）。 */
function optionsToQuery(options: BookImportOptions): Record<string, string | number | boolean> {
  const query: Record<string, string | number | boolean> = {};
  if (options.quickInit !== undefined) query['quick_init'] = options.quickInit;
  if (options.overwriteStyle !== undefined) query['overwrite_style'] = options.overwriteStyle;
  if (options.overwriteCards !== undefined) query['overwrite_cards'] = options.overwriteCards;
  if (options.autoExtractTitles !== undefined) query['auto_extract_titles'] = options.autoExtractTitles;
  if (options.maxCharacterCards !== undefined) query['max_character_cards'] = options.maxCharacterCards;
  if (options.maxWorldCards !== undefined) query['max_world_cards'] = options.maxWorldCards;
  return query;
}

/**
 * 书籍导入。
 *
 * 推荐流程是 preview → 用户在界面上勾选卷/章 → confirm，
 * 而不是一步 import：整本书解析后作者往往要手工并卷或剔除前言、版权页。
 */
export const importsApi = {
  previewFile: (projectId: string, file: File, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file);
    return http.post<BookImportPreview>(`${base(projectId)}/book/preview`, {
      form,
      timeout: Timeout.batch,
      signal,
    });
  },

  previewText: (projectId: string, body: BookImportTextRequest, signal?: AbortSignal) =>
    http.post<BookImportPreview>(`${base(projectId)}/book/text/preview`, {
      body,
      timeout: Timeout.batch,
      signal,
    }),

  importFile: (projectId: string, file: File, options: BookImportOptions = {}, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file);
    return http.post<BookImportResult>(`${base(projectId)}/book`, {
      form,
      query: optionsToQuery(options),
      timeout: Timeout.batch,
      signal,
    });
  },

  importText: (projectId: string, body: BookImportTextRequest, signal?: AbortSignal) =>
    http.post<BookImportResult>(`${base(projectId)}/book/text`, { body, timeout: Timeout.batch, signal }),

  confirm: (projectId: string, body: BookImportConfirmRequest, signal?: AbortSignal) =>
    http.post<BookImportResult>(`${base(projectId)}/book/confirm`, { body, timeout: Timeout.batch, signal }),
};
