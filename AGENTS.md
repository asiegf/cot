# Agent Instructions

This repository uses **bd** (Beads) for durable issue tracking. Run `bd prime`
at the start of a session for the full workflow context, then use `bd` for all
task state.

Before changing files, read and follow `ai/AGENT_RULES.md`. Keep `ai/map.md`
current with open bead state when bead status changes.

## Beads Workflow

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work atomically
bd close <id>           # Complete work
bd dolt push            # Push Beads data to the Dolt remote
```

- Use `bd` for all task tracking; do not create markdown TODO lists or ad hoc
  memory files.
- Use `bd remember` for durable project knowledge.
- Issues live in the local Dolt database under `.beads/dolt/`; cross-machine
  sync uses `bd dolt push/pull` via `refs/dolt/data`.
- `.beads/issues.jsonl` is a passive export, not the source of truth.
- See the Beads
  [sync concepts](https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md)
  for the one-screen overview and anti-patterns.

## Non-Interactive Shell Commands

Always use non-interactive flags with file operations to avoid hanging on
confirmation prompts. Shell commands like `cp`, `mv`, and `rm` may be aliased
to include `-i`.

```bash
cp -f source dest
mv -f source dest
rm -f file

rm -rf directory
cp -rf source dest
```

Other commands that may prompt:

- `scp` - use `-o BatchMode=yes`
- `ssh` - use `-o BatchMode=yes`
- `apt-get` - use `-y`
- `brew` - use `HOMEBREW_NO_AUTO_UPDATE=1`

## Session Completion

Before ending a work session:

1. File beads for remaining work.
2. Run relevant quality gates if code changed.
3. Close or update beads for completed and in-progress work.
4. Commit code changes.
5. Push the branch with `git push`.
6. Push Beads state with `bd dolt push` when Beads data changed.
