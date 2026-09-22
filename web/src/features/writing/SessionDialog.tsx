import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CheckCheck, MessageSquareReply, Radio, RefreshCw, Sparkles } from 'lucide-react';
import { projectScope, sessionApi } from '@/api';
import type { Draft, SceneBrief, SessionFlowResponse, SessionQuestion } from '@/api/types';
import { ApiError } from '@/lib/http';
import { subscribeWs, wsPath, type WsEnvelope } from '@/lib/ws';
import { formatChapterLabel } from '@/lib/chapter-id';
import { Badge, Button, Dialog, Input, Textarea } from '@/components/ui';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

import { ArchiveDetails } from './ArchiveStatus';

type Stage = 'form' | 'questions' | 'draft' | 'confirmed';

/**
 * AI 写章会话。
 *
 * 后端流程（SessionController）：
 *   start → 档案员产 scene_brief → 可能反问(waiting_user_input)
 *   → answer-questions → writer 产版本化草稿(waiting_feedback)
 *   → feedback(action=revise) 反复改 / (action=confirm) 定稿并触发分析
 * 流式重写走 /stream-draft SSE，只产文本不落盘，采纳时由前端保存。
 */
export function SessionDialog({
  projectId,
  chapter,
  chapterTitle,
  currentContent,
  onAdopt,
  onClose,
}: {
  projectId: string;
  chapter: string;
  chapterTitle: string;
  currentContent: string;
  onAdopt: (text: string) => void;
  onClose: () => void;
}): ReactNode {
  const client = useQueryClient();

  const [stage, setStage] = useState<Stage>('form');
  const [goal, setGoal] = useState('');
  const [targetWords, setTargetWords] = useState('3000');
  const [characters, setCharacters] = useState('');
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [feedbackText, setFeedbackText] = useState('');
  const [rejected, setRejected] = useState<string[]>([]);

  const [flow, setFlow] = useState<SessionFlowResponse | null>(null);
  const [streamed, setStreamed] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(false);
  const [progress, setProgress] = useState('');
  const streamAbort = useRef<AbortController | null>(null);

  // WS 进度：后端在研究和写作各阶段都广播，直接拿最后一条当状态行
  useEffect(() => {
    return subscribeWs(wsPath.session(projectId), {
      onMessage: (batch: WsEnvelope[]) => {
        const last = batch[batch.length - 1];
        const message = last?.message;
        if (typeof message === 'string' && message !== '') setProgress(message);
      },
    });
  }, [projectId]);

  useEffect(() => () => streamAbort.current?.abort(), []);

  /** 统一处理 start/answer/feedback 的响应落点。 */
  const settle = (data: SessionFlowResponse): void => {
    setFlow(data);
    if (data.success === false) {
      toast.error(data.error ?? data.message ?? '会话失败');
      return;
    }
    if (data.status === 'waiting_user_input' && (data.questions?.length ?? 0) > 0) {
      setAnswers({});
      setStage('questions');
      return;
    }
    if (data.status === 'completed') {
      setStage('confirmed');
      void client.invalidateQueries({ queryKey: projectScope(projectId) });
      return;
    }
    if (data.draft_v1 !== undefined || data.draft !== undefined) {
      setStage('draft');
      return;
    }
    if (data.status === 'waiting_feedback') setStage('draft');
  };

  const start = useMutation({
    mutationFn: () =>
      sessionApi.start(projectId, {
        chapter,
        chapter_title: chapterTitle,
        chapter_goal: goal.trim() || undefined,
        target_word_count: Number.parseInt(targetWords, 10) || 3000,
        character_names:
          characters.trim() === ''
            ? undefined
            : characters
                .split(/[,，、\s]+/)
                .map((name) => name.trim())
                .filter(Boolean),
      }),
    onSuccess: settle,
    onError: (error) => toast.error(reason(error, '启动会话失败')),
  });

  const answer = useMutation({
    mutationFn: () => {
      const questions = flow?.questions ?? [];
      return sessionApi.answerQuestions(projectId, {
        chapter,
        chapter_title: chapterTitle,
        chapter_goal: goal.trim() || undefined,
        target_word_count: Number.parseInt(targetWords, 10) || 3000,
        answers: questions.map((question, index) => ({
          type: question.type ?? '',
          question: question.text ?? question.question ?? '',
          ...(question.key !== undefined ? { key: question.key } : {}),
          answer: answers[index] ?? '',
        })),
      });
    },
    onSuccess: settle,
    onError: (error) => toast.error(reason(error, '提交回答失败')),
  });

  const feedback = useMutation({
    mutationFn: (action: 'revise' | 'confirm') =>
      sessionApi.feedback(projectId, {
        chapter,
        feedback: action === 'confirm' ? '' : feedbackText.trim(),
        action,
        rejected_entities: rejected,
      }),
    onSuccess: (data, action) => {
      if (data.success === false) { settle(data); return; }
      if (action === 'confirm') {
        if (data.archive?.status === 'complete') toast.success('已定稿，归档完成');
        else toast.warning('正文已定稿，部分归档待完成');
      } else {
        setFeedbackText('');
        toast.success(`已按反馈重写（第 ${data.iteration ?? '?'} 轮）`);
      }
      settle(data);
    },
    onError: (error) => toast.error(reason(error, '反馈失败')),
  });

  const streamRewrite = (): void => {
    streamAbort.current?.abort();
    const controller = new AbortController();
    streamAbort.current = controller;
    setStreamed('');
    setStreaming(true);
    void sessionApi
      .streamDraft(
        projectId,
        chapter,
        (chunk) => setStreamed((current) => (current ?? '') + chunk),
        controller.signal,
      )
      .then(() => setStreaming(false))
      .catch((error: unknown) => {
        setStreaming(false);
        if (!controller.signal.aborted) toast.error(reason(error, '流式生成失败'));
      });
  };

  const versionedDraft: Draft | null = flow?.draft_v1 ?? flow?.draft ?? null;
  const preview = streamed ?? versionedDraft?.content ?? '';
  const busy = start.isPending || answer.isPending || feedback.isPending;

  const adopt = (): void => {
    if (preview === '') return;
    onAdopt(preview);
    onClose();
  };

  const close = (): void => {
    streamAbort.current?.abort();
    if (stage !== 'form') void sessionApi.cancel(projectId).catch(() => undefined);
    onClose();
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) close();
      }}
      title={`AI 写章 · ${formatChapterLabel(chapter)}`}
      size="lg"
      dismissible={!busy && !streaming}
      footer={
        stage === 'form' ? (
          <>
            <Button tone="outline" onClick={close}>
              取消
            </Button>
            <Button tone="primary" icon={<Sparkles />} loading={busy} onClick={() => start.mutate()}>
              开始写章
            </Button>
          </>
        ) : stage === 'questions' ? (
          <>
            <Button tone="outline" onClick={close}>
              取消
            </Button>
            <Button tone="primary" loading={busy} onClick={() => answer.mutate()}>
              提交并开始写作
            </Button>
          </>
        ) : stage === 'draft' ? (
          <>
            <Button tone="ghost" onClick={close}>
              关闭
            </Button>
            <Button
              tone="outline"
              icon={<RefreshCw />}
              loading={streaming}
              disabled={busy}
              onClick={streamRewrite}
            >
              流式重写
            </Button>
            {versionedDraft !== null && streamed === null && (
              <Button
                tone="outline"
                icon={<CheckCheck />}
                loading={busy}
                disabled={streaming}
                onClick={() => feedback.mutate('confirm')}
              >
                定稿
              </Button>
            )}
            <Button tone="primary" onClick={adopt} disabled={preview === '' || streaming}>
              采用到编辑器
            </Button>
          </>
        ) : (
          <Button tone="primary" onClick={onClose}>
            完成
          </Button>
        )
      }
    >
      {/* 进度行：生成期显示 WS 广播的最新阶段 */}
      {(busy || streaming) && (
        <p className="mb-3 flex items-center gap-2 text-2xs text-gamboge-300">
          <Radio className="size-3 animate-pulse" />
          {progress !== '' ? progress : streaming ? '正在流式生成…' : '正在处理…'}
        </p>
      )}

      {stage === 'form' && (
        <div className="flex flex-col gap-3">
          {currentContent.trim() !== '' && (
            <p className="rounded-md border border-gamboge-500/30 bg-gamboge-500/10 px-3 py-2 text-2xs text-gamboge-300">
              本章已有正文，AI 草稿不会直接覆盖——生成后由你决定采用或放弃。
            </p>
          )}
          <label className="flex flex-col gap-1">
            <span className="text-2xs text-ink-400">本章目标（可空，留空用章纲/摘要）</span>
            <Textarea
              value={goal}
              onChange={(event) => setGoal(event.target.value)}
              rows={2}
              placeholder="这一章要推进什么、落到哪个钩子上…"
            />
          </label>
          <div className="grid grid-cols-2 gap-3">
            <label className="flex flex-col gap-1">
              <span className="text-2xs text-ink-400">目标字数</span>
              <Input
                value={targetWords}
                onChange={(event) => setTargetWords(event.target.value)}
                inputMode="numeric"
              />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-2xs text-ink-400">出场人物（逗号分隔，可空）</span>
              <Input value={characters} onChange={(event) => setCharacters(event.target.value)} />
            </label>
          </div>
          <p className="text-2xs leading-relaxed text-ink-600">
            流程：档案员先出场景简报并跑一轮资料研究，证据不足会反问你几个问题，然后作家动笔。
          </p>
        </div>
      )}

      {stage === 'questions' && flow !== null && (
        <div className="flex flex-col gap-3">
          <BriefSummary brief={flow.scene_brief ?? null} />
          <div className="border-t border-ink-800 pt-3">
            <p className="mb-2 text-xs font-medium text-ink-200">动笔前还需要你确认：</p>
            {(flow.questions ?? []).map((question: SessionQuestion, index) => (
              <div key={question.key ?? index} className="mb-2.5">
                <p className="mb-1 text-2xs text-ink-300">
                  {question.text ?? question.question ?? ''}
                  {question.reason !== undefined && (
                    <span className="ml-1.5 text-ink-600">（{question.reason}）</span>
                  )}
                </p>
                <Input
                  value={answers[index] ?? ''}
                  onChange={(event) =>
                    setAnswers((current) => ({ ...current, [index]: event.target.value }))
                  }
                  placeholder="留空表示交给模型判断"
                />
              </div>
            ))}
          </div>
        </div>
      )}

      {stage === 'draft' && (
        <div className="flex flex-col gap-3">
          <div className="flex items-center gap-2 text-2xs text-ink-500">
            {versionedDraft !== null && streamed === null && (
              <Badge tone="accent">{versionedDraft.version}</Badge>
            )}
            {streamed !== null && <Badge tone="warning">{streaming ? '生成中' : '流式稿'}</Badge>}
            <span className="tnum">{preview.length.toLocaleString()} 字</span>
            {(flow?.iteration ?? 0) > 0 && <span>第 {flow?.iteration} 轮修订</span>}
          </div>

          <div className="prose-chapter max-h-[46vh] overflow-y-auto rounded-md border border-ink-800 bg-ink-900/50 p-4 text-ink-200">
            {preview === '' ? (
              <p className="text-ink-600">尚无内容。</p>
            ) : (
              preview
                .split(/\n{2,}/)
                .map((paragraph, index) => <p key={index}>{paragraph}</p>)
            )}
          </div>

          {(flow?.proposals?.length ?? 0) > 0 && (
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="text-2xs text-ink-500">草稿里检出的实体，勾掉下次别再写：</span>
              {(flow?.proposals ?? []).map((proposal, index) => {
                const name = String(
                  (proposal as Record<string, unknown>).name ??
                    (proposal as Record<string, unknown>).entity ??
                    proposal,
                );
                const active = rejected.includes(name);
                return (
                  <button
                    key={`${name}-${index}`}
                    type="button"
                    onClick={() =>
                      setRejected((current) =>
                        active ? current.filter((item) => item !== name) : [...current, name],
                      )
                    }
                    className={
                      active
                        ? 'rounded border border-cinnabar-500/60 bg-cinnabar-500/15 px-1.5 py-0.5 text-2xs text-cinnabar-300 line-through'
                        : 'rounded border border-ink-700 px-1.5 py-0.5 text-2xs text-ink-300 hover:border-ink-500'
                    }
                  >
                    {name}
                  </button>
                );
              })}
            </div>
          )}

          <div className="flex items-start gap-2 border-t border-ink-800 pt-3">
            <Textarea
              value={feedbackText}
              onChange={(event) => setFeedbackText(event.target.value)}
              rows={2}
              placeholder="哪里不对？例如：开头太平淡，把冲突提前到第一场"
              className="flex-1"
            />
            <Button
              tone="default"
              size="sm"
              icon={<MessageSquareReply />}
              loading={busy}
              disabled={feedbackText.trim() === ''}
              onClick={() => feedback.mutate('revise')}
            >
              按反馈改
            </Button>
          </div>
        </div>
      )}

      {stage === 'confirmed' && (
        <div className="py-4">
          {flow?.archive ? <ArchiveDetails report={flow.archive} /> : <p>正文已定稿，归档状态请在写作页查看。</p>}
        </div>
      )}
    </Dialog>
  );
}

function BriefSummary({ brief }: { brief: SceneBrief | null }): ReactNode {
  if (brief === null) return null;
  return (
    <section className="flex flex-col gap-1.5 rounded-md border border-ink-800 bg-ink-900/50 p-3">
      <p className="text-2xs font-medium text-celadon-400">场景简报</p>
      {brief.goal !== null && brief.goal !== '' && (
        <p className="text-2xs text-ink-300">目标：{brief.goal}</p>
      )}
      {brief.conflict !== null && brief.conflict !== '' && (
        <p className="text-2xs text-ink-400">冲突：{brief.conflict}</p>
      )}
      {brief.key_beats.length > 0 && (
        <ul className="list-inside list-disc text-2xs text-ink-400">
          {brief.key_beats.slice(0, 4).map((beat) => (
            <li key={beat}>{beat}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
