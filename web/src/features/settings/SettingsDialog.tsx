import { useMemo, useState, type ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Check,
  CheckCircle2,
  ChevronDown,
  FlaskConical,
  Loader2,
  Pencil,
  Plus,
  RefreshCw,
  Trash2,
  XCircle,
} from 'lucide-react';
import { configApi, qk } from '@/api';
import type { DeAiRewriteConfig, JsonRecord, LlmProfile, LlmProviderMeta } from '@/api/types';
import { ApiError } from '@/lib/http';
import {
  Badge,
  Button,
  ConfirmDialog,
  Dialog,
  EmptyState,
  IconButton,
  Input,
  Menu,
  MenuContent,
  MenuItem,
  MenuSeparator,
  MenuTrigger,
  Select,
  SkeletonList,
  Slider,
  Switch,
  TabPanel,
  Tabs,
  Textarea,
} from '@/components/ui';
import {
  useDeleteLlmProfile,
  useLlmAssignments,
  useLlmProfiles,
  useLlmProviders,
  useRewriteConfig,
  useSaveLlmAssignments,
  useSaveLlmProfile,
  useSaveRewriteConfig,
  useTestLlmProfile,
} from './queries';

type SettingsTab = 'profiles' | 'agents' | 'rewrite';

const AGENT_LABEL: Record<string, { label: string; hint: string }> = {
  archivist: { label: '档案员', hint: '检索、简报、卡片与事实提取' },
  writer: { label: '写手', hint: '正文起草与续写' },
  editor: { label: '编辑', hint: '审校、反馈与修订' },
  rewrite: { label: '改写', hint: '去味/润色用的通用模型通道' },
  extractor: { label: '提取器', hint: '导入与卡片的结构化提取' },
};

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/**
 * 全局设置：模型档案、Agent 分工、项目改写配置。
 * 打开频率低，整个对话框走懒加载。
 */
export function SettingsDialog({
  projectId = '',
  onClose,
}: {
  projectId?: string;
  onClose: () => void;
}): ReactNode {
  const [tab, setTab] = useState<SettingsTab>('profiles');
  const items = [
    { value: 'profiles', label: '模型档案' },
    { value: 'agents', label: '分工' },
    ...(projectId !== '' ? [{ value: 'rewrite', label: '去味改写' }] : []),
  ];

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title="设置"
      description="模型档案与分工是全局的；去味改写按项目存。"
      size="lg"
      footer={
        <Button tone="primary" onClick={onClose}>
          完成
        </Button>
      }
    >
      <Tabs
        value={tab}
        onValueChange={(value) => setTab(value as SettingsTab)}
        size="sm"
        items={items}
        className="min-h-80"
      >
        <TabPanel value="profiles" className="pt-3">
          <ProfilesTab />
        </TabPanel>
        <TabPanel value="agents" className="pt-3">
          <AgentsTab />
        </TabPanel>
        {projectId !== '' && (
          <TabPanel value="rewrite" className="pt-3">
            <RewriteTab projectId={projectId} />
          </TabPanel>
        )}
      </Tabs>
    </Dialog>
  );
}

// ── 模型档案 ───────────────────────────────────────────────────────

