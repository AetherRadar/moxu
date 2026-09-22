/**
 * 查询键工厂。
 *
 * 全部收在一处，是为了让 mutation 能按前缀成片失效而不是逐个手写字符串。
 * 例如保存草稿后 `invalidateQueries({ queryKey: qk.project(id) })` 就能把该项目
 * 下所有派生数据（章节列表、统计、事实树、绑定）一次性标脏。
 */

export const qk = {
  health: ['health'] as const,

  projects: ['projects'] as const,
  project: (projectId: string) => ['projects', projectId] as const,

  volumes: (projectId: string) => ['projects', projectId, 'volumes'] as const,
  volumeSummary: (projectId: string, volumeId: string) =>
    ['projects', projectId, 'volumes', volumeId, 'summary'] as const,

  drafts: (projectId: string) => ['projects', projectId, 'drafts'] as const,
  draft: (projectId: string, chapter: string) => ['projects', projectId, 'drafts', chapter] as const,
  draftVersion: (projectId: string, chapter: string, version: string) =>
    ['projects', projectId, 'drafts', chapter, 'version', version] as const,
  draftVersions: (projectId: string, chapter: string) =>
    ['projects', projectId, 'drafts', chapter, 'versions'] as const,
  draftFinal: (projectId: string, chapter: string) =>
    ['projects', projectId, 'drafts', chapter, 'final'] as const,
  sceneBrief: (projectId: string, chapter: string) =>
    ['projects', projectId, 'drafts', chapter, 'scene-brief'] as const,
  chapterSummaries: (projectId: string, volumeId?: string) =>
    ['projects', projectId, 'drafts', 'summaries', volumeId ?? 'all'] as const,
  rewriteConfig: (projectId: string) => ['projects', projectId, 'rewrite-config'] as const,

  outline: (projectId: string) => ['projects', projectId, 'outline'] as const,

  characterCards: (projectId: string) => ['projects', projectId, 'cards', 'characters'] as const,
  worldCards: (projectId: string) => ['projects', projectId, 'cards', 'world'] as const,
  styleCard: (projectId: string) => ['projects', projectId, 'cards', 'style'] as const,
  extractCapabilities: (projectId: string) =>
    ['projects', projectId, 'cards', 'extract-candidates'] as const,

  facts: (projectId: string) => ['projects', projectId, 'canon', 'facts'] as const,
  factsTree: (projectId: string) => ['projects', projectId, 'facts', 'tree'] as const,
  timeline: (projectId: string, chapter?: string) =>
    ['projects', projectId, 'canon', 'timeline', chapter ?? 'all'] as const,
  characterStates: (projectId: string) => ['projects', projectId, 'canon', 'character-state'] as const,

  bindings: (projectId: string, chapter: string) => ['projects', projectId, 'bindings', chapter] as const,
  memoryPack: (projectId: string, chapter: string) =>
    ['projects', projectId, 'memory-pack', chapter] as const,
  relationships: (projectId: string) => ['projects', projectId, 'graph-indexes', 'relationships'] as const,

  sessionStatus: (projectId: string) => ['projects', projectId, 'session', 'status'] as const,

  llmProfiles: ['config', 'llm', 'profiles'] as const,
  llmAssignments: ['config', 'llm', 'assignments'] as const,
  llmProviders: ['config', 'llm', 'providers'] as const,
} as const;

/** 项目级前缀：删除/重置项目后用它把该项目所有缓存清干净。 */
export function projectScope(projectId: string): readonly unknown[] {
  return ['projects', projectId];
}
