# Live compression evaluation

## Delivery Verification on 2026-09-24

The delivered protection now has three independent parts: bounded verbatim source
anchors plus the chapter ending before generation, local post-generation warnings,
and explicit revision-bound confirmation before archival.

Changes since the preceding retest:

- Early time/identifier/duration excerpts have a separate 600-character budget;
  the latest predecessor ending retains its 2,000-character budget.
- Unresolved gaps remain visible even when working memory is present and truncated.
- New precise clock times, typed identifiers, and retrospective durations absent
  from the preceding source are warnings, not assertions of semantic contradiction.
  Page-count arithmetic checks are also included.
- The adoption UI shows findings and relevant source quotations. Confirmation
  rechecks the current source. Failed checks do not adopt or finalize a draft.
- Finalization independently checks a revision bound to project, chapter, source
  and draft content. Changing source or draft invalidates the previous approval.
- Failed, empty, or error-sentinel streaming responses cannot be adopted as finished drafts.
- Ordinary writer saves retain local findings in pending confirmations. Local checks
  and confirmations make no model requests and never rewrite prose automatically.

### Last Paid Comparison

Private artifact directory:

```text
backend-java/target/live-evaluation/2026-09-24T03-22-19.737484400Z-b769f5c5/
```

| Stage | Prompt tokens | Completion tokens | Total tokens | Wall time |
| --- | --- | --- | --- | --- |
| Fresh extraction | 3,904 | 1,614 | 5,518 | 13,037 ms |
| Full-source reference | 8,107 | 1,633 | 9,740 | 11,333 ms |
| Protected compressed context | 6,449 | 1,552 | 8,001 | 15,743 ms |

Three requests used **23,259 provider-reported tokens**. Generation input was
**20.45% smaller** and generation total tokens **17.85% smaller**. Generation was
slower in this sample; do not equate input compression with latency savings.
The original manuscript/configuration hashes remained unchanged.

Assistant inspection found the card in the inner pocket and no invented 13:04
timestamp. It also found a new error: a thirty-second missed call became a
seven-minute call, and the phone-contact state has an additional continuity concern.
Therefore this output is **not globally quality-approved**.

The initial local report had zero findings because duration checking was not yet
implemented. After adding `continuity-local-v2`, the exact same saved output was
replayed without a model call. `compressed-continuity-review-v2.json` correctly
reports `unverified_past_duration` at "seven minutes" and includes the original
thirty-second missed-call sentence for comparison. The original report remains
available rather than being overwritten.

The duration rule and its additional prompt anchors were added after the last paid
comparison and verified through regression/replay, not an additional paid generation.
This is the intended failure-handling result: a known error becomes review-required,
not silently accepted or silently rewritten.

### Final Verification

- Backend: 442 tests discovered, 441 passed, zero failures/errors, one paid live test
  skipped in the final regression run. The local-only replay test was explicitly enabled.
- Frontend: 83 tests passed, including review/adoption/finalization and interrupted-stream paths.
- Frontend lint, TypeScript/build and backend packaging passed.
- Executable backend: `backend-java/target/moxu-backend.jar`.
- Frontend output: `web/dist/`.
- Current services were not restarted. Frontend/backend updates must be deployed together.

Coverage remains explicitly `local_rules_only`. These checks are not full semantic
causal analysis, do not validate all earlier chapters, and cannot certify fiction as
contradiction-free. Legitimate new plot details may trigger a warning and remain the
author's decision. Existing full-book continuity/causal workflows remain separate.

## Fix and Retest on 2026-09-24

Implemented after the user's request to fix the observed failures:

- `CanonEvidenceGuard` permits layout-only whitespace differences while preserving
  source offsets and restoring the accepted evidence to the exact original substring.
  It does not accept reordered sentences, noncontiguous quotes, changed punctuation,
  deleted negation, or joined Latin words/numbers. Sentence-level uncertainty checks
  also cover qualifiers separated by a line wrap.
- Combined and legacy extraction prompts explicitly request the final location,
  possession/storage of important objects, latest injury condition and restrictions.
