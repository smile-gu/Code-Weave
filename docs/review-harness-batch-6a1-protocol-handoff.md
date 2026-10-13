# 批次6A1：计划、Result3.0与版本基础交付

日期：2026-10-13。依据仓库外51正式冻结稿、50确认记录与ADR-0009/0015/0016；基线9ed189b，开始时HEAD一致。仅6A1协议切片，零付费/真实Coding，未暂存/提交/推送；下一切片须先独立复审和按用户纪律授权。

## 已接通的协议边界

- Python新domain/multipass.py，独立ReviewPlan1.0与Result3.0，旧ExecutionContract/Result2.0/State1/2/3不松绑。新增CreateRun1.1必须带完整reviewPlan及3.0执行契约；旧1.0不接受新字段。完整DTO规范字节包含选择和费用授权引用，测试选择改变即改变指纹；实际1.1 RunStore幂等事务在执行接通切片实现。
- 固定六Pass顺序，Python拒绝空/重复/未知/乱序，不推断默认选择。Java ReviewPlan.create明确写六项默认、规范化非空选项；空选择、重复、未知及缺费用授权拒绝。executionBudget固定300/2000/10次和120/1800/10800/600秒，这些是类型契约，尚未改变运行引擎原16次等配置。
- feeAuthorization以UTC微秒、UUID、CNY正定点字符串和priceProfileId/digest记录；最多6位小数/12位整数、精确微元转换，无float。只检验价格引用形状，不伪造已核实价格/持久费用保护。feeSummary精确整数管理金额，unknown须保留预留，overrun保留单次预留超额即便Run预算未突破；不是Provider实扣账单。正式事务/核价/过期/发送保护留给6A2。
- Result3严格六维固定顺序、selected与原计划一致、COMPLETED/INCOMPLETE/SKIPPED及不同skipReason、SELECTED_COMPLETE/INCOMPLETE。未选择/未启动不伪造路径、调用或FULL覆盖；无材料可INCOMPLETE且写原因。字段及跨引用、Run/Snapshot/producer/time、调用总量/去重10、全球Candidate32/Evidence128、授权摘要与1MiB结果边界均检查。
- Finding contributions保留Pass/candidate/evidence/codeReferences/原disposition/validation call；primary固定按Pass顺序、同Pass按candidateId选。不能重复贡献候选/Evidence或把RISK主判断升级；每Pass findingIds绑定贡献谱系，最终仅在primary Pass展示一次。只是谱系一致性，不是实际根因去重；与受信候选内容比较/确定性召回/模型去重编排属于6A3。
- 报告确定性渲染复用既有转义/单Pass部分，3.0外层声明选择、skip与整体覆盖；Python和Java按结构校验Markdown及UTF-8 64KiB。原2.0渲染未改变，只将Java source/render助手改为包内以供复用。

## 明确关闭的执行边界

缺省GET review-capabilities仍旧1.0。显式?protocolVersion=1.1给3.0目标契约，status=NOT_READY、feeReservation=NOT_READY、MULTIPASS_RUNTIME_NOT_READY；未声明可执行多Pass。Java production client新requireMultipassCompatibility调用该版本查询，检查模式/Plan/费用/Ready，不兼容时先失败，不预留/提交。

POST合法1.1先严格校验后EXECUTION_CONTRACT_UNSUPPORTED，无Run/事件/Provider发送；缺Plan等非法请求422；未知版本400。旧1.0模拟与单Pass入口回归保持。本切片不把新Schema接到旧执行器，不尝试用单Pass运行伪装六Pass。

Result3读取入口为Python ReviewResultV3.model_validate及Java ReviewResultV3.validate。正常RunStore/Java FactImporter/ReviewProtocol状态事件导入仍旧路径；3.0原子Result提交、完整外层hash/ETag/原计划比对及产品导入接通放在6A3，尚未通过完整3.0跨服务闭环。State4、review_passes/生命周期事件在6A3；账本/调用时限执行在6A2。未提前增加6B报告文件存储或6C Coding入口。

