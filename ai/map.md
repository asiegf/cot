# Beads Work Map

Last updated: 2026-05-11

## Open Beads

- P1 generator hot zone:
  - `cot-ai6` — Scope generated property specs to avoid name collisions.
  - `cot-clp` — Preserve or document array cardinality compatibility.
  - `cot-xl8` — Fix enum specs for false and nil values.
- P2 generator/security/docs:
  - `cot-auc` — Handle OpenAPI security OR alternatives.
  - `cot-b7g` — Expand security scheme test coverage.
  - `cot-bgp` — Update deftestgen docstring for security inputs.
  - `cot-ogk` — Align README parameter forwarding note with implementation.
- P2 documentation:
  - `cot-dvr` — Update roadmap for resolved array and inline schema limitations.
  - `cot-nyw` — Add changelog entries for recent user-facing behavior changes.
- P2/P3 e2e test harness:
  - `cot-0z9` — Avoid unresolved macro-generated vars in e2e tests.
- P3 agent/beads setup hygiene:
  - `cot-px8` — Clean up duplicated and placeholder agent instructions.
  - `cot-a04` — Use canonical Beads upstream URL consistently.

## Dispatch State

- In progress on background workers:
  - `cot-xl8` on `codex/cot-xl8-enum-specs`.
  - `cot-dvr` on `codex/cot-dvr-roadmap-update`.
  - `cot-nyw` on `codex/cot-nyw-changelog`.
  - `cot-ogk` on `codex/cot-ogk-readme-forwarding`.
  - `cot-a04` on `codex/cot-a04-beads-url`.
- Queued behind generator hot-zone sequencing:
  - `cot-ai6`, `cot-clp`, `cot-auc`, `cot-b7g`, and `cot-bgp`.
- Queued behind e2e test-file sequencing:
  - `cot-0z9`.
- Queued behind agent-instruction sequencing:
  - `cot-px8`.

## Hot Zones

- `src/cot/generator.clj` is the main hot zone. Beads touching generator implementation/docstring should run sequentially or in tightly coordinated batches.
- E2E test helper files under `test/cot/deftestgen_*_e2e_test.clj` are isolated from docs and may run in parallel with documentation work.
- Documentation files (`readme.md`, `roadmap.md`, `CHANGELOG.md`) may be handled together by one worker to avoid overlapping prose edits.
- Agent/beads setup files (`AGENTS.md`, `CLAUDE.md`, `.beads/README.md`) may be handled together by one worker.
