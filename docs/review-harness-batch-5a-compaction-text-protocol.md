# 批次 5A 局部摘要协议收敛

实施依据：仓库外 33 复盘、34 摘要协议冻结稿（2026-10-12）。仅 COMPACTION 子项；Evidence/Candidate 句柄未冻结，本次不实现。零付费请求，未提交、未推送、不进入批次 6。

## 改动

- 真实 Adapter 的 COMPACTION 请求不设置 response_format/json_object、不发送 tools；非思考、非流式、角色/调用配对、最终字节计量和 ModelCall 生命周期仍沿用现有实现。INVESTIGATION/VALIDATION 不变。
- 模型只输出不可信叙事，说明目标、已调查内容、排除假设和未完成线索。平台不解析 JSON 样式叙事、不从正文注册 UUID/hash/来源。旧 CompactionResponse 三字段模型删除，内部 CompactionText 只用于文本边界校验，不是 JSON 输出协议。
- 正文必须非空白，最多 8192 字符且 UTF-8 16384 字节；Provider 空/null/截断/工具返回、身份/Usage/漂移规则保持；脱敏后再验证边界。超限拒绝，不截断当成功，不增加重试或纠错额度。
- 平台确定性合并进入压缩前的 State 锚点、前序有效 Summary 的来源以及选定连续闭合 prefix 的结构引用。Artifact/Evidence/Candidate 按身份、SourceRef 按 canonical 去重。Summary 与保留 State 使用同一集合，修复原 Summary.sourceRefs 漏掉 prefix 独有引用的问题。
- 集合超限或无效归属/hash 在摘要调用前明确失败。继续 FULL Artifact 发布、重装配调查请求、来源核验、Token 严格下降、Checkpoint 保存确认后才采用。失败不提前替换旧消息；模型摘要正文不是事实表。
- FakeAdapter 保留独立测试协议及其返回引用的拒绝回归；生产纯文本路径不要求 Fake 模型生成引用。

来源集合表示“压缩保留的来源上下文”，不宣称每个材料都被叙事实际提及。sourceMessageIds/sourceDigest 表示选定消息范围与前序 Summary 血缘；State 中 Evidence/Candidate 和用户目标/Snapshot 锚点不由叙事重建。

## 红绿证据与复现

生产 Adapter 中文/emoji 和 JSON 样式合法叙事先被旧 JSON 请求或三字段 Parser 拒绝；新纯文本路径通过。prefix 独有 SourceRef 的红测明确得到 Summary.sourceRefs=[]、State 有来源；共用平台集合后通过。生产组合离线连续三次摘要保留 Evidence/Candidate，未知 ID 叙事不注册引用，之后真实 Provider 原 ID 工具调用与结果配对续接。

本地纯文本计量回归样例：19 Tokens，请求 digest `sha256:14674876f1fe0ff59d82c33d18e8c9628dddabb0d8393fdc58ebc5bbd3c150a3`。这是固定资产的离线样例，不是托管 Usage 对照；旧 JSON 摘要的 34 Tokens 样例仍是历史，不认证新模板。

回归入口：test_compaction.py、test_real_context.py、test_deepseek_adapter.py、test_deepseek_meter.py、Provider HTTP/ModelCall/Schema/real_review 受影响矩阵。最终计数和时间完成后追加。新增回归覆盖两次来源集合合并与前序 Summary、去重、坏 hash/损坏 Artifact/跨 Run/集合超限/未闭合工具、文本长度及 UTF-8 上限、空/null/空白/length、无 Token 下降、发布/Checkpoint 不确定与取消边界。

一次中间 pytest 因长中文参数自动生成 Windows 路径超长导致 2 个 setup error，已给参数明确短 IDs，未改产品限制；另工具拒绝测试先被测试 Usage 大于估计阻断，改为合法低 Usage 后证明目标门禁。均如实区分 fixture 错误，不作为产品失败或有效红测。

## Schema 与兼容

外部 ProviderRequest/ProviderResponse、Summary、State 和 Result 2.0 的字段结构未变；没有持久化迁移。旧 Summary 保持可读且不改写。旧三字段 CompactionResponse 未曾导出共享 Schema，删除不会遗漏静态资产；通过现有导出模型同步比较受影响静态 Schema，严格 items/additionalProperties 保持。Java/UI 和 Evidence/Candidate/factChecks 未改，保留已有历史证据。

摘要 Provider 请求模板改变，旧真实对照不证明新模板。最终 Python 全量/干净环境安排在整批提交前，本次只跑受影响范围。后续真实验收须先独立复审并另确认授权：最多一次探索 Run，明确修复后最多一次确认 Run，同原因再失败返回设计窗口；保持单 Run 16 次模型预算，必须观察成功摘要、Checkpoint 替换、Token 下降、后续工具原 ID 与诚实交付。当前不宣称真实压缩通过或语义无损。


## 2026-10-12T19:56:30+08:00 摘要纯文本子项最终离线交付

完整阅读33复盘、34冻结稿后仅调整摘要。先红：合法文本被旧JSON/sourceRefs协议阻断；prefix-only SourceRef在旧Summary遗漏。绿测后扩大矩阵 `pytest tests/integration/test_compaction.py tests/integration/test_real_context.py tests/integration/test_deepseek_adapter.py tests/integration/test_deepseek_meter.py tests/integration/test_provider_http.py tests/integration/test_model_calls.py tests/integration/test_real_schema_fixtures.py tests/integration/test_real_review.py -q --tb=short --basetemp=C:/Users/ROG/AppData/Local/Temp/code-weave-b5a-text-compaction-20261012`：108通过，183.52秒。末尾新增工具响应拒绝测试后，最终摘要/Adapter/Context/Meter矩阵74通过，36.78秒；之后仅新增脱敏发布测试，单独1通过，1.45秒，产品代码未再改。这些计数重叠，不相加冒称全量。

