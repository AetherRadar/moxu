import { useState, type ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { canonApi, qk } from '@/api';
import type { JsonRecord } from '@/api/types';
import { ApiError } from '@/lib/http';
import { Button, Dialog, Field, Input, Textarea } from '@/components/ui';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function lines(value: unknown): string {
  return Array.isArray(value) ? value.map(String).join('\n') : '';
}

function relationshipLines(value: unknown): string {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return '';
  return Object.entries(value as Record<string, unknown>)
    .map(([name, relation]) => name + '：' + String(relation))
    .join('\n');
}

function splitLines(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '');
}

function parseRelationships(value: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of splitLines(value)) {
    const sep = line.search(/[：:]/);
    if (sep <= 0) continue;
    out[line.slice(0, sep).trim()] = line.slice(sep + 1).trim();
  }
  return out;
}

/**
 * 人物状态编辑。后端把每次保存追加进 canon/character_state.jsonl 历史并重建快照，
 * 所以编辑语义是"写入一条新的最新状态"，不会原地改历史。
 */
export function CharacterStateDialog({
  projectId,
  entry,
  onClose,
}: {
  projectId: string;
  /** 该人物最新一条状态记录 */
  entry: JsonRecord;
  onClose: () => void;
}): ReactNode {
  const client = useQueryClient();
  const name = String(entry.character ?? entry.name ?? '');

  const [state, setState] = useState(text(entry.state ?? entry.summary));
  const [location, setLocation] = useState(text(entry.location));
  const [emotion, setEmotion] = useState(text(entry.emotional_state ?? entry.emotion));
  const [goals, setGoals] = useState(lines(entry.goals));
  const [injuries, setInjuries] = useState(lines(entry.injuries));
  const [inventory, setInventory] = useState(lines(entry.inventory));
  const [relationships, setRelationships] = useState(relationshipLines(entry.relationships));
  const [lastSeen, setLastSeen] = useState(text(entry.last_seen ?? entry.source));
  const save = useMutation({
    mutationFn: () => {
      const payload: JsonRecord = { character: name, state: state.trim() };
      if (location.trim() !== '') payload.location = location.trim();
      if (emotion.trim() !== '') payload.emotional_state = emotion.trim();
      const goalList = splitLines(goals);
      if (goalList.length > 0) payload.goals = goalList;
      const injuryList = splitLines(injuries);
      if (injuryList.length > 0) payload.injuries = injuryList;
      const inventoryList = splitLines(inventory);
      if (inventoryList.length > 0) payload.inventory = inventoryList;
      const relationMap = parseRelationships(relationships);
      if (Object.keys(relationMap).length > 0) payload.relationships = relationMap;
      if (lastSeen.trim() !== '') payload.last_seen = lastSeen.trim();
      return canonApi.saveCharacterState(projectId, name, payload);
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.characterStates(projectId) });
      toast.success('人物状态已保存');
      onClose();
    },
    onError: (error) => toast.error(reason(error, '保存失败')),
  });

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={'编辑人物状态 · ' + name}
      description="保存会追加一条新的最新状态，历史记录保留。"
      size="md"
      dismissible={!save.isPending}
      footer={
        <>
          <Button tone="outline" onClick={onClose} disabled={save.isPending}>
            取消
          </Button>
          <Button
            tone="primary"
            loading={save.isPending}
            disabled={state.trim() === ''}
            onClick={() => save.mutate()}
          >
            保存
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Field label="状态概述">
          {(props) => (
            <Textarea
              {...props}
              value={state}
              onChange={(event) => setState(event.target.value)}
              rows={3}
              placeholder="当前处境、心境、与主线的关系…"
            />
          )}
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="位置">
            {(props) => (
              <Input {...props} value={location} onChange={(event) => setLocation(event.target.value)} />
            )}
          </Field>
          <Field label="情绪">
            {(props) => (
              <Input {...props} value={emotion} onChange={(event) => setEmotion(event.target.value)} />
            )}
          </Field>
        </div>
        <Field label="目标" hint="一行一条">
          {(props) => (
            <Textarea {...props} value={goals} onChange={(event) => setGoals(event.target.value)} rows={2} />
          )}
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="伤势" hint="一行一条">
            {(props) => (
              <Textarea
                {...props}
                value={injuries}
                onChange={(event) => setInjuries(event.target.value)}
                rows={2}
              />
            )}
          </Field>
          <Field label="随身物品" hint="一行一条">
            {(props) => (
              <Textarea
                {...props}
                value={inventory}
                onChange={(event) => setInventory(event.target.value)}
                rows={2}
              />
            )}
          </Field>
        </div>
        <Field label="关系" hint="一行一条，形如：林雪：互不信任的盟友">
          {(props) => (
            <Textarea
              {...props}
              value={relationships}
              onChange={(event) => setRelationships(event.target.value)}
              rows={2}
            />
          )}
        </Field>
        <Field label="最近出现" hint="章节号，如 V1C12">
          {(props) => (
            <Input {...props} value={lastSeen} onChange={(event) => setLastSeen(event.target.value)} />
          )}
        </Field>
      </div>
    </Dialog>
  );
}
