# Agent instructions

Read `CLAUDE.md` in this folder: it is the guide for this repository and applies to every agent and every person,
whatever the tool. The workspace guide one level up (`../CLAUDE.md`, with `../AGENTS.md` as its summary) adds the
cross-repository rules and wins where the two differ.

In short: feature packages with `api` → `application` → `domain` and `infrastructure` implementing what the inner
layers need, enforced by `ArchitectureTest`; one branch per change from a fresh `main`, Conventional Commits,
no AI attribution, no push or pull request before the owner has seen the work; additive API changes with the
OpenAPI snapshot; Flyway migrations only; secrets only in Railway; production only on the owner's explicit "go".