function ProfilesTab(): ReactNode {
  const profiles = useLlmProfiles();
  const providers = useLlmProviders();
  const remove = useDeleteLlmProfile();
  const [editing, setEditing] = useState<LlmProfile | 'new' | null>(null);
  const [deleting, setDeleting] = useState<LlmProfile | null>(null);

  if (editing !== null) {
    return (
      <ProfileForm
        initial={editing === 'new' ? {} : editing}
        providers={providers.data ?? []}
        onDone={() => setEditing(null)}
      />
    );
  }

  if (profiles.isPending) return <SkeletonList rows={4} />;

  const list = profiles.data ?? [];

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex justify-end">
        <Button tone="ghost" size="xs" icon={<Plus />} onClick={() => setEditing('new')}>
          新建档案
        </Button>
      </div>
      {list.map((profile) => (
        <section
          key={profile.id}
          className="group flex items-center gap-2.5 rounded-md border border-ink-800 px-3 py-2 hover:border-ink-700"
        >
          <div className="min-w-0 flex-1">
            <p className="truncate text-xs font-medium text-ink-100">{profile.name}</p>
            <p className="mt-0.5 truncate text-2xs text-ink-500">
              {profile.provider}
              {profile.model !== undefined && profile.model !== '' && ` · ${profile.model}`}
            </p>
          </div>
          <Badge tone="outline">{profile.provider}</Badge>
          <div className="flex opacity-0 transition-opacity group-hover:opacity-100">
            <Button tone="ghost" size="xs" icon={<Pencil />} onClick={() => setEditing(profile)}>
              改
            </Button>
            <Button tone="dangerGhost" size="xs" icon={<Trash2 />} onClick={() => setDeleting(profile)}>
              删
            </Button>
          </div>
        </section>
      ))}
      {list.length === 0 && (
        <EmptyState
          title="还没有模型档案"
          description="建一个档案（供应商 + 密钥 + 模型），再在「分工」里指给各角色。"
        />
      )}

      {deleting !== null && (
        <ConfirmDialog
          open
          onOpenChange={(open) => {
            if (!open) setDeleting(null);
          }}
          title={`删除档案「${deleting.name}」？`}
          description="用到它的分工会被一起清空。"
          confirmLabel="删除"
          danger
          loading={remove.isPending}
          onConfirm={() => remove.mutate(deleting.id ?? '', { onSuccess: () => setDeleting(null) })}
        />
      )}
    </div>
  );
}

const API_STYLE_OPTIONS = [
  { value: '', label: '自动（chat）' },
  { value: 'responses', label: 'responses' },
];

