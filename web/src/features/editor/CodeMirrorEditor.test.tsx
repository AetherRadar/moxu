// @vitest-environment jsdom
import { createRef } from 'react';
import { act, cleanup, render } from '@testing-library/react';
import { EditorView } from '@codemirror/view';
import { undo } from '@codemirror/commands';
import { afterEach, expect, it } from 'vitest';
import { useLayoutStore } from '@/stores/layout';
import { useWritingPreferences } from '@/stores/writing-preferences';
import { CodeMirrorEditor, type EditorHandle } from './CodeMirrorEditor';

afterEach(() => {
  cleanup();
  useWritingPreferences.getState().reset();
});

it('keeps unsaved text, selection and undo history when changing theme', () => {
  useLayoutStore.setState({ theme: 'dark' });
  const ref = createRef<EditorHandle>();
  const { container } = render(<CodeMirrorEditor ref={ref} docId="chapter" initialDoc="原文" />);
  const view = EditorView.findFromDOM(container.querySelector('.cm-editor')!)!;
  act(() => view.dispatch({ changes: { from: 2, insert: '续写' }, selection: { anchor: 4 } }));
  act(() => useLayoutStore.getState().toggleTheme());
  expect(EditorView.findFromDOM(container.querySelector('.cm-editor')!)).toBe(view);
  expect(ref.current?.getDoc()).toBe('原文续写');
  expect(view.state.selection.main.head).toBe(4);
  act(() => {
    undo(view);
  });
  expect(ref.current?.getDoc()).toBe('原文');
});

it('keeps editor, selection and undo when changing typography', () => {
  const ref = createRef<EditorHandle>();
  const { container } = render(<CodeMirrorEditor ref={ref} docId="chapter" initialDoc="开头" />);
  const view = EditorView.findFromDOM(container.querySelector('.cm-editor')!)!;
  act(() => view.dispatch({ changes: { from: 2, insert: '未存内容' }, selection: { anchor: 2, head: 6 } }));
  act(() =>
    useWritingPreferences
      .getState()
      .update({ font: 'sans', fontSize: 23, lineHeight: 2.4, width: 'compact' }),
  );
  expect(EditorView.findFromDOM(container.querySelector('.cm-editor')!)).toBe(view);
  expect(ref.current?.getDoc()).toBe('开头未存内容');
  expect(ref.current?.selection()).toEqual({ from: 2, to: 6, text: '未存内容' });
  expect(JSON.parse(localStorage.getItem('moxu:writing-preferences')!).state.appearance.fontSize).toBe(23);
  act(() => {
    undo(view);
  });
  expect(ref.current?.getDoc()).toBe('开头');
});
