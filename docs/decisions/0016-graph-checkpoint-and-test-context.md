# ADR-0016：图状态、独立 Checkpoint 与测试模型预算

- 日期：2026-10-10
- 状态：批次 4 实施，待独立复审
- 上游：ADR-0009/0014/0015，批次 4 技术冻结稿
- 基线：dc311d77686c8bdae30e09097af7bb8c97d0cc79

## 决策

使用实际安装并锁定的 LangGraph 1.2.14、langgraph-checkpoint-sqlite 3.1.1（传递版本见 requirements.lock），支持 Python 3.12。StateGraph 最小探针为 assemble → model → tools → assemble/结束；工具只走既有 ToolGateway，默认模拟执行器不变。TypedDict 只描述图 channel；ReviewGraphState 在入口、节点输出、持久读取使用 strict/extra=forbid 的生产模型验证。

独立数据库配置为可选 checkpoint_database_url（sqlite+aiosqlite URL，绝对文件路径），未配置时图 Checkpoint 能力 DISABLED。禁止与产品库相同物理文件及链接/路径别名，禁止放在 Snapshot/execution/Artifact 根内；父目录必须存在。不将可选图能力故障伪装成默认模拟 Readiness 故障；认证 graph-capabilities 单独诊断，GET 时重新验证表/列与写访问。

使用 LangGraph 的 AsyncSqliteSaver.setup/aput/aget_tuple 真实实现，setup 管理其 checkpoints/writes 表，不用产品 Alembic 伪装独立库版本。锁定版本的表访问能力在启动验证。采用 JSON-only Serializer，未知 typed 标签拒绝，无 pickle/msgpack 对象构造和模块导入，状态与 blob 有独立字节上限。

配置映射为 thread_id=reviewThreadId、checkpoint_ns=snapshotId、checkpoint_id=平台 UUID。metadata 包含 runId、stateVersion、revision、identityDigest 和完整 stateDigest。每次读取明确 exact checkpointId，独立连接检查完整状态和元数据；不从 namespace 最新记录自动继承。保存后都确认；提交异常时同样独立核对精确内容，确认一致可接受，否则 CHECKPOINT_STATE_WRITE_UNCERTAIN 并停止调用。

图编译器不自动存储任意中间候选。节点通过上述真实 Saver 在明确的状态提交边界保存 gate snapshot，确保未确认 Compaction 不被图运行时提前采用。这些是调查状态快照，不提供调度器恢复/interrupt/resume；不使用 Saver 的最新选择或 pending writes 来重放。重复启动同 Run 的图即拒绝；同 Thread/Snapshot 的新 Run 只能从平台重新生成空调查状态，完全禁止跨 Run 自动继承。Checkpoint 不写 Run.status/resourceState/Result。

模型仅 FakeAdapter：capabilities/build_request/count_request/invoke/normalize_response 明确分离，最终 request 是不可变规范 JSON bytes。test-only-utf8-units 定义为完整测试协议 UTF-8 字节数量，包含 role/content、Tool Schema、包装及输出预留；不是生产 Token。未知模型/计量配置拒绝。Context Manifest FULL/READY 发布与 State 引用提交确认后，重新校验来源、能力、请求 digest，再调用同一 request。响应 Usage 与 estimated 通过 modelCallId 写入有界 State，不新增 ModelCall 表。

P0 来自平台策略、原 Run 用户输入（user角色）、原 Manifest 范围及身份、必需材料与未闭合调用。用户原文不在 State/诊断额外复制，只有目标摘要。仓库、工具与 Summary 均标为不可信数据；Summary 不获得 system 权限。requestDigest 仅在受控内部 Artifact 中，仍不是秘密零泄漏保证。

Compaction 选择连续且闭合的历史前缀，保留最近至少一个闭合单元。多工具调用按 ID 配对，可按实际完成顺序返回；未完成/重复/缺失结果拒绝。摘要请求同样使用完整预算和调用前 Manifest，预算不足只缩小闭合前缀，不递归摘要。摘要脱敏后保存 FULL Artifact，验证引用，重装配完整最终调查请求严格下降，再提交候选 State 并确认后采用。失败保持旧有效视图，提交未知时停止 Run。每请求最多2次尝试，每Run最多8次成功压缩；模型调用（含摘要）和图循环步默认各16。TestWindowExceeded 仅为测试边界，恢复占用同一压缩预算，每次实际调用新 Manifest；未实现真实 Provider 超窗治理。

实际模型/摘要/Checkpoint async Task 按 executionRoot+Run 注册，与文件 Future 一样，App 同进程重建不能误认为旧任务已经停止。Checkpoint 提交任务不通过取消等待包装假装回滚；模型协作取消，残留任务使执行副本 RETAINED、Run FAILED+UNCERTAIN。Run 清理只检查自己的任务，不让 A 的残留任务污染 B。全根最多2个模型/Checkpoint任务，无隐藏排队；此计数与文件线程槽位分别管理。

## 迁移与范围

产品库新增 0004_context_artifacts，只扩展 CONTEXT_MANIFEST/COMPACTION_SUMMARY kind CHECK，不修改0001–0003。旧Artifact保留，包含新kind的降级拒绝数据丢失。State的 Evidence/Candidate 引用本批明确拒绝非空；批次5有真实实体时再扩展及验收。

本批没有真实 Provider、生产 tokenizer、Evidence/Finding/Memory、Pass报告或生产图选择开关。SUCCEEDED 仍只意味着模拟基础设施完成。跨 Run SAFE_FOR_NEW_RUN 属于未来目标，本批实现零继承子集。POSIX 未运行时须明确报告。

2026-10-10复审修复：图Tool信封消费者预算8KiB由ToolGateway统一处理，平台output_budget不在模型参数Schema中。非分页超限可靠Spill，分页缩页保正文/Cursor；State保留完整信封，不用状态摘要隐藏正文丢失。默认服务32KiB上限不变。取消隔离正式测试改事件控制及1/0.5秒人工预算，生产默认预算不变，详见B4-R01/R02交付。
