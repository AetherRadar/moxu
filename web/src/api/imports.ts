import { http, seg, Timeout } from '@/lib/http';
import type {
  BookImportConfirmRequest,
  BookImportPreview,
  BookImportResult,
  BookImportTextRequest,
} from './types';

const base = (projectId: string): string => `/projects/${seg(projectId)}/imports`;

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

  confirm: (projectId: string, body: BookImportConfirmRequest, signal?: AbortSignal) =>
    http.post<BookImportResult>(`${base(projectId)}/book/confirm`, { body, timeout: Timeout.batch, signal }),
};