function ProfileForm({
  initial,
  providers,
  onDone,
}: {
  initial: LlmProfile;
  providers: LlmProviderMeta[];
  onDone: () => void;
}): ReactNode {
  const save = useSaveLlmProfile();
  const test = useTestLlmProfile();
  const [form, setForm] = useState({
    name: initial.name ?? '',
    provider: initial.provider ?? 'openai',
    base_url: initial.base_url ?? '',
    api_key: initial.api_key ?? '',
    model: initial.model ?? '',
    api_style: String(initial.api_style ?? ''),
    compatibility_mode: initial.compatibility_mode === true,
    max_tokens: String(initial.max_tokens ?? ''),
    temperature: String(initial.temperature ?? ''),
  });
  const [testResult, setTestResult] = useState<JsonRecord | null>(null);
  const [fetchedModels, setFetchedModels] = useState<string[]>(
    Array.isArray(initial.models) ? (initial.models as string[]) : [],
  );
  const [modelMenuOpen, setModelMenuOpen] = useState(false);

  const meta = providers.find((p) => p.id === form.provider);
  const fields = (meta?.fields as string[] | undefined) ?? ['base_url', 'api_key', 'model'];
  const providerOptions = useMemo(
    () =>
      providers.length > 0
        ? providers.map((p) => ({ value: String(p.id), label: String(p.label ?? p.id) }))
        : [
            { value: 'openai', label: 'OpenAI' },
            { value: 'anthropic', label: 'Anthropic' },
            { value: 'custom', label: 'Custom / OpenAI Compatible' },
            { value: 'mock', label: 'Mock / Demo' },
          ],
    [providers],
  );

  const fetchModels = useMutation({
    mutationFn: () =>
      configApi.fetchModels({
        provider: form.provider,
        base_url: form.base_url || undefined,
        api_key: form.api_key || undefined,
      }),
    onSuccess: (data) => {
      const models = (data.models as string[] | undefined) ?? (data.data as string[] | undefined) ?? [];
      if (models.length > 0) {
        setFetchedModels(models);
        toast.success(`拉到 ${models.length} 个模型`);
      } else {
        toast.info('没拉到模型列表，手填一个吧');
      }
    },
    onError: (error) => toast.error(reason(error, '拉模型列表失败')),
  });

  const toProfile = (): LlmProfile => ({
    ...initial,
    name: form.name.trim() || undefined,
    provider: form.provider,
    base_url: form.base_url.trim() || undefined,
    api_key: form.api_key.trim() || undefined,
    model: form.model.trim() || undefined,
    models: fetchedModels.length > 0 ? fetchedModels : undefined,
    api_style: form.api_style || undefined,
    compatibility_mode: form.compatibility_mode,
    max_tokens: form.max_tokens === '' ? undefined : Number(form.max_tokens),
    temperature: form.temperature === '' ? undefined : Number(form.temperature),
  });

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <p className="text-xs font-medium text-ink-200">
          {initial.id !== undefined ? '编辑档案' : '新建档案'}
        </p>
        <Button tone="ghost" size="xs" onClick={onDone}>
          返回列表
        </Button>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Input
          value={form.name}
          onChange={(event) => setForm((f) => ({ ...f, name: event.target.value }))}
          placeholder="档案名（可空自动生成）"
        />
        <Select
          value={form.provider}
          onValueChange={(value) => {
            setFetchedModels([]);
            setForm((f) => ({ ...f, provider: value }));
          }}
          options={providerOptions}
        />
      </div>

      {fields.includes('base_url') && (
        <Input
          value={form.base_url}
          onChange={(event) => {
            setFetchedModels([]);
            setForm((f) => ({ ...f, base_url: event.target.value }));
          }}
          placeholder="base_url，如 https://api.openai.com/v1"
        />
      )}
      {fields.includes('api_key') && (
        <Input
          value={form.api_key}
          type="password"
          onChange={(event) => {
            setFetchedModels([]);
            setForm((f) => ({ ...f, api_key: event.target.value }));
          }}
          placeholder="api_key"
          autoComplete="off"
        />
      )}
      {fields.includes('model') && (
        <div className="flex items-center gap-2">
          <Input
            value={form.model}
            onChange={(event) => setForm((f) => ({ ...f, model: event.target.value }))}
            placeholder="模型名，如 gpt-4o / deepseek-chat"
            className="flex-1"
          />
          <Menu
            open={modelMenuOpen}
            onOpenChange={(open) => {
              setModelMenuOpen(open);
              if (open && fetchedModels.length === 0 && !fetchModels.isPending) {
                fetchModels.mutate();
              }
            }}
          >
            <MenuTrigger asChild>
              <Button tone="ghost" size="xs" icon={<ChevronDown />}>
                模型列表
              </Button>
            </MenuTrigger>
            <MenuContent align="end" className="max-h-64 w-72 overflow-y-auto">
              {fetchModels.isPending ? (
                <MenuItem disabled icon={<Loader2 className="animate-spin" />}>
                  正在拉取…
                </MenuItem>
              ) : fetchedModels.length === 0 ? (
                <MenuItem disabled>没拉到模型，直接手填</MenuItem>
              ) : (
                <>
                  {fetchedModels.map((m) => (
                    <MenuItem
                      key={m}
                      icon={form.model === m ? <Check /> : <span className="size-3.5" />}
                      onSelect={() => setForm((f) => ({ ...f, model: m }))}
                    >
                      {m}
                    </MenuItem>
                  ))}
                  <MenuSeparator />
                  <MenuItem icon={<RefreshCw />} onSelect={() => fetchModels.mutate()}>
                    重新拉取
                  </MenuItem>
                </>
              )}
            </MenuContent>
          </Menu>
        </div>
      )}
      {fields.includes('api_style') && (
        <label className="flex items-center gap-2 text-2xs text-ink-500">
          api_style
          <Select
            value={form.api_style}
            onValueChange={(value) => setForm((f) => ({ ...f, api_style: value }))}
            options={API_STYLE_OPTIONS}
            className="w-44"
          />
        </label>
      )}

      {form.provider !== 'mock' && form.provider !== 'anthropic' && form.api_style !== 'responses' && (
        <Switch
          label="中转兼容模式"
          hint="接口断连时可尝试：使用模型默认采样参数，生成完成后一次返回内容。"
          checked={form.compatibility_mode}
          onCheckedChange={(compatibility_mode) => setForm((current) => ({ ...current, compatibility_mode }))}
        />
      )}
      <div className="grid grid-cols-2 gap-3">
        <Input
          value={form.max_tokens}
          inputMode="numeric"
          onChange={(event) => setForm((f) => ({ ...f, max_tokens: event.target.value }))}
          placeholder="max_tokens（可空）"
        />
        <Input
          value={form.temperature}
          inputMode="decimal"
          onChange={(event) => setForm((f) => ({ ...f, temperature: event.target.value }))}
          placeholder="temperature（可空）"
        />
      </div>

      {testResult !== null && (
        <div
          className={
            testResult.success === true
              ? 'rounded-md border border-celadon-700/50 bg-celadon-500/5 px-3 py-2 text-2xs'
              : 'rounded-md border border-cinnabar-600/50 bg-cinnabar-500/5 px-3 py-2 text-2xs'
          }
        >
          {testResult.success === true ? (
            <p className="flex items-center gap-1.5 text-celadon-300">
              <CheckCircle2 className="size-3.5" />
              连通正常 · {String(testResult.model)} · {String(testResult.elapsed_ms)}ms
            </p>
          ) : (
            <p className="flex items-center gap-1.5 text-cinnabar-300">
              <XCircle className="size-3.5" />
              {String(testResult.error ?? testResult.message ?? '测试失败')}
            </p>
          )}
          {typeof testResult.preview === 'string' && testResult.preview !== '' && (
            <p className="mt-1 truncate text-ink-500">{testResult.preview}</p>
          )}
        </div>
      )}

      <div className="flex justify-end gap-2">
        <Button
          tone="ghost"
          size="sm"
          icon={<FlaskConical />}
          loading={test.isPending}
          onClick={() =>
            test.mutate(toProfile(), {
              onSuccess: (data) => setTestResult(data),
            })
          }
        >
          测试连通
        </Button>
        <Button
          tone="primary"
          size="sm"
          loading={save.isPending}
          onClick={() => save.mutate(toProfile(), { onSuccess: onDone })}
        >
          保存档案
        </Button>
      </div>
    </div>
  );
}

