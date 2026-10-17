# 批次6 最终离线基线刷新：eea5b1f

记录日期：2026-10-17（Asia/Shanghai）。执行于2026-10-16 23:04–23:29左右；用户中断等待后后台任务继续，次日读取原日志，未重新启动全量。

基线eea5b1f47104e87544aff58bc8ea060a2b4d84fc；执行前及读回时均无已跟踪文件差异。仅新增收口/本验收文档，既有未跟踪output/和架构可视化未编辑。本轮无产品改动、真实Provider/费用、密钥读取或CC，无提交推送。

## 新鲜结果

| 项目 | 原证据 |
|---|---|
| 当前Python3.12.6全量 | 783 passed，1385.32秒；JUnit tests=783/failures=0/errors=0/skipped=0 |
| 官方干净Python3.12完整验收 | 锁安装、editable安装、pip check；783 passed，1369.98秒；Ruff及156文件格式通过；空库迁移、服务启动和认证Readiness的CLEAN_ACCEPTANCE标记存在 |
| 干净环境清理 | 官方临时根code-weave-harness-acceptance-75n26eh0在读回时不存在；说明脚本临时目录已清理，不扩大声称所有OS停机路径已覆盖 |
| 2026-10-17独立静态重验 | Ruff全服务目录通过、156文件format检查通过，72份Schema元校验通过，git diff --check通过 |

本次可据日志、JUnit及官方成功标记确认测试/安装/迁移/Readiness阶段通过。但原PTY/exec进程句柄在会话恢复后返回Unknown process id，**没有再次取得两条顶层命令的退出码**；不能把阶段成功标记写成“本窗口直接观察exit0”。原命令保留退出码传递逻辑，但不以推测替代观测。没有为补这个记录缺口重复跑783项。

## 复现命令与原始证据

证据根C:/Users/ROG/AppData/Local/Temp/code-weave-b6-final-eea5b1f-c7c5ebb6。baseline.md、python-full.log、python-full.xml、python-clean.log及独立pytest临时根均保留。两套过程使用不同临时根并行执行，未修改产品。

在services/review-harness下运行，子进程设置PYTHONUTF8=1并清除继承的CODE_WEAVE_REVIEW_*操作配置（只影响本次子进程，不更改用户/机器环境）：

```powershell
.venv/Scripts/python.exe -m pytest tests -q --tb=short --basetemp <证据根>/pytest-current --junitxml=<证据根>/python-full.xml
.venv/Scripts/python.exe tests/verify_clean_environment.py
```

stdout/stderr分别重定向到原日志。当前全量日志末次写入23:27:25，干净日志23:28:58；时间来自文件元数据而非精确命令退出记录。全量JUnit自身记录1385.242秒，pytest显示1385.32秒，均为同次执行的不同计时边界。

原日志SHA256：

- python-full.log：0127ea2ea2f2d7016a66598502715637e0abb312429568cd56f088930bc169e8。
- python-clean.log：eb05650bcbc1341255e4d7126b348547e23c406394463b9a298aa4ea064a2bfe。

## 适用范围与下一步

这刷新了最终基线的Python工程验收，不借用旧625/633/694或重叠专项计数。Java/UI/Legacy本次未跑，其历史矩阵保持历史；无相关实现或合同改动。本次未执行新的真实六Pass、Java在线导入/UI或Coding。

c11真实语义偏差仍是已知质量失败；DUPLICATE_KEY具体反馈尚未真实复验；同模型验证、固定小样例、高容量/POSIX和超窗恢复等限制不因离线783通过消失。真实RISK组审计确认后合并的既有成功证据继续成立。

推荐下一项使用已保存真实Result在隔离环境验收Java/UI导入、人工纠正c11形成新报告版本、批准与准备Handoff不自动启动Coding；先核对现有运行/测试装配及所需权限，不能借本次离线授权自动开启真实Coding或追加付费。批次6是否按带已知限制的工程交付收口，留待用户决定。
