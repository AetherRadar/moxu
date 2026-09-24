# Continuity Upgrade

Date: 2026-09-24

This iteration implements items 1-4 of the follow-up. Item 5, a long-book live-model
acceptance suite, is deferred. No paid provider evaluation is part of this iteration.

## Incremental Causal Analysis

- Event identity uses chapter, frame, narrative scope, normalized claims and duplicate
  occurrence, not the whole chapter hash or evidence offsets.
- Evidence is always relocated against the current manuscript revision. Stable event
  identity is not permission to reuse an old citation or author approval.
- Impact calculation compares replayed prerequisites, effects, confidence thresholds
  and uncertainty. Equal clean state stops propagation; missing producers and tainted
  dependencies still affect downstream consumers.
- Independent state assignments can stop propagation. A dependent assignment with
  unresolved prerequisites cannot silently clear uncertainty.
- Segment extraction caches use literal segment content, chapter, model configuration
  and extractor version. Unchanged segments can be reused within an edited chapter.
- The graph is still replayed locally; this is not a persistent graph database or an
  assertion that arbitrary prose edits have a perfectly known semantic effect.

## Draft Semantic Review

The existing read-only continuity endpoint now includes `semantic_status`:
`unchecked`, `partial`, or `complete`. Checks and archival do not make model calls.

An explicit request starts bounded model work:

```text
POST /projects/{projectId}/drafts/{chapter}/semantic-check
```

```json
{
  "content": "exact draft preview",
  "revision": "revision from the latest continuity-check response",
  "max_tokens": 60000
}
```

- Reuses cached causal extraction from every written predecessor. Missing or outdated
  predecessor extraction returns 409 and requires the existing causal synchronization.
- Never includes current saved prose or future chapters as predecessor evidence.
- Maximum draft length is 16,000 UTF-16 characters. At most two extraction segments
  and two suspect-pair verification calls; the existing call ledger enforces a maximum
  of four calls, a three-minute admission deadline and a token budget.
- Token budgets are 1,000-200,000; zero is not unlimited. Admission estimates are
  conservative, not a guarantee of provider-reported usage. In-flight network requests
  retain the provider's timeout.
- Extracted prerequisites cover call/contact status, possession, location, injuries,
  event outcomes and knowledge. Only suspected pairs need model verification.
- Missing prerequisites, unresolved chronology and limits remain visible warnings.
  A completed check can still contain unresolved findings; it is not story approval.
- Report revisions bind the exact draft, all preceding source revisions, model
  configuration, extraction/review versions and findings.
- Author confirmation is rechecked without model calls. Source edits, draft edits or
  a new semantic report invalidate the old acknowledgement.
- Authors can explicitly choose manual confirmation instead of model review. Neither
  path automatically edits prose. Plain saves remain unrestricted.

The frontend shows a budget input and semantic-review action alongside the existing
evidence-based confirmation flow. Failures and partial results do not auto-continue.

## Scene-Aware Context

- The continuity pool remains bounded at 5,200 characters, excluding labels and the
  separately bounded scene brief and unresolved gaps. This is not an exact token cap.
- Protect the latest source ending and time/number anchors first. Select additional
  predecessor excerpts by scene goal, named characters and temporal context.
- Remaining capacity goes to ranked working-memory units instead of a prefix cut.
  Short preceding chapters leave more room for memory.
- Source-labelled bullet records retain their section headings. Complete sentences
  keep negation, decimal quantities and surrogate pairs intact.
- Constraints outrank background; retained units keep their source order. Oversized
  scene goals, hard constraints or unresolved-gap sets stop generation with a message
  instead of being silently cut. Excerpt selection does not promise to retain every
  constraint present anywhere in a long source chapter.

## Nonlinear Narration

`causal-v2` adds optional narrative metadata to events:

```json
{
  "narrative": {
    "time": "2001-01-02",
    "timeQuote": "2001年1月2日",
    "perspective": "",
    "scope": ""
  }
}
```

- Explicit dates accept ISO or Chinese calendar-date evidence. An inferred relative
  date is not enough to reorder events.
- Objective events are chronologically replayed only when all objective events in
  the reviewed graph carry explicit dates. Same-day flashbacks remain uncertain.