// ── 分工 ───────────────────────────────────────────────────────────

// Radix Select 不允许空字符串的 Item value，空值用哨兵代替
const PROFILE_NONE = '__default_profile__';
const MODEL_INHERIT = '__profile_default__';

type AgentEntry = { profile_id: string; model: string };

function normalizeAgentEntry(value: unknown): AgentEntry {
  if (typeof value === 'object' && value !== null) {
    const record = value as JsonRecord;
    return { profile_id: String(record.profile_id ?? ''), model: String(record.model ?? '') };
  }
  return { profile_id: String(value ?? ''), model: '' };
}

function AgentsTab(): ReactNode {
  const profiles = useLlmProfiles();
  const assignments = useLlmAssignments();
  const save = useSaveLlmAssignments();
  const client = useQueryClient();
  const [draft, setDraft] = useState<Record<string, AgentEntry> | null>(null);

  /** 行内拉模型：写回档案的 models 字段，下次打开直接有候选 */
  const fetchRowModels = useMutation({
    mutationFn: async ({ agent, profile }: { agent: string; profile: LlmProfile }) => {
      const data = await configApi.fetchModels({
        provider: profile.provider,
        base_url: profile.base_url || undefined,
        api_key: profile.api_key || undefined,
      });
      const models = (data.models as string[] | undefined) ?? (data.data as string[] | undefined) ?? [];
      return { agent, profile, models };
    },
    onSuccess: async ({ profile, models }) => {
      if (models.length === 0) {
        toast.info('没拉到模型列表，可直接手填');
        return;
      }
      toast.success(`拉到 ${models.length} 个模型`);
      try {
        await configApi.saveProfile({ ...profile, models });
        void client.invalidateQueries({ queryKey: qk.llmProfiles });
      } catch {
        // 列表没落盘也不影响本次选择
      }
    },
    onError: (error) => toast.error(reason(error, '拉模型列表失败')),
  });

  if (assignments.isPending || profiles.isPending) return <SkeletonList rows={4} />;

  const current: Record<string, AgentEntry> =
    draft ??
    Object.fromEntries(
      Object.keys(AGENT_LABEL).map(
        (agent) => [agent, normalizeAgentEntry(assignments.data?.[agent])] as const,
      ),
    );

  const profileList = profiles.data ?? [];
  const profileOptions = [
    { value: PROFILE_NONE, label: '（默认档案）' },
    ...profileList.map((profile) => ({
      value: String(profile.id),
      label: `${profile.name}`,
      hint: `${profile.provider}${profile.model ? ` · ${profile.model}` : ''}`,
    })),
  ];

  const setAgent = (agent: string, patch: Partial<AgentEntry>): void =>
    setDraft({
      ...current,
      [agent]: { ...(current[agent] ?? { profile_id: '', model: '' }), ...patch },
    });

  return (
    <div className="flex flex-col gap-1">
      {Object.entries(AGENT_LABEL).map(([agent, meta]) => {
        const entry = current[agent] ?? { profile_id: '', model: '' };
        const profile = profileList.find((p) => String(p.id) === entry.profile_id);
        const models = Array.isArray(profile?.models) ? profile.models : [];
        const customMissing = entry.model !== '' && !models.includes(entry.model);
        const canFetch = profile !== undefined && profile.provider !== 'mock';
        return (
          <div key={agent} className="flex items-center gap-2 py-1.5">
            <div className="w-24 shrink-0">
              <p className="text-xs text-ink-200">{meta.label}</p>
              <p className="mt-0.5 text-2xs text-ink-600">{meta.hint}</p>
            </div>
            <Select
              value={entry.profile_id === '' ? PROFILE_NONE : entry.profile_id}
              onValueChange={(value) => setAgent(agent, { profile_id: value === PROFILE_NONE ? '' : value })}
              options={profileOptions}
              placeholder="选档案"
              className="min-w-0 flex-1"
            />
            {models.length > 0 ? (
              <Select
                value={entry.model === '' ? MODEL_INHERIT : entry.model}
                onValueChange={(value) => setAgent(agent, { model: value === MODEL_INHERIT ? '' : value })}
                options={[
                  {
                    value: MODEL_INHERIT,
                    label: profile?.model ? `档案默认 · ${profile.model}` : '档案默认模型',
                  },
                  ...models.map((m) => ({ value: m, label: m })),
                  ...(customMissing ? [{ value: entry.model, label: `${entry.model}（手填）` }] : []),
                ]}
                className="w-48 shrink-0"
              />
            ) : (
              <Input
                value={entry.model}
                onChange={(event) => setAgent(agent, { model: event.target.value })}
                placeholder={profile?.model ? `默认 ${profile.model}` : '模型（可空用档案默认）'}
                className="w-48 shrink-0"
              />
            )}
            <IconButton
              label="拉取该中转的模型列表"
              size="sm"
              disabled={!canFetch}
              onClick={() => {
                if (profile !== undefined) fetchRowModels.mutate({ agent, profile });
              }}
            >
              {fetchRowModels.isPending && fetchRowModels.variables?.agent === agent ? (
                <Loader2 className="animate-spin" />
              ) : (
                <RefreshCw />
              )}
            </IconButton>
          </div>
        );
      })}
      <p className="mt-1 text-2xs text-ink-600">
        模型列留空 = 用档案自带模型；同一档案可在不同分工挂不同模型。
      </p>
      <div className="mt-2 flex justify-end">
        <Button
          tone="primary"
          size="sm"
          loading={save.isPending}
          disabled={draft === null}
          onClick={() =>
            save.mutate(current, {
              onSuccess: () => setDraft(null),
            })
          }
        >
          保存分工
        </Button>
      </div>
    </div>
  );
}

