# 批次6：验证位置引用协议离线实施交付

完成记录时间：2026-10-16T18:30:30+08:00。依据[位置引用冻结稿](review-harness-batch-6-position-reference-freeze.md)。基线为HEAD `30a0054f7ab3ec8ed865ce2ff0f18ccd25d7809a` 加此前已复审、保持未提交的JSON诊断/Finding资格补修。本次只做位置引用协议；没有读取密钥、付费调用、真实CC、提交或推送。

## 实现与版本落点

| State | 模型验证协议 | 行为 |
|---|---|---|
| 2.0 | 既有UUID/原文协议 | 保持严格读回与原校验 |
| 3.0 单Pass、4.0 多Pass | 既有局部句柄＋原文协议 | 保持严格读回与原校验，不接受新字段 |
| 5.0 单Pass、6.0 多Pass | `POSITION_V1` | 本次新Run默认；请求明确版本，输出必须带validationVersion |

只按State类型/显式stateVersion选协议，parse_state不按字段猜测。新State具有固定validationProtocol及最多6个validationRecordRefs；未知版本、混交candidateText/sourceQuote及协议缺失拒绝。旧Schema不原地扩字段，新增四份独立Schema。旧Checkpoint可重新打开并精确读回，**不代表允许重放旧Run**；原有零重放/身份/完整性检查保持。

主要变更：

- `domain/position.py`：位置FactCheck、完整行区间、单/多Pass批次及新State。
- `application/position_validation.py`：合法候选字符串叶子JSON Pointer投影、坐标与编号正文展示、来源授权/区间解析及向原内部FactCheck转换。
- `runtime/review.py`：新协议选择、按对应字段修正判断、接受后验证记录发布和Checkpoint绑定；旧原文校验路径保留。
- `bootstrap.py`、`runtime/context.py`、`runtime/multipass.py`、`runtime/compaction.py`、`domain/real_graph.py`：新版本初始化/读回、原多Pass预算与压缩阈值延续、验证Artifact引用保护。没有改执行编排、费用账本、Evidence提交事务或纠错政策。
- 新增Schema：review-graph-state-v5/v6、review-position-validation-v1、review-position-validation-multipass-v1。外部Result2/3、Java/UI合同不改。

## 解析与安全边界

模型只选candidateHandle、candidateField及sourceSelection；不抄写候选或源码。candidateFields仅列出title/claim/category/suggestion和reasoningSummary的现存非空字符串叶子，数组下标逐一列出。身份、权限、引用字段以及任意JSONPath、非法转义/下标均不获授权。

来源展示路径/side、sourceVersion、coordinateKind、带物理行号的numberedText。SOURCE_FILE_LINE为文件行，DIFF_PHYSICAL_LINE为整个Diff文本行，ARTIFACT_PHYSICAL_LINE为Artifact正文行；不新增坐标换算器。**既有base源码来源仍不支持并严格拒绝**，本切片不新增base捕获/读取能力。

提取仅使用EvidenceService已提供的安全材料，不读实时工作区或脱敏前全文。先校验候选专属Evidence、Run/Snapshot、Registry源版本/哈希、完整观察区间，再检查同一授权材料覆盖全区间。相邻完整观察区间由既有Registry合并，未读洞/半行不补齐。原Evidence SourceRef与材料SourceRef均须匹配；多个授权材料内容冲突拒绝，不因相同字符串偶合换来源。解析后显式保留确定的evidenceId/sourceIndex；源码文本4096字符上限不截断，超限要求缩小区间。

提取后继续执行内部FactCheck/身份/来源/哈希/事实修正、核心反例、未解决假设与缺陷资格门禁。CORRECTED按同一candidateField比较原/修正版本：字段消失或未改变（含只加首尾空白）拒绝；不再用原文是否出现在修正对象其他任意字段判断。身份、claimKind、Evidence和代码选择仍不得改。userQuote保持可选且逐字核对，USER_INPUT不能充当实现事实来源。

## 持久化与失确认

每个通过校验的批次先发布不可变SYSTEM/TOOL_OUTPUT验证Artifact：recordVersion、State/Run/Snapshot/Pass/模型调用身份、原State revision、原始位置提交、解析后内部FactCheck、候选原版本摘要、sourceBinding、Evidence槽位及安全提取文本/脱敏状态完整保存。Artifact必须FULL且摘要等于预期，再把引用写入新State，沿原Checkpoint精确确认后才继续。

Artifact发布与Checkpoint**不是一个原子事务**；发布失败、取消或Checkpoint失确认均停止，不自动重试/假报回执。可能有已发布但未被确认State引用的审计Artifact，它不使验证成功。跨Pass及压缩保留validationRecordRefs，纳入原Artifact引用集合上限和完整性核验。验证判断Artifact的producer为SYSTEM，未将其改作工具事实Evidence。

## 红绿与原失败回放

