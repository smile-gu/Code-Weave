# 批次5最终收口：9baa2ac

基线：9baa2ac2d3d176f64b729e44215a74e595a312c6，2026-10-13。本轮只运行验收和新增文档，不修改实现/测试输入，不发送模型请求，不暂存、提交、推送或进入批次6。原不可变Result、Checkpoint和历史产物保留。

本轮命令/UTC起止时间/退出码与日志目录：C:/Users/ROG/AppData/Local/Temp/code-weave-b5-final-9baa2ac-r76rqhv_，run_matrix.py为复现入口。逐行pytest计数只从最终完成摘要取值，不加总相互重叠的专项。

## Java/UI影响核对

历史完整验收为2026-10-12，见review-harness-batch-5-final-offline-acceptance.md及code-weave-b5-final-20261012-144612矩阵日志。9b48c19为后续实施checkpoint提交，不能用其提交时间误判所有Java/UI代码在10月12日验收后新增；该提交包含此前未提交开发内容。

`git diff 9b48c19..9baa2ac -- services/control-plane apps/web-console contracts`为空；两次补修仅Python review.py与回归/文档。其间预算及局部句柄变动对共享契约的影响，已有5项Java专项补验记录，本轮又以9baa2ac运行同专项。Java/UI实现自已记录验收后无新增改动的依据为连续交付记录和上述提交差分；历史验收没有完整工作区源码哈希清单，不能声称已完成验收时脏工作区与当前源码的逐字节独立证明。

按影响本轮补跑Java ReviewResultV2Test/ReviewRealContractTest，5项通过，failures/errors/skips均0；未重复Java正式/Legacy全量及UI正式/Legacy测试/构建。历史Java正式286、Legacy39，UI正式57/Legacy3及两构建成功，均明确属于10月12日证据，不是当前新鲜计数。历史保存真实Result的Java/UI21断言及浏览器展示属于隔离控制器/生产Panel/固定材料环境，不是完整生产部署或完整登录导航端到端验证。

## 真实证据适用范围

| 产物/Run | 已证实 | 不证明或失败 |
| --- | --- | --- |
| code-weave-b5-final-20261012-144612/saved-real，原c07a7fdb | 原Result规范字节、Java严格幂等导入、身份/哈希/引用负例、认证材料、只读草稿/历史 | 历史报告public错误原样保留；不证明新协议或完整部署 |
| code-weave-b5-compatible-confirmation-a6h4knf8/compaction | 旧窗口实际两次压缩及压缩后工具执行，小幅Token低估告警后继续 | 旧工具总上限耗尽，无Result；不认证256K |
| code-weave-b5-tools64-confirmation-3qf1sqtr/compaction，0e9ea482 | 11有效Usage、摘要与Token下降、SUCCEEDED/RELEASED、INCOMPLETE Result | 9Evidence/0Finding、1未验证候选；该Run压缩后没有新工具；不认证256K |
| code-weave-real-smoke-61zgnocw，9fc69d84 | 局部n/e/c映射、真实验证回复持久、UUID Result安全交付 | VERIFIED+成立反例导致零Finding；旧实现，public质量失败 |
| code-weave-real-smoke-qhknbxa9，95f08793 | 两次纠错上限及FAILED/RELEASED | checkedScope三次错误；未到验证，不评价审查能力或矛盾纠错 |
| code-weave-real-smoke-ebnedhfp，cc33d3cd | 当前范围首次通过、真实矛盾反馈后纠正、3Evidence→1VERIFIED Finding→Result2.0，5有效Usage | trigger仍错误public；没有压缩/256K高容量测试；不能认证整体质量 |

原真实结果不重新生成、回放收费、改写Markdown或替换测试输入。最后一轮核心null回归成立，权限/可达性文字偏差仍存在，不能以有Finding将质量目标判通过。

## 明确未验与已知缺陷

