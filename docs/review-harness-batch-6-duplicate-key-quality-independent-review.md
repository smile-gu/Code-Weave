# 重复键诊断与c11质量基准独立复审

2026-10-16。基线6ef9a01加已复审预分区工作区，对照duplicate-key-and-quality-freeze及交付记录。仅新增本复审文档，无产品改动、付费、密钥读取、CC、提交或推送。

复审结论：A工程补修及B质量基准交付通过，未发现阻断性问题；B不是模型语义修复，不宣称c11质量问题关闭。

## 静态结论

工程改动限domain/provider.py与runtime/review.py：专用ValueError子类固定消息、不含键值，parse_submission先于通用ValueError捕获，投影DUPLICATE_KEY、不伪造坐标。JSON语法坐标及其他严格解析路径保留；下一纠错请求明确同对象唯一键、同值重复也拒绝、不同对象同名合法及禁止first/last wins。身份、来源、两次共享纠错、去重预算不变；未改位置协议、预分区或外部合同。

c11基准标记KNOWN_MODEL_QUALITY_FAILURE，保留原候选及SUPPORTED/VERIFIED误判。新增位置测试仅通过脚本驱动CORRECTED或REJECTED原通道，不声称平台自动识别语义错误；未增加提示词/分类器/Schema/重试。两方法均抛NPE不意味着异常消息等所有行为维度相同，此边界有明确记录。

## 独立新鲜证据

- 单独运行test_submission_json_diagnostics.py：14 passed，0.67秒。
- Ruff check与144文件format检查通过；git diff --check通过，但该命令不涵盖未跟踪新增文件的暂存正文检查。
- 三份历史035/037/038回复正文与原trace逐字一致；c11验证record与architecture-c11-validation.json一致。
- c11 originalFinding为PassFinding，和最终Result对应项相比仅无contributions包装；排除此结果层字段后所有原始字段相等。一次直接比较整个Result项的独立断言曾因该包装差异失败，已定位为比较层级差异，非夹具改写，未修改夹具或原件。

独立HTTP/位置专项命令（services/review-harness目录）：

```powershell
.venv/Scripts/python.exe -m pytest tests/unit/test_submission_json_diagnostics.py tests/integration/test_dedup_fix.py tests/integration/test_position_review.py -q -k 'duplicate or c11' --tb=short
```

专项15 passed、59 deselected，72.19秒；包含重复键安全诊断/原回复、五种HTTP纠错与句柄边界、c11原误判及CORRECTED/REJECTED位置通道，与前述14项单元测试部分重叠，不相加。开发83/108/5等命令有重叠，本窗口不累加或冒称已重跑。未运行全量/干净环境/Java/UI/Legacy及真实模型。

## 能力边界

工程离线通过不代表真实模型下次能正确去掉重复键；c11回放及通道通过不代表语义问题已解决。保留该已知质量失败，不用换成脚本正确回复来“关单”。原真实RISK组合并成功证据仍有效，VERIFIED组合法判断未完成的历史仍保留。后续真实评估须另行授权，不继续自动付费试错。
