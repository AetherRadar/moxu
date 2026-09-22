import { useState, type ReactNode } from 'react';
import { BookOpen, ChevronRight, Feather, PenLine, Sparkles, X } from 'lucide-react';
import { Button } from '@/components/ui';

/**
 * 写作中的轻量助手入口。
 *
 * WenShape 的浮动助手把“开始写章 / 看简报 / 修订选区”放在正文旁边。
 * 这里不复制一套聊天状态，而是把入口连接到墨序已有的对话框，避免出现
 * 两个互相不同步的 AI 会话。
 */
export function WritingCompanion({ hasSelection, onDraft, onBrief, onRevise }: { hasSelection: boolean; onDraft: () => void; onBrief: () => void; onRevise: () => void }): ReactNode {
  const [open, setOpen] = useState(false);
  return (
    <div className="writing-companion fixed right-5 bottom-12 z-30 flex flex-col items-end gap-2">
      {open && (
        <section className="w-64 overflow-hidden rounded-lg border border-ink-800 bg-ink-900 shadow-float" aria-label="写作助手">
          <header className="flex items-center gap-2 border-b border-ink-800 px-3 py-2.5"><Sparkles className="size-4 text-celadon-500" /><span className="flex-1 text-xs font-medium text-ink-100">写作助手</span><button type="button" aria-label="关闭助手" onClick={() => setOpen(false)}><X className="size-4 text-ink-500" /></button></header>
          <div className="flex flex-col gap-1.5 p-2">
            <CompanionAction icon={<Feather />} title="和助手一起起草" hint="从目标和人物开始" onClick={onDraft} />
            <CompanionAction icon={<BookOpen />} title="查看本章简报" hint="目标、冲突和世界约束" onClick={onBrief} />
            <CompanionAction icon={<PenLine />} title="修订选中内容" hint={hasSelection ? '根据指令生成修改建议' : '先在正文中选择一段'} disabled={!hasSelection} onClick={onRevise} />
          </div>
          <p className="border-t border-ink-800 px-3 py-2 text-2xs leading-relaxed text-ink-500">助手只生成建议，采用前会先显示差异。</p>
        </section>
      )}
      <Button tone={open ? 'outline' : 'primary'} size="sm" icon={open ? <X /> : <Sparkles />} onClick={() => setOpen((value) => !value)} aria-expanded={open}>
        {open ? '收起助手' : '写作助手'}
      </Button>
    </div>
  );
}

function CompanionAction({ icon, title, hint, disabled = false, onClick }: { icon: ReactNode; title: string; hint: string; disabled?: boolean; onClick: () => void }): ReactNode {
  return <button type="button" disabled={disabled} onClick={onClick} className="group flex items-center gap-2 rounded-md px-2.5 py-2 text-left hover:bg-ink-850 disabled:cursor-not-allowed disabled:opacity-45"><span className="text-celadon-500 [&_svg]:size-4">{icon}</span><span className="min-w-0 flex-1"><span className="block text-xs text-ink-200">{title}</span><span className="mt-0.5 block truncate text-2xs text-ink-500">{hint}</span></span><ChevronRight className="size-3.5 text-ink-600 transition-transform group-hover:translate-x-0.5" /></button>;
}
