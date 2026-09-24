import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { AlertTriangle, CheckCheck, RefreshCw, ScanSearch } from 'lucide-react';
import { draftsApi } from '@/api';
import type { DraftContinuityReport } from '@/api/drafts';
import { Button, Input } from '@/components/ui';
import { awaitDraftWrites } from '@/lib/draft-write-barrier';

export function DraftContinuityReview({
  projectId, chapter, content, onReady, onCancel,
}: {
  projectId: string;
  chapter: string;
  content: string;
  onReady: (revision: string) => void;
  onCancel: () => void;
}) {
  const [updated, setUpdated] = useState<DraftContinuityReport | null>(null);
  const [changed, setChanged] = useState(false);
  const [maxTokens, setMaxTokens] = useState(60000);
  const delivered = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  const load = async () => {
    await awaitDraftWrites(projectId);
    return draftsApi.checkContinuity(projectId, chapter, content);
  };
  const query = useQuery({
    queryKey: ['draft-continuity-review', projectId, chapter, content],
    queryFn: load, retry: false, staleTime: 0, gcTime: 0, refetchOnWindowFocus: false,
  });
  const report = updated ?? query.data;
  const semantic = useMutation({
    mutationFn: async () => {
      await awaitDraftWrites(projectId);
      if (!report) throw new Error('尚无核对版本');
      return draftsApi.checkSemantics(projectId, chapter, content, report.revision, maxTokens);
    },
    onSuccess: (fresh) => {
      if (!mounted.current || delivered.current) return;
      setUpdated(fresh);
      setChanged(false);
    },
  });
  const confirm = useMutation({
    mutationFn: load,
    onSuccess: (fresh) => {
      if (!mounted.current || delivered.current) return;
      if (fresh.revision !== report?.revision) {
        setUpdated(fresh);
        setChanged(true);
      } else {
        delivered.current = true;
        onReady(fresh.revision);
      }
    },
  });
  useEffect(() => {
    if (report && report.issues.length === 0 && !delivered.current && !confirm.isError && !query.isError
      && !semantic.isError && !semantic.isPending) {
      delivered.current = true;
      onReady(report.revision);
    }
  }, [report, onReady, confirm.isError, query.isError, semantic.isError, semantic.isPending]);
  const error = query.isError || confirm.isError;
  const busy = query.isFetching || confirm.isPending || semantic.isPending;

  return (
    <section className="space-y-3 border-b border-ink-800 pb-4" aria-label="草稿连续性核对">
      <div className="flex items-center gap-2 text-sm font-medium">
        <AlertTriangle size={16} /> 草稿连续性核对
      </div>
      {busy && <p role="status" className="text-sm text-ink-400">正在核对最新前文…</p>}
      {changed && <p role="alert" className="text-sm text-amber-400">前文已变化，请重新核对以下提示。</p>}
      {error && <p role="alert" className="text-sm text-cinnabar-300">核对未完成，尚未采纳或定稿。</p>}
      {semantic.isError && <p role="alert" className="text-sm text-cinnabar-300">
        {semantic.error instanceof Error ? semantic.error.message : '语义核查失败'}
      </p>}
      {report?.semantic_status === 'partial' && <p role="alert" className="text-sm text-amber-400">语义核查未完成</p>}
      {report && report.issues.length > 0 && (
        <>
          <ul className="max-h-64 space-y-3 overflow-auto text-sm">
            {report.issues.map((issue) => (
              <li key={`${issue.type}:${issue.offset}`}>
                <p>{issue.message}</p>
                <blockquote className="mt-1 break-words border-l-2 border-ink-700 pl-3 text-ink-400">
                  {issue.evidence}
                </blockquote>
              </li>
            ))}
          </ul>
          {report.reference_excerpts?.trim() && <div className="text-sm">
            <p className="mb-1 font-medium">前文原句 · {report.source_chapter}</p>
            <blockquote className="max-h-40 overflow-auto whitespace-pre-wrap break-words border-l-2 border-ink-700 pl-3 text-ink-400">
              {report.reference_excerpts}
            </blockquote>
          </div>}
          {report.truncated && <p className="text-sm text-amber-400">仅显示前 30 项，仍有其他待核实项。</p>}
        </>
      )}
      <div className="flex flex-wrap gap-2">
        <Button tone="outline" onClick={onCancel}>返回草稿</Button>
        {report?.semantic_status && report.semantic_status !== 'not_requested'
          && report.semantic_status !== 'complete' && (
          <>
            <label className="flex items-center gap-2 text-sm">
              Token 上限
              <Input type="number" min={1000} max={200000} step={1000} value={maxTokens}
                disabled={busy} className="w-28"
                onChange={(event) => setMaxTokens(Number(event.target.value))} />
            </label>
            <Button icon={<ScanSearch />} loading={semantic.isPending}
              disabled={busy || error || !Number.isInteger(maxTokens) || maxTokens < 1000 || maxTokens > 200000}
              onClick={() => semantic.mutate()}>语义核查</Button>
          </>
        )}
        {error ? (
          <Button icon={<RefreshCw />} disabled={busy} onClick={() => {
            confirm.reset();
            semantic.reset();
            setUpdated(null);
            void query.refetch();
          }}>重新检查</Button>
        ) : report && report.issues.length > 0 ? (
          <Button tone="primary" icon={<CheckCheck />} loading={busy} onClick={() => confirm.mutate()}>
            {report.semantic_status === 'unchecked' || report.semantic_status === 'partial'
              ? '人工确认，继续' : '已核对，继续'}
          </Button>
        ) : null}
      </div>
    </section>
  );
}
