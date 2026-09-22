import i18next, { type i18n as I18nInstance } from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from './locales/zh-CN.json';

/**
 * 国际化。
 *
 * 中文是默认语言并静态打包（绝大多数用户只用中文）；英文按需动态加载，
 * 不进首屏 chunk。上游把两份 1300 行的翻译对象都直接 import 进主包，
 * 光这一项就占了几十 KB。
 */

export const SUPPORTED_LANGUAGES = ['zh-CN', 'en-US'] as const;
export type Language = (typeof SUPPORTED_LANGUAGES)[number];

const STORAGE_KEY = 'moxu:lang';

function detectLanguage(): Language {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved && (SUPPORTED_LANGUAGES as readonly string[]).includes(saved)) return saved as Language;
  return navigator.language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US';
}

const loaded = new Set<Language>(['zh-CN']);

async function ensureBundle(language: Language): Promise<void> {
  if (loaded.has(language)) return;
  const module = (await import('./locales/en-US.json')) as { default: Record<string, unknown> };
  i18next.addResourceBundle(language, 'translation', module.default, true, true);
  loaded.add(language);
}

export async function setLanguage(language: Language): Promise<void> {
  await ensureBundle(language);
  localStorage.setItem(STORAGE_KEY, language);
  document.documentElement.lang = language;
  await i18next.changeLanguage(language);
}

export async function initI18n(): Promise<I18nInstance> {
  const language = detectLanguage();

  await i18next.use(initReactI18next).init({
    lng: 'zh-CN',
    fallbackLng: 'zh-CN',
    supportedLngs: SUPPORTED_LANGUAGES,
    resources: { 'zh-CN': { translation: zhCN } },
    interpolation: { escapeValue: false }, // React 自己转义
    returnNull: false,
  });

  document.documentElement.lang = language;
  if (language !== 'zh-CN') await setLanguage(language);
  return i18next;
}

export { i18next };
