import { lazy, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  ChevronDown,
  ChevronRight,
  Compass,
  GitBranch,
  ListTree,
  RefreshCw,
  Sparkles,
  Wand2,
} from 'lucide-react';
import { outlineApi, type PlannedChapter } from '@/api';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Panel,
  Select,
  SkeletonList,
  TabPanel,
  Tabs,
  Textarea,
} from '@/components/ui';
import { LazyOverlay } from '@/components/ui/LazyOverlay';
import { ApiError } from '@/lib/http';
import { formatChapterLabel, formatVolumeLabel } from '@/lib/chapter-id';
import { useChapterTree } from '@/features/chapters/queries';
import type { ChapterNode } from '@/features/chapters/tree';
import {
  outlineContent,
  useApplyChapterPlan,
  useProjectOutline,
  useSaveChapterOutline,
  useSaveMasterOutline,
  useSaveVolumeOutline,
} from '@/features/outline/queries';
import type { ExplorationSession, OutlineCandidate } from '@/api/types';
import { useWorkspaceStore } from '@/stores/workspace';

const ChapterPlanDialog = lazy(() =>
  import('@/features/outline/ChapterPlanDialog').then((m) => ({ default: m.ChapterPlanDialog })),
);

type OutlineTab = 'master' | 'volumes' | 'chapters' | 'explore';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export function OutlinePanel(): ReactNode {
  const { projectId = '' } = useParams<{ projectId: string }>();
  const [tab, setTab] = useState<OutlineTab>('master');
  const outline = useProjectOutline(projectId);

  return (
    <Panel
      title="大纲"
      scroll={false}
      actions={
        <Button
          tone="ghost"
          size="xs"
          icon={<RefreshCw />}
          onClick={() => outline.refetch()}
          disabled={outline.isFetching}
        >
          刷新
        </Button>
      }
    >
      <Tabs
        value={tab}
        onValueChange={setTab}
        size="sm"
        items={[
          { value: 'master', label: '总纲' },
          { value: 'volumes', label: '分卷' },
          { value: 'chapters', label: '章纲' },
          { value: 'explore', label: '探索', icon: <Compass /> },
        ]}
        className="h-full"
      >
        {outline.isPending ? (
          <SkeletonList rows={8} />
        ) : outline.isError ? (
          <EmptyState title="读不到大纲" description="确认后端已启动。" />
        ) : (
          <>
            <TabPanel value="master" className="p-2.5">
              <MasterTab projectId={projectId} />
            </TabPanel>
            <TabPanel value="volumes" className="p-2.5">
              <VolumesTab projectId={projectId} />
            </TabPanel>
            <TabPanel value="chapters" className="p-2.5">
              <ChaptersTab projectId={projectId} />
            </TabPanel>
            <TabPanel value="explore" className="p-2.5">
              <ExploreTab projectId={projectId} />
            </TabPanel>
          </>
        )}
      </Tabs>
    </Panel>
  );
}

/** 编辑器外壳：内容进本地 state，保存才落盘；外部数据变化时提供「重载」。 */
function OutlineEditor({
  saved,
  saving,
  onSave,
  placeholder,
  minRows = 8,
}: {
  saved: string;
  saving: boolean;
  onSave: (content: string) => void;
  placeholder: string;
  minRows?: number;
}): ReactNode {
  const [draft, setDraft] = useState(saved);
  const lastSaved = useRef(saved);
  const dirty = draft !== saved;

  // 外部内容变化（反推/采纳候选/刷新）时：编辑器干净则跟随，脏则保留用户输入
  useEffect(() => {
    if (saved === lastSaved.current) return;
    setDraft((current) => (current === lastSaved.current ? saved : current));
    lastSaved.current = saved;
  }, [saved]);

  return (
    <div className="flex flex-col gap-2">
      <Textarea
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={placeholder}
        rows={minRows}
        autoGrow
        className="font-mono leading-relaxed"
        spellCheck={false}
      />
      <div className="flex items-center gap-2">
        <Button
          tone="primary"
          size="sm"
          onClick={() => onSave(draft)}
          loading={saving}
          disabled={!dirty}
        >
          保存
        </Button>
        {dirty && (
          <>
            <Button tone="ghost" size="sm" onClick={() => setDraft(saved)}>
              放弃修改
            </Button>
            <span className="text-2xs text-gamboge-400">未保存</span>
          </>
        )}
      </div>
    </div>
  );
}