// ── 去味改写（项目级）─────────────────────────────────────────────

const DEFAULT_REWRITE: DeAiRewriteConfig = {
  engine: 'local',
  intensity: 58,
  beamWidth: 4,
  maxPasses: 2,
  colloquiality: 52,
  sentenceVariety: 68,
  connectiveCleanup: 78,
  paragraphReshape: 35,
  useProjectCorpus: false,
  preserveParagraphs: true,
  hotspotLocalEnabled: false,
  extraModelPassEnabled: false,
  extraModelPassPrompt: '',
  returnDebug: true,
};

function RewriteTab({ projectId }: { projectId: string }): ReactNode {
  const config = useRewriteConfig(projectId);
  const save = useSaveRewriteConfig(projectId);
  const [draft, setDraft] = useState<DeAiRewriteConfig | null>(null);

  if (config.isPending) return <SkeletonList rows={6} />;

  const current = draft ?? { ...DEFAULT_REWRITE, ...(config.data ?? {}) };
  const set = (patch: Partial<DeAiRewriteConfig>): void => setDraft({ ...current, ...patch });

  return (
    <div className="flex flex-col">
      <div className="flex items-center gap-3 py-1.5">
        <span className="w-36 text-xs text-ink-200">引擎</span>
        <Select
          value={current.engine}
          onValueChange={(value) => set({ engine: value as DeAiRewriteConfig['engine'] })}
          options={[
            { value: 'local', label: '本地算法', hint: '不换模型，快' },
            { value: 'llm', label: '通用模型', hint: '走分工里的改写档案' },
          ]}
          className="flex-1"
        />
      </div>

      <Slider
        label="整体强度"
        value={current.intensity}
        min={0}
        max={100}
        onValueChange={(intensity) => set({ intensity })}
      />
      <Slider
        label="口语化"
        value={current.colloquiality}
        min={0}
        max={100}
        onValueChange={(colloquiality) => set({ colloquiality })}
      />
      <Slider
        label="句式变化"
        value={current.sentenceVariety}
        min={0}
        max={100}
        onValueChange={(sentenceVariety) => set({ sentenceVariety })}
      />
      <Slider
        label="连接词清理"
        value={current.connectiveCleanup}
        min={0}
        max={100}
        onValueChange={(connectiveCleanup) => set({ connectiveCleanup })}
      />
      <Slider
        label="段落重塑"
        value={current.paragraphReshape}
        min={0}
        max={100}
        onValueChange={(paragraphReshape) => set({ paragraphReshape })}
      />
      <div className="grid grid-cols-2 gap-3">
        <Slider
          label="候选宽度"
          value={current.beamWidth}
          min={1}
          max={8}
          onValueChange={(beamWidth) => set({ beamWidth })}
        />
        <Slider
          label="遍数"
          value={current.maxPasses}
          min={1}
          max={4}
          onValueChange={(maxPasses) => set({ maxPasses })}
        />
      </div>

      <div className="mt-1 divide-y divide-ink-850">
        <Switch
          checked={current.preserveParagraphs}
          onCheckedChange={(preserveParagraphs) => set({ preserveParagraphs })}
          label="保留段落结构"
        />
        <Switch
          checked={current.useProjectCorpus}
          onCheckedChange={(useProjectCorpus) => set({ useProjectCorpus })}
          label="参考本书语料"
          hint="拿已定稿的章节做风格参照"
        />
        <Switch
          checked={current.hotspotLocalEnabled}
          onCheckedChange={(hotspotLocalEnabled) => set({ hotspotLocalEnabled })}
          label="热点局部改写"
        />
        <Switch
          checked={current.extraModelPassEnabled}
          onCheckedChange={(extraModelPassEnabled) => set({ extraModelPassEnabled })}
          label="追加一遍模型润色"
        />
        <Switch
          checked={current.returnDebug}
          onCheckedChange={(returnDebug) => set({ returnDebug })}
          label="返回调试信息"
        />
      </div>

      {current.extraModelPassEnabled && (
        <Textarea
          value={current.extraModelPassPrompt}
          onChange={(event) => set({ extraModelPassPrompt: event.target.value })}
          rows={3}
          placeholder="追加润色时给模型的补充要求…"
          className="mt-2"
        />
      )}

      <div className="mt-3 flex justify-end">
        <Button
          tone="primary"
          size="sm"
          loading={save.isPending}
          disabled={draft === null}
          onClick={() => save.mutate(current, { onSuccess: () => setDraft(null) })}
        >
          保存改写配置
        </Button>
      </div>
    </div>
  );
}
