import { useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { draftsApi, qk } from '@/api';
import type { SceneBrief } from '@/api/types';
import { ApiError } from '@/lib/http';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, Dialog, EmptyState, Field, Input, SkeletonList, Textarea } from '@/components/ui';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

function linesToText(value: string[] | undefined): string {
  return (value ?? []).join('\n');
}

function textToLines(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '');
}

const EMPTY: SceneBrief = {
  chapter: null,
  title: null,
  goal: null,
  conflict: null,
  key_beats: [],
  characters: [],
  timeline_context: {},
  world_constraints: [],
  facts: [],
  style_reminder: null,
  forbidden: [],
};

/** 没有简报（404）是正常状态：允许手工写一份。 */
function useBriefQuery(projectId: string, chapter: string) {
  return useQuery({
    queryKey: qk.sceneBrief(projectId, chapter),
    queryFn: () => draftsApi.sceneBrief(projectId, chapter),
    retry: false,
  });
}

/**
 * 本章场景简报的查看与编辑。
 *
 * 简报由会话生成，也可以手工改写后落盘；人物表与时间线上下文属于派生数据，
 * 这里只读保留，避免一次保存把结构化上下文抹平。
 */
export function SceneBriefDialog({
  projectId,
  chapter,
  onClose,
}: {
  projectId: string;
  chapter: string;
  onClose: () => void;
}): ReactNode {
  const client = useQueryClient();
  const brief = useBriefQuery(projectId, chapter);

  const [goal, setGoal] = useState('');
  const [conflict, setConflict] = useState('');
  const [styleReminder, setStyleReminder] = useState('');
  const [keyBeats, setKeyBeats] = useState('');
  const [facts, setFacts] = useState('');
  const [worldConstraints, setWorldConstraints] = useState('');
  const [forbidden, setForbidden] = useState('');
  const [loaded, setLoaded] = useState<SceneBrief | null>(null);

  // 数据到达时初始化表单：渲染期 setState 是 React 认可的「随 props 调整」模式，
  // 比 useEffect 少一次提交，也不会被 strict 双跑弄脏。
  if (brief.data !== undefined && loaded === null) {
    const data = brief.data;
    setLoaded(data);
    setGoal(data.goal ?? '');
    setConflict(data.conflict ?? '');
    setStyleReminder(data.style_reminder ?? '');
    setKeyBeats(linesToText(data.key_beats));
    setFacts(linesToText(data.facts));
    setWorldConstraints(linesToText(data.world_constraints));
    setForbidden(linesToText(data.forbidden));
  }
  const save = useMutation({
    mutationFn: () => {
      const current = loaded ?? EMPTY;
      const body: SceneBrief = {
        ...current,
        chapter: current.chapter ?? chapter,
        goal: goal.trim() === '' ? null : goal.trim(),
        conflict: conflict.trim() === '' ? null : conflict.trim(),
        style_reminder: styleReminder.trim() === '' ? null : styleReminder.trim(),
        key_beats: textToLines(keyBeats),
        facts: textToLines(facts),
        world_constraints: textToLines(worldConstraints),
        forbidden: textToLines(forbidden),
      };
      return draftsApi.saveSceneBrief(projectId, chapter, body);
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.sceneBrief(projectId, chapter) });
      toast.success('本章简报已保存');
      onClose();
    },
    onError: (error) => toast.error(reason(error, '保存简报失败')),
  });

  const missing = brief.isError && brief.error instanceof ApiError && brief.error.isNotFound;
  const current = loaded ?? EMPTY;
  const characterNames = current.characters
    .map((item) => Object.keys(item)[0] ?? '')
    .filter((name) => name !== '');
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={'本章简报 · ' + formatChapterLabel(chapter)}
      description="写章会话按这份简报准备上下文；改完保存，下一次会话即生效。"
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <Button tone="outline" onClick={onClose} disabled={save.isPending}>
            取消
          </Button>
          <Button tone="primary" loading={save.isPending} onClick={() => save.mutate()}>
            保存简报
          </Button>
        </>
      }
    >
      {brief.isPending ? (
        <SkeletonList rows={8} />
      ) : missing && loaded === null ? (
        <EmptyState
          title="还没有场景简报"
          description="可以先手工写一份保存，或跑一次 AI 写章会话自动生成。"
        />
      ) : (
        <div className="flex flex-col gap-3">
          {characterNames.length > 0 && (
            <p className="text-2xs text-ink-600">出场人物（只读保留）：{characterNames.join('、')}</p>
          )}
          <div className="grid grid-cols-2 gap-3">
            <Field label="场景目标">
              {(props) => (
                <Textarea {...props} value={goal} onChange={(e) => setGoal(e.target.value)} rows={3} />
              )}
            </Field>
            <Field label="核心冲突">
              {(props) => (
                <Textarea
                  {...props}
                  value={conflict}
                  onChange={(e) => setConflict(e.target.value)}
                  rows={3}
                />
              )}
            </Field>
          </div>
          <Field label="关键节拍" hint="一行一个">
            {(props) => (
              <Textarea {...props} value={keyBeats} onChange={(e) => setKeyBeats(e.target.value)} rows={4} />
            )}
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="可用事实" hint="一行一条">
              {(props) => (
                <Textarea {...props} value={facts} onChange={(e) => setFacts(e.target.value)} rows={3} />
              )}
            </Field>
            <Field label="世界约束" hint="一行一条">
              {(props) => (
                <Textarea
                  {...props}
                  value={worldConstraints}
                  onChange={(e) => setWorldConstraints(e.target.value)}
                  rows={3}
                />
              )}
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="文风提醒">
              {(props) => (
                <Input {...props} value={styleReminder} onChange={(e) => setStyleReminder(e.target.value)} />
              )}
            </Field>
            <Field label="禁止出现" hint="一行一条">
              {(props) => (
                <Textarea
                  {...props}
                  value={forbidden}
                  onChange={(e) => setForbidden(e.target.value)}
                  rows={2}
                />
              )}
            </Field>
          </div>
          {current.chapter !== null && <Badge tone="outline">简报已存在，保存将覆盖</Badge>}
        </div>
      )}
    </Dialog>
  );
}
