# Continuity and Compression Delivery

Date: 2026-09-24

## Follow-up Upgrade

The subsequent items 1-4 implementation is documented in `docs/CONTINUITY_UPGRADE.md`.
It adds semantic event identity/state convergence, explicit bounded draft semantic
review, scene-ranked context and isolated nonlinear narrative scopes. The review
endpoint now also reports `semantic_status` and may return `local_and_semantic`
coverage. The long-book live acceptance suite (item 5) remains deferred.
Follow-up verification: 476 backend tests and 87 frontend tests passed; two opt-in
backend tests skipped. Packaging, frontend build and lint passed. Separate startup
commands resolved the earlier launch failure. Isolated services on 3001/8091 passed
runtime HTTP checks and browser verification using a local protocol fixture;
original services on 3000/8080 remain unchanged. See the upgrade document for the
data-directory isolation and rapid-save history fixes and the exact verification
limits. The final isolated backend passed four consecutive ten-case HTTP runs.

The delivery and paid-evaluation figures below describe the preceding delivery,
not a new paid evaluation of these additions.

## Delivered

- Source-aligned evidence validation accepts layout differences without accepting
  rewritten or noncontiguous quotations.
- Generation reads the latest preceding prose directly, protects its ending and
  earlier time/number excerpts, and preserves unresolved gaps outside memory truncation.
- Generated drafts get non-model local checks for precise times, typed identifiers,
  retrospective durations and page-count arithmetic.
- Adoption displays warnings and original-source excerpts. Confirmation rechecks
  source freshness. Interrupted streaming drafts cannot be adopted.
- The archival endpoint requires the current review revision when there are findings;
  both source changes and draft changes invalidate approval.
- Analysis caches use `analysis-v2-continuity`; local review revisions use
  `continuity-local-v2`. There is no automatic whole-book migration or rewriting.

## API Contract

`POST /projects/{projectId}/drafts/{chapter}/continuity-check`

```json
{"content": "the exact draft to review"}
```

Returns `revision`, `source_chapter`, `source_hash`, `draft_hash`,
`coverage: "local_rules_only"`, `issues`, `truncated` and `reference_excerpts`.
It is read-only and makes no model calls.

For confirmed archival, pass the reviewed `continuity_revision` to the existing
session feedback endpoint with `action: "confirm"`. Missing/stale approval for a
draft with findings returns HTTP 409 before archival. Clients must recheck after
source/draft changes, not automatically approve the new revision.

Ordinary manual saves remain available; this is not a restriction on authors editing
their own text. A warning identifies a review need, not proof of contradiction.

## Verification and Build

- Backend regression: 441 passed, one paid live test skipped; local replay enabled.
- Frontend: 83 passed; lint and production build passed.
- Real configured-model evaluation: three calls, 23,259 reported tokens.
- Last paired generation input savings: 20.45%; not a latency or whole-book guarantee.
- The actual generated duration error is detected by a no-cost replay under the
  final rules. Full details and limitations: `docs/LIVE_COMPRESSION_EVALUATION.md`.

Built artifacts:

```text
backend-java/target/moxu-backend.jar
web/dist/
```

Deploy frontend and backend together. Preserve the existing data directory and
provider configuration; retain the current `MOXU_DATA_DIR` setting/startup location.
The running services have not been restarted, and the changes have not been pushed.
No manuscript or model configuration was changed by the evaluation.

This delivery provides bounded continuity safeguards and a human review gate, not
a claim of complete automatic semantic verification or a hallucination-free model.
