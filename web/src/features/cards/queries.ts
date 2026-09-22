import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { canonApi, cardsApi, factsTreeApi, qk } from '@/api';
import type { CharacterCard, Fact, JsonRecord, StyleCard, WorldCard } from '@/api/types';
import { ApiError } from '@/lib/http';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export function useCharacterCards(projectId: string) {
  return useQuery({
    queryKey: qk.characterCards(projectId),
    queryFn: () => cardsApi.characters(projectId),
    enabled: projectId !== '',
  });
}

export function useWorldCards(projectId: string) {
  return useQuery({
    queryKey: qk.worldCards(projectId),
    queryFn: () => cardsApi.world(projectId),
    enabled: projectId !== '',
  });
}

export function useStyleCard(projectId: string) {
  return useQuery({
    queryKey: qk.styleCard(projectId),
    queryFn: () => cardsApi.style(projectId),
    enabled: projectId !== '',
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
  });
}

function useCardMutation<T>(
  keys: readonly unknown[],
  fn: (vars: T) => Promise<unknown>,
  success: string,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys });
      toast.success(success);
    },
    onError: (error) => toast.error(reason(error, '操作失败')),
  });
}

export function useSaveCharacterCard(projectId: string) {
  return useCardMutation<{ name: string; card: CharacterCard; create?: boolean }>(
    qk.characterCards(projectId),
    ({ name, card, create }) =>
      create ? cardsApi.createCharacter(projectId, card) : cardsApi.updateCharacter(projectId, name, card),
    '人物卡已保存',
  );
}

export function useDeleteCharacterCard(projectId: string) {
  return useCardMutation<string>(
    qk.characterCards(projectId),
    (name) => cardsApi.deleteCharacter(projectId, name),
    '人物卡已删除',
  );
}

export function useSaveWorldCard(projectId: string) {
  return useCardMutation<{ name: string; card: WorldCard; create?: boolean }>(
    qk.worldCards(projectId),
    ({ name, card, create }) =>
      create ? cardsApi.createWorldCard(projectId, card) : cardsApi.updateWorldCard(projectId, name, card),
    '世界卡已保存',
  );
}

export function useDeleteWorldCard(projectId: string) {
  return useCardMutation<string>(
    qk.worldCards(projectId),
    (name) => cardsApi.deleteWorldCard(projectId, name),
    '世界卡已删除',
  );
}

export function useSaveStyleCard(projectId: string) {
  return useCardMutation<StyleCard>(
    qk.styleCard(projectId),
    (card) => cardsApi.saveStyle(projectId, card),
    '文风卡已保存',
  );
}

/** AI 生成卡片描述：返回 {description}，调用方自己决定填到哪。 */
export function useGenerateCardDescription(projectId: string) {
  return useMutation({
    mutationFn: (body: { card_type: 'character' | 'world' | 'style'; name: string; style_hint?: string; note?: string }) =>
      cardsApi.generateDescription(projectId, body),
    onError: (error) => toast.error(reason(error, '生成描述失败')),
  });
}

export function useFacts(projectId: string) {
  return useQuery({
    queryKey: qk.facts(projectId),
    queryFn: () => canonApi.facts(projectId),
    enabled: projectId !== '',
  });
}

export function useFactsTree(projectId: string) {
  return useQuery({
    queryKey: qk.factsTree(projectId),
    queryFn: () => factsTreeApi.get(projectId),
    enabled: projectId !== '',
  });
}

export function useTimeline(projectId: string) {
  return useQuery({
    queryKey: qk.timeline(projectId),
    queryFn: () => canonApi.timeline(projectId),
    enabled: projectId !== '',
  });
}

export function useCharacterStates(projectId: string) {
  return useQuery({
    queryKey: qk.characterStates(projectId),
    queryFn: () => canonApi.characterStates(projectId),
    enabled: projectId !== '',
  });
}

export function useSaveFact(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, fact, create }: { id: string; fact: Partial<Fact>; create?: boolean }) =>
      create ? canonApi.createFactManual(projectId, fact) : canonApi.updateFact(projectId, id, fact),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.facts(projectId) });
      void client.invalidateQueries({ queryKey: qk.factsTree(projectId) });
      toast.success('事实已保存');
    },
    onError: (error) => toast.error(reason(error, '保存事实失败')),
  });
}

export function useDeleteFact(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => canonApi.deleteFact(projectId, id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.facts(projectId) });
      void client.invalidateQueries({ queryKey: qk.factsTree(projectId) });
      toast.success('事实已删除');
    },
    onError: (error) => toast.error(reason(error, '删除事实失败')),
  });
}

export function useCreateTimelineEvent(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (event: JsonRecord) => canonApi.createTimelineEvent(projectId, event),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.timeline(projectId) });
      toast.success('时间线事件已添加');
    },
    onError: (error) => toast.error(reason(error, '添加失败')),
  });
}
