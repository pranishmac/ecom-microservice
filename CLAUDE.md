# Project Rules

## Decision & Flow Logging (MANDATORY)

Whenever you make a design decision, architectural choice, or any 
non-trivial code change, you MUST:

1. Update `decision.md` — append a new entry (never overwrite old ones)
2. Update `flow.md` — reflect method call changes if control flow changed

Do this BEFORE ending your response. Do not skip even for small changes.

### decision.md format
Append entries in this format:

​```
## [YYYY-MM-DD HH:MM] <short title>
**Context:** what prompted this
**Decision:** what was decided
**Reason:** why this approach over alternatives
**Alternatives considered:** (if any)
**Files touched:** file1.java, file2.tsx
​```

### flow.md format
Maintain as a living document (edit in place, not append-only):

​```
## <Feature/Module name>
`ControllerX.methodA()` 
  → `ServiceX.methodB()` 
    → `RepositoryX.methodC()`

**Last changed:** YYYY-MM-DD — <1-line what changed in this prompt>
​```
Update the relevant module section; add new sections for new modules.

## Enforcement
If a prompt results in ANY code change or design decision, decision.md 
AND flow.md updates are NOT optional — treat them as part of the task, 
not an afterthought.