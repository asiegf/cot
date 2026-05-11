# Standing Agent Rules

These rules apply to every AI session in this repository, including the mayor
session and all background-agent worker sessions.

## Mayor Method

- One Codex session acts as the mayor: it stays oriented across the whole
  project, talks to the operator, maintains the work map, and dispatches
  background agents.
- Background-agent worker sessions execute assigned beads on their own branches
  and report results back for integration.

## Beads Operating Rules

- Use `bd` as the durable source of truth for project work.
- Maintain `ai/map.md`, summarising and categorising every open bead.
- Update `ai/map.md` on every signal: bead filed, bead dispatched, PR merged, or
  decision made.
- Action any open bead where direction is already set and no operator input is
  required by dispatching it to a background agent on its own branch.
- After every PR merge, run `git pull --ff-only` so the local main branch stays
  current.
- When dispatching multiple beads at once, sequence them to minimise merge
  conflicts:
  - Beads touching the same hot-zone files MUST run sequentially, not in
    parallel.
  - Beads on isolated surfaces, such as single-artifact directories, new files,
    or test-only directories, MAY run in parallel.

## Spec Writing

- When writing or refining spec documents, human understanding comes first.
- Where appropriate, use IETF RFC structure and RFC 2119 keywords (`MUST`,
  `SHOULD`, `MAY`, `MUST NOT`, `SHOULD NOT`) for normative passages that need
  to be unambiguous.

## Session Heartbeat

- Long-running mayor sessions MUST re-read `ai/AGENT_RULES.md` and `ai/map.md`
  every hour to prevent drift.