/** AI 候选卡片列表：总纲/分卷纲生成结果共用。 */
function CandidateCards({
  candidates,
  recommendedRouteId,
  adopting,
  onAdopt,
}: {
  candidates: OutlineCandidate[];
  recommendedRouteId: string;
  adopting: boolean;
  onAdopt: (candidate: OutlineCandidate) => void;
}): ReactNode {
  if (candidates.length === 0) return null;
  return (
    <div className="flex flex-col gap-2">
      {candidates.map((candidate) => (
        <section
          key={candidate.route_id}
          className="rounded-md border border-ink-800 bg-ink-900/70 px-3 py-2"
        >
          <header className="flex items-center gap-1.5">
            <GitBranch className="size-3.5 shrink-0 text-celadon-500" />
            <span className="min-w-0 flex-1 truncate text-xs font-medium text-ink-100">
              {candidate.label}
            </span>
            {candidate.route_id === recommendedRouteId && <Badge tone="accent">推荐</Badge>}
          </header>
          {candidate.summary !== '' && (
            <p className="mt-1 text-2xs leading-relaxed text-ink-300">{candidate.summary}</p>
          )}
          {candidate.why_it_fits !== '' && (
            <p className="mt-1 text-2xs leading-relaxed text-ink-500">{candidate.why_it_fits}</p>
          )}
          {candidate.focus_points.length > 0 && (
            <div className="mt-1.5 flex flex-wrap gap-1">
              {candidate.focus_points.map((point) => (
                <Badge key={point} tone="outline">
                  {point}
                </Badge>
              ))}
            </div>
          )}
          <div className="mt-2 flex items-center gap-2">
            <Button
              tone="primary"
              size="xs"
              loading={adopting}
              onClick={() => onAdopt(candidate)}
            >
              采用此纲
            </Button>
            <span className="tnum text-2xs text-ink-600">
              {candidate.planned_volume_count_hint !== undefined
                ? `约 ${candidate.planned_volume_count_hint} 卷`
                : candidate.chapter_count_hint !== undefined
                  ? `约 ${candidate.chapter_count_hint} 章`
                  : ''}
            </span>
          </div>
        </section>
      ))}
    </div>
  );
}

/** 生成候选的指令输入 + 结果列表。 */
function CandidateGenerator({
  hint,
  pending,
  onGenerate,
  candidates,
  recommendedRouteId,
  onAdopt,
}: {
  hint: string;
  pending: boolean;
  onGenerate: (instruction: string) => void;
  candidates: OutlineCandidate[];
  recommendedRouteId: string;
  onAdopt: (candidate: OutlineCandidate) => void;
}): ReactNode {
  const [instruction, setInstruction] = useState('');
  return (
    <div className="flex flex-col gap-2 rounded-md border border-ink-800 bg-ink-900/40 p-2.5">
      <div className="flex items-center gap-2">
        <Input
          value={instruction}
          onChange={(event) => setInstruction(event.target.value)}
          placeholder={hint}
          className="h-7 flex-1"
        />
        <Button
          tone="default"
          size="sm"
          icon={<Sparkles />}
          loading={pending}
          onClick={() => onGenerate(instruction.trim())}
        >
          候选
        </Button>
      </div>
      <CandidateCards
        candidates={candidates}
        recommendedRouteId={recommendedRouteId}
        adopting={false}
        onAdopt={onAdopt}
      />
    </div>
  );
}

