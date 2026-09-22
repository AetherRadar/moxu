import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { EditorState, type Extension } from '@codemirror/state';
import {
  EditorView,
  drawSelection,
  highlightActiveLine,
  keymap,
  placeholder as cmPlaceholder,
} from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { highlightSelectionMatches, searchKeymap } from '@codemirror/search';
import { cn } from '@/lib/cn';

export interface EditorSelection {
  from: number;
  to: number;
  text: string;
}

/** 暴露给父组件的命令式句柄：流式追加、整文替换都走这里，不经过 React state。 */
export interface EditorHandle {
  getDoc: () => string;
  /** 整文替换，作为单个撤销步 */
  setDoc: (text: string) => void;
  /** 流式生成时往文末追加 */
  append: (text: string) => void;
  selection: () => EditorSelection;
  replaceRange: (from: number, to: number, text: string) => void;
  focus: () => void;
}

/**
 * 小说正文编辑器。
 *
 * CodeMirror 而不是 textarea/contenteditable：
 *   - 撤销栈、搜索面板、选区 API 都是现成的
 *   - updateListener 只在我们需要时通知 React，长文档打字不卡
 *   - docId 变化才重建视图；正文本身不进 React state
 *
 * 主题随全局配色，正文用衬线 + 大行高，栏宽收敛到适合中文阅读的量度。
 */
export const CodeMirrorEditor = forwardRef<
  EditorHandle,
  {
    /** 文档标识（章节 ID）。变化时重建编辑器。 */
    docId: string;
    initialDoc: string;
    readOnly?: boolean;
    placeholder?: string;
    onDocChange?: (doc: string) => void;
    onSelectionChange?: (selection: EditorSelection) => void;
    className?: string;
  }
>(function CodeMirrorEditor(
  { docId, initialDoc, readOnly = false, placeholder, onDocChange, onSelectionChange, className },
  ref,
) {
  const hostRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);

  // 回调放 ref：父组件每次渲染的新函数不该触发编辑器重建
  const onDocChangeRef = useRef(onDocChange);
  onDocChangeRef.current = onDocChange;
  const onSelectionChangeRef = useRef(onSelectionChange);
  onSelectionChangeRef.current = onSelectionChange;

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return;

    const extensions: Extension[] = [
      drawSelection(),
      highlightActiveLine(),
      history(),
      EditorView.lineWrapping,
      keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap, indentWithTab]),
      highlightSelectionMatches(),
      EditorState.readOnly.of(readOnly),
      EditorView.theme(
        {
          '&': {
            height: '100%',
            fontSize: '1.0625rem',
            backgroundColor: 'transparent',
          },
          '.cm-content': {
            fontFamily: 'var(--font-serif)',
            lineHeight: 'var(--prose-leading)',
            letterSpacing: '0.01em',
            padding: '1.5rem 0 40vh 0',
            caretColor: 'var(--color-celadon-400)',
            // 中文阅读舒服的行宽；居中靠 padding 而不是 max-width，
            // 否则光标定位和选区高亮会在宽屏下偏移
            paddingInline: 'max(2rem, calc(50% - 24em))',
          },
          '.cm-scroller': {
            overflow: 'auto',
            fontFamily: 'var(--font-serif)',
          },
          '.cm-line': { paddingInline: '0.125rem' },
          '&.cm-focused': { outline: 'none' },
          '.cm-gutters': {
            backgroundColor: 'transparent',
            border: 'none',
          },
          '.cm-activeLine': {
            backgroundColor: 'color-mix(in oklch, var(--color-ink-850) 55%, transparent)',
          },
          '.cm-selectionMatch': {
            backgroundColor: 'color-mix(in oklch, var(--color-gamboge-500) 22%, transparent)',
          },
          '.cm-searchMatch': {
            backgroundColor: 'color-mix(in oklch, var(--color-celadon-600) 35%, transparent)',
          },
          '.cm-searchMatch-selected': {
            backgroundColor: 'color-mix(in oklch, var(--color-celadon-500) 50%, transparent)',
          },
          '.cm-panels': {
            backgroundColor: 'var(--color-ink-900)',
            color: 'var(--color-ink-200)',
            borderColor: 'var(--color-ink-800)',
          },
          '.cm-panels input, .cm-panels button': {
            fontFamily: 'var(--font-sans)',
            fontSize: '0.75rem',
          },
          '.cm-placeholder': { color: 'var(--color-ink-600)' },
        },
        { dark: true },
      ),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          onDocChangeRef.current?.(update.state.doc.toString());
        }
        if (update.selectionSet || update.docChanged) {
          const range = update.state.selection.main;
          onSelectionChangeRef.current?.({
            from: range.from,
            to: range.to,
            text: update.state.sliceDoc(range.from, range.to),
          });
        }
      }),
    ];
    if (placeholder !== undefined) extensions.push(cmPlaceholder(placeholder));

    const view = new EditorView({
      state: EditorState.create({ doc: initialDoc, extensions }),
      parent: host,
    });
    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // docId 是唯一的重建依据：readOnly/回调变化不值得换整个文档视图
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docId]);

  useImperativeHandle(
    ref,
    () => ({
      getDoc: () => viewRef.current?.state.doc.toString() ?? '',
      setDoc: (text) => {
        const view = viewRef.current;
        if (view === null) return;
        view.dispatch({
          changes: { from: 0, to: view.state.doc.length, insert: text },
          // 用户自己「接受草稿/恢复版本」该进撤销栈，程序性替换也保留一步撤销
        });
      },
      append: (text) => {
        const view = viewRef.current;
        if (view === null || text === '') return;
        view.dispatch({
          changes: { from: view.state.doc.length, insert: text },
          // 流式追加时把视口钉在文末，用户看着字往外蹦
          effects: EditorView.scrollIntoView(view.state.doc.length, { y: 'end' }),
        });
      },
      selection: () => {
        const view = viewRef.current;
        if (view === null) return { from: 0, to: 0, text: '' };
        const range = view.state.selection.main;
        return {
          from: range.from,
          to: range.to,
          text: view.state.sliceDoc(range.from, range.to),
        };
      },
      replaceRange: (from, to, text) => {
        viewRef.current?.dispatch({ changes: { from, to, insert: text } });
      },
      focus: () => viewRef.current?.focus(),
    }),
    [],
  );

  return <div ref={hostRef} className={cn('h-full min-h-0 overflow-hidden', className)} />;
});
