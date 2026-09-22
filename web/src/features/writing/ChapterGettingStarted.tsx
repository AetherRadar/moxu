import { useState, type ReactNode } from 'react';
import { Feather, ListTree, Sparkles, X } from 'lucide-react';
import { formatChapterLabel } from '@/lib/chapter-id';

export function ChapterGettingStarted({ chapter, projectId, onWrite, onOutline, onAssistant }: { chapter: string; projectId: string; onWrite: () => void; onOutline: () => void; onAssistant: () => void }): ReactNode {
  const key = `moxu:chapter-start:${projectId}:${chapter}`;
  const [dismissed, setDismissed] = useState(() => { try { return localStorage.getItem(key) === 'done'; } catch { return false; } });
  if (dismissed) return null;
  const finish = (action: () => void): void => { setDismissed(true); try { localStorage.setItem(key, 'done'); } catch { /* optional preference */ } action(); };
  return <section className="mx-auto mt-4 max-w-[40em] rounded-md border border-ink-800 bg-ink-950/60 p-4" aria-label="开始这一章">
    <div className="flex items-start justify-between gap-3"><div><p className="text-2xs tracking-[0.14em] text-ink-500 uppercase">章节已准备好</p><h2 className="mt-1 font-serif text-lg text-ink-100">{formatChapterLabel(chapter)}，想从哪里开始？</h2></div><button type="button" onClick={() => finish(onWrite)} aria-label="收起引导"><X className="size-4 text-ink-500" /></button></div>
    <div className="mt-4 grid gap-2 sm:grid-cols-3"><GuideOption icon={<Feather />} title="直接落笔" hint="写下第一句" onClick={() => finish(onWrite)} /><GuideOption icon={<ListTree />} title="先列提纲" hint="梳理事件目标" onClick={() => finish(onOutline)} /><GuideOption icon={<Sparkles />} title="和助手起草" hint="描述这一章想写什么" onClick={() => finish(onAssistant)} /></div>
  </section>;
}

function GuideOption({ icon, title, hint, onClick }: { icon: ReactNode; title: string; hint: string; onClick: () => void }): ReactNode {
  return <button type="button" onClick={onClick} className="flex items-center gap-2 rounded-md border border-ink-800 px-2.5 py-2 text-left hover:border-celadon-700 hover:bg-ink-900"><span className="text-celadon-500 [&_svg]:size-4">{icon}</span><span><span className="block text-xs text-ink-200">{title}</span><span className="mt-0.5 block text-2xs text-ink-500">{hint}</span></span></button>;
}
