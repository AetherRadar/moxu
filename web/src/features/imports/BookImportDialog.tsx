import { useMemo, useRef, useState, type DragEvent, type ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useVirtualizer } from '@tanstack/react-virtual';
import { toast } from 'sonner';
import {
  AlertTriangle,
  BookOpen,
  Check,
  ChevronDown,
  ChevronRight,
  FileUp,
  Loader2,
  Sparkles,
  Upload,
} from 'lucide-react';
import { importsApi, projectScope } from '@/api';
import type {
  BookImportPreview,
  BookImportResult,
  BookImportVolumeDraft,
  ImportPreviewChapter,
  ImportPreviewVolume,
} from '@/api/types';
import { cn } from '@/lib/cn';
import { ApiError } from '@/lib/http';
import {
  Badge,
  Button,
  Dialog,
  EmptyState,
  Field,
  Input,
  Slider,
  Switch,
  TabPanel,
  Tabs,
  Textarea,
} from '@/components/ui';

type Step = 'source' | 'preview' | 'importing' | 'done';
type SourceTab = 'file' | 'text';

const ACCEPT = '.txt,.epub';

interface ImportOptions {
  quickInit: boolean;
  autoExtractTitles: boolean;
  overwriteStyle: boolean;
  overwriteCards: boolean;
  maxCharacterCards: number;
  maxWorldCards: number;
}

const DEFAULT_OPTIONS: ImportOptions = {
  quickInit: true,
  autoExtractTitles: false,
  overwriteStyle: false,
  overwriteCards: false,
  maxCharacterCards: 9,
  maxWorldCards: 10,
};

const chapterKey = (volumeIndex: number, chapterIndex: number): string =>
  `${volumeIndex}:${chapterIndex}`;

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/**
 * 书籍导入向导：选源 → 解析预览（勾选卷/章）→ 确认导入 → 结果。
 *
 * 预览体里带每章全文——整本书可能几十 MB，所以内容只进 state、不进 Query 缓存，
 * 也绝不整本渲染：列表用虚拟滚动，确认时才把勾选的 content 装回请求体。
 */