- Undated or ambiguously ordered flashbacks cannot overwrite present state.
- Dream, hypothetical and belief events use separate chapter/scene/perspective state.
  Unspecified scopes or perspective holders remain uncertain.
- Subjective knowledge does not automatically become objective fact or another
  character's knowledge. Broad narrative understanding remains model-dependent.

## Compatibility

- Existing five-field event constructors and missing narrative JSON fields default
  to forward narrative metadata. Other extraction fields remain strictly validated.
- Old causal extraction caches/reports are invalidated by `causal-v2`; no automatic
  paid migration or manuscript rewrite runs.
- Deploy frontend and backend together. Preserve the existing data directory and
  provider configuration.

## Verification

- Backend: 478 discovered, 476 passed, zero failures/errors, two opt-in tests skipped
  (paid evaluation and saved-output replay).
- Frontend: 87 tests across 14 files passed; lint, TypeScript and production build passed.
- Full backend regression passed; subsequent `mvn package -DskipTests` rebuilt the
  tested sources successfully. Updated outputs:
  `backend-java/target/moxu-backend.jar` and `web/dist/`.
- Regressions cover semantic no-op edits, removed producers, long dependency chains,
  convergence at independent state assignments, evidence relocation, segment reuse,
  explicit calendar ordering, subjective-state isolation, retrospective conflicts,
  call-state mismatch, stale approvals, partial review, budget validation and
  scene-selected constraints.
- `git diff --check` passed. Existing manuscript/provider data was not edited by this
  iteration. No code was committed or pushed.
- The initial combined background-launch command failed. Retrying with separate
  commands succeeded. A new isolated frontend/backend is running on 3001/8091;
  the existing services on 3000/8080 were not replaced or restarted.
- No new paid model calls or long-book live acceptance evaluation were performed.

## Runtime Verification Follow-up

Actual startup found a data-isolation defect: a nonexistent explicitly configured
data directory silently fell back to the repository's original `data`. The first
test instance was stopped after read-only project listing; no manuscript write or
model request was made through that instance. `BaseStorage` now always honors its
configured data path, rejects non-directory paths, and creates missing directories
only through normal writes. There is no implicit `../data` discovery; use an explicit
`MOXU_DATA_DIR` or `--moxu.data-dir` when starting from another working directory.

The isolated backend now uses `backend-java/target/continuity-smoke/data`. A local
deterministic OpenAI-compatible fixture runs on loopback port 8092, with no real
credentials or external requests. This validates integration, not model quality.

Reproducible fixture/check script:

```text
node scripts/continuity-runtime-smoke.mjs serve
node scripts/continuity-runtime-smoke.mjs check
```

The check refuses servers containing non-test projects. It verifies ten runtime
cases: isolation, no-cost read-only checks, missing-prefix rejection, causal
extraction, semantic conflict evidence, cached review reuse, stale archival
acknowledgement (409), budget admission, resume and provider failure.
After the final storage fix, four consecutive runs passed all ten cases. Results
are saved under `backend-java/target/continuity-smoke/runtime-results*.json`.
Repeated checks preserve the existing browser test chapter.

Rapid runtime saves also exposed second-resolution history filename collisions.
History now uses uniquely created backup files, and copies the previous manuscript
before atomically replacing the current text instead of moving it out of place.
Current/final saves share a normalized chapter-path lock; unchanged text does not
rotate history. Three regression tests cover rapid saves and three-version
retention, failed replacement preserving current text, and concurrent saves using
canonical chapter aliases. This is single-process serialization, not a
cross-process lock or a multi-file metadata transaction.

Browser verification exercised actual writing, follow-up questions, generated
preview, adoption review, a 1,000-token partial check, a 60,000-token retry and saved
editor content. It also verified model-configuration invalidation, HTTP 500 handling
and successful retry. Default and 390x844 viewport screenshots showed usable review
controls without overlap; captured browser warning/error logs were empty. An empty
source-excerpt heading found during this check was fixed and regression-tested.
After restarting the final backend package, browser refresh retained the saved
sample and the frontend proxy remained reachable.

Production services and manuscripts remain untouched. Successful real-model
archival and long-book quality acceptance are not claimed by this smoke test.