## Schema与文件

新增6个自包含Schema：review-plan-v1、review-pass-result-v3、review-execution-contract-v2、review-capabilities-v2、review-harness-create-run-v1.1、review-harness-result-v3；所有对象additionalProperties=false。新正反例独立在contracts/fixtures/review-multipass-v1/examples.json，6正常/6结构反例，Result正例带真实形状的Contribution/Evidence（纯合成Fixture，不是模型质量证明）。业务反例另在正式Python/Java测试。导出入口`python -m tests.export_multipass_contracts`，旧公共/句柄fixtures和旧Schema字节没有修改。

Java新增ReviewPlan/ReviewResultV3/ReviewMultipassContractTest，延伸Schema注册与显式协商方法；Python API DTO/routes和report接上述边界，新增测试/export。ADR-0009/0015、Schema README已同步。

## 离线红绿与最终新鲜结果

Plan首轮因无multipass模块红测；Result3/API新版本/Java新类型在实现前红测；伪Markdown明确红测后加确定性校验。自查另外发现空范围INCOMPLETE/FULL与重复贡献Evidence，两条红测先失败，最小约束后通过。期间Rust regex不支持前瞻，改为Python编译正则保留相同导出pattern；金额零/float/非精确定点拒绝。辅助写入脚本两次使用错误cwd而未写文件，改为绝对路径后完成；均非产品失败、未操作其他目录。

最终日志/UTC命令时间/退出码：C:/Users/ROG/AppData/Local/Temp/code-weave-b6a1-qlegmmg6（run_matrix.py）。

| 项目 | 新鲜结果 | 入口 |
| --- | --- | --- |
| Python受影响 | 82通过，18.97秒 | `python -m pytest tests/integration/test_multipass_contracts.py tests/integration/test_real_schema_fixtures.py tests/integration/test_real_contracts.py tests/integration/test_contracts.py tests/integration/test_real_enablement.py tests/integration/test_lifecycle.py -q --tb=short --basetemp <日志根>/pytest` |
| 新协议专项 | 39组包含于82，不额外加总 | test_multipass_contracts：选择/金额/时间/零发送/原协议/无材料/谱系/合并/Markdown/Schema |
| Java新旧契约 | 11通过，failure/error/skip均0；Maven9.927秒 | `../../mvnw.cmd -Dtest=ReviewMultipassContractTest,ReviewRealContractTest,ReviewResultV2Test,ReviewCapabilityNegotiationTest test` |
| Schema | 40份元校验、184本地引用解析通过 | Draft202012Validator；schema-check.json，无网络解析 |
| Ruff/格式 | 通过；129文件 | `python -m ruff check .` / `python -m ruff format --check .` |
| 差异 | 通过 | git diff --check（既有CRLF提示不是差异失败） |

Java validator对discriminator发既有未知注释关键词提示；实际anyOf/const/required与业务校验执行，11项通过，未以discriminator当授权。旧1.0执行合同及旧2.0/JSON fixtures重新验证；新capability实际HTTP查询版本且NOT_READY阻断。没有Python全量/干净环境、Java正式/Legacy全量或UI矩阵，本次是受影响范围；批次5历史525/525仅保留历史证据，不认证6A1。

## 独立复审重点与后续

请设计窗口复审Plan的显式选择/金额与指纹、旧读拒绝新版本、Capability/POST零发送、六维终态/空范围、Contribution/primary/来源/等级、确定性Markdown及集合字节上限。尚未做任何多Pass调度或正式账本，不宣称六Pass质量、费用绝对封顶或重启恢复完成。

原public偏差、同模型盲点、256K真实大容量和POSIX未验、超窗恢复DISABLED继续公开；6A1不消除这些质量缺陷。原Result/Checkpoint与验收失败不修改，仓库外问题日志只追加，output及已有架构可视化不操作。

状态：6A1协议基础交付待独立复审；6A2/6A3/6B/6C未实施。复审通过后由用户授权小步提交；不自动推送或继续收费测试。
