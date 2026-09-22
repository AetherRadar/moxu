import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

/**
 * 当前工作位置：打开的项目、卷、章，以及每个项目最后停留的章节。
 * 只放"我在哪"，不放"数据是什么"——数据一律由 TanStack Query 管。
 */

interface WorkspaceState {
  projectId: string | null;
  chapter: string | null;
  /** 大纲树里展开的卷，Set 在 persist 时转成数组 */
  expandedVolumes: string[];
  /** projectId -> 上次打开的章节，重进项目直接回到原处 */
  lastChapter: Record<string, string>;

  openProject: (projectId: string) => void;
  closeProject: () => void;
  openChapter: (chapter: string | null) => void;
  toggleVolume: (volumeId: string) => void;
  setVolumeExpanded: (volumeId: string, expanded: boolean) => void;
  expandVolumes: (volumeIds: string[]) => void;
  resumeChapter: (projectId: string) => string | null;
}

export const useWorkspaceStore = create<WorkspaceState>()(
  persist(
    (set, get) => ({
      projectId: null,
      chapter: null,
      expandedVolumes: [],
      lastChapter: {},

      openProject: (projectId) => {
        if (get().projectId === projectId) return;
        set({ projectId, chapter: get().lastChapter[projectId] ?? null, expandedVolumes: [] });
      },

      closeProject: () => set({ projectId: null, chapter: null, expandedVolumes: [] }),

      openChapter: (chapter) => {
        const { projectId, lastChapter } = get();
        if (!projectId || chapter === null) {
          set({ chapter });
          return;
        }
        set({ chapter, lastChapter: { ...lastChapter, [projectId]: chapter } });
      },

      toggleVolume: (volumeId) => {
        const expanded = get().expandedVolumes;
        set({
          expandedVolumes: expanded.includes(volumeId)
            ? expanded.filter((id) => id !== volumeId)
            : [...expanded, volumeId],
        });
      },

      setVolumeExpanded: (volumeId, expanded) => {
        const current = get().expandedVolumes;
        const has = current.includes(volumeId);
        if (expanded === has) return;
        set({ expandedVolumes: expanded ? [...current, volumeId] : current.filter((id) => id !== volumeId) });
      },

      expandVolumes: (volumeIds) => {
        const merged = new Set([...get().expandedVolumes, ...volumeIds]);
        set({ expandedVolumes: [...merged] });
      },

      resumeChapter: (projectId) => get().lastChapter[projectId] ?? null,
    }),
    {
      name: 'moxu:workspace',
      version: 1,
      storage: createJSONStorage(() => localStorage),
      partialize: ({ projectId, lastChapter }) => ({ projectId, lastChapter }),
    },
  ),
);

/** 常用选择器，避免在组件里写内联箭头函数导致每次渲染都是新引用。 */
export const selectProjectId = (state: WorkspaceState): string | null => state.projectId;
export const selectChapter = (state: WorkspaceState): string | null => state.chapter;