- 首个位置协议测试在模块/协议尚不存在时失败，随后实现严格版本Schema和解析。
- State6消息数量压缩阈值红测观察summaryVersion=0，补齐旧阈值对State6的适用后State4/6均通过，未改阈值。
- 只给原字段加空白的CORRECTED红测曾错误SUCCEEDED；按对应字段去首尾空白比较后拒绝；真修正、原文在别处作为历史引用仍可通过。
- 上次真实第29次回复使用原State4/原协议重放，仍FACT_QUOTE_INVALID，未修改该Run或原产物。另构造POSITION_V1离线对照输入（candidateField=/claim，源区间从原授权材料确定）可解析全部3项并经过原内部门禁。

回放入口：`C:/Users/ROG/AppData/Local/Temp/code-weave-position-offline-fyklv32v/replay_original_failure.py`；输出 original-failure-position-fixture.json、original-failure-resolved-bindings.json、original-failure-replay-summary.json。该对照是离线构造，**不是模型实际修复，也不是历史Run转为成功**。脚本只读原SQLite（mode=ro），输出写在新证据目录。

## 新鲜验收与复现

环境Python3.12.6，日期2026-10-16。证据目录 `C:/Users/ROG/AppData/Local/Temp/code-weave-position-offline-fyklv32v`。

| 阶段 | 命令/范围 | 新鲜结果 |
|---|---|---|
| 整合全量阶段 | `.venv/Scripts/python.exe -m pytest tests -q --tb=short` | **694通过，785.71秒**，python-full.log |
| 最终影响回归 | 位置单元/新Schema/生产API/Diff Registry/原Schema，共5个文件 | **79通过，104.44秒**，final-affected-v2.log（此前阶段78通过日志亦保留） |
| 最终压缩专项 | test_multipass_runtime.py -k closed_history | **2通过，4.47秒**，含State6验证记录保留 |
| 历史事实修正/多Pass/原Schema中途专项 | 3个文件 | 68通过，208.53秒；是阶段证据，不叠加为最终新增计数 |
| Ruff check | review_harness tests | 通过 |
| Ruff format --check | review_harness tests | 141文件通过 |
| 差异检查 | git diff --check | 通过 |

计数说明：当前收集709例。694项全量运行期间，继续补齐了15项边界并收紧新协议路径的字段比较、源元数据核对、编号展示和压缩引用保护，之后按影响完成79＋2回归。**没有再执行一次最终709项全量命令，不将694称为最终709全量**；这些计数有重叠，不能相加冒称用例总数。测试后没有再改产品代码。

最终影响命令（服务目录下）：

```powershell
.venv/Scripts/python.exe -m pytest tests/unit/test_position_validation.py tests/unit/test_position_schemas.py tests/integration/test_position_review.py tests/integration/test_diff_registry.py tests/integration/test_real_schema_fixtures.py -q --tb=short
.venv/Scripts/python.exe -m pytest tests/integration/test_multipass_runtime.py -q -k closed_history --tb=short
.venv/Scripts/python.exe -m ruff check review_harness tests
.venv/Scripts/python.exe -m ruff format --check review_harness tests
git diff --check
```

覆盖：候选指针/数组下标、完整坐标、未读洞和半行、授权Evidence子区间、源版本/哈希/Run/Snapshot错误、多个Evidence槽位与歧义、final/Diff/Artifact坐标、脱敏不回填、USER_INPUT拒绝、base严格拒绝、真实Git多文件跨页与跨Pass观察隔离、旧省略号协议继续拒绝、public默认权限修正、核心反例/矛盾判断、良性REJECTED、身份不可替换、userQuote真实性、两次额度耗尽无Result、取消、验证记录存储故障、Checkpoint失确认与可确认丢回执、新旧版本读回和Result3六Pass模拟闭环/去重/费用结算。

原子Evidence提交、Snapshot转换、多hunk/无末尾换行等原有回归保留在全量中，未重写历史产物。现有quote类测试明确使用State3兼容夹具，未删例、未把旧模型回复猜成新协议；另有生产默认State5/6 API测试。未重跑Java/UI或官方干净环境；没有相关合同/产品修改，旧证据仅作历史。

## 限制与复审入口

位置合法、原文提取正确不证明模型语义判断正确；一个字段内每个事实是否充分核对、修正是否充分仍是模型责任。未新增Java解析器或确定性语义分类器。编号展示增加的请求内容继续由完整请求计量和原预算门禁治理，不静默删除关键材料。两次共享纠错、费用与调用上限、取消和资源释放策略不变。

本轮只有离线实现/模拟验证，未发送付费模型请求，未跑CC，未提交推送。原真实Run仍FAILED，无真实最终Result；下一次真实验收必须独立复审后另行授权。

问题日志历史字节：用户已指出此前更新的校验失败。本轮仅把开始时当前日志保存为原始字节基线（146220字节，SHA256 fda168b8b58e423bb13919435719ac1ebeeda7e2a252be069a61a78350e840ce），追加时检查该当前基线及追加前字节前缀；**这不恢复或证明此前历史字节完全保留**。追加收据单独保存，不把仓库外日志纳入Git。