export function BookImportDialog({
  projectId,
  onClose,
}: {
  projectId: string;
  onClose: () => void;
}): ReactNode {
  const client = useQueryClient();

  const [step, setStep] = useState<Step>('source');
  const [tab, setTab] = useState<SourceTab>('file');
  const [file, setFile] = useState<File | null>(null);
  const [text, setText] = useState('');
  const [sourceName, setSourceName] = useState('');
  const [dragging, setDragging] = useState(false);

  const [preview, setPreview] = useState<BookImportPreview | null>(null);
  const [deselected, setDeselected] = useState<ReadonlySet<string>>(new Set());
  const [collapsed, setCollapsed] = useState<ReadonlySet<number>>(new Set());
  const [options, setOptions] = useState<ImportOptions>(DEFAULT_OPTIONS);
  const [result, setResult] = useState<BookImportResult | null>(null);

  const listRef = useRef<HTMLDivElement>(null);

  const previewMutation = useMutation({
    mutationFn: (): Promise<BookImportPreview> => {
      if (tab === 'file') {
        if (file === null) throw new ApiError('先选一个 txt 或 epub 文件', 400, '', null);
        return importsApi.previewFile(projectId, file);
      }
      if (text.trim() === '') throw new ApiError('先粘贴正文', 400, '', null);
      return importsApi.previewText(projectId, {
        sourceName: sourceName.trim() || undefined,
        text,
      });
    },
    onSuccess: (data) => {
      setPreview(data);
      setDeselected(new Set());
      setCollapsed(new Set());
      setStep('preview');
    },
    onError: (error) => toast.error(reason(error, '解析失败，换个文件试试')),
  });

  const confirmMutation = useMutation({
    mutationFn: (body: Parameters<typeof importsApi.confirm>[1]) =>
      importsApi.confirm(projectId, body),
    onSuccess: (data) => {
      setResult(data);
      setStep('done');
      // 项目结构被整个改写，所有派生缓存作废
      void client.invalidateQueries({ queryKey: projectScope(projectId) });
    },
    onError: (error) => {
      toast.error(reason(error, '导入失败'));
      setStep('preview');
    },
  });

  const pickFile = (candidate: File | null | undefined): void => {
    if (!candidate) return;
    const name = candidate.name.toLowerCase();
    if (!name.endsWith('.txt') && !name.endsWith('.epub')) {
      toast.error('只支持 txt 或 epub 文件');
      return;
    }
    setFile(candidate);
  };

  const onDrop = (event: DragEvent): void => {
    event.preventDefault();
    setDragging(false);
    pickFile(event.dataTransfer.files[0]);
  };

  // ── 预览态的勾选模型 ──────────────────────────────────────────────
  // useMemo 稳住引用：否则下游两个 useMemo 每渲染都重算
  const volumes = useMemo(() => preview?.volumes ?? [], [preview]);

  const selectedCount = useMemo(() => {
    let total = 0;
    for (let v = 0; v < volumes.length; v += 1) {
      const chapters = volumes[v]?.chapters ?? [];
      for (let c = 0; c < chapters.length; c += 1) {
        if (!deselected.has(chapterKey(v, c))) total += 1;
      }
    }
    return total;
  }, [volumes, deselected]);

  const toggleChapter = (volumeIndex: number, chapterIndex: number): void => {
    const key = chapterKey(volumeIndex, chapterIndex);
    const next = new Set(deselected);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    setDeselected(next);
  };

  const toggleVolume = (volumeIndex: number): void => {
    const chapters = volumes[volumeIndex]?.chapters ?? [];
    const next = new Set(deselected);
    const allOff = chapters.every((_, c) => next.has(chapterKey(volumeIndex, c)));
    for (let c = 0; c < chapters.length; c += 1) {
      const key = chapterKey(volumeIndex, c);
      if (allOff) next.delete(key);
      else next.add(key);
    }
    setDeselected(next);
  };

  const toggleCollapsed = (volumeIndex: number): void => {
    const next = new Set(collapsed);
    if (next.has(volumeIndex)) next.delete(volumeIndex);
    else next.add(volumeIndex);
    setCollapsed(next);
  };

  type Row =
    | { kind: 'volume'; volume: ImportPreviewVolume; volumeIndex: number }
    | { kind: 'chapter'; chapter: ImportPreviewChapter; volumeIndex: number; chapterIndex: number };

  const rows = useMemo<Row[]>(() => {
    const flat: Row[] = [];
    volumes.forEach((volume, volumeIndex) => {
      flat.push({ kind: 'volume', volume, volumeIndex });
      if (collapsed.has(volumeIndex)) return;
      volume.chapters.forEach((chapter, chapterIndex) => {
        flat.push({ kind: 'chapter', chapter, volumeIndex, chapterIndex });
      });
    });
    return flat;
  }, [volumes, collapsed]);

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => listRef.current,
    estimateSize: () => 30,
    overscan: 10,
  });

  const confirmImport = (): void => {
    if (preview === null) return;
    const drafts: BookImportVolumeDraft[] = volumes
      .map((volume, volumeIndex) => ({
        title: volume.title,
        explicit: volume.explicit,
        chapters: volume.chapters
          .map((chapter, chapterIndex) => ({ chapter, chapterIndex }))
          .filter(({ chapterIndex }) => !deselected.has(chapterKey(volumeIndex, chapterIndex)))
          .map(({ chapter }) => ({
            selected: true,
            title: chapter.title,
            content: chapter.content,
            syntheticTitle: chapter.synthetic_title,
          })),
      }))
      .filter((volume) => (volume.chapters?.length ?? 0) > 0);

    setStep('importing');
    confirmMutation.mutate({
      sourceName: preview.filename || sourceName.trim() || undefined,
      format: preview.format,
      quickInit: options.quickInit,
      autoExtractTitles: options.autoExtractTitles,
      overwriteStyle: options.overwriteStyle,
      overwriteCards: options.overwriteCards,
      maxCharacterCards: options.maxCharacterCards,
      maxWorldCards: options.maxWorldCards,
      volumes: drafts,
    });
  };

  const busy = step === 'importing';

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={
        step === 'done'
          ? '导入完成'
          : step === 'importing'
            ? '正在导入'
            : preview !== null
              ? `导入预览 · ${preview.filename}`
              : '导入书籍'
      }
      size="xl"
      dismissible={!busy}
      contentClassName="h-[calc(100vh-6rem)]"
      footer={
        step === 'source' ? (
          <>
            <Button tone="outline" onClick={onClose}>
              取消
            </Button>
            <Button
              tone="primary"
              onClick={() => previewMutation.mutate()}
              loading={previewMutation.isPending}
              disabled={tab === 'file' ? file === null : text.trim() === ''}
            >
              解析预览
            </Button>
          </>
        ) : step === 'preview' ? (
          <>
            <Button tone="outline" onClick={() => setStep('source')}>
              返回
            </Button>
            <Button
              tone="primary"
              onClick={confirmImport}
              disabled={selectedCount === 0}
              icon={<Upload />}
            >
              导入 {selectedCount} 章
            </Button>
          </>
        ) : step === 'done' ? (
          <Button tone="primary" onClick={onClose}>
            完成
          </Button>
        ) : undefined
      }
    >
      {step === 'source' && (
        <Tabs
          value={tab}
          onValueChange={setTab}
          items={[
            { value: 'file', label: '文件', icon: <FileUp /> },
            { value: 'text', label: '粘贴文本', icon: <BookOpen /> },
          ]}
          className="h-full"
        >
          <TabPanel value="file" className="pt-3">
            <input
              id="book-import-file"
              type="file"
              accept={ACCEPT}
              className="hidden"
              onChange={(event) => pickFile(event.target.files?.[0])}
            />
            <label
              htmlFor="book-import-file"
              onDragOver={(event) => {
                event.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
              className={cn(
                'flex h-56 cursor-pointer flex-col items-center justify-center gap-2.5 rounded-lg border border-dashed transition-colors',
                dragging
                  ? 'border-celadon-500 bg-celadon-900/20'
                  : 'border-ink-700 bg-ink-950/40 hover:border-ink-500',
              )}
            >
              {previewMutation.isPending ? (
                <Loader2 className="size-6 animate-spin text-celadon-400" />
              ) : (
                <FileUp className="size-6 text-ink-500" />
              )}
              <span className="text-xs text-ink-300">
                {file !== null ? file.name : '拖入 txt / epub，或点击选择文件'}
              </span>
              {file !== null && (
                <span className="tnum text-2xs text-ink-500">
                  {(file.size / 1024 / 1024).toFixed(1)} MB
                </span>
              )}
              <span className="text-2xs text-ink-600">按章节标题自动分卷分章</span>
            </label>
          </TabPanel>

          <TabPanel value="text" className="flex flex-col gap-3 pt-3">
            <Field label="来源名（可选）">
              {(props) => (
                <Input
                  {...props}
                  value={sourceName}
                  onChange={(event) => setSourceName(event.target.value)}
                  placeholder="例如：我的小说.txt"
                />
              )}
            </Field>
            <Textarea
              value={text}
              onChange={(event) => setText(event.target.value)}
              placeholder="粘贴整本书或若干章的正文…"
              className="min-h-48 flex-1 font-mono text-2xs leading-relaxed"
              spellCheck={false}
            />
          </TabPanel>
        </Tabs>
      )}

      {step === 'preview' && preview !== null && (
        <div className="flex h-full min-h-0 gap-4">
          <div className="flex min-w-0 flex-1 flex-col">
            <div className="flex items-center gap-2 pb-2 text-2xs text-ink-400">
              <Badge tone="accent">{preview.format.toUpperCase()}</Badge>
              <span>
                <span className="tnum">{preview.volume_count}</span> 卷 ·{' '}
                <span className="tnum">{preview.chapter_count}</span> 章 · 已选{' '}
                <span className="tnum text-celadon-300">{selectedCount}</span>
              </span>
              <button
                type="button"
                className="ml-auto text-celadon-400 hover:text-celadon-300"
                onClick={() => setDeselected(new Set())}
              >
                全选
              </button>
              <button
                type="button"
                className="text-ink-500 hover:text-ink-300"
                onClick={() =>
                  setDeselected(
                    new Set(
                      volumes.flatMap((volume, v) =>
                        volume.chapters.map((_, c) => chapterKey(v, c)),
                      ),
                    ),
                  )
                }
              >
                全不选
              </button>
            </div>

            {preview.warnings.length > 0 && (
              <div className="mb-2 flex items-start gap-1.5 rounded-md border border-gamboge-700/50 bg-gamboge-900/20 px-2.5 py-2 text-2xs text-gamboge-300">
                <AlertTriangle className="mt-px size-3.5 shrink-0" />
                <span>{preview.warnings.join('；')}</span>
              </div>
            )}

            <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto rounded-md border border-ink-800">
              <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
                {virtualizer.getVirtualItems().map((item) => {
                  const row = rows[item.index];
                  if (row === undefined) return null;
                  return (
                    <div
                      key={item.key}
                      style={{
                        position: 'absolute',
                        top: 0,
                        left: 0,
                        right: 0,
                        height: item.size,
                        transform: `translateY(${item.start}px)`,
                      }}
                    >
                      {row.kind === 'volume' ? (
                        <VolumeCheckRow
                          volume={row.volume}
                          volumeIndex={row.volumeIndex}
                          collapsed={collapsed.has(row.volumeIndex)}
                          deselected={deselected}
                          onToggleCollapse={() => toggleCollapsed(row.volumeIndex)}
                          onToggleAll={() => toggleVolume(row.volumeIndex)}
                        />
                      ) : (
                        <ChapterCheckRow
                          chapter={row.chapter}
                          checked={!deselected.has(chapterKey(row.volumeIndex, row.chapterIndex))}
                          onToggle={() => toggleChapter(row.volumeIndex, row.chapterIndex)}
                        />
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          </div>

          <aside className="flex w-52 shrink-0 flex-col gap-1 overflow-y-auto border-l border-ink-800 pl-4">
            <p className="pb-1 text-2xs font-medium tracking-wide text-ink-500 uppercase">导入选项</p>
            <Switch
              label="快速初始化"
              hint="导入后自动生成摘要、文本块索引、卡片与文风包"
              checked={options.quickInit}
              onCheckedChange={(quickInit) => setOptions({ ...options, quickInit })}
            />
            <Switch
              label="LLM 提取章名"
              hint="对合成标题的章节用模型起更贴切的题目"
              checked={options.autoExtractTitles}
              onCheckedChange={(autoExtractTitles) => setOptions({ ...options, autoExtractTitles })}
            />
            <Switch
              label="覆盖文风卡"
              checked={options.overwriteStyle}
              disabled={!options.quickInit}
              onCheckedChange={(overwriteStyle) => setOptions({ ...options, overwriteStyle })}
            />
            <Switch
              label="覆盖设定卡"
              checked={options.overwriteCards}
              disabled={!options.quickInit}
              onCheckedChange={(overwriteCards) => setOptions({ ...options, overwriteCards })}
            />
            <Slider
              label="人物卡上限"
              min={0}
              max={20}
              value={options.maxCharacterCards}
              disabled={!options.quickInit}
              onValueChange={(maxCharacterCards) => setOptions({ ...options, maxCharacterCards })}
            />
            <Slider
              label="世界卡上限"
              min={0}
              max={30}
              value={options.maxWorldCards}
              disabled={!options.quickInit}
              onValueChange={(maxWorldCards) => setOptions({ ...options, maxWorldCards })}
            />
            <p className="mt-2 flex items-start gap-1 text-2xs leading-relaxed text-ink-600">
              <Sparkles className="mt-px size-3 shrink-0" />
              初始化会调用模型，全书可能耗时较久
            </p>
          </aside>
        </div>
      )}

      {step === 'importing' && (
        <EmptyState
          icon={<Loader2 className="animate-spin" />}
          title="正在导入并初始化"
          description="写草稿、建索引、抽卡片——大书可能要几分钟，别关窗口。"
        />
      )}

      {step === 'done' && result !== null && <ImportResultView result={result} />}
    </Dialog>
  );
}

function VolumeCheckRow({
  volume,
  volumeIndex,
  collapsed,
  deselected,
  onToggleCollapse,
  onToggleAll,
}: {
  volume: ImportPreviewVolume;
  volumeIndex: number;
  collapsed: boolean;
  deselected: ReadonlySet<string>;
  onToggleCollapse: () => void;
  onToggleAll: () => void;
}): ReactNode {
  const checkedCount = volume.chapters.reduce(
    (count, _, c) => count + (deselected.has(chapterKey(volumeIndex, c)) ? 0 : 1),
    0,
  );
  const allChecked = checkedCount === volume.chapters.length && volume.chapters.length > 0;
  const partial = checkedCount > 0 && !allChecked;

  return (
    <div className="flex h-[30px] items-center gap-1.5 border-b border-ink-850 bg-ink-900 px-2">
      <button type="button" onClick={onToggleCollapse} className="text-ink-500 [&_svg]:size-3.5">
        {collapsed ? <ChevronRight /> : <ChevronDown />}
      </button>
      <input
        type="checkbox"
        checked={allChecked}
        ref={(el) => {
          if (el) el.indeterminate = partial;
        }}
        onChange={onToggleAll}
        className="size-3.5 accent-celadon-500"
        aria-label={`选择 ${volume.title}`}
      />
      <span className="truncate text-xs font-medium text-ink-200">{volume.title}</span>
      {volume.explicit && <Badge>分卷</Badge>}
      <span className="tnum ml-auto text-2xs text-ink-500">
        {checkedCount}/{volume.chapters.length}
      </span>
    </div>
  );
}

function ChapterCheckRow({
  chapter,
  checked,
  onToggle,
}: {
  chapter: ImportPreviewChapter;
  checked: boolean;
  onToggle: () => void;
}): ReactNode {
  return (
    <label className="flex h-[30px] cursor-pointer items-center gap-2 px-2 pl-8 text-xs hover:bg-ink-850">
      <input
        type="checkbox"
        checked={checked}
        onChange={onToggle}
        className="size-3.5 accent-celadon-500"
      />
      <span className="truncate text-ink-200">{chapter.title}</span>
      {chapter.synthetic_title && (
        <span title="解析器合成的标题，可在导入时让模型重起">
          <Badge>合成</Badge>
        </span>
      )}
      <span className="tnum ml-auto shrink-0 text-2xs text-ink-600">
        {chapter.word_count.toLocaleString('zh-CN')} 字
      </span>
    </label>
  );
}

function ImportResultView({ result }: { result: BookImportResult }): ReactNode {
  const extraction = result.title_extraction;
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 py-6">
      <span className="flex size-11 items-center justify-center rounded-full bg-celadon-900/60 text-celadon-300">
        <Check className="size-5" />
      </span>
      <div className="text-center">
        <p className="text-sm font-medium text-ink-100">
          已导入 <span className="tnum">{result.imported_volume_count}</span> 卷 ·{' '}
          <span className="tnum">{result.imported_chapter_count}</span> 章
        </p>
        <p className="mt-1 text-2xs text-ink-500">
          {extraction.replaced > 0
            ? `模型重起了 ${extraction.replaced}/${extraction.candidate_count} 个合成标题`
            : extraction.candidate_count > 0
              ? `${extraction.candidate_count} 个合成标题保留原样`
              : '标题均来自原文'}
        </p>
      </div>

      {result.warnings.length > 0 && (
        <div className="flex max-w-md items-start gap-1.5 rounded-md border border-gamboge-700/50 bg-gamboge-900/20 px-3 py-2 text-2xs text-gamboge-300">
          <AlertTriangle className="mt-px size-3.5 shrink-0" />
          <span>{result.warnings.join('；')}</span>
        </div>
      )}

      <div className="w-full max-w-md space-y-1">
        {result.volumes.map((volume) => (
          <div
            key={volume.volume_id}
            className="flex items-center gap-2 rounded border border-ink-800 bg-ink-900 px-3 py-1.5 text-xs"
          >
            <span className="tnum text-2xs text-ink-500">{volume.volume_id}</span>
            <span className="truncate text-ink-200">{volume.title}</span>
            <span className="tnum ml-auto text-2xs text-ink-500">{volume.chapter_count} 章</span>
          </div>
        ))}
      </div>
    </div>
  );
}
