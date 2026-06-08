# Beads Work Map

Last updated: 2026-06-08

## Open Beads

- P1 schema isolation:
  - `cot-na6` — Scope generated property specs by schema. Closed locally.
- P1 generated test grouping:
  - `cot-cti` — Allow multiple deftestgen invocations per namespace. Closed locally.
- P1 response validation:
  - `cot-v60` — Restore response body validation against OpenAPI. Closed locally.
- P3 repository hygiene:
  - `cot-ht7` — Version AI coordination files in gitignore. Closed locally.
- P2 validation diagnostics:
  - `cot-msz` — Improve invalid validation failure diagnostics. Closed locally.
- P3 environment validation:
  - `cot-13j` — Validate `#env` profile value in example. Closed locally.
- P3 example inputs:
  - `cot-ouo` — Demonstrate `#env` in example `inputs.edn`. Closed locally.
- P1 validation reporting:
  - `cot-5qz` — Report invalid validation cases at test level. Closed locally.
- P1 validation API:
  - `cot-2i8` — Support ordered runtime-configured validation cases. Closed locally.
- P2 integration test alias:
  - `cot-w28` — Add root `clj -X:test-integration` alias for example tests. Closed locally.
- P1 generator hot zone:
  - `cot-ai6` — Scope generated property specs to avoid name collisions. Closed on `codex/cot-ai6-property-spec-scope`.
  - `cot-clp` — Preserve or document array cardinality compatibility. Closed on `codex/cot-clp-array-compatibility`.
- P2 generator/security/docs:
  - `cot-auc` — Handle OpenAPI security OR alternatives. Closed on `codex/cot-auc-security-or-alternatives`.
  - `cot-b7g` — Expand security scheme test coverage. Closed on `codex/cot-b7g-security-coverage`.
  - `cot-bgp` — Update deftestgen docstring for security inputs. Closed on `codex/cot-bgp-deftestgen-docstring`.
  - `cot-ogk` — Align README parameter forwarding note with implementation. Closed on `codex/cot-ogk-readme-forwarding`.
- P2 documentation:
  - `cot-dvr` — Update roadmap for resolved array and inline schema limitations. Closed on `codex/cot-dvr-roadmap-update`.
  - `cot-nyw` — Add changelog entries for recent user-facing behavior changes. Closed on `codex/cot-nyw-changelog`.
- P2/P3 e2e test harness:
  - `cot-1ks` — Report generated-test harness exceptions as failures. Closed on `codex/cot-1ks-e2e-harness-errors`.
  - `cot-0z9` — Avoid unresolved macro-generated vars in e2e tests. Closed on `codex/cot-0z9-e2e-var-resolution`.
- P3 agent/beads setup hygiene:
  - `cot-px8` — Clean up duplicated and placeholder agent instructions. Closed on `codex/cot-px8-agent-instructions`.
  - `cot-a04` — Use canonical Beads upstream URL consistently. Closed on `codex/cot-a04-beads-url`.

## Dispatch State

- In progress on background workers:
  - None.
- Completed on background branches:
  - `cot-ai6` on `codex/cot-ai6-property-spec-scope`.
  - `cot-xl8` on `codex/cot-xl8-enum-specs`.
  - `cot-1ks` on `codex/cot-1ks-e2e-harness-errors`.
  - `cot-dvr` on `codex/cot-dvr-roadmap-update`.
  - `cot-nyw` on `codex/cot-nyw-changelog`.
  - `cot-ogk` on `codex/cot-ogk-readme-forwarding`.
  - `cot-a04` on `codex/cot-a04-beads-url`.
  - `cot-px8` on `codex/cot-px8-agent-instructions`.
  - `cot-clp` on `codex/cot-clp-array-compatibility`.
  - `cot-auc` on `codex/cot-auc-security-or-alternatives`.
  - `cot-bgp` on `codex/cot-bgp-deftestgen-docstring`.
  - `cot-b7g` on `codex/cot-b7g-security-coverage`.
  - `cot-0z9` on `codex/cot-0z9-e2e-var-resolution`.
- Completed locally:
  - `cot-na6` — owner-scoped component and inline-response property specs.
  - `cot-cti` — independent generated-test groups for multiple `deftestgen` forms.
  - `cot-v60` — restored schema-bearing example cases and non-empty object validation.
  - `cot-ht7` — explicit `.gitignore` keep rules for AI/agent and shared Beads files.
  - `cot-msz` — structured invalid-validation failure diagnostics.
  - `cot-w28` — root `clj -X:test-integration` alias.
  - `cot-2i8` — ordered cases, runtime EDN inputs, status-aware validation.
  - `cot-5qz` — invalid validation cases reported at test level.
  - `cot-ouo` — runnable `#env` example in `example/inputs.edn`.
  - `cot-13j` — positive and negative `#env` profile validation.
- Queued behind generator hot-zone sequencing:
  - None.
- Needs retry after e2e branch integration:
  - None.

## Hot Zones

- `src/cot/generator.clj` is the main hot zone. Beads touching generator implementation/docstring should run sequentially or in tightly coordinated batches.
- E2E test helper files under `test/cot/deftestgen_*_e2e_test.clj` are isolated from docs and may run in parallel with documentation work.
- Documentation files (`readme.md`, `roadmap.md`, `CHANGELOG.md`) may be handled together by one worker to avoid overlapping prose edits.
- Agent/beads setup files (`AGENTS.md`, `CLAUDE.md`, `.beads/README.md`) may be handled together by one worker.
