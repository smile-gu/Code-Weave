# Coding Review Platform

一个 Coding-Review 一体化 Agent 平台。Coding Runtime 复用 Claude Code，Review Harness、工作流控制、可观测能力和评测系统由本项目独立开发。

## M0 范围

M0 用于验证以下技术链路是否可行：

```text
Git repository -> isolated Worktree -> Claude Code -> Git Diff -> Maven verification
```

当前里程碑只包含工程骨架，以及已经冻结的 `M0_RESTRICTED` Claude Code Adapter 契约。当前代码尚未真正执行 Claude Code，也尚未创建 Worktree。

## 仓库结构

```text
apps/                         Web 工作台（预留）
services/control-plane/       Java Spring Boot 工作流控制平面
services/review-harness/      Python Review Harness（预留）
workers/                      语言分析 Worker（预留）
contracts/schemas/            跨语言共享的 JSON Schema
fixtures/spring-boot-demo/    M0 使用的隔离目标项目
benchmarks/spring-boot/       Review 评测用例（预留）
runtime/                      本地运行数据，Git 默认忽略
docs/decisions/               已冻结的技术决策
```

## 环境要求

- Java 21
- Maven 3.9+
- Git 2.40+
- Claude Code CLI 2.1+

## 验证项目

```powershell
.\mvnw.cmd test
```
