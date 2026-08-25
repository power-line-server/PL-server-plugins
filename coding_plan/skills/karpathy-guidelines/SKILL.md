---
name: karpathy-guidelines
description: Behavioral guidelines for software development tasks that reduce common LLM coding mistakes. Use when writing, debugging, reviewing, or refactoring code and you want explicit assumptions, simple solutions, surgical edits, and verifiable success criteria instead of over-engineering or broad unrelated changes.
---

# Karpathy Guidelines

Apply cautious coding behavior for nontrivial development work. Bias toward clarity, simplicity, and tight diffs over speed.

For trivial edits, use judgment. Do not force heavy process onto obvious one-line changes.

## 1. Think Before Coding

Before changing code:

- State assumptions that matter.
- Surface ambiguity instead of silently picking one interpretation.
- Present tradeoffs when more than one path is plausible.
- Push back if simpler approach solves problem better.
- Stop and ask when unknowns make implementation risky.

Do not hide confusion behind confident implementation.

## 2. Simplicity First

Solve task with minimum code needed.

- Do not add features user did not ask for.
- Do not introduce abstraction for single-use logic.
- Do not add configurability unless requirement calls for it.
- Do not add speculative error handling for impossible paths.
- Rewrite bulky solution if much smaller version can do same job.

Use standard senior-engineer test:

```text
Would experienced reviewer call this overcomplicated?
```

If yes, simplify.

## 3. Surgical Changes

Touch only code required by task.

- Do not refactor unrelated areas.
- Do not rewrite adjacent comments, formatting, or style without need.
- Match local code style unless user asks otherwise.
- Mention unrelated dead code if noticed, but do not remove it.

Clean only mess created by your own change:

- remove imports made unused by your edit
- remove variables made unused by your edit
- remove helpers made dead by your edit

Every changed line should trace back to user request.

## 4. Goal-Driven Execution

Turn vague task into verifiable target.

Examples:

- bug fix -> reproduce failure, then make check pass
- validation -> add failing case, then make it pass
- refactor -> preserve behavior and confirm before/after checks

For multi-step work, state short plan with verification per step:

```text
1. Change X -> verify: check Y
2. Change A -> verify: check B
3. Cleanup C -> verify: tests or diff review
```

Prefer success criteria that let agent verify progress independently.

## Working Style

During task execution:

- gather local context before editing
- prefer smallest safe patch
- verify with most direct check available
- report risks, blockers, and unverified assumptions clearly

If user asks for review, prioritize bugs, regressions, and missing tests over style commentary.

## Done Criteria

Work follows this skill when:

- assumptions and ambiguities were surfaced before risky edits
- solution stayed as small as task allowed
- diff avoided unrelated churn
- verification matched requested outcome