| 项目 | 状态 | 边界 |
| --- | --- | --- |
| public事实描述偏差 | 已知缺陷 | 用户称public但源码package-private；Finding trigger与报告沿用错误描述。factChecks合法引用未覆盖该关键事实，不手工改历史产物 |
| 独立验证的独立性 | 已知局限 | 独立请求与候选专属原材料，不是不同Provider/模型的独立裁决；同模型可能共享盲点，VERIFIED不保证语义正确 |
| 256K真实高容量 | 未验 | 默认W262144/B257024及保守余量仅离线边界验证；最新真实小输入最大窗口不等于已消耗高容量，旧窗口压缩不能替代 |
| 超窗自动恢复 | DISABLED | 未有支持的明确Provider超窗码依据；超时/未知结果不自动重发，兼容余量是工程策略而非精确Token误差保证 |
| POSIX | 未验 | 本次Windows/Python3.12；不认证POSIX文件/线程/取消清理语义 |
| 远端取消效果/模型整体质量 | 未验 | 本地取消保护有离线回归，不证明远端停止计费；有限样例不建立漏报/误报统计 |
| Tokenizer资产主站身份 | 既有限制 | 使用镜像获得的参考资产，主站身份独立核验仍待完成；不宣称精确DSML计量修复 |
| 当前版本全量Java/UI | 未重跑 | 历史矩阵保留；本轮仅Java契约5新鲜通过，按变更影响选择 |
| 整批最终审批 | 待设计窗口复审 | 本文不批准整批、部署、推送或批次6 |

最终Python与干净环境结果见下表，不使用历史410或局部54替代当前全量。

## 当前基线最终验收表

| 验收项 | 本轮状态/结果 | 命令与范围 |
| --- | --- | --- |
| Python全量 | 已通过：525，648.63秒 | `.venv/Scripts/python.exe -m pytest -q --tb=short --basetemp <验收根>/pytest`，16:34:41–16:45:32 +08:00，退出0 |
| Schema一致性专项 | 已通过：17，2.25秒 | `python -m pytest tests/integration/test_real_schema_fixtures.py tests/integration/test_contracts.py -q --tb=short --basetemp <验收根>/schema-pytest`；包含于全量，不加总 |
| 全部Schema元校验/引用 | 已通过：34份、155引用 | Draft202012Validator.check_schema＋递归本地$ref/JSON Pointer解析，无网络解析；schema-meta-reference-observation.json |
| Ruff | 已通过 | `python -m ruff check .`，退出0 |
| 格式 | 已通过：126文件 | `python -m ruff format --check .`，退出0 |
| Java公共契约 | 已通过：5；无失败/错误/跳过 | `../../mvnw.cmd -Dtest=ReviewResultV2Test,ReviewRealContractTest test`，16:35:20–16:35:28 +08:00；Maven报告6.372秒 |
| 差异 | 已通过 | `git diff --check`，退出0；最终文档追加后复查 |
| 干净Python3.12 | 已通过：525，676.88秒；安装/依赖/迁移/启动/认证Readiness均通过 | `python -m tests.verify_clean_environment`；独立venv、锁安装、依赖检查、全量、静态、迁移/启动/认证Readiness |

每项精准UTC时间详见验收根对应JSON，表内时间为Asia/Shanghai。没有将全量与Schema专项累加，也没有将本地全量与独立环境全量加总成覆盖数量。

## 完成结论与交付边界

本轮2026-10-13 16:34:41–16:58:24 +08:00；本地Python3.12.6，独立venv使用同一3.12解释器。干净验收整体16:45:32–16:58:24（含安装）；pytest耗时676.88秒，其525项不是额外525个不同用例。锁安装、editable安装、pip check、全量、静态/格式、空库迁移、独立服务启动与带认证Readiness全部通过，退出0。pytest全量没有失败或跳过摘要；本轮没有改变测试输入来避开public冲突。

matrix-summary.json汇总本轮记录，原日志保留。验收期间与完成后复核HEAD仍9baa2ac，服务/契约/测试/Java/UI与基线无差异；本次新增仅统一收口文档与仓库外日志/临时验收记录，无功能开发。既有无关架构产物与output目录未操作。

结论：指定的无需付费最终回归已通过，可交设计窗口最终复审；存在已知public文字/可达性偏差及上述未验项，不将代码回归通过当作整批模型质量通过或提交/推送批准。Java/UI历史完整结果不冒充当前全量。此次没有向其他窗口自动发送消息，交付文档由用户转交。
