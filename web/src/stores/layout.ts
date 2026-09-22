import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

/**
 * 界面布局状态。
 *
 * 单独成一个 store，是为了让"切章"不触发布局相关组件重渲染——
 * 上游把布局、当前章节、会话进度全塞在一个 Context 里，流式生成时
 * 每来一个片段整棵子树都要 reconcile。
 */

export type SideView = 'explorer' | 'outline' | 'cards' | 'facts' | 'search' | 'agents';

export type Theme = 'dark' | 'light';

interface LayoutState {
  theme: Theme;
  sideView: SideView;
  sideVisible: boolean;
  sideWidth: number;
  auxVisible: boolean;
  auxWidth: number;
  /** 沉浸写作：隐藏两侧面板，只留正文 */
  focusMode: boolean;

  setTheme: (theme: Theme) => void;
  toggleTheme: () => void;
  setSideView: (view: SideView) => void;
  /** 点击当前已激活的图标时收起面板，与常见 IDE 行为一致 */
  activateSideView: (view: SideView) => void;
  setSideWidth: (width: number) => void;
  toggleSide: () => void;
  setAuxVisible: (visible: boolean) => void;
  setAuxWidth: (width: number) => void;
  toggleFocusMode: () => void;
}

const SIDE_MIN = 200;
const SIDE_MAX = 620;
const AUX_MIN = 260;
const AUX_MAX = 760;

const clamp = (value: number, min: number, max: number): number =>
  Math.min(max, Math.max(min, Math.round(value)));

export const useLayoutStore = create<LayoutState>()(
  persist(
    (set, get) => ({
      theme: 'dark',
      sideView: 'explorer',
      sideVisible: true,
      sideWidth: 288,
      auxVisible: false,
      auxWidth: 380,
      focusMode: false,

      setTheme: (theme) => set({ theme }),
      toggleTheme: () => set({ theme: get().theme === 'dark' ? 'light' : 'dark' }),
      setSideView: (sideView) => set({ sideView, sideVisible: true }),
      activateSideView: (view) => {
        const { sideView, sideVisible } = get();
        if (sideView === view && sideVisible) set({ sideVisible: false });
        else set({ sideView: view, sideVisible: true });
      },
      setSideWidth: (width) => set({ sideWidth: clamp(width, SIDE_MIN, SIDE_MAX) }),
      toggleSide: () => set({ sideVisible: !get().sideVisible }),
      setAuxVisible: (auxVisible) => set({ auxVisible }),
      setAuxWidth: (width) => set({ auxWidth: clamp(width, AUX_MIN, AUX_MAX) }),
      toggleFocusMode: () => set({ focusMode: !get().focusMode }),
    }),
    {
      name: 'moxu:layout',
      version: 1,
      storage: createJSONStorage(() => localStorage),
      // 宽度和面板选择值得记住，focusMode 每次进来都该是关的
      partialize: ({ theme, sideView, sideVisible, sideWidth, auxVisible, auxWidth }) => ({
        theme,
        sideView,
        sideVisible,
        sideWidth,
        auxVisible,
        auxWidth,
      }),
    },
  ),
);

export const layoutBounds = { SIDE_MIN, SIDE_MAX, AUX_MIN, AUX_MAX } as const;
