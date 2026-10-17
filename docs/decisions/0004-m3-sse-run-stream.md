# ADR-0004：M3 Run 事件 SSE 实时通道

- 状态：M3 已采纳
- 日期：2026-08-17

## 背景

M1 已提供按 `sequence` 增量查询事件的 REST API，M2 已提供业务结果与 Unified Diff。
Web 工作台可以通过轮询展示任务过程，但当前文件事件日志每次读取都会校验完整 NDJSON 文件，持续轮询会重复产生磁盘读取，也无法提供理想的实时体验。

## 决策

M3 使用 Server-Sent Events 作为 Run 事件的主要实时通道：

```text
RunEventJournal durable append
    -> RunEventStreamHub best-effort publish
        -> GET /api/v1/legacy/worktree-runs/{runId}/stream
            -> Web EventSource
```

接口支持 `afterSequence` 查询参数和浏览器自动发送的 `Last-Event-ID`。每条 SSE 使用 Run 事件序号作为 `id`，固定事件名为 `run-event`，完整 `RunEvent` 作为 `data`。

连接建立时先注册实时订阅，再读取并发送持久化历史，最后消费订阅期间进入缓冲区的新事件。发送端根据 `sequence` 去重，避免历史补发与实时通知之间出现丢失或乱序。

## 可靠性边界

- `FileRunEventJournal` 仍是唯一事实源。
- 事件必须先持久化成功，才能进入实时通知通道。
- SSE 投递失败、客户端断开或缓冲区溢出不能导致 Coding Run 失败。
- 每个订阅使用有限内存缓冲；慢客户端被断开后通过 `sequence` 重连恢复。
- 心跳只用于保持连接，不写入事实日志。
- `GET /events?afterSequence=` 继续保留，作为排障、测试和手动重放接口。

## 技术选择

Control Plane 继续使用 Spring MVC，通过 `SseEmitter` 输出事件。每条长连接由 Java 21 虚拟线程负责发送，不引入 WebFlux、WebSocket 或消息队列。

## 暂不实现

- 服务重启后的 Run 状态恢复
- 跨 Control Plane 实例广播
- 远程多用户鉴权
- 浏览器客户端消费确认
- 事件长期归档和索引
