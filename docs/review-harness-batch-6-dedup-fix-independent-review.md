# 批次6 最小去重补修独立复审

日期：2026-10-16。基线d5f75f0加当前未提交去重补修。对照dedup-fix-freeze与开发交付，静态复核dedup.py、dedup_audit.py、multipass.py及新增测试。未改产品、未付费、未运行CC、未提交或推送。

结论：本次最小离线去重补修复审通过，未发现阻断性问题。真实模型语义去重仍未验，不据此宣称整批真实质量通过。

## 设计符合性

- 来源重叠只召回连通分量，删除rule全文相等要求；固定顺序、32项分块后重新求分量，跨块遗漏有说明。连通性不作为语义合并充分条件。
- 沿用整组DedupDecision，所有布尔为真且精确本组句柄匹配才允许进入合并；不同、不确定或混合组保留原项。模型只判断，不重写主判断。
- 删除trigger/suggestion全文相等门槛，但保留claimKind/disposition一致、有效原始PassFinding、连通来源与16/16/32合同容量。固定主项、不重算等级，全部贡献和来源并集保留。
- check_originals核对State/passLedger、候选绑定、验证调用、Evidence安全原件和来源引用；save_decision保存原成员/摘要、分组边、模型依据和动作，再校验FULL/字节数/哈希及Checkpoint精确读回，确认后才改内存最终集合。
- 容量不足保留分立；存储、完整性、失确认、取消、远端UNKNOWN仍阻断。可能存在孤立Artifact，但它不代表合并已采用。保留既有费用/调用/时间和纠错额度。
- 使用State.artifactRefs保护审计；未改POSITION_V1、State/Result3合同或Java/UI。

## 独立证据范围

五条夹具与原真实Result逐项相等，未先统一rule、trigger或suggestion。原真实产物路径：C:/Users/ROG/AppData/Local/Temp/code-weave-b6-position-real-qmb0wiq8/real/result.json。

独立命令（services/review-harness目录）：

```powershell
.venv/Scripts/python.exe -m pytest tests/integration/test_dedup_fix.py -q --tb=short
.venv/Scripts/python.exe -m pytest tests/integration/test_multipass_runtime.py -q -k dedup --tb=short
.venv/Scripts/python.exe -m ruff check review_harness tests
.venv/Scripts/python.exe -m ruff format --check review_harness tests
git diff --check
```

新鲜结果：去重专项37 passed（147.41秒）；原多Pass去重专项2 passed、27 deselected（0.26秒）。两组为39项不同用例。Ruff、143文件格式及差异检查通过。开发66项整合、211项影响回归及72份Schema为开发证据，不冒称本窗口独立重跑；未执行全量/干净环境/Java/UI/Legacy。

## 剩余限制

脚本三true后五项合一及false后分立，只证明生产协议和处理，不证明真实模型能区分根因。理由只有非空、有界与身份校验，没有确定性语义充分性证明。混合组、跨side、无交叠及跨块可能漏合并，符合本次保守边界。其他成员等级差异从审计回放，外部仍是固定主项。

下次真实去重须另行授权；不能将原五项夹具的离线成功改称真实模型成功。位置协议不重新打开，原重复Result及摘要偏差不回写。不自动付费重跑或进入下一批。

## 提交前补充检查

此前git diff --check未包含未跟踪夹具。暂存后检查发现position-real-changes.diff第6、12行的单个空格被报为尾随空格；它们是嵌入统一Diff中空白上下文行的必要前缀，保留原文，不修剪。其他暂存文件常规差异检查通过；该夹具使用单次git -c core.whitespace=-blank-at-eol diff --cached --check单独检查，不修改仓库配置。此前“差异检查通过”仅指当时已跟踪差异，并非所有新文件。
