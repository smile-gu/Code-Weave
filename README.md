# Coding Review Platform

A Coding-Review integrated agent platform. The coding runtime reuses Claude Code, while the review harness, workflow control, observability, and evaluation system are developed independently.

## M0 scope

M0 proves one technical path:

```text
Git repository -> isolated Worktree -> Claude Code -> Git Diff -> Maven verification
```

The current milestone contains only the engineering baseline and the frozen `M0_RESTRICTED` Claude Code adapter contract. It does not yet execute Claude Code or create Worktrees.

## Repository layout

```text
apps/                         Web workspace (reserved)
services/control-plane/       Java Spring Boot workflow control plane
services/review-harness/      Python review harness (reserved)
workers/                      Language analyzer workers (reserved)
contracts/schemas/            Cross-language JSON Schemas
fixtures/spring-boot-demo/    Isolated M0 target repository fixture
benchmarks/spring-boot/       Review benchmark cases (reserved)
runtime/                      Local run data, ignored by Git
docs/decisions/               Frozen technical decisions
```

## Prerequisites

- Java 21
- Maven 3.9+
- Git 2.40+
- Claude Code CLI 2.1+

## Verify

```powershell
.\mvnw.cmd test
```

