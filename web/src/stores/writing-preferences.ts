import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

export interface WritingAppearance {
  font: 'serif' | 'sans';
  fontSize: number;
  lineHeight: number;
  width: 'compact' | 'comfortable' | 'wide';
}

export const defaultWritingAppearance: WritingAppearance = {
  font: 'serif',
  fontSize: 18,
  lineHeight: 2,
  width: 'comfortable',
};

interface WritingPreferences {
  appearance: WritingAppearance;
  update: (patch: Partial<WritingAppearance>) => void;
  reset: () => void;
}

export const useWritingPreferences = create<WritingPreferences>()(
  persist(
    (set) => ({
      appearance: defaultWritingAppearance,
      update: (patch) => set(({ appearance }) => ({ appearance: { ...appearance, ...patch } })),
      reset: () => set({ appearance: defaultWritingAppearance }),
    }),
    {
      name: 'moxu:writing-preferences',
      storage: createJSONStorage(() => localStorage),
      partialize: ({ appearance }) => ({ appearance }),
    },
  ),
);