Ruff通过，format117文件通过，git diff --check通过；4个相关静态Schema与model_json_schema一致且严格结构未变，扩大矩阵含8共享正反例。没有需要重新导出的Schema字段变化，旧CompactionResponse未被共享导出，内部移除不遗漏资产。

纯文本摘要COMPACTION无tools/json_object，原Provider身份/finish/Usage/Token漂移保持；最多8192字符/16384UTF8字节，脱敏后非空；JSON样式文本不解析身份。平台来源由State/前序Summary/闭合prefix结构引用合并，Summary和保留State共用集合；超限失败，归属/hash不放宽。连续三次真实Adapter离线压缩保留Evidence/Candidate，未知ID叙事不注册来源，随后工具原ProviderID配对续接。Fake独立协议保留，不强迫生产模型生成引用。

改动范围：domain/provider.py、runtime/deepseek.py、runtime/context.py、runtime/compaction.py；测试test_compaction.py/test_real_context.py/test_deepseek_adapter.py/test_deepseek_meter.py；ADR0015/0016及交付文档同步。Evidence/Candidate/factChecks/句柄、Result2.0、Java/UI、Run事务不改。旧Summary/Result/诊断保留。

本轮零付费、未读取密钥、未暂存/提交/推送、不进入批次6。旧Python全量/干净410是历史，不认证当前代码；Java/UI外部协议未变保留已有证据。新纯文本请求模板只有离线19Tokens样例，旧JSON托管样例不认证新模板；真实压缩仍待独立复审后另行授权。最多一次探索、明确补修后最多一次确认，同原因再失败回设计窗口，不无限叠提示。模型叙事真实性/语义无损未保证。

详情 docs/review-harness-batch-5a-compaction-text-protocol.md。请求设计窗口独立复审摘要子项，不扩大Evidence句柄范围。


## 2026-10-12 20:05–20:06 +08:00 纯文本摘要一次真实探索

用户授权由开发判断额度并开始；本次仅压缩单目标一次探索Run，最多16请求，按重核官方价新预留1.409024元，叠加前保守1.403754元管理上界2.812778元。公开六文件合成输入，未降低生产水位/门禁/预算，未注入回复。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-nv_19v9k/compaction。

Run b1dd0bed-5c7a-416f-8b48-58c4b69cb46f，实际11请求：9调查+1COMPACTION+1压缩后调查。纯文本COMPACTION成功（估计/实际输入19082一致），Summary1 FULL发布，Checkpoint revision52/53保存summaryVersion1。摘要Artifact内容hash与引用吻合，平台sourceRefs与保留State一致；没有旧JSON/sourceRefs模型输出失败。成功替换证明通过原平台严格Token下降检查；具体替换前完整调查请求Token仍需离线重建，不以不同purpose请求直接作下降比较。

压缩后调查请求本地7897、实际7975，少算78，触发PROVIDER_TOKEN_METER_DRIFT。Provider返回tool_calls，但由于漂移门禁未执行这些工具，未完成续接或Result。Run FAILED/RELEASED，无执行副本/staging-run残留，Result不可用。不能认定整个摘要验收通过；已观察摘要/Checkpoint替换，剩余Token计量、工具续接和诚实交付阻塞。

11次全部有效Usage，无未知费用；本轮峰值价格费用上界0.236118元，保守累计1.639872元，不是实际扣费。失败后停止，不自动重跑；后续先离线重建最后请求和计量边界，不能固定加78/放宽冻结门禁碰运气。本轮无产品改动，未提交/推送/进入批次6。保留旧失败和新Summary产物，最多一次确认Run须明确修复及后续许可，不以本条自动发送。


## 2026-10-12 纯文本压缩后78Token差异：离线定位

已从持久Checkpoint revision53、完整Summary Artifact、原Manifest和ModelCall精确重建最后调查请求：SHA sha256:14118200bb7c3c3e1d7876b6176af80c246df97e36cf576c910bba03706fb065与发送记录完全一致，本地7897再次复现，实际Usage7975、差78。请求21502 UTF8字节，9消息，角色system/user/user/user/assistant/4tool，严格ProviderRequest及工具配对通过，无json_object，tools auto。逐正文Token扫描未发现参考Tokenizer特殊控制Token。

对同一实际调查模板重建压缩前最后有效State请求22445，压缩后7897，减少14548，确认原平台Token下降门禁有实际依据；这比不同purpose计量比较更可靠。Summary来源19项、Checkpoint替换及前序有效记录保留。产物 compaction/offline-request-reconstruction.json 与根reconstruct_meter_offline.py（只读重建、零发送）提供复现。

结论：排除了“发送与持久摘要不一致”和本地不可重复计量；没有证据证明78来自具体字段、Provider模板差异或Token资产问题。前10次实际Usage均相等，只有压缩后视图出现漂移，但相关性不能证明摘要角色或多工具是根因。纯离线无法观察Provider私有编码过程，仍未知。没有改编码器、添加固定78、放宽门禁、清除漂移或付费重跑。

下一步需设计窗口评估有限诊断对照（原字节与单变量消息投影）或显式预算治理政策；未经确认不把任意投影当生产修复。摘要成功/Checkpoint替换/Token下降已有真实证据，工具执行续接与Result仍未通过。当前未改产品代码，不需重跑已通过矩阵；未提交/推送/进入批次6。