function MasterTab({ projectId }: { projectId: string }): ReactNode {
  const outline = useProjectOutline(projectId);
  const save = useSaveMasterOutline(projectId);
  const [candidates, setCandidates] = useState<OutlineCandidate[]>([]);
  const [recommended, setRecommended] = useState('');

  const generate = useMutation({
    mutationFn: (instruction: string) =>
      outlineApi.generateMasterCandidates(projectId, { instruction }),
    onSuccess: (data) => {
      setCandidates(data.candidates);
      setRecommended(data.recommended_route_id);
    },
    onError: (error) => toast.error(reason(error, '生成总纲候选失败')),
  });

  // 采纳候选：把 outline_content 写进总纲
  const adopt = useMutation({
    mutationFn: (content: string) => outlineApi.saveMaster(projectId, content),
    onSuccess: () => {
      void outline.refetch();
      setCandidates([]);
      toast.success('已采用并保存总纲');
    },
    onError: (error) => toast.error(reason(error, '采用失败')),
  });

  return (
    <div className="flex flex-col gap-3">
      <OutlineEditor
        saved={outlineContent(outline.data, 'master')}
        saving={save.isPending}
        onSave={(content) => save.mutate(content)}
        placeholder="全书总纲：主题、主线冲突、结局走向、分卷构想…"
      />
      <CandidateGenerator
        hint="给模型一句话方向（可空），生成几版总纲挑一个"
        pending={generate.isPending}
        onGenerate={(instruction) => generate.mutate(instruction)}
        candidates={candidates}
        recommendedRouteId={recommended}
        onAdopt={(candidate) => adopt.mutate(candidate.outline_content)}
      />
    </div>
  );
}

function VolumesTab({ projectId }: { projectId: string }): ReactNode {
  const tree = useChapterTree(projectId);
  const apply = useApplyChapterPlan(projectId);
  const [planOpen, setPlanOpen] = useState<{
    volumeId: string;
    title: string;
    chapters: PlannedChapter[];
  } | null>(null);

  if (tree.isPending) return <SkeletonList rows={6} />;
  if (tree.volumes.length === 0) {
    return <EmptyState title="还没有分卷" description="先在章节面板建一卷。" />;
  }

  return (
    <div className="flex flex-col gap-1.5">
      {tree.volumes.map((volume) => (
        <VolumeOutlineSection
          key={volume.id}
          projectId={projectId}
          volumeId={volume.id}
          volumeTitle={volume.title}
          onPlanned={(chapters) =>
            setPlanOpen({
              volumeId: volume.id,
              title: `${formatVolumeLabel(volume.id, volume.title)} · 章节规划`,
              chapters,
            })
          }
        />
      ))}

      {planOpen !== null && (
        <LazyOverlay>
          <ChapterPlanDialog
            projectId={projectId}
            volumeId={planOpen.volumeId}
            title={planOpen.title}
            initialChapters={planOpen.chapters}
            applying={apply.isPending}
            onApply={(chapters) =>
              apply.mutate(
                { volumeId: planOpen.volumeId, chapters },
                { onSuccess: () => setPlanOpen(null) },
              )
            }
            onClose={() => setPlanOpen(null)}
          />
        </LazyOverlay>
      )}
    </div>
  );
}

function VolumeOutlineSection({
  projectId,
  volumeId,
  volumeTitle,
  onPlanned,
}: {
  projectId: string;
  volumeId: string;
  volumeTitle: string;
  onPlanned: (chapters: PlannedChapter[]) => void;
}): ReactNode {
  const [open, setOpen] = useState(false);
  const outline = useProjectOutline(projectId);
  const save = useSaveVolumeOutline(projectId, volumeId);
  const [candidates, setCandidates] = useState<OutlineCandidate[]>([]);
  const [recommended, setRecommended] = useState('');
  const content = outlineContent(outline.data, { volumeId });

  const generate = useMutation({
    mutationFn: (instruction: string) =>
      outlineApi.generateVolumeCandidates(projectId, volumeId, { instruction }),
    onSuccess: (data) => {
      setCandidates(data.candidates);
      setRecommended(data.recommended_route_id);
    },
    onError: (error) => toast.error(reason(error, '生成分卷纲候选失败')),
  });

  const adopt = useMutation({
    mutationFn: (text: string) => outlineApi.saveVolume(projectId, volumeId, text),
    onSuccess: () => {
      void outline.refetch();
      setCandidates([]);
      toast.success('已采用并保存分卷纲');
    },
    onError: (error) => toast.error(reason(error, '采用失败')),
  });

  const plan = useMutation({
    mutationFn: () => outlineApi.planChapters(projectId, volumeId, content),
    onSuccess: (data) => onPlanned(data.chapters),
    onError: (error) => toast.error(reason(error, '拆章失败，先写好分卷纲')),
  });

  return (
    <section className="rounded-md border border-ink-800">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className="flex w-full items-center gap-1.5 px-2 py-1.5 text-left hover:bg-ink-850"
      >
        <span className="text-ink-500 [&_svg]:size-3.5">
          {open ? <ChevronDown /> : <ChevronRight />}
        </span>
        <span className="min-w-0 flex-1 truncate text-xs font-medium text-ink-200">
          {formatVolumeLabel(volumeId, volumeTitle)}
        </span>
        {content === '' && <Badge tone="warning">空纲</Badge>}
      </button>

      {open && (
        <div className="flex flex-col gap-2.5 border-t border-ink-800 p-2.5">
          <OutlineEditor
            saved={content}
            saving={save.isPending}
            onSave={(text) => save.mutate(text)}
            placeholder="这一卷的阶段目标、主线推进、卷末落点…"
            minRows={5}
          />
          <CandidateGenerator
            hint="方向指令（可空）"
            pending={generate.isPending}
            onGenerate={(instruction) => generate.mutate(instruction)}
            candidates={candidates}
            recommendedRouteId={recommended}
            onAdopt={(candidate) => adopt.mutate(candidate.outline_content)}
          />
          <Button
            tone="outline"
            size="sm"
            icon={<ListTree />}
            loading={plan.isPending}
            disabled={content.trim() === ''}
            onClick={() => plan.mutate()}
          >
            按分卷纲拆成章节规划
          </Button>
        </div>
      )}
    </section>
  );
}

