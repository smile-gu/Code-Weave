# 批次6 去重兼容性预分区独立复审

2026-10-16。基线6ef9a01，对照dedup-partition-freeze及开发交付。范围为comparison_groups预分区及测试夹具/装配；原真实验收说明差异不是本切片产品改动。未改产品、未付费、未运行CC、未提交或推送。

结论：本次最小预分区补修离线复审通过，未发现阻断性问题；真实模型效果与审计确认后真实合并仍待另行验收。

## 静态复核

产品只修改runtime/dedup.py::comparison_groups：对原始Finding先按已保存claimKind/disposition精确分区，再各自调用来源重叠召回；组件、32项分块和块内连通都在分区内完成。不同键不能桥接同键不相交节点。

组件/组按最早(PASS_ORDER,candidateId)排序，组内固定顺序，全局groupIndex在最终排序后分配；不依赖枚举字母顺序或传入字典顺序。grouping投影保存strategy与partitionKey，现有审计路径原样持久化，无State/模型Schema/Result合同变化。

仅兼容项允许比较，不代表同根。整组确认、本地兼容复核、来源/身份/容量、审计FULL/哈希/Checkpoint确认及失确认/UNKNOWN保护仍保留。未改位置协议、收费/调用/时间或两次共享纠错。共享测试夹具只增加九项数据集与多候选共享验证调用装配，旧五项数据及路径保留。

## 新鲜独立证据

仓库九项夹具与原证据original-pass-outcomes.json的九个Finding逐项相等，严格解析后分为五项STATIC_CORRECTNESS/VERIFIED及四项ENVIRONMENT_DEPENDENT/RISK；独立乱序对照分组及元数据完全一致。原始证据路径：C:/Users/ROG/AppData/Local/Temp/code-weave-b6-dedup-real-pezip0nv。

独立命令（services/review-harness目录）：

```powershell
.venv/Scripts/python.exe -m pytest tests/integration/test_dedup_partition.py tests/integration/test_fee_ledger.py::test_summary_has_its_own_ten_calls_and_ten_minutes -q --tb=short
.venv/Scripts/python.exe -m ruff check review_harness tests
.venv/Scripts/python.exe -m ruff format --check review_harness tests
git diff --check
```

对contracts/schemas下72份JSON执行Draft202012Validator.check_schema通过；Ruff、144文件格式及已跟踪差异检查通过。新增JSON/Python/文档尚未暂存，git diff --check不覆盖其未跟踪正文，不把该命令误称所有新增文件的暂存检查。

新鲜独立专项24 passed，123.67秒，包括正式双组请求/审计确认、异键桥接与稳定排序、两组共享纠错、首组确认后预算中断和第二组UNKNOWN，以及原去重10次/600秒账本门禁。开发70项原回归为开发证据，本窗口未重复执行；未运行全量/干净环境/Java/UI/Legacy或真实模型。

## 限制与后续边界

本次证明确定性分区和脚本决策处理，不证明模型会对五/四项返回正确整组判断。不能把上次非法选五项的回复套到新组后追认旧Run成功。混合缺陷整组分立、跨side/无交叠/跨块漏召回仍保留。通用反馈的整组句柄明细及Active pass残留是已知限制，本次未扩大范围补修。

位置协议保持已通过，不因本次复审重新打开。下次真实测试需用户另行授权，仍不能承诺两组都应合并或最终必须两条；必须区分合法否定决策、合法肯定合并和再次非法回复。原失败/账本/Result不改写。
