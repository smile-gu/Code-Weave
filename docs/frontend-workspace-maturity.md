# 工作台与审查页面成熟度改造

日期：2026-10-17。基线：`d136152`。本轮限前端，不修改后端、协议、数据库或模型提示词。

## 目标与开源调研

将面向实现细节的页面改为面向使用流程的工作台：连接仓库 → 审查变更 → 阅读问题与依据 → 人工裁决 → 显式修复。
保留黑白主题；仅用红色强调失败和高等级问题。技术标识不是业务信息，默认不展示，但不能破坏追溯和写请求绑定。

Tavily 工具本轮不可用，使用 in-app browser 查看 GitHub，并通过 GitHub Contents API 读取锁定版本的源码和许可证。

| 项目及锁定版本 | 实际查看内容 | 本轮采用范围 |
| --- | --- | --- |
| [shadcn-admin](https://github.com/satnaing/shadcn-admin/tree/e16c87f213a5ba5e45964e9b67c792105ec74d26)，MIT | Card、Badge、Skeleton、Main、Header 源码及 LICENSE | 直接改编 Card／CardHeader／CardContent、Badge、Skeleton 的组件结构和 data-slot；借鉴工作台分区、间距、边框与面包屑 |
| [shadcn/ui](https://github.com/shadcn-ui/ui/tree/d2fba50ee5b9113008b5bb908563527b7efd2f27)，MIT | 仓库说明、sidebar-07 page 源码 | 布局对照；不整体引入 SidebarProvider、Tailwind、组件注册表或示例路由 |
| [assistant-ui](https://github.com/assistant-ui/assistant-ui/tree/69668210c9476323380067307284509f707b507a)，MIT | README 的 Thread／Message／Composer、运行时和 SDK 接入说明 | 候选评估；本轮未复制源码或接入其运行时，避免为展示改造替换现有持久事件、任务确认及付费门禁 |

确实复用的源码位于 `apps/web-console/src/shared/ui/surfaces.tsx`。
将上游 Tailwind 类转换为现有 CSS，删除未使用 slot 和多态能力，不增加 npm 依赖。
完整上游 MIT 声明保存在 `apps/web-console/THIRD_PARTY_NOTICES.md`。
不是复制整个开源后台；业务路由、接口、资源保护与事件模型仍为原平台实现。

## 页面变化

| 之前 | 现在 |
| --- | --- |
| 欢迎页式大标题、创建表单和简单历史 | 紧凑工作台、真实最近会话概览、创建与历史双栏 |
| 侧栏底部强调内部边界说明 | 实际可导航的会话工作台入口，无虚构用户、设置或健康状态 |
| 会话头部直接显示 Session UUID、基线字符串 | 默认显示仓库名；路径、会话 ID、基线在技术详情内 |
| 每条审查用完整 Run UUID 命名，进度文本堆叠 | 可读列表序号、执行状态、折叠进度，历史与报告分栏 |
| 手动选择才能打开报告 | 默认打开首个已完成报告，并固定选择；刷新出现新记录不会替换当前报告 |
| 正文直接显示 Snapshot、Evidence UUID | 快照放入技术详情，证据以“材料 N”呈现，完整原始引用仍可查看 |
| 问题详情主要是描述与证据 ID | 标题、等级、判断、文件及行号，支持本地关键词和等级筛选 |
| 未区分对话读取失败和首次空白 | 加载骨架、读取失败及显式重试、真正的空白对话分别展示 |
| 预览直接显示完整 Commit 和高精度 ISO 时间 | 默认显示分支、文件、维度、费用与本地可读有效期；版本标识折叠 |

同一已选审查被再次点击时，不重置人工编辑界面。
原始报告与模型正文不做正则删改，不尝试把正文中所有看似哈希的文本删掉。
当前“审查 N”是当前最多 50 条历史列表内的序号，不是新的持久业务 ID。

## 不变边界

- 接口 URL、ID、Snapshot、摘要、价格绑定及请求负载不变，隐藏只是渐进展示。
- 页面挂载、历史读取、筛选、展开和预览不发起付费审查或 Coding。
- 启动仍要求明确费用授权；失确认的原请求保护、后端门禁与 SessionStorage 行为不变。
- 六维完成状态、范围与限制、失败诊断仍可见；不把有限范围完成改称全项目通过。
- 未改原 Result、历史英文正文或人工版本；历史文本可由原有人工修订功能处理。
- Markdown 继续禁止 HTML／图片，危险链接限制保留；证据读取仍经 Java API。
- Coding／Review／变更／交付视图继续保持挂载，切换不丢弃未发送 Coding 草稿。

## 新鲜验收

在 `apps/web-console` 执行：

```powershell
npm.cmd test -- --reporter=dot
npm.cmd run test:legacy-worktree-run
npm.cmd run build
```

- 正式前端：17 文件，84 项通过。
- Legacy：1 文件，3 项通过。
- TypeScript 与 Vite 生产构建通过；既有单包超过 500 kB 的体积告警仍存在，未以调高阈值消除。
- `git diff --check` 通过（LF／CRLF 提示不是差异错误）。
- 新增回归：技术详情默认关闭且可展开、筛选不改原报告及不发请求、刷新固定选择、对话错误不伪装成空记录；原付费授权、防重复提交、关闭会话、修订和交接回归继续通过。

Playwright 连接本地真实服务，仅浏览已有会话／报告和 GET 预览：

- 桌面 1440×1000；窄屏 390×844。首页、报告及预览没有整页横向溢出；窄屏 Tab 栏允许局部滚动。
- 默认报告正文无平台 UUID／SHA-256 字符串；展开技术详情后可读取原标识，再收起可隐藏。
- 高等级筛选：4 条变为 2 条；不存在的关键词展示无匹配；清空后恢复。
- 展开问题展示真实 `CheckoutService.java` 及第 12 行位置。
- 费用预览完成后未勾选授权，启动按钮仍禁用，未执行 POST 启动。
- 检查时浏览器控制台错误数为 0。

截图和浏览器过程保存在未纳入提交的 `output/playwright/mature-*.png` 和 `.playwright-cli/`。
没有新增付费请求、没有启动真实 Coding，没有自动提交或推送。

## 范围限制

本轮没有重跑 Python／Java：没有对应实现改动，不将历史结果当作本轮验收。
浏览器检查基于现有本地合成仓库与历史报告，并非新一轮模型质量验证，也不代表所有浏览器与所有尺寸均通过。
本轮复用的是展示组件，不宣称获得上游整个产品的可用性／无障碍保证。

## 第二轮：质感与动画升级（同日后续）

用户反馈第一轮仍偏普通后台，并授权新增前端依赖与动画。前述“不增加依赖”及 84 项结果属于第一轮历史；本节为第二轮现状。

### 新增设计对照

- [Midday](https://github.com/midday-ai/midday/tree/51587319f26a0ffaa9dfccab1920373cb65689b7)：读取 Sidebar、Header 源码，参考克制层级、细分隔线与桌面工作台轮廓。该项目为 AGPL-3.0；没有复制其源码、品牌或素材，不将其代码并入当前实现。
- [bolt.diy](https://github.com/stackblitz-labs/bolt.diy/tree/353b44c8f386aeb15abdf277515d78ff50f7578e)：读取 `app/styles/variables.scss`、`app/components/ui/PanelHeader.tsx`，参考多层表面、主次文字和紧凑工具栏；没有整套引入其主题及运行时。
- Medusa 仅查询了 GitHub 仓库元信息，许可证未确认，没有采用其源码，不将其算作已完成源码评审的案例。

视觉改为石墨色导航 + 内嵌纸白工作区；首页计数从三块独立卡片改为一条低权重概览，留白和字号有明确主次。
表单为主要工作表面，历史记录减少包围边框；按钮使用轻微高光与阴影，问题详情增大正文行距，避免以大渐变、动效背景或假数据制造质感。
深色侧栏单独设置文字及键盘焦点颜色，保留可读性与可见焦点。

### 技术与服务边界

- 保持 React 19 + TypeScript + Vite 前端服务，Java 控制面服务，Python Harness 服务。不为界面美化改成 Next.js 或合并服务。
- 新增 MIT `motion@14.3.0`，`package-lock.json` 固定解析版本；通过 `LazyMotion + domAnimation + m` 使用，`MotionConfig reducedMotion="user"` 统一减弱动画策略。
- 页面入场约 280ms；侧栏宽度约 220ms；页签指示线使用共享布局动画；原生详情展开约 180ms。
- 动画不绑到发送、重试、付费授权或完成事件，不给任务面板添加变动 key，不通过退出动画延迟或重新挂载业务面板。
- 减少动态效果模式同时关闭 Motion 位移及 CSS 根布局／子元素过渡，保留静态选中态。
- 移除首页自动抢焦点，避免入场时将用户滚到表单中部。
- 详情页按路由懒加载，首页无需先下载 Diff、Markdown 与报告编辑器。不是通过放大构建告警阈值解决体积问题。

### 第二轮新鲜验收

- 正式前端：18 文件，86 项通过（含新增两项动画容器回归）；Legacy 3 项通过。
- 新增测试覆盖动画容器重新渲染时不重新挂载草稿、减少动态效果时直接处于最终位置。原付费防重、页面切换草稿、历史选择、修订与交接回归保留。
- TypeScript / Vite 构建通过。首屏 JS 367.06 kB（gzip 119.03 kB），详情 JS 282.18 kB（gzip 87.07 kB）。对比第一轮单块 JS 563.88 kB（gzip 174.87 kB）；总体代码量因动画增加，但首屏下载减少，本次无单块超过 500 kB 告警。
- 浏览器：1440×1000 及 390×844，侧栏折叠后页面无横向溢出；窄屏 Review 默认不展示 UUID／哈希。
- 真实浏览器中输入未发送 Coding 草稿，切到 Review 再切回，草稿完整保留，页签指示线仅一条；没有点击发送或启动。
- 模拟 `prefers-reduced-motion: reduce` 并重载：入口 transform 为 `none`、透明度为 1、根布局 transition 为 `0s`，运行中动画数为 0。
- 原生详情展开保留，未把它换成依赖 JavaScript 才能读取的自制弹窗；来源、位置与范围限制不变。
- 截图：`output/playwright/polish-home-desktop.png`、`polish-home-mobile.png`、`polish-review-desktop.png`、`polish-review-mobile.png`。浏览器及截图只验证展示范围，不代表模型质量新验收。

本轮没有付费、启动 Coding、修改 Java/Python、提交或推送。此前未提交的第一轮改动及其他产物保留。

## 第三轮：按用户指定的三个来源改进

用户最终指定 `greensock/gsap-skills`、`DavidHDev/react-bits`、`Leonxlnx/taste-skill`。
此前提及的 `kill-creator`、`vibe-research` 已由用户明确忽略。

- GSAP Skills 锁定 `aed9cfd3277740755f6bfc1155c7aa645403b760`，读取 core、React、performance 三个完整技能；以官方安装助手安装。用于 scoped ref、`useGSAP`、media-context 清理、减少动态效果及 transform/opacity 约束。
- Taste Skill 锁定 `f624f37a8c650b9cc550253104925efe4c8de246`。其主 `taste-skill` 明确不适用于 dashboard / app screen，因此读取并采用同仓库 `redesign-existing-projects`，而不是强行套营销页需要的四张照片、八处动画或图库随机风格。该技能已安装。按现有项目局部改进，不生成虚假业务数据、法律页面或设置入口。
- React Bits 不是 skill，而是组件源码库。锁定 `310fc737fadcaa149a4e06fb088fc95332dc8bfb`，完整读取 TS AnimatedContent 并改编 mount 模式，许可原文纳入 `THIRD_PARTY_NOTICES.md`。

### 实际改动

- `PageEntrance` 改用 GSAP，首页按标题、概览、操作内容做约 45ms 的短错峰；详情仅作用于头部的三个显式标记区域，不动持续挂载的任务面板。
- 移除上游的 `visibility:hidden` 初始状态、ScrollTrigger、模糊、遮罩、旋转、消失及自动回调；没有滚动接管或无限动画。
- `gsap.matchMedia` 对 no-preference 才创建动画，偏好变化与卸载会 revert；清除结束后的 opacity/transform。Motion 仅保留原有导航指示线，同一 DOM 属性不由两个动画引擎同时管理。
- 去除工作区按钮、导航按钮、审查启动区与报告指标的伪金属渐变，采用同族中性灰和清楚的表面层级。侧栏副标题改为可读中文，不再是 8px 的字母装饰。
- 延续前一小轮已实施的字号／控件 token、移动端 44px 交互区域、两列换行页签、表单错误关联；不再依赖横向滚动才能看到最后一个页签。
- 模块交付保持独立操作组：桌面 40px 等高、12px 间距，手机等宽纵排且至少 44px；没有放松归档就绪条件。

### 许可与适用性

GSAP Skills 为 MIT，但 GSAP npm runtime 是 GSAP Standard License，两者不混称。
React Bits 为 MIT + Commons Clause，不是纯 MIT；允许集成到应用，禁止将组件本身单独售卖／再分发为组件包。
本项目只按应用集成，不将改编组件单独发布。若未来要销售模板／组件库，必须重新核对许可范围。

### 验证范围

新的动画回归覆盖减少动态效果不建 Tween、卸载时 revert、重渲染不丢草稿；创建表单补充了错误字段关联与保留输入的回归。
前述 86 项、367kB 数值为历史，不代表此轮最终结果。实际命令仍为正式／Legacy Vitest 和 TypeScript/Vite 构建，最终计数以本轮工具结果为准。
页面测量保存在 `output/playwright/uiux-*` 及后续 `gsap-*` 截图。未付费、未运行 Coding、未提交推送。

第三轮最终新鲜结果：正式前端 **88 项／19 文件**、Legacy **3 项**通过；TypeScript／Vite 构建通过；首屏 JS 438.64 kB（gzip 147.00 kB），详情块 283.09 kB（gzip 87.30 kB），未出现单块超过 500 kB 告警。GSAP 增加了总包量，不能沿用第二轮 367 kB 的现状描述。
浏览器在 375px、768px、1024px、1440px 检查当前页面无整页横向溢出；375px 页签为两列 44px 点击区域。GSAP 最终版本另在 375px 首页模拟减少动态效果并重载，三个入场分区均为 opacity 1、transform none；控制台错误数 0。
截图 `gsap-home-desktop.png`、`gsap-home-mobile.png` 已人工查看，保留真实会话数据；未以截图检查替代代码回归。

## 黑白科技风预览（用户另行选择）

基线 `c18e4d1`。用户选择黑白科技风，新增独立 `tech-theme.css`：石墨背景、冷白按钮、统一暗色输入／报告／证据／交付及 Diff 表面。首页增加自行绘制的 SVG 线框标识和一次性扫描线；仅启用主按钮悬停扫光，不引入循环背景、网络请求或虚假进度。未添加依赖。

主题只覆盖颜色与装饰，保留原有布局／门禁。浏览器检查时发现并修复了原审查／对话容器的白底残留。报告正文不增加循环动画。减少动态效果时扫描线和伪元素动画禁用。

新鲜正式前端 88 项通过，Legacy 3 项通过，构建通过。桌面模块操作两按钮同顶、均 40px；375px 两按钮同左、等宽、均 44px，页面不横向溢出。截图见 `output/playwright/tech-home-desktop.png`、`tech-review-desktop.png`、`tech-module-mobile.png`。
本次为可交互外观预览，未提交／推送，未启动付费审查或 Coding。
