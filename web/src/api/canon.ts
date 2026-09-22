import { http, seg } from '@/lib/http';
import type { CharacterState, Fact, JsonRecord, TimelineEvent } from './types';

const base = (projectId: string): string => `/projects/${seg(projectId)}/canon`;

/**
 * 正典域。
 *
 * 事实 ID 形如 `F12`，但也有手工录入的自由 ID；后端为此准备了 `/facts/by-id/{id}`
 * 这条不受正则约束的路由。这里一律走 by-id，免得自由 ID 撞上 `^F\d+$` 的路由约束。
 */
export const canonApi = {
  facts: (projectId: string) => http.get<Fact[]>(`${base(projectId)}/facts`),

  createFact: (projectId: string, body: Partial<Fact>) =>
    http.post<Fact>(`${base(projectId)}/facts`, { body }),

  updateFact: (projectId: string, factId: string, body: Partial<Fact>) =>
    http.put<Fact>(`${base(projectId)}/facts/by-id/${seg(factId)}`, { body }),

  deleteFact: (projectId: string, factId: string) =>
    http.delete<JsonRecord>(`${base(projectId)}/facts/by-id/${seg(factId)}`),

  timeline: (projectId: string, chapter?: string) =>
    http.get<TimelineEvent[]>(`${base(projectId)}/timeline`, {
      query: chapter ? { chapter } : undefined,
    }),

  createTimelineEvent: (projectId: string, body: TimelineEvent) =>
    http.post<JsonRecord>(`${base(projectId)}/timeline`, { body }),

  updateTimelineEvent: (projectId: string, eventId: string, body: TimelineEvent) =>
    http.put<JsonRecord>(`${base(projectId)}/timeline/${seg(eventId)}`, { body }),

  deleteTimelineEvent: (projectId: string, eventId: string) =>
    http.delete<JsonRecord>(`${base(projectId)}/timeline/${seg(eventId)}`),

  characterStates: (projectId: string) => http.get<CharacterState[]>(`${base(projectId)}/character-state`),

  characterState: (projectId: string, name: string) =>
    http.get<CharacterState>(`${base(projectId)}/character-state/${seg(name)}`),

  saveCharacterState: (projectId: string, name: string, body: CharacterState) =>
    http.put<JsonRecord>(`${base(projectId)}/character-state/${seg(name)}`, { body }),
};

/** 事实树是另一个控制器，按卷/章聚合，用于百科视图。 */
export const factsTreeApi = {
  get: (projectId: string) => http.get<JsonRecord>(`/projects/${seg(projectId)}/facts/tree`),
};