- Writer prompt construction reads up to 2,000 characters from the nearest preceding
  written chapter directly from disk on each generation. This separate source excerpt
  survives the 2,600-character working-memory cap. Both streaming entry points and
  regular generation use it, without requiring re-archival after manual edits.
- Predecessors are selected in numeric volume/chapter order; current/future chapters
  and empty plans are excluded. A final draft takes priority over legacy versions.
  Read failures stop generation rather than substituting an older chapter.
- Analysis prompt version is now `analysis-v2-continuity`. Explicitly requested
  analyses do not reuse candidates from the old prompt. No book-wide migration runs.

The isolated three-call retest used the same configured Gemini alias:

| Stage | Prompt tokens | Completion tokens | Total tokens | Wall time |
| --- | --- | --- | --- | --- |
| Fresh extraction | 3,904 | 1,460 | 5,364 | 7,633 ms |
| Full-source reference | 8,071 | 2,124 | 10,195 | 12,817 ms |
| Compressed plus protected ending | 6,373 | 1,431 | 7,804 | 9,834 ms |

All calls completed with `finish_reason=stop`, using **23,363 provider-reported
tokens in 3 calls**. Generation input savings are **21.04%**, not the previous
40.61%: preserving source continuity intentionally spends some of the savings.
The full-source reference already contains the entire ending and therefore does
not get a duplicate ending excerpt. Scene goal and fresh summary remain shared.

Source-grounded inspection:

- All five freshly extracted facts were admitted with located original-source
  evidence; `pending_facts` was empty in this sample.
- Extracted character state and summary state changes explicitly retained the
  half student ID in the jacket's inner pocket.
- The actual compressed prompt includes the pocket action, the roll-call warning,
  the hiding place and the cold/no-longer-hot ending condition.
- The compressed continuation keeps the ID in the inner pocket instead of putting
  it in his hand. It also preserves the injured right palm's cold/numb condition.
- **Remaining quality failure:** the compressed continuation invents a precise
  13:04 roll-call time. The source describes the class as early the next morning
  and supplies no such timestamp. The source-ending protection does not capture
  every fact elsewhere in a chapter and does not make generation hallucination-free.
  This retest verifies the specific card-location fix, not overall story acceptance.

This remains a controlled single-chapter comparison and assistant inspection, not
UI end-to-end testing, human editorial approval, or a many-chapter statistical result.
No additional paid retest was made after these three calls. The later legacy-prompt
parity edits do not affect the combined extraction path used in this live run.

Private, ignored artifacts:

```text
backend-java/target/live-evaluation/2026-09-24T02-51-41.574236500Z-53e8be28/
```

The original manuscript and configuration hashes matched before and after the run.
Existing services were not restarted; this run executed the freshly compiled Java
implementation directly. Restart the backend when deploying the change.
Final offline backend regression: 427 discovered tests, 426 passed, zero failures
or errors, one opt-in live test skipped. The 13 added regression cases cover evidence
alignment/rejection, fresh bounded predecessor reads, both writer entry paths and
old-prompt cache invalidation. `git diff --check` also passed.

## Successful Retest on 2026-09-24

After the user requested another test, both small direct requests succeeded using the
existing configured endpoint and credential. The project configuration was not changed.
The earlier failures below describe those attempts only, not persistent unavailability.

| Direct smoke request | HTTP | Result | Wall time | Reported total tokens |
| --- | --- | --- | --- | --- |
| `claude-fable-5-dd-muidem-b021-sso-tpg` | 200 | OK | 2,542 ms | 117 |
| `Gemini-3.8-Flash限量` | 200 | OK | 3,583 ms | 99 |

Then the current Java harness completed all three actual calls through the project's
gateway with the configured Gemini alias, without changing models or retrying:

| Stage | Prompt tokens | Completion tokens | Total tokens | Wall time |
| --- | --- | --- | --- | --- |
| Fresh full-source extraction | 3,835 | 1,536 | 5,371 | 8,567 ms |
| Full-source reference continuation | 8,018 | 1,347 | 9,365 | 18,130 ms |
| Working-memory continuation | 4,762 | 1,492 | 6,254 | 17,554 ms |

All three returned `finish_reason=stop`. These are provider-reported usage figures,
not independently verified billing. Including the two smoke requests, this retest
used **5 requests / 21,206 reported tokens**. No further paid requests were made.

Measured effects in this one paired sample:

- Generation input tokens: **40.61% lower**.
- Generation input plus output tokens: **33.22% lower**.
- Including the same fresh extraction in each arm: 14,736 versus 11,625 tokens,
  **21.11% lower**. The extraction was actually run once and shared.
- Generation latency: 18.13 versus 17.55 seconds; the input savings did not produce
  a comparable latency reduction.
- Neither arm was output-truncated. A completed API request is not a quality pass.

### Source-Grounded Inspection

This is assistant inspection against the source, not human editorial acceptance,
an independent model judge, or a multi-sample quality benchmark.

**The compressed continuation has a concrete continuity error.**
The source explicitly puts the half student ID into the jacket's inner pocket.
The full-source continuation keeps it there. The compressed continuation opens with
the ID clenched between the protagonist's fingers and pressing into his injured palm,
without a retrieval action. The pocket state is absent from the fresh extraction,
the working memory and the final compressed prompt.

Three extracted facts were also moved into `pending_facts` by `CanonEvidenceGuard`.
All three quotes fail exact substring matching but match after removing whitespace
only. They span paragraph/line boundaries that the model joined:

- The ordinary roll call skips number 14.
- The student ID warns not to answer roll call.
- The entity leaves after calling Chen Shuo, then calls number 14 / Zhou Xun.

The final compressed prompt lacks the explicit warning and the source's ending
temperature state ("no longer hot"). It does retain the protagonist hiding under
the lectern through the shared summary. Missing details alone do not establish a
contradiction, but the card-location error does.

Limitations relevant to attribution:

- This harness supplies freshly extracted facts, brief summary and retrieved chunks
  to the real memory builder. It does not run the entire production research planner
  or include every production character/world card, character-state record or gap.
  Do not attribute every omission to the memory builder itself.
- A single generation per arm cannot estimate an error rate or prove causation.
- The full-source continuation introduces new scene details, and has not received
  comprehensive editorial approval either.
- No compression/evidence-validation production fix had been applied in that retest.
  Whitespace-aware evidence location, explicit end-of-chapter state retention and
  provenance-preserving compression need regression tests and a subsequent live retest.
  Do not use fuzzy semantic matching to automatically approve unsupported evidence.

Successful run artifacts, private and git-ignored:

```text
backend-java/target/live-evaluation/2026-09-24T02-37-17.253948800Z-9bc1abbc/
  full-source-draft.md
  compressed-draft.md
  full-source-messages.json
  compressed-messages.json
  working-memory.txt
  extracted.json
  evidence.json
  source.md
  calls.json
  ledger.json
  budget.json
  original-hashes.json
  original-hashes-after.json
```

Original source and model configuration hashes matched. No manuscript was archived,
no draft was saved into the real project, and no service was restarted.
The live JUnit test passed its execution checks; it has no automatic semantic quality
assertion and therefore **does not certify compression quality**.

## Earlier Failed Attempts on 2026-09-24

Status at that time: **HTTP failures; no generated drafts or compression quality result**.

- Source: the existing project's `V1C1/final.md`, read into an isolated temporary store.
- Model: configured alias `Gemini-3.8-Flash限量`, shared by writer and archivist.
  This is a configuration label, not verification of the underlying model.
- Two single-attempt extraction calls were made. Neither returned an extraction.
- Attempt 1: HTTP exception, 1,233 ms. The original recorder retained the exception type,
  but not its HTTP status. Its status cannot be reconstructed.
- A separate, non-generation model-list request returned HTTP 200 and included the alias.
  Being listed does not establish that inference is available.
