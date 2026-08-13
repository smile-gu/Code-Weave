# ADR-0001: ClaudeCodeAdapter M0 contract

- Status: Accepted for M0
- Date: 2026-08-13

## Context

The platform needs a reproducible and observable way to invoke the user's existing Claude Code CLI without changing its global configuration. M0 must validate isolation, protocol parsing, cancellation, and verification before broader coding capabilities are enabled.

## Decision

The adapter uses a configurable `CapabilityProfile`. M0 selects `M0_RESTRICTED`; these limits are not the final product capability ceiling.

`M0_RESTRICTED` uses:

```text
--print
--input-format text
--output-format stream-json
--verbose
--no-session-persistence
--safe-mode
--permission-mode dontAsk
--session-id <run UUID>
--max-budget-usd 1.00
--tools Read Glob Grep Edit Write
--allowedTools Read Glob Grep Edit Write
```

The task prompt is sent through UTF-8 standard input. It is never placed on the command line. The process working directory is the Run Worktree.

M0 deliberately excludes Bash, Git operations, Maven execution, network access, user Skills, Hooks, MCP servers, plugins, and Claude's own Worktree management. Git and Maven are executed independently by the Control Plane.

## Event truth

Only emitted protocol events prove tool activity. Text claims such as "I read the file" or "tests passed" are not trusted. File changes are derived from Git Diff, and final verification is derived from the platform Verifier.

Claude events map as follows:

| Claude event | Platform event |
|---|---|
| `system/init` | `agent.runtime.initialized` |
| `system/thinking_tokens` | `agent.usage.updated` |
| assistant `text` | `agent.message.completed` |
| assistant `tool_use` | `tool.call.started` |
| user `tool_result` | `tool.call.completed` or `tool.call.failed` |
| result success | `agent.runtime.completed` |
| result error | `agent.runtime.failed` |

Thinking text is neither persisted nor displayed.

## Failure rules

Adapter failure types are:

- `START_FAILED`: the runtime cannot be started.
- `START_TIMEOUT`: no `system/init` within 30 seconds.
- `WORKSPACE_MISMATCH`: init `cwd` differs from the Worktree.
- `TOOL_POLICY_VIOLATION`: init or events expose an unauthorized tool.
- `PROTOCOL_ERROR`: stdout contains an invalid NDJSON protocol line.
- `INCOMPLETE_STREAM`: the process exits without a terminal result.
- `AGENT_FAILED`: terminal result has `is_error=true`.
- `PROCESS_FAILED`: process exit code is non-zero.
- `TIMED_OUT`: execution exceeds 10 minutes.
- `CANCELLED`: the user cancels the Run.

A single failed tool call does not fail the adapter because the agent may recover. Adapter success and coding task success are separate. Coding requires a policy-compliant Git Diff and a passing independent Maven verification.

## Windows note

The installed CLI exposes `claude.cmd`. Windows launcher and argument escaping are implemented behind a platform-specific process launcher. Dynamic user content must never be concatenated into a shell command.

## Evolution

Later profiles may enable restricted Bash, trusted project instructions, Hooks, Skills, MCP, session recovery, and subagents. The Workflow Control Plane continues to control lifecycle and gates while Claude Code remains autonomous inside the coding stage.