function ChaptersTab({ projectId }: { projectId: string }): ReactNode {
  const tree = useChapterTree(projectId);
  const outline = useProjectOutline(projectId);
  const currentChapter = useWorkspaceStore((state) => state.chapter);
  const [picked, setPicked] = useState<string | null>(null);

  const chapterId = picked ?? currentChapter ?? tree.chapterIds[0] ?? null;
  const save = useSaveChapterOutline(projectId, chapterId ?? '');
  const content = chapterId === null ? '' : outlineContent(outline.data, { chapterId });

  const allChapters = useMemo(
    () => tree.volumes.flatMap((volume) => volume.chapters),
    [tree.volumes],
  );
  const chapterOptions = useMemo(
    () =>
      allChapters.map((item) => ({
        value: item.id,
        label: `${formatChapterLabel(item.id)}${item.title === '' ? '' : ` · ${item.title}`}`,
      })),
    [allChapters],
  );

  const generate = useMutation({
    mutationFn: ({ instruction }: { instruction: string }) =>
      outlineApi.generate(projectId, { chapter_id: chapterId ?? '', instruction }),
    onSuccess: () => {
      void outline.refetch();
      toast.success('已生成章纲');
    },
    onError: (error) => toast.error(reason(error, '生成章纲失败')),
  });

  const reverse = useMutation({
    mutationFn: () => outlineApi.reverseEngineerChapter(projectId, chapterId ?? ''),
    onSuccess: () => {
      void outline.refetch();
      toast.success('已从正文反推章纲');
    },
    onError: (error) => toast.error(reason(error, '反推失败，确认该章已有正文')),
  });

  const [instruction, setInstruction] = useState('');

  if (tree.isPending) return <SkeletonList rows={6} />;
  if (tree.chapterIds.length === 0) {
    return <EmptyState title="还没有章节" description="先在章节面板建章。" />;
  }

  const chapterNode: ChapterNode | undefined = allChapters.find((item) => item.id === chapterId);

  return (
    <div className="flex flex-col gap-3">
      <Select
        value={chapterId ?? undefined}
        onValueChange={setPicked}
        options={chapterOptions}
        placeholder="选择章节"
      />
      {chapterId !== null && (
        <>
          <OutlineEditor
            key={chapterId}
            saved={content}
            saving={save.isPending}
            onSave={(text) => save.mutate(text)}
            placeholder={`${formatChapterLabel(chapterId)}${chapterNode?.title ? ` · ${chapterNode.title}` : ''} 的可执行章纲…`}
            minRows={7}
          />
          <div className="flex items-center gap-2">
            <Input
              value={instruction}
              onChange={(event) => setInstruction(event.target.value)}
              placeholder="生成指令（可空）"
              className="h-7 flex-1"
            />
            <Button
              tone="default"
              size="sm"
              icon={<Wand2 />}
              loading={generate.isPending}
              onClick={() => generate.mutate({ instruction: instruction.trim() })}
            >
              生成
            </Button>
            <Button
              tone="outline"
              size="sm"
              loading={reverse.isPending}
              disabled={chapterNode === undefined || chapterNode.wordCount === 0}
              onClick={() => reverse.mutate()}
            >
              反推
            </Button>
          </div>
          <p className="text-2xs leading-relaxed text-ink-600">
            「反推」从这章已有正文提炼可执行章纲并直接保存；「生成」不看正文，按总纲+指令写。
          </p>
        </>
      )}
    </div>
  );
}