- Attempt 2: HTTP 503, 630 ms, no Retry-After value.
- Each extraction request contained 5,440 characters / 13,886 UTF-8 bytes across messages,
  with a 3,000 output-token cap.
- Neither failed call reported token usage. The ledger conservatively charged 16,950
  budget units per attempt, 33,900 total. **This is not measured token consumption or billing.**
- No automatic retries, model substitution, manuscript writes, config writes, commits,
  deployments, or service restarts were performed.
- Original manuscript and the two model configuration file hashes matched before and
  after both attempts.

Local ignored artifacts:

```text
backend-java/target/live-evaluation/2026-09-24T01-59-47.007498500Z-fff355bb/
backend-java/target/live-evaluation/2026-09-24T02-01-43.762742700Z-d619bce1/
```

These directories contain the source snapshot, outgoing extraction messages, local
digest diagnostic, hashes, calls and budget ledgers. There are **no generated draft
files**, because execution stopped before generation. They contain private manuscript
text; do not publish them. Maven clean may remove these local artifacts.

## Reproducible Harness

`LiveCompressionEvaluationTest` is disabled unless `MOXU_LIVE_EVAL=true`.
Normal test runs cannot invoke the provider through this harness.

The sample is specifically a continuation of the inspected campus-suspense manuscript,
with Chen Shuo as protagonist. Do not reuse its fixed scene goal as a generic benchmark.

Intended sequence:

1. Actual `ArchivistAgent.analyzeCombined`, using the full current source.
2. Actual `Orchestrator.buildWorkingMemory`, using extracted facts/summary and local
   `DraftStorage.searchTextChunks`.
3. Two actual writer prompts, with the same scene goal and fresh summary:
   one supplemented with the full source inside the data zone, one using working memory.
4. Single-attempt non-streaming model calls and actual writer postprocessing.
5. Source-grounded inspection of both drafts, with separate accounting for extraction,
   generation input, generation output, and latency.

This isolates compression and message assembly. It does **not** exercise the UI,
multi-round research, persistent sync jobs, whole-book causality, or streaming transport.
The full-source arm is a controlled reference, not a claim about an older product version.
Retrieved evidence is assembled by the harness, not the full production research planner.
No unchanged chapter is re-archived.

Historical example from the repository root, carrying the first two failed attempts
forward. The subsequent successful retest above used a newly announced five-call
allowance; these historical counts must not be reused as its current remaining budget:

```powershell
$env:MOXU_LIVE_EVAL = 'true'
$env:MOXU_LIVE_DATA = Join-Path (Get-Location) 'data'
$env:MOXU_LIVE_PROJECT = '<existing project directory name>'
# Carry forward the two attempts in this evaluation; do not reset the budget.
$env:MOXU_LIVE_PRIOR_CALLS = '2'
$env:MOXU_LIVE_PRIOR_TOKENS = '33900'
try {
    mvn -f backend-java/pom.xml -Dtest=LiveCompressionEvaluationTest test
} finally {
    Remove-Item Env:MOXU_LIVE_EVAL
    Remove-Item Env:MOXU_LIVE_DATA
    Remove-Item Env:MOXU_LIVE_PROJECT
    Remove-Item Env:MOXU_LIVE_PRIOR_CALLS
    Remove-Item Env:MOXU_LIVE_PRIOR_TOKENS
}
```

At that earlier point the remaining allowance was three calls and 66,100 conservatively
accounted tokens. The successful retest's separate five-call allowance is now exhausted.
The 100,000-token admission budget is not an invoice guarantee. Unknown failed-call usage
is charged conservatively. Runs are not automatically resumed; carry prior counts forward.
Do not rerun successful paid stages to recover missing report files.

Acceptance must distinguish a fact omitted from a short continuation from a contradiction.
Inspect the card warning, student numbers, card location, right-palm state, hiding location,
and ordinary versus supernatural roll calls against the source. Unresolved mysteries
must not become invented established facts. One chapter cannot validate hundreds of
chapters, a compression ratio target, or overall story quality.