/** 探索向导：scope → questions → routes → generated → apply。 */
function ExploreTab({ projectId }: { projectId: string }): ReactNode {
  const currentChapter = useWorkspaceStore((state) => state.chapter);
  const apply = useApplyChapterPlan(projectId);

  const [scope, setScope] = useState<'next_chapter' | 'next_multi'>('next_chapter');
  const [session, setSession] = useState<ExplorationSession | null>(null);
  const [answers, setAnswers] = useState<Record<string, { option?: string; note: string }>>({});
  const [planOpen, setPlanOpen] = useState(false);

  const start = useMutation({
    mutationFn: () =>
      outlineApi.startExploration(projectId, {
        scope,
        chapter_id: currentChapter ?? undefined,
      }),
    onSuccess: (data) => {
      setSession(data.session);
      setAnswers({});
    },
    onError: (error) => toast.error(reason(error, '启动探索失败')),
  });

  const answer = useMutation({
    mutationFn: () => {
      const questions = session?.questions ?? [];
      return outlineApi.answerExploration(
        projectId,
        session?.session_id ?? '',
        questions.map((question) => {
          const answer = answers[question.id];
          return {
            question_id: question.id,
            option_ids: answer?.option !== undefined ? [answer.option] : [],
            custom_note: answer?.note.trim() || undefined,
          };
        }),
      );
    },
    onSuccess: (data) => setSession(data.session),
    onError: (error) => toast.error(reason(error, '生成路线失败')),
  });

  const selectRoute = useMutation({
    mutationFn: (routeId: string) =>
      outlineApi.selectExplorationRoute(projectId, session?.session_id ?? '', routeId),
    onSuccess: (data) => setSession(data.session),
    onError: (error) => toast.error(reason(error, '生成规划失败')),
  });

  if (session === null) {
    return (
      <div className="flex flex-col gap-3 pt-1">
        <p className="text-2xs leading-relaxed text-ink-500">
          续写探索：回答几个方向问题，模型给出几条路线，选定后落成可执行的章节规划。
          {currentChapter !== null && ` 锚点：${formatChapterLabel(currentChapter)}。`}
        </p>
        <div className="flex flex-col gap-1.5">
          {(
            [
              ['next_chapter', '续写下一章', '围绕当前章规划一章的去向'],
              ['next_multi', '规划 2~3 章', '一小段情节弧，连续几章的推进'],
            ] as const
          ).map(([value, label, hint]) => (
            <label
              key={value}
              className="flex cursor-pointer items-start gap-2 rounded-md border border-ink-800 px-2.5 py-2 hover:border-ink-600"
            >
              <input
                type="radio"
                name="explore-scope"
                checked={scope === value}
                onChange={() => setScope(value)}
                className="mt-0.5 accent-celadon-500"
              />
              <span>
                <span className="block text-xs text-ink-200">{label}</span>
                <span className="block text-2xs text-ink-500">{hint}</span>
              </span>
            </label>
          ))}
        </div>
        <Button tone="primary" size="sm" icon={<Compass />} loading={start.isPending} onClick={() => start.mutate()}>
          开始探索
        </Button>
      </div>
    );
  }

  if (session.stage === 'questions') {
    const allAnswered = session.questions.every(
      (question) =>
        answers[question.id]?.option !== undefined ||
        (question.allow_custom && (answers[question.id]?.note.trim() ?? '') !== ''),
    );
    return (
      <div className="flex flex-col gap-3">
        <p className="text-2xs text-ink-500">
          {session.volume_title} · 锚点 {formatChapterLabel(session.anchor_chapter_id)}
        </p>
        {session.questions.map((question) => (
          <section key={question.id} className="flex flex-col gap-1.5">
            <p className="text-xs font-medium text-ink-200">{question.title}</p>
            {question.prompt !== '' && (
              <p className="text-2xs leading-relaxed text-ink-500">{question.prompt}</p>
            )}
            {question.options.map((option) => (
              <label
                key={option.id}
                className="flex cursor-pointer items-start gap-2 rounded border border-ink-800 px-2 py-1.5 hover:border-ink-600"
              >
                <input
                  type="radio"
                  name={`explore-${question.id}`}
                  checked={answers[question.id]?.option === option.id}
                  onChange={() =>
                    setAnswers((current) => ({
                      ...current,
                      [question.id]: { option: option.id, note: current[question.id]?.note ?? '' },
                    }))
                  }
                  className="mt-0.5 accent-celadon-500"
                />
                <span>
                  <span className="block text-2xs text-ink-200">{option.label}</span>
                  <span className="block text-2xs text-ink-500">{option.description}</span>
                </span>
              </label>
            ))}
            {question.allow_custom && (
              <Input
                value={answers[question.id]?.note ?? ''}
                onChange={(event) =>
                  setAnswers((current) => ({
                    ...current,
                    [question.id]: {
                      option: current[question.id]?.option,
                      note: event.target.value,
                    },
                  }))
                }
                placeholder="或者自己写一句…"
                className="h-7"
              />
            )}
          </section>
        ))}
        <div className="flex items-center gap-2">
          <Button
            tone="primary"
            size="sm"
            loading={answer.isPending}
            disabled={!allAnswered}
            onClick={() => answer.mutate()}
          >
            生成路线
          </Button>
          <Button tone="ghost" size="sm" onClick={() => setSession(null)}>
            重来
          </Button>
        </div>
      </div>
    );
  }

  if (session.stage === 'routes') {
    return (
      <div className="flex flex-col gap-2.5">
        <p className="text-2xs text-ink-500">挑一条路线，模型按它生成章节规划。</p>
        {session.route_candidates.map((route) => (
          <section key={route.route_id} className="rounded-md border border-ink-800 bg-ink-900/60 px-3 py-2">
            <p className="text-xs font-medium text-ink-100">{route.label}</p>
            <p className="mt-1 text-2xs leading-relaxed text-ink-300">{route.summary}</p>
            {route.why_it_fits !== '' && (
              <p className="mt-1 text-2xs leading-relaxed text-ink-500">{route.why_it_fits}</p>
            )}
            {route.beats.length > 0 && (
              <ul className="mt-1.5 list-inside list-disc text-2xs text-ink-400">
                {route.beats.map((beat) => (
                  <li key={beat}>{beat}</li>
                ))}
              </ul>
            )}
            {route.risks.length > 0 && (
              <p className="mt-1 text-2xs text-cinnabar-400">风险：{route.risks.join('；')}</p>
            )}
            <Button
              tone="primary"
              size="xs"
              className="mt-2"
              loading={selectRoute.isPending}
              onClick={() => selectRoute.mutate(route.route_id)}
            >
              走这条
            </Button>
          </section>
        ))}
        <Button tone="ghost" size="sm" onClick={() => setSession(null)}>
          重来
        </Button>
      </div>
    );
  }

  // generated：给出规划预览入口
  const plan = session.generated_plan;
  return (
    <div className="flex flex-col gap-3">
      <p className="text-2xs leading-relaxed text-ink-400">
        路线「{plan?.route_label ?? session.selected_route_id}」已生成 {plan?.chapters.length ?? 0}{' '}
        章规划，落到 {plan?.volume_title ?? session.volume_title}。
      </p>
      <Button tone="primary" size="sm" icon={<ListTree />} onClick={() => setPlanOpen(true)}>
        查看并应用规划
      </Button>
      <Button tone="ghost" size="sm" onClick={() => setSession(null)}>
        再来一轮
      </Button>

      {planOpen && plan !== null && (
        <LazyOverlay>
          <ChapterPlanDialog
            projectId={projectId}
            volumeId={plan.volume_id}
            title={`续写规划 · ${plan.route_label}`}
            initialChapters={plan.chapters}
            applying={apply.isPending}
            onApply={(chapters) =>
              apply.mutate(
                { volumeId: plan.volume_id, chapters },
                {
                  onSuccess: () => {
                    setPlanOpen(false);
                    setSession(null);
                  },
                },
              )
            }
            onClose={() => setPlanOpen(false)}
          />
        </LazyOverlay>
      )}
    </div>
  );
}
