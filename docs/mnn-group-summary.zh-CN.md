# 在 Telegram 群聊中使用 MNN Chat 本机总结

开发分支：[`feature/mnn-group-summary`](https://github.com/Naza3/Telegram/tree/feature/mnn-group-summary)。基于 Telegram Android `12.10.6 (7112)`，上游提交 `f2908b14133bbffbf7ab04f641ecb5bfaf533242`。远程仓库为用户创建的 [Naza3/Telegram](https://github.com/Naza3/Telegram)，本地 `origin` 指向该 Fork，`upstream` 保留官方仓库。

功能的阶段状态、验收证据和待测项目见 [实施计划](mnn-group-summary-roadmap.zh-CN.md)。已完成代码验证的功能仍需 [真机验收](mnn-device-validation.zh-CN.md)，不能以模拟测试代替实际 MNN 模型效果。

本轮已改进直接群聊总结：开启流式时，每个来源分段和每轮合并都显示当前请求的正文草稿，切换请求时清空旧草稿并标明阶段；生成中、成功或失败后可选择请求查看实际输入。完整本地回归通过：15 个 JVM 测试类，HTTP 62 项、其余核心 1308 项断言，7 项构建配置与 24 组样例检查通过。Android 最终编译通过（59.7 秒），2834 份已跟踪 Java／XML 源文件的编译前后哈希一致；源码 `22214a5` 的 [APK CI](https://github.com/Naza3/Telegram/actions/runs/37172659455) 已成功（19 分 09 秒），[下载新版 APK](https://github.com/Naza3/Telegram/actions/runs/37172659455/artifacts/11292556741)。真机仍未测。

最近发布的 Markdown 导出已改为 **群聊对话**，按“A 说／B 说／C 回复 A”阅读，JSON 继续保留完整数据。168 项导出断言与 Android Java 编译（1 分 3 秒）通过，源码 `cc7f912` 的 [APK CI 37167137561](https://github.com/Naza3/Telegram/actions/runs/37167137561) 已通过（18 分 21 秒），[下载聊天对话格式 APK](https://github.com/Naza3/Telegram/actions/runs/37167137561/artifacts/11290681738)。该产物不包含本轮每请求草稿显示改动。更早的原始导出 `c78b111` 及[其 APK](https://github.com/Naza3/Telegram/actions/runs/37163785988/artifacts/11288507718) 尚不包含聊天化排版；真机保存与分享交互仍待验证。

## 使用方式

1. 在同一手机的 MNN Chat 中打开已下载的文本模型，进入聊天页。
2. 打开菜单 → API 设置 → 启动本机 API，等状态变为“可用”，复制 API 密钥。
3. 打开此修改版 Telegram 中当前账号可读取的普通群、超级群、频道或具体 Topic，在右上角菜单选择 **AI 总结群聊**。受保护群也提供入口；论坛的话题列表菜单可总结全部话题。聊天预览展开为完整页面后，菜单会恢复对应入口。
4. 进入 **MNN API 设置**：

   | 设置 | 默认/填写方式 |
   | --- | --- |
   | 服务地址 | `http://127.0.0.1:8080/v1`；也接受完整的 `/v1/chat/completions` 地址 |
   | 模型名称 | `mnn-local` |
   | API Key | 粘贴 MNN Chat 中复制的密钥；仅在 MNN 关闭鉴权时留空 |
   | 最大输出 token | `512`；本次对接的 MNN 分支最高支持 `2048`。客户端为其他兼容服务保留 64–8192 的输入范围 |
   | 流式输出 | 可选；不支持 SSE 时可手动切回普通模式 |
   | 上下文字符预算 | 默认 `6000`，可设置为 2048–32000；包含规则、方向、消息及输出预留，不是精确 token 数 |

5. 可先用 **测试连接** 检查服务、鉴权与回复格式；该测试不读取聊天消息。
6. 选择消息范围、总结模板和补充要求，再开始总结。默认仍为最近 N 条文字消息和通用模板。
7. 查看 **话题、结论、待办**。点击 `[m1]` 等引用会先重新核验和预览原文，再选择跳回群聊。
8. 新版会自动保存成功生成的摘要。主聊天列表顶部 **⋯ → AI 总结历史** 查看当前账号的全部记录；总结面板中的 **本聊天历史** 查看当前聊天记录。独立持久历史属于本轮新增功能，已通过本地回归与 Android Java 编译，完整 APK／CI 已通过，真机验收仍待完成，`68737de` 版尚不包含它。

请保持 MNN Chat 的本机 API 可用。更改过端口时以 MNN 设置页为准。支持普通和 SSE 流式 Chat Completions，所有分段和合并请求均遵循流式开关。本轮在开启流式时，让每个来源分段、每轮合并都显示当前请求经过思考过滤的正文草稿；进入下一请求时清空旧草稿，显示“总结第 i / n 段”或“第 r 轮合并 · 第 i / n 段”，并标记“生成中，尚未完成或校验”。草稿不是完整结果，不开放引用跳转；只有最终结果完整生成并通过引用校验后才显示成功结果。思考内容继续隐藏，断流、取消或 `length` 保持失败／未完成状态，不保存成功历史或推进总结游标。流式改变传输和可见进度，不等于模型推理加速；普通模式仍等待完整响应处理，不新增中间草稿。

MNN 使用当前已加载的模型，填写模型名不会替 MNN 切换模型。配套服务是 [Naza3/MNN 的 feature/mnn-chat-local-api 分支](https://github.com/Naza3/MNN/tree/feature/mnn-chat-local-api)，此前接口核对依据为提交 `097ebe312c34d2391c55b2dfe3df05265b1c13ee`，后续 API 思考设置改动见下文。它会将 `max_tokens` 应用于原生推理，接受范围为 1–2048；此前针对其他 MNN 版本“可能忽略该参数”的说明不适用于这个分支。

该接口明确拒绝 `temperature`、`top_p`、工具调用等未支持参数。客户端现在只发送模型名称（非空时）、`messages`、`max_tokens` 和 `stream`，不再自动附加 `temperature: 0.2`；采样行为由 MNN 服务决定。单次请求还受 64 KiB 请求体、32768 字符消息正文等限制，建议先使用默认 `6000` 上下文字符预算。服务端支持纯文本，不接受媒体内容或 MNN 媒体标签。

API Key 在 Android 6.0+ 使用 AndroidKeyStore 加密保存；旧版本或加密保存失败时仅本次运行有效，界面会提示。

## 启动时的权限行为

打开、返回 App 或切换账号时，不再自动申请通讯录、通知、存储／照片视频权限，也不再弹出 MIUI 锁屏显示、Android 全屏来电权限设置提醒。进入联系人页不申请手机通讯录权限；主导航和附件面板中的联系人图标均不再因缺少权限显示感叹号。登录页缺少读取手机号权限时直接跳过自动填号，可以手动输入。

后台发现大量手机联系人时，原来的导入确认按“取消”处理，不因隐藏弹窗而自动同意上传。旧文件迁移引导从启动页移除，仍可在存储使用设置中进入。已有系统授权和联系人同步设置保持原值；相机、录音、位置、分享联系人等具体功能仍在用户主动使用时按需请求权限。通知权限可自行在系统设置中开启。Telegram 服务条款由用户正常确认。

## 截图与录屏

此客户端允许截图和录屏，原口令设置页的“最近任务中显示内容”截图关联开关已移除。统一策略覆盖主窗口、受保护聊天、Secret Chat、图片／一次性媒体查看器、翻译、口令和付款弹层、通话加密标识，以及 Story 的窗口和视频 Surface。旧的本地截图配置不会重新开启客户端限制。

截图与最近任务缩略图原来共用 `FLAG_SECURE`；解除限制后，聊天内容可能出现在最近任务预览中。口令验证和自动锁定仍保留；截图通知、复制／转发／保存权限也维持各自逻辑。截图可用不代表该内容已纳入 AI 总结，Secret Chat 和一次性媒体仍不作为总结输入。系统设备管理员等外部限制不由此客户端控制。本轮截图及录屏行为尚待真机验证。

## 连接测试与 HTTP 400 诊断

“请核对模型名称、上下文长度和 Chat Completions 参数兼容性”是客户端的通用提示，不是 MNN 返回的具体原因。新版连接测试会在 HTTP 错误后增加 **连接测试诊断**，展示状态码、响应格式，以及能够提取的 **服务端错误说明**；可以长按选择说明文字。

诊断仍发送固定的简短测试文本，使用普通模式，`max_tokens` 使用设置页当前填写的最大输出值（默认 `512`），不读取群聊，也不保存设置。测试过程中会显示本次上限。等待响应的读取超时与普通生成一致，为 300 秒；这不是整个测试的总时限，可随时取消。错误说明仅在该测试中显示，过滤当前配置的 API Key 和常见凭据形式，限制长度，不写入日志或持久存储。总结和追问失败仍不回显服务端正文。鉴权失败和重定向不读取错误正文；HTML 错误页只显示格式提示，不展示页面内容。服务没有提供可用原因时会明确提示。

此前连接测试将输出上限写死为 `64`，所以即使设置为 `512`，测试仍可能返回长度限制错误；此问题已修复。服务返回 `finish_reason=length` 时，新提示会区分“API 已响应”和“完整文本生成尚未验证”，并显示本次设置值，不把截断的正文当成测试成功。可从 `512` 调到 `1024` 或 `2048` 后重试；本次 MNN 分支最高接受 `2048`。思考过程可能占用输出预算，但关闭思考仍不能保证一定在给定上限内结束；本地服务以生成 token 计数达到上限来标记 `length`。

最新用户反馈：新版 Telegram 的连接测试成功；随后仍用 `2000` 输出 tokens／`32000` 上下文字符预算、通用模板、空补充要求、开启流式，总结最近两条短文字。界面接收字符数超过 `5000` 且继续增加，用户确认检测到思考内容，但回答正文为空。这支持继续排查 API 会话的思考行为；字符数不是 token 数，不能据此认定全部接收字符都是思考内容或 `max_tokens` 被忽略。此为用户观察，尚未独立真机复现，也不能用连接测试成功代替总结完成验证。

此前核对的 MNN 提交 `097ebe312c34d2391c55b2dfe3df05265b1c13ee` 会新建独立 API 模型会话（`useCustomConfig=false`），使用基础 `config.json`，不加载聊天页的 `custom_config`；聊天页思考开关写入的正是后者。因此，聊天页“关闭思考”不能作为 API 已关闭思考的依据，也不能据此断言用户手机上的 API 开启了思考。Telegram 的流式选择和提示词不会直接修改 MNN 会话的思考设置。

MNN 配套改动已应用并推送至[提交 `35affe5d9d94e7e843bac4daecee1d2ddf60123f`](https://github.com/Naza3/MNN/commit/35affe5d9d94e7e843bac4daecee1d2ddf60123f)：在原生模型加载成功后，为 API 会话临时设置 `jinja.context.enable_thinking=false`，避免加载中的 `context.json` 再次覆盖；不调用会写聊天配置的 `updateThinking`。现有高级设置保存的仍是 `custom_config`，不能当作已修改 API 的基础配置。[MNN CI 37133978173](https://github.com/Naza3/MNN/actions/runs/37133978173) 已成功：原生编译、API／完整 App 测试、release lint、APK 审计及签名检查全部通过。未真机验证用户模型对该开关的支持和性能变化。这是独立 MNN 应用的改动，下面提供的 Telegram APK 不包含它。

**配套 MNN 下载：[0.8.3-localapi.4（834）Actions 已签名 ARM64 APK](https://github.com/Naza3/MNN/actions/runs/37133978173/artifacts/11278482493)**。登录 GitHub 下载 ZIP，解压 `MNN-Chat-API-arm64-ci-test.apk`。用户后来已澄清旧包来自 GitHub Actions 签名；本次沿用该 MNN CI 签名方案，但手机已安装包的证书未独立核验。确实使用自己私钥签名的用户，才需要下载[未签名产物](https://github.com/Naza3/MNN/actions/runs/37133978173/artifacts/11278242921)并用原密钥签名；不要通过卸载旧版绕过签名不匹配。停止旧 API，完成更新后重新启动 API，再用相同两条短消息复测；Telegram 保持 `2000`／`32000`、通用模板、空补充要求与流式开启。

本次 MNN 云端报告确认：51 项构建辅助测试、85 项 API 专项测试及 531 项完整 App 测试（含专项），均无失败、错误或跳过。未签名 APK 为 32,547,449 字节，SHA-256 为 `ee492c538460b1a6796adf7e91e6eeb7c0f9670f534f741a47e3564fb8bb00ba`；重签名后哈希会改变。产物于 2026-10-17 16:03 UTC 左右到期。[审计报告与源码／许可说明包](https://github.com/Naza3/MNN/actions/runs/37133978173/artifacts/11278497383)请与再分发的 APK 一起保留。

### 输出上限与上下文预算如何搭配

“上下文字符预算不足以容纳规则、补充要求和输出预留”是在发出总结请求前进行的本地检查。当前按 `最大输出 tokens × 4` 预留字符，再扣除规则、方向、阶段说明，至少留出 512 字符放入消息；这不是模型 tokenizer 的精确计算。输出 2048 会预留 8192 字符，无法放进默认 6000 字符总预算，需要同步调高上下文预算。预留只用于客户端分块计算，不作为额外文本发送，也不计入界面的输入字符量；例如最大输出 2000 对应的 8000 字符预留不会额外加进输入。

| 用途 | 最大输出 tokens | 上下文字符预算 |
| --- | --- | --- |
| 简短总结、默认起点 | 512 | 6000 |
| 需要更长回复时先尝试 | 1024 | 12000 |
| 仍需增加输出空间 | 2048 | 16000 |

先使用空白或简短补充要求、最近 20 条文字消息验证。以上搭配用于客户端的字符估算，不承诺手机内存、模型上下文或生成质量达标。当前 MNN API 接受 1–2048 输出 tokens；Telegram 设置页为其他兼容服务保留 64–8192 的输入范围，填入更大数值不会提高 MNN 的服务端上限。输出达到上限需要结合实际停止原因和模型设置排查，增加上限也可能只延长等待；输入预算不足则需要为规则、原文和输出预留一起留够空间。

### 直接总结发送哪些内容

直接总结发送的是 system 规则与 user 中的方向、阶段说明和来源 JSONL，不使用“群聊对话”Markdown 导出文件。每条来源包含 `ref`、`part`、完整时间、发送者名称和 `text`，已知时还包含 `sender_id`、回复关系等字段；这些信息用于来源引用、身份和关系判断，所以界面的输入量通常大于原文长度。普通总结不带追问的历史问答；需要合并时，后续请求会处理本轮已生成的分段摘要。

合成输入审计使用 100 条、每条 20 字的文字消息，无回复关系：

| 输入组成 | Java 字符数 |
| --- | ---: |
| 原文 | 2000 |
| 逐条 JSONL 元数据及记录语法 | 10792 |
| system 规则 | 259 |
| user 规则及阶段说明 | 576 |
| 界面显示的输入总量 | **13627** |

这些数值来自合成样例，不能套算真实群聊或每次请求。Java 字符数按 UTF-16 代码单元计算，emoji 等字符可能占两个单位；它不是字节数或 token 数，也不含 HTTP JSON 外壳和输出预留。样例证据在 `/workspace/build-logs/mnn-prompt-input-audit/`，没有使用用户群聊原文。

本轮增加 **查看模型输入（N次请求）** 入口：生成中、成功页和错误页都可点击，从本次任务的请求列表中选择某个来源分段或合并请求，查看实际提交的完整 system／user 文本及字符数，并可长按选择、复制文字。普通和流式请求都可查看，内容与 Markdown 导出不同。查看内容不包含认证 API Key，不写入日志或持久总结历史；列表只保存在当前会话内存中，开始新总结、重试或关闭面板时清除。

### 本轮生成耗时处理与观察

用户已反馈：最大输出为 **2000 tokens**、上下文字符预算为 **32000**，约 10 条消息等待 200 秒仍未完成，最终收到 `length`，普通和流式两种模式都尝试过。这是用户报告，尚未在连接的真机上独立复现；不能将此问题归因于默认小预算或多分段，也不能仅凭耗时断言模型正在思考。这里的 32000 是客户端字符预算，不代表模型具有同样数量的 token 上下文。

本轮压缩每条来源消息的重复元数据，省略已明确约定的默认值，保留引用、正文、身份和已确认回复关系；未知关系仍保持未知，不将它写成已确认的否定。摘要长度改为随输出预算和来源数量给出精简写作目标：本次请求不超过 10 个来源时，正文目标最多 220 个汉字；较多来源最多 440 个汉字，较小输出预算还会收紧。保留话题、结论、待办和来源引用，简单内容允许更短。这是发给模型的写作要求，不是截断正文，也不保证模型一定遵守或在上限内结束。

生成面板增加当前请求的发送／接口已响应／收到内容阶段、输入字符量、输出 token 上限和等待时间。输入量为 system 与 user 文本的字符长度，不是请求体字节数或 tokenizer 结果；收到的字符量包含被隐藏的思考内容，不等于屏幕正文长度或已生成 token 数。检测到 `<think>` 标记或接口思考字段时才显示“检测到思考内容，已隐藏”；没有观察到这些信号不能证明模型没有思考。普通模式通常要等完整响应才能统计内容，阶段和字符量都不是完成百分比。

达到 `length` 时提示本次输出上限；只有实际观察到思考内容时才增加检查 API 思考配置的提示。已经显示的当前请求草稿仍不算成功，不保存到总结历史。提高输出上限也可能延长等待，流式本身不会降低生成量。此前元数据压缩与请求观察已通过回归及构建；本轮每请求草稿及输入查看的本地回归、Android 最终编译和 [APK／CI](https://github.com/Naza3/Telegram/actions/runs/37172659455) 已通过，实际延迟、模型质量和真机行为尚未验证。

旧版的 `temperature: 0.2` 会被上述 MNN 分支确定性地拒绝，返回 `Invalid request: Unsupported generation option`；此不兼容字段已移除，其他请求参数和重试行为不变。若再次出现 400，请提供新显示的错误说明，以及 MNN Chat 版本、API 地址和模型名称；不需要 API Key 或完整 MNN 调试日志。这里的 API Key 来自 MNN Chat，与 Telegram 构建使用的 `api_id/api_hash` 不同。源码和模拟接口验证仍需手机复测确认。

## 总结方向与追问

提供通用总结、项目进展、决策与争议、待办跟进四种模板，以及最多 1000 个 Unicode 字符的补充要求。例如：

> 重点整理发布进度、阻塞和已确认决定。待办列出明确负责人和截止时间，没有提到的写“未指定”；保留反对意见及原消息引用。

方向可仅本次使用，或保存为该群／Topic 的偏好、账号默认值。优先级为本次覆盖、群／Topic、账号、内置默认。每个分块和每轮合并使用同一份配置快照。改方向只影响下一次任务，不重置增量进度；可以明确选择重做上次范围。

“突出与我相关”只改变关注点；“实际筛选”先在客户端按真实发送者、明确提及或已确认回复关系筛选，并显示额外保留的上下文数量。可组合成员 ID 和关键词（不区分大小写的字面子串）条件。没有明确提及不代表讨论与本人无关；筛选后的任务不推进通用增量进度。

当前总结面板中的成功结果可继续追问，最多五轮，问题最多 500 个 Unicode 字符。每一轮都从本次消息快照取证，不自动查询更多群历史。问题和旧回答不作为群聊事实；上下文不足或超预算时明确提示。问答不改变总结进度，也不会向群内发送内容。更换范围、方向或来源失效时开启新会话。独立“AI 总结历史”只供阅读和核验引用，不提供历史追问。

## 消息范围

- “最近 N 条”统计当前账号已发送成功且可读取的有效文字消息，不是屏幕上已有的 N 行。首次读取最近消息及增量的最新位置探测使用 `offset_date=0`，从服务端最新位置确定消息 ID 上界，不用手机日期裁掉较新的消息。允许带链接预览的文字，但不读取网页正文。
- “当日”以当前账号的 Telegram 校准时间确定日期和截止时刻，按手机时区计算当天零点，不使用服务器 UTC 零点。这里的校准时间是本地时钟加 Telegram 的时间差值，并非每次现场查询服务器；手动修改手机时间后不保证立刻重新校准。Telegram 消息时间精度为秒。
- “上次总结之后”首次明确用最近 N 条初始化，之后从保存位置向新消息连续分批。每批上限按原始历史消息计算（包含被跳过的非文字位置），完整成功才推进；可继续补齐大量积压。普通最近 N 条、当日、未读、重做与实际筛选不移动已有增量位置。
- “进入聊天时的未读”固定进入时的边界，新到达消息不混入。论坛群需要进入具体 Topic；无法确认边界时禁用，不以最近 N 条替代。
- 增量进度记录只保存范围、进度和结果摘要哈希，不保存聊天原文或摘要正文；独立总结历史另行加密保存已完成摘要。重启后可直接查看已保存摘要，也可从原进度继续，或按上次范围重新生成；重新读取时原文可能已编辑或删除。删除总结历史不重置增量游标。
- 在具体 Topic 中只读取该 Topic；在论坛话题列表的“全部话题”入口或群的全部消息视图中覆盖该群的所有 Topic。全部话题范围不提供无法可靠确定的未读边界。
- 支持当前账号可读取的频道、受保护群及带 `noforwards` 标记的文字；该标记不再是 AI 入口、历史筛选或引用核验的拒绝条件。真实读取权限仍由 Telegram 请求及账号／聊天／Topic 核验确认，不能读取已删除、当前不可访问或范围外的消息。
- 开启普通自动删除计时的文字（`ttl_period`）在尚未过期且仍可读取时纳入；历史读取和来源重新核验时，以账号 Telegram 校准时间核对是否已过期。这与一次性／自毁媒体不同，后者仍被排除。
- 跳过媒体 caption、图片／语音／文件内容、服务消息、临时或未发送消息、已过期文字、一次性／自毁媒体及其他媒体 TTL 内容；不处理私聊、秘密聊天及频道私信。
- 当日最多纳入 2000 条有效文字，最多扫描 100 页/10000 条历史。达到限制或游标无法继续时显示“部分结果”和实际覆盖范围，不宣称已总结全部。
- 当前版不拼接超级群迁移前的旧群历史，结果范围说明会标明。后续页加载失败时直接报错，不把失败伪装为完整结果。
- 范围说明显示首尾消息 ID，便于与实际来源及固定快照核对。ID 只在所属聊天／Topic 范围内解释；两端之间可能有媒体、删除或筛选产生的空位，不能把 ID 差值当作纳入条数。
- 历史请求不修改已读状态。开始总结时，消息内容发送到设置中的模型接口；结果在本机总结面板和已保存历史中查看，不自动发回群。下面的原文导出是用户单独选择的文件操作。

长内容按有界字符预算分块，再合并摘要，引用始终使用原始消息编号。超出总输入预算或无法完整合并会明确失败，不静默截断。模型引用只允许映射到本次消息集合；无有效引用或越界引用会报错，避免错误跳转。引用存在并不保证模型的结论正确，仍应以原文为准。

## 导出待总结群聊文字

导出直接使用当前账号可读取的文字消息，不需要启动 MNN，也不会调用 Chat Completions、生成摘要或推进增量游标。它复用“最近 N 条”“当日”“上次总结之后”“进入聊天时的未读”的范围读取规则和覆盖说明；未知的未读边界仍不可用，论坛整群视图不伪造未读范围。导出一次“上次总结之后”后再次导出，不能因为上次文件已保存而跳过那些消息。

在总结面板选择范围、筛选条件及总结方向后，点击 **导出待总结消息**，再选择 **群聊对话（Markdown，推荐）** 或 **JSON（完整数据）**。当前模板及自定义 Prompt 自动附带，明确区分用户处理要求与聊天原文。成员、关键词及与我相关的实际筛选遵循现有规则；“突出与我相关”只改变处理方向，不因此删除消息。首版不提供 REPLAY 导出，需重新选择上述四种范围之一。

聊天版 Markdown 顶部只保留少量中文说明：群名、范围、条数、必要的覆盖限制和当前 Prompt。正文按本地日期分组，每条显示 `HH:mm 姓名 说` 或 `HH:mm 姓名 回复 姓名`，完整原文放在 Markdown 引用块中。例如：

```markdown
## 2026-10-04

10:00 阿青 说：

> 周五先发内测版。

10:01 小林 说：

> 测试环境已经准备好。

10:02 小周 回复 阿青：

> > 回复片段：周五先发内测版。
>
> 我负责整理发布清单。
```

回复旁的预览最多 80 个 Unicode 字符，超出后加省略号；“引用片段”来自 Telegram 提供的引用，“回复片段”来自本份导出中父消息的正文。这只缩短预览，不截断该条消息正文。目标不在最终导出集合或目标会话未知时，显示“回复未导出的消息”，不猜被回复者姓名。不同真实发送者使用相同显示名时附自然序号以区分；全话题导出使用 General 和本份导出的自然话题序号，不把技术 ID 当显示标题，也不猜未知话题名称。

聊天版供阅读，不提供逐条机器元数据或原始引用字段的无损还原保证。需要完整字段、完整 `quote_text` 或可靠程序处理时选择 JSON；本轮 JSON 结构保持不变。其 `schema_version` 为 `1`，聊天、消息、发送者及话题 ID 均用字符串保存，避免外部工具把大整数读成近似值。主要字段如下：

| 字段 | 含义 |
| --- | --- |
| `chat`、`topic`、`exported_at` | 聊天身份、导出的是整群还是具体话题、导出时刻及时区 |
| `selection`、`direction` | 范围、筛选、覆盖／分页说明，以及模板与自定义要求 |
| `messages[].ref`、`key` | `[mN]` 只是本份文件的索引；`key` 使用 `dialog_id:message_id` 标识实际消息 |
| `messages[].reply_to` | 目标复合键和 `in_export`；无已知回复目标时为 `null`，目标会话 ID 为 `"0"` 表示未确定，不能自动关联本群同号消息 |
| `messages[].topic_id`、`quote_text`、`text` | 逐条话题、引用片段及完整原文；时间、发送者和身份／提及信息也随每条消息保留 |
| `missing_reply_target_count` | 回复目标未纳入文件的回复消息数量，不是去重后的缺失父消息数量 |

JSON 的 `topic_id`、`quote_text` 分别来自消息实际话题归属和 Telegram 提供的引用片段；引用片段不等于父消息全文。`topic_id="0"` 表示非论坛或未知，`"1"` 表示 General，其他值为真实话题根消息 ID。目标关联仍使用聊天／消息复合键，不能仅凭相同数字消息 ID 在不同群之间关联。

不会为补全回复链额外读取范围外历史。目标不在集合时，只能说明本次未包含，不能据此猜测它已删除、私密、不可访问或没有正文；缺失的话题／引用元数据同样不补造。全话题导出按每条实际元数据保留话题归属，不把全部消息归到当前页面显示的某一个 Topic。

文件准备完成后，先核对范围、实际文字条数、回复目标未包含的数量及文件大小，再明确选择 **保存到文件** 或 **分享导出文件**：保存使用 Android 系统文件选择器（SAF）选择位置，分享使用 FileProvider 的临时只读 URI 授权和系统分享面板。不会自动选择接收应用、上传服务或群聊，不额外申请广泛存储权限。SINCE／UNREAD 完整分批后可选择 **读取并导出下一批**，批次位置只在本次导出会话中继续，仍不写总结游标。导出包含原文，外部文件不属于加密的“总结历史”，也不会作为一条成功摘要写入历史。

每份导出上限为 **8 MiB**，按 UTF-8 实际编码字节计算，不是字符数。超限明确失败，不截断消息后宣称成功；历史读取沿用每页 45 秒超时，未增加独立的文件写入超时。取消系统保存选择器时不开始写入；写到一半遇到文件提供方错误，目标文档可能不完整，界面提示重试，不自动删除用户选择的文档。

分享文件暂存于应用缓存的 `ai-chat-exports` 子目录，本轮仅为这个窄目录新增 FileProvider 路径，具体文件按临时只读 URI 授权分享。未分享的临时文件取消时清理；已经交给系统分享的文件保留约 24 小时，退出账号也不立即删除已交付的文件，应用运行时定时清理，进程退出后在后续导出时清理过期文件，不保证进程被杀后准点清除。已另存或交给接收应用的外部副本不属于这份缓存。

上述文件交付、取消、账号切换及面板关闭行为仍需真机验证；上一版 APK 构建通过不代替新版排版和这些交互的真机验收。验收项目见 [X01–X16 导出用例](mnn-device-validation.zh-CN.md)。

上一版原始导出的回归记录（2026-10-04）：15 个 JVM 测试类、HTTP 59 项、其余核心 1237 项断言通过，包括导出格式 97 断言、增量范围 17 组／125 断言及历史加载 14 组／92 断言；24 组样例结构与 7 项构建配置检查通过。Android Java 编译耗时 1 分钟，源码 [`c78b111242985bddf2551e2f1fd9ec42588837e0`](https://github.com/Naza3/Telegram/commit/c78b111242985bddf2551e2f1fd9ec42588837e0) 的 [APK／CI](https://github.com/Naza3/Telegram/actions/runs/37163785988) 通过，产物为 [`Telegram-MNN-arm64-debug-10`](https://github.com/Naza3/Telegram/actions/runs/37163785988/artifacts/11288507718)。这组证据不包含本轮聊天化 Markdown；新版 168 项导出断言与 Android 编译通过，APK CI 已通过，手机 SAF、分享及排版均未验收。

聊天化改版源码 [`cc7f912`](https://github.com/Naza3/Telegram/commit/cc7f912afec92f453754fe7cd9ec710dac3a3df0)：本地导出 168 项断言通过，冻结源码的 Android Java 编译通过（1 分 3 秒，2834 份受版本控制的 Java／XML 文件编译前后一致）。同一份三条合成对话的 JSON 逐字节不变，Markdown 从 4103 降为 582 UTF-8 字节，正文完整；该样例不代表所有群的文件缩小比例。新 [APK CI](https://github.com/Naza3/Telegram/actions/runs/37167137561) 已通过：15 类 JVM，HTTP 59 项、其余核心 1308 项断言，24 组样例结构和 7 项构建配置检查通过；手机排版、保存和分享未独立验证。

## 预算、缓存和来源有效性

字符预算是保守估计，不能替代模型 tokenizer；预算不足会在请求前提示，原文过长会分块并保留稳定引用。回复链尽量放在相邻分块中；同一个模型端点只允许一个活动任务。客户端取消停止等待及后续请求，但是否终止服务端推理仍取决于 MNN。

成功摘要可在当前面板明确选择“查看已有结果”，同时显示生成时间；正常生成和重新生成不会自动复用缓存。这层生成缓存只驻留内存，最多八条／2 MiB，与下述持久历史分开；`mnn-local` 可对应后来切换的实际模型，因此不能把缓存当作最新推理结果。已观察到的消息编辑、删除、权限变化和退出账号会使当前面板的相关结果与缓存失效；源消息变更不会抹除已经保存的历史摘要。

当前总结面板的引用预览与跳转前会重新读取原消息。删除、编辑、过期、实际读取权限丢失、账号或范围变化时不展示旧正文，需重新选择范围生成；仅增加 `noforwards` 标记不会使仍可读取的原文失效。缺少足够的当前服务端信息时明确提示暂不能核验，不用旧缓存声称仍有读取权限。校验引用存在不能证明模型结论有事实支持，仍需结合原文核对。

本轮没有新增自动过期调度：已生成快照的重做／追问不会只因时间流逝自动刷新，也不承诺消息到期瞬间清除所有缓存或中止推理。收到删除事件后会使当前面板的相关结果失效；点击引用仍重新读取并核验当前状态。

## 独立总结历史

主聊天列表顶部 **⋯ → AI 总结历史** 打开当前账号的记录列表。总结面板的 **本聊天历史** 限定当前聊天：`topicId=0` 显示该群全部 Topic 的记录，具体 Topic 只显示对应 Topic 的记录。关闭面板或重启 App 后仍可查看已保存正文，打开记录不会再次调用模型。

新生成的成功结果自动保存，包括“历史范围只有部分覆盖、但该批摘要已完整生成并通过引用校验”的结果；这类记录保留“部分覆盖”标记，不会冒充全群总结。断流、失败、取消草稿以及没有有效文字的批次不保存。加密或写入失败时明确提示“未保存”并可重试；当前看到摘要不等于已持久保存。

历史使用 AndroidKeyStore 在本机加密，文件存放于 App 的 `noBackup` 目录。按账号槽位和真实账号身份隔离，每份历史最多 100 条／8 MiB，超限淘汰最旧记录。只保存摘要正文、生成时元数据和有序来源引用及校验哈希，不保存 API Key、接口地址或整批来源原文；摘要本身仍可能包含模型摘录的原话。Android 6.0（API 23）以下不能持久保存；加密不可用时没有明文回退。

详情中的每次引用预览都会从 Telegram 重新核验原消息，跳回聊天前再次核验。原文编辑、删除、过期或失去读取权限后，历史摘要仍保留生成时的样子，引用提示不可用，不用旧正文冒充现状。历史页只读，不以摘要或来源哈希恢复原文快照，也不提供历史追问；需要新结论时重新生成。

可删除单条或清空所选范围历史，这些操作不重置增量游标。检测到切换或退出账号后，历史列表／详情会清空旧内容、关闭引用预览并显示错误，要求从当前账号重新打开；页面本身不会自动退出。退出账号会清理该身份的历史文件和密钥。旧版本中已经关闭而丢失的摘要正文无法从原有哈希恢复，新历史仅从升级后新生成并成功保存的结果开始积累。

本轮持久历史已完成实现，并通过本地回归与 Android Java 编译；完整 APK／CI 已通过，真机验收仍待完成。此前 `68737de` 的 APK 和通过记录不包含本功能。

本轮自动化记录（2026-10-03）：14 个 JVM 测试类通过，HTTP／SSE／问答 59 项，其余核心共 1116 项断言；24 组样例结构检查及 7 项构建配置测试通过。真实 Android `compileDebugJavaWithJavac` 通过，耗时 1 分 9 秒，日志 `/workspace/build-logs/mnn-history-page/android-compile.log`。这些检查未运行手机上的 MNN 模型，不代表历史页交互、持久加密或生成性能已经真机验收。

本轮源码为 `4655df1a8f582f76a3041877698fc40ff0f71e3b`；[完整 APK／CI 37130095722](https://github.com/Naza3/Telegram/actions/runs/37130095722) 已成功（14 分 30 秒），签名、16 KB 对齐和 ARM64 架构检查通过；[下载 APK 与校验文件](https://github.com/Naza3/Telegram/actions/runs/37130095722/artifacts/11276861783)。

## 代码位置

| 文件 | 职责 |
| --- | --- |
| `ui/ChatActivity.java`、`ui/TopicsFragment.java` | 群／频道／Topic 及论坛全部话题入口、原消息定位、预览展开与生命周期处理 |
| `ui/Components/GroupSummarySheet.java` | 范围、设置、加载、错误和结果交互 |
| `messenger/ai/SummaryHistoryLoader.java` | 独立 GUID 的 MTProto 历史分页、范围/账号/Topic 检查 |
| `messenger/ai/SummaryMessage.java` | 原消息位置与正文 |
| `messenger/ai/SummaryChatExport.java` | 有界群聊对话 Markdown／完整 JSON 导出、复合回复关系；聊天化改版的 168 项导出断言通过 |
| `messenger/ai/AiSummaryPrompt.java` | JSONL 输入、分块、合并与引用校验 |
| `messenger/ai/AiSummaryClient.java`、`AiSummarySse.java` | 普通／流式请求、诊断、端点并发限制、取消及问答 |
| `messenger/ai/PromptOptions.java`、`PromptPreferences.java` | 不可变方向配置、按身份和范围保存偏好 |
| `messenger/ai/SummaryStateStore.java` | 完整范围记录和增量进度的原子提交 |
| `messenger/ai/SummaryHistoryStore.java`、`SummaryHistoryStorage.java` | 独立摘要历史、容量淘汰、本机加密文件及账号清理 |
| `ui/SummaryHistoryActivity.java`、`messenger/ai/SummarySourceReference.java` | 历史列表／详情与不含原文的来源引用；预览和跳转重新核验 |
| `messenger/ai/SummarySourceVerifier.java` | 预览／跳转前重新核验消息及权限 |
| `messenger/ai/SummaryFilter.java`、`SummaryResultCache.java` | 明确筛选、有界且显式使用的内存缓存 |
| `messenger/ai/SummaryQuestionPrompt.java`、`ui/Components/SummaryQuestionSheet.java` | 本次原文问答的预算、事实边界和界面 |
| `messenger/ai/AiSummarySettings.java` | 按账号保存接口配置 |
| `messenger/ai/AiSummarySecretStore.java` | API Key 加密持久化及失败时的内存回退 |
| `messenger/AndroidUtilities.java`、`messenger/FlagSecureReason.java` | 客户端统一截图策略；独立窗口及 Story 渲染层使用同一策略 |

Java 路径相对于 `TMessagesProj/src/main/java/org/telegram/`。未添加外部 App 运行时依赖，也未改变 MTProto 协议实现。

## 开发验证

核心逻辑有独立 JVM 测试，使用模拟 Telegram/Android 适配层和本机 HTTP 测试服务，不访问真实聊天或 MNN 服务：

```bash
ECJ_JAR=/path/to/ecj.jar JSON_JAR=/path/to/json.jar bash tests/ai-summary/run.sh
```

本工作区依赖位于 `/workspace/ai-summary-tools/`。具体测试数量及阶段证据见实施计划；测试涵盖 HTTP／SSE、分页与增量、Prompt、偏好、游标、来源核验、预算、缓存、筛选和问答。测试编译并运行实际历史加载、提示词和 HTTP 客户端代码；UI 和密钥存储另以 Android 36 的 `android.jar` 核对编译。模拟适配层不能替代完整 Telegram 构建、真机 UI、真实 MTProto 和 AndroidKeyStore 验证。

此前提交 `68737de` 包含最近消息定位、普通自动删除文字、可读频道／受保护群／全部话题入口、菜单恢复、来源范围说明和截图策略。该版统一回归已通过：12 个 JVM 测试类、56 项 HTTP 用例、其他核心 758 项断言，另有 24 组合成样例结构校验和 7 项构建配置测试。Android `compileDebugJavaWithJavac` 已通过，耗时 1 分 6 秒；日志位于 `/workspace/build-logs/mnn-history-ranges/android-compile.log`。完整 ARM64 APK 已由 [CI 37125822349](https://github.com/Naza3/Telegram/actions/runs/37125822349) 构建并校验通过，真机与模型质量未测。该产物不包含新增持久总结历史；本轮验证与 APK 下载记录见上方及下方的最新构建。

本工作区已补全检出和 15 个子模块，并安装 JDK 21、Android SDK 36 / Build Tools 36.0.0、NDK 27.2.12479018、CMake 3.22.1 和 platform-tools。其他新克隆在构建前需要补全子模块：

```bash
git submodule update --init --recursive --depth=1
```

本地 ARM64 调试构建：

```bash
export JAVA_HOME=/workspace/toolchains/jdk-21
export ANDROID_HOME=/workspace/android-sdk
export GRADLE_USER_HOME=/workspace/gradle-cache
# Read credentials without putting their values in shell history or terminal output.
IFS= read -r -s -p 'Telegram API ID: ' TELEGRAM_API_ID; printf '\n'
IFS= read -r -s -p 'Telegram API hash: ' TELEGRAM_API_HASH; printf '\n'
export TELEGRAM_API_ID TELEGRAM_API_HASH
bash tools/build-mnn-debug.sh
unset TELEGRAM_API_ID TELEGRAM_API_HASH
```

如果网络要求代理，按执行环境设置 Java/Gradle 的 HTTP、HTTPS 代理，并保留可信 CA 校验。本工作区代理为 `proxy:8080`。构建脚本使用 `tools/mnn-debug.init.gradle` 限制 ARM64，自动创建 `.local-build/debug.keystore`，仅覆盖 debug 签名。生成目录为 `TMessagesProj_App/build/outputs/apk/afat/debug/`，包名为 `org.telegram.messenger.beta`；该目录及本地密钥/缓存不入 Git。

构建现在必须提供自有 `TELEGRAM_API_ID` 和 `TELEGRAM_API_HASH` 环境变量，不再回退到上游默认 API 配置。ID 必须是 1–2147483647 的十进制整数，不带前导零；hash 必须恰好为 32 位十六进制字符，两者都不能有空白。脚本在启动 Gradle 前校验，直接调用 Gradle 也会校验；Android Studio 需要从已设置这两个环境变量的环境启动，已有进程需重启。校验只能检查格式，不能证明凭据在 Telegram 服务端有效。

此构建仍为调试版；正式发行还需按仓库 README 配置签名、应用身份和 Firebase 等参数。ADB 当前未检测到连接设备；真机验收仍需覆盖登录、群／频道／全部话题、受保护文字、自动删除计时、跨午夜、首尾消息 ID、带链接文字、取消、错误 Key、MNN 未加载模型、截图／录屏，以及引用跳转到尚未加载的消息。

## GitHub Actions 构建与下载

工作流位于 [`.github/workflows/mnn-debug-apk.yml`](../.github/workflows/mnn-debug-apk.yml)。推送到 `feature/mnn-group-summary` 后自动构建 ARM64 调试 APK；工作流也声明了 `workflow_dispatch`，但 GitHub 的手动运行入口需要该工作流存在于默认分支。若 Fork 的 Actions 尚未启用，先在仓库 **Actions** 页面启用，再推送新的提交或运行工作流。

构建前打开 [Repository secrets 设置](https://github.com/Naza3/Telegram/settings/secrets/actions)，选择 **New repository secret**，添加下面两项（使用 Secrets，不是 Variables；不要写入源文件、工作流 YAML 或提交记录）：

| Secret 名称 | 值 |
| --- | --- |
| `TELEGRAM_API_ID` | 你在 [my.telegram.org](https://my.telegram.org) 为此客户端申请的 `api_id` |
| `TELEGRAM_API_HASH` | 同一应用的 `api_hash` |

两个值只传入校验和构建步骤的环境变量。配置缺失或格式错误会在安装 Android 工具链前终止，不会生成继续沿用上游 API ID 的 APK。新增或修改 Secret 不会自动触发构建；配置后可在**使用新工作流的运行记录**中选择 **Re-run all jobs**，或推送新提交。重跑旧版本工作流不会自动使用这项接入。

校验错误和构建摘要不显示凭据值；生成的 `BuildConfig` 位于忽略的构建目录，不修改源文件。构建脚本关闭 Gradle build/configuration cache，Actions 的 Gradle 缓存仅包含依赖和 wrapper，不缓存生成配置。Secrets 用于避免凭据进入 Git 和普通日志；Telegram 客户端必须携带 API 配置，因此它仍会包含在 APK 中。不要把手机验证码、二步验证密码或 MNN 的 API Key 填入这两项。

自有 API 配置不会强制“发送验证码到已登录设备”。客户端已支持 Telegram 设备内验证码；短信、设备内消息等投递方式由 [Telegram 服务端选择](https://core.telegram.org/api/auth)。构建成功后仍需在手机上验证实际登录结果。

安装使用新 API 配置的 APK 后，若登录页仍保留旧版的短信验证码步骤，先返回手机号输入页重新发起登录，让新请求使用新的 API 配置；更新 APK 不会改变此前已经发出的验证码的投递方式。

1. 打开仓库的 [Actions 页面](https://github.com/Naza3/Telegram/actions)，进入对应提交的构建记录。
2. 等构建成功，在记录底部的 **Artifacts** 下载 APK 压缩包；GitHub 通常要求登录后下载。
3. 解压得到 `.apk` 和 `.sha256`，将 APK 传到 ARM64 安卓手机安装。产物保留时间以工作流的 `retention-days` 为准，过期后可重新构建。

CI 使用 Ubuntu 24.04、JDK 21 和与本地相同的 SDK/NDK/CMake 版本，递归检出子模块，执行完整 Gradle 构建、签名与 16 KB 对齐校验。APK 上传前计算 SHA-256。Actions 构建不连接 Telegram 账号或 MNN 服务，仍需真机验收。

CI 独立生成调试签名，并通过 Actions 缓存在后续构建间复用；不上传 keystore 到产物。缓存失效或被清理后会生成新的调试签名。CI 签名与之前的本地 APK 不同；如果手机安装提示签名冲突，应先核对已安装包与新包的证书并找回原签名，不通过卸载来绕过冲突。正式分发应另行配置持久的私有签名密钥。

## 最新构建：各请求流式草稿与模型输入查看（2026-10-04）

源码提交：[`22214a5321a9ab0663a2c68aec159ab64e745123`](https://github.com/Naza3/Telegram/commit/22214a5321a9ab0663a2c68aec159ab64e745123)。直接总结的来源分段及每轮合并均可显示当前请求的流式正文草稿；普通及流式模式可通过“查看模型输入（N次请求）”查看实际 system／user 输入。本版保留此前聊天化导出等功能。

- **[下载新版 APK 与 SHA-256 校验文件](https://github.com/Naza3/Telegram/actions/runs/37172659455/artifacts/11292556741)**：`Telegram-MNN-arm64-debug-12`，ZIP 为 62,537,189 字节。登录 GitHub 下载 ZIP，解压安装 `Telegram-MNN-arm64-debug.apk`；产物到期时间为 2026-10-18 03:17:07 UTC。
- [GitHub Actions 运行记录](https://github.com/Naza3/Telegram/actions/runs/37172659455)：**成功**，运行耗时 19 分 09 秒（02:58:23–03:17:32 UTC），构建任务耗时 19 分 06 秒，完整 Gradle 构建耗时 16 分 08 秒。本地和云端 15 类 JVM 回归均通过：HTTP 62 项、其余核心 1308 项断言（含导出 168 项），7 项构建配置及 24 组样例检查通过。最终本地 Android Java 编译通过（59.7 秒），2834 份已跟踪 Java／XML 文件的编译前后哈希一致。
- APK 签名证书 SHA-256 为 `c8715ba0c50510b9c1cc4d52b0fd2b0aed82dd55675f89c4f48d68978b7df313`，与上一版 Telegram CI 完全一致；签名缓存命中并恢复 `mnn-debug-keystore-1402824680-v1`。v1／v2 签名、16 KB 对齐和 ARM64 检查通过。
- GitHub 记录的 ZIP 摘要为 `sha256:35a76b7099d426278d6fcd50ef83904f59b4b2b5b81a6479bb8a7cfb7525f8ef`，不是裸 APK 的 SHA-256；APK 请用 ZIP 内配套 `.sha256` 文件核对。
- 真机流式显示、输入查看及清理仍按 [S03–S04、D03–D05](mnn-device-validation.zh-CN.md) 验证，构建通过不代表实际模型耗时或手机交互已验收。

完整 CI 日志核验记录位于 `/workspace/build-logs/mnn-all-stage-stream/ci-review/`。

## 上一版构建：简洁群聊对话导出（2026-10-04）

源码提交：`cc7f912afec92f453754fe7cd9ec710dac3a3df0`。推荐导出格式改为“群聊对话（Markdown，推荐）”，按日期、时间和姓名展示“谁说／谁回复谁”，保留完整正文及最多 80 个 Unicode 字符的回复预览；JSON 继续提供完整数据。旧文件需重新导出才能使用新排版。

- **[下载新版 APK 与 SHA-256 校验文件](https://github.com/Naza3/Telegram/actions/runs/37167137561/artifacts/11290681738)**：`Telegram-MNN-arm64-debug-11`，ZIP 为 62,533,605 字节。登录 GitHub 下载 ZIP，解压安装 `Telegram-MNN-arm64-debug.apk`；产物到期时间为 2026-10-18 01:27:17 UTC。
- [GitHub Actions 运行记录](https://github.com/Naza3/Telegram/actions/runs/37167137561)：**成功**，任务耗时 18 分 21 秒；完整 Gradle 构建耗时 16 分 2 秒。15 类 JVM 回归（HTTP 59 项，其余核心 1308 项断言，含导出 168 项）、24 组样例、7 项构建配置、签名、16 KB 对齐及 ARM64 检查通过。
- APK 签名证书 SHA-256 为 `c8715ba0c50510b9c1cc4d52b0fd2b0aed82dd55675f89c4f48d68978b7df313`，与上一版 Telegram CI `37163785988` 一致。签名缓存命中 `mnn-debug-keystore-1402824680-v1`，v1／v2 验签通过。
- GitHub 记录的 ZIP 摘要为 `sha256:206239bca7c374a9f945b038dfe7de18fcc5c8c21f8df5e8b84bf0ac296afde3`；它不是裸 APK 的 SHA-256。APK 请用 ZIP 内的配套 `.sha256` 文件核对。
- 手机端排版、保存及分享仍按 [X01–X16](mnn-device-validation.zh-CN.md) 验证，构建成功不代替实际设备验收。

## 此前构建：原始群聊文字导出（2026-10-04）

源码提交：`c78b111242985bddf2551e2f1fd9ec42588837e0`。包含最初的 Markdown／JSON 原文导出、回复关系与 Topic／引用元数据、系统保存及分享，不包含本轮“姓名说／回复姓名”的聊天化 Markdown。

- **[下载上一版 APK 与 SHA-256 校验文件](https://github.com/Naza3/Telegram/actions/runs/37163785988/artifacts/11288507718)**：`Telegram-MNN-arm64-debug-10`，ZIP 为 62,531,165 字节。需登录 GitHub 下载并解压，安装 `Telegram-MNN-arm64-debug.apk`；到期时间为 2026-10-18 00:17:57 UTC。
- [GitHub Actions 运行记录](https://github.com/Naza3/Telegram/actions/runs/37163785988)：**成功**，任务耗时 13 分 44 秒。15 类 JVM 回归、24 组样例结构、7 项构建配置、完整 ARM64 构建、签名、16 KB 对齐、架构检查和上传全部通过。
- 签名验证通过（v1／v2），证书 SHA-256 为 `c8715ba0c50510b9c1cc4d52b0fd2b0aed82dd55675f89c4f48d68978b7df313`，与上一轮 Telegram CI `37130095722` 一致；缓存命中 `mnn-debug-keystore-1402824680-v1`，没有生成新签名。此处是 Telegram 的 CI 签名，与 MNN 应用签名分别管理。
- GitHub 记录的 ZIP 产物摘要为 `sha256:21b15754cffbbb4a5ca718a3b86989f21ce9e1d53269f60d8bafd5a66e37f5ec`；它不是 APK 本身的哈希。APK 请使用压缩包内配套 `.sha256` 核对。
- 本次导出不要求 MNN 启动；手机上的保存、分享及真实群聊回复元数据仍按 [X01–X14](mnn-device-validation.zh-CN.md) 验证，不能由构建成功代替。

## 此前构建：持久总结历史与请求进度（2026-10-03）

源码提交：`4655df1a8f582f76a3041877698fc40ff0f71e3b`。包含本机加密历史列表／详情、自动保存、来源指纹核验、精简 Prompt 与每次模型请求的进度，保留此前范围、入口、权限与截图修改。

- **[下载新版 APK 与 SHA-256 校验文件](https://github.com/Naza3/Telegram/actions/runs/37130095722/artifacts/11276861783)**：`Telegram-MNN-arm64-debug-9`，ZIP 为 62,511,865 字节。需登录 GitHub 下载并解压，安装 `Telegram-MNN-arm64-debug.apk`；到期时间为 2026-10-17 14:49:03 UTC。
- [GitHub Actions 运行记录](https://github.com/Naza3/Telegram/actions/runs/37130095722)：**成功**，任务耗时 14 分 30 秒。配置和回归检查、完整 ARM64 构建、签名、16 KB 对齐与架构检查、上传全部通过，复用已有 CI 调试签名密钥。
- GitHub 记录的 ZIP 产物摘要为 `sha256:1cdf1c243a9257841994e3b22e86c5f04d01a562cd0eb7ef800cec3742d54fe2`；它不是 APK 本身的哈希。APK 请使用压缩包内配套 `.sha256` 核对。
- 真机上的历史持久性、UI、原消息跳转及 Qwen3.5-2B 耗时仍需验证。MNN 独立 API 思考改动已[另行推送](https://github.com/Naza3/MNN/commit/35affe5d9d94e7e843bac4daecee1d2ddf60123f)，其 [CI 37133978173](https://github.com/Naza3/MNN/actions/runs/37133978173) 已通过；本 Telegram APK 不包含 MNN 端修改。

## 此前构建：消息范围、群聊入口与截图修复（2026-10-03）

源码提交：`68737de922c3edb9e8023f3815fd44ff7d0c7e92`，包含连接测试输出上限修复以及范围、入口、截图改动，不包含独立持久总结历史。构建继续使用 Repository secrets 中的自有 Telegram API 配置。

- **[下载此前 APK 与 SHA-256 校验文件](https://github.com/Naza3/Telegram/actions/runs/37125822349/artifacts/11275577541)**：`Telegram-MNN-arm64-debug-8`，ZIP 为 62,487,476 字节。需登录 GitHub 后下载并解压，安装其中的 `Telegram-MNN-arm64-debug.apk`；产物到期时间为 2026-10-17 13:36:45 UTC。
- [GitHub Actions 运行记录](https://github.com/Naza3/Telegram/actions/runs/37125822349)：**成功**，任务耗时 16 分 50 秒。回归、样例与构建配置检查、完整 ARM64 构建、签名／16 KB 对齐／架构检查和上传全部通过；复用已有 CI 调试签名密钥。
- 本地 12 个 JVM 测试类、24 组样例结构检查、7 项构建配置测试与 Android Java 编译通过。范围回归覆盖最近 N 条、当日、增量、未读、固定范围、普通自动删除文字及账号时间差异，直接核对来源消息 ID。
- 真机上的 Telegram 服务端分页、菜单、截图／录屏及 MNN 实际生成仍待复测；按 [设备验收表](mnn-device-validation.zh-CN.md) 记录结果。安装新版后可先取最近 20 条，对照范围说明中的实际首尾时间和消息 ID。

## 中间构建：连接测试输出上限修复（2026-10-03）

提交 `a47966c` 的 [GitHub Actions 运行 37123873201](https://github.com/Naza3/Telegram/actions/runs/37123873201) 已成功，[APK 与校验文件产物 11274700253](https://github.com/Naza3/Telegram/actions/runs/37123873201/artifacts/11274700253)可作为该次连接测试修复的中间版本下载。它不包含后续消息覆盖、入口与截图改动；这些改动见上方 `68737de` 构建。两者均不包含本轮持久总结历史。

## 此前构建：MNN 接口与启动权限修复（2026-10-03）

源码提交：`3e7d53ed62df926c6af59161189f00eb3d4f8793`，包含 MNN 请求参数与诊断修复 `eaacc782fbc05d370855f5ebede13977bb4a3e30`，以及上述启动权限提示／联系人感叹号修改。使用 Repository secrets 中的自有 Telegram API 配置。

- **[下载此前 APK 与 SHA-256 校验文件](https://github.com/Naza3/Telegram/actions/runs/37122119618/artifacts/11274156614)**：`Telegram-MNN-arm64-debug-6`，ZIP 为 62,484,812 字节。需登录 GitHub 后下载、解压；产物到期时间为 2026-10-17 UTC。
- [GitHub Actions 运行记录](https://github.com/Naza3/Telegram/actions/runs/37122119618)：**成功**，任务耗时 11 分 27 秒。配置校验、回归和样例校验、完整 ARM64 构建、签名／16 KB 对齐／架构检查和上传全部通过；复用已有 CI 调试签名密钥。
- 该提交的 HTTP 回归包含 53 项用例，覆盖固定文本连接测试与普通／流式总结和追问，并验证 `temperature` 被拒的旧请求对照及诊断正文过滤。另以实际 MNN 提交的原始 Kotlin 解析函数完成 6 项前后对照；这只验证请求解析，不代表模型推理已实测。
- 权限修改经独立调用链复核；本地 Android Java 编译通过，耗时 58 秒。没有连接手机，启动无弹窗、真实 MNN 普通／流式推理及 UI 行为仍需按 [真机验收表](mnn-device-validation.zh-CN.md) 复测。

## 此前自有 Telegram API 构建记录（2026-10-03）

源码提交：`3fa7dfe50ac0fde435b700f6e6e6e376d1bcdf38`。已移除上游 API ID/hash 回退，改由 `TELEGRAM_API_ID`、`TELEGRAM_API_HASH` Repository secrets 提供编译配置；登录验证码的投递逻辑未修改。

- [GitHub Actions 运行记录](https://github.com/Naza3/Telegram/actions/runs/37117842508)：**成功**。Secrets 校验、7 项配置测试、AI 回归与样例校验、完整 ARM64 APK 构建、签名／16 KB 对齐／架构检查和上传全部通过。复用了上一轮 CI 调试签名密钥。
- [下载本次 APK 与 SHA-256 校验文件](https://github.com/Naza3/Telegram/actions/runs/37117842508/artifacts/11272192904)：产物 `Telegram-MNN-arm64-debug-4`，需登录 GitHub 后下载 ZIP 并解压；保留 14 天。
- 本地用合成配置验证了有效值生成、无 clean 的凭据轮换、旧生成文件存在时缺失配置仍失败，以及非法 ID/hash 拦截和日志不回显。Android Java 编译通过，耗时 36 秒；没有分发使用合成配置的 APK。
- 尚未在手机上验证真实登录或验证码投递渠道；Secrets 格式校验及构建成功不等于 Telegram 服务端凭据验收。

## S0–S7 集成构建记录（此前使用上游测试配置）

源码提交：`c40e71f6fb5685223824690fb3b9b9ae12eb1b96`。本次全套 JVM 回归包含 12 个测试类（47 项 HTTP／SSE／追问用例，其他核心合计 672 项断言）；24 组合成样例结构校验通过，模型调用次数为 0。

本地 `assembleAfatDebug --offline` 使用已有构建缓存，成功耗时 **2 分 47 秒**，301 个任务中执行 10 个；该耗时不用于和首轮完整构建或手机推理耗时比较。真实 Android 工程的 Java、DEX、APK 打包通过，未跳过项目原生库。

| 项目 | 本地集成产物 |
| --- | --- |
| 文件 | `/workspace/artifacts/Telegram-MNN-S0-S7-arm64-debug.apk` |
| 大小 | 68,250,128 字节 |
| 源码 | `c40e71f6fb5685223824690fb3b9b9ae12eb1b96` |
| SHA-256 | `98314909bd0c7971920fe523f40b1fdd6d9646fff70fc4266fc67ec94735add7` |
| 原生架构 | 仅 `arm64-v8a` |
| 校验 | `apksigner verify`、16 KB `zipalign` 通过；DEX 含总结、筛选、来源核验、游标、缓存和问答类 |
| 签名 | 本地调试密钥，与 GitHub CI 密钥不同 |
| 真机 | ADB 无设备，未安装／未联调实际 MNN |

最终 GitHub 构建：[运行记录](https://github.com/Naza3/Telegram/actions/runs/37114223765) **成功**，任务耗时 **18 分 23 秒**；回归测试、签名、16 KB 对齐、ARM64 架构检查和产物上传全部通过。

**[下载完整 S0–S7 APK 压缩包](https://github.com/Naza3/Telegram/actions/runs/37114223765/artifacts/11271248907)**（`Telegram-MNN-arm64-debug-3`，62,484,960 字节，约 62.5 MB；保留 14 天）。需登录 GitHub，解压后得到 `Telegram-MNN-arm64-debug.apk` 和 `.sha256`。下载时以该次 Actions 的 APK 及配套 `.sha256` 为准，不用本地 APK 的校验值核对 CI APK。

## 初始版本的本地构建记录（历史）

以下校验值对应最初版本，不代表后续阶段的新 APK；新版本请以对应 GitHub Actions 产物及其中的 `.sha256` 为准。

2026-10-03 执行 `:TMessagesProj_App:assembleAfatDebug`，完整构建成功，首次耗时 9 分 51 秒。包括真实 Android 工程中的 Java、资源、原生 C++、DEX 与 APK 打包，未跳过原生库。

| 项目 | 结果 |
| --- | --- |
| APK | `/workspace/artifacts/Telegram-MNN-arm64-debug.apk` |
| 文件大小 | 62,407,885 字节（约 62.4 MB） |
| 包名 / 标签 | `org.telegram.messenger.beta` / `Telegram Beta` |
| 版本 | `12.10.6`，versionCode `71129` |
| Android 版本 | minSdk 21、targetSdk 36 |
| 原生架构 | 仅 `arm64-v8a`，包含 `libtmessages.49.so` |
| 签名验证 | `apksigner verify` 通过，使用本地调试密钥，v1/v2 签名有效 |
| 对齐验证 | `zipalign -c -P 16 -v 4` 通过 |
| 功能打包 | `apkanalyzer dex packages --defined-only` 确认 7 个核心功能类在 APK 内定义 |
| SHA-256 | `1382ac539b51272ab05a493aa773385c5493204ca80b1e4f695573fc8e41b158` |

构建日志与验证记录位于 `/workspace/build-logs/`，校验文件位于 APK 旁的 `.sha256` 文件。APK 尚未安装到真机，也尚未对接真实 Telegram 账号和手机上的 MNN 服务。

## MNN 接口依据

用户提供的操作和配置已用于默认值；另核验 MNN 提交 `024a946b0b8fcf87c8a418229fadd4cd7858ffba`：

- [ApiServerConfig.kt](https://github.com/alibaba/MNN/blob/024a946b0b8fcf87c8a418229fadd4cd7858ffba/apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/api/openai/service/ApiServerConfig.kt)：loopback、8080、默认 Bearer 鉴权。
- [MNNChatService.kt](https://github.com/alibaba/MNN/blob/024a946b0b8fcf87c8a418229fadd4cd7858ffba/apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/api/openai/network/services/MNNChatService.kt)：当前已加载模型及普通回复路径。
- [ResponseHandler.kt](https://github.com/alibaba/MNN/blob/024a946b0b8fcf87c8a418229fadd4cd7858ffba/apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/api/openai/network/handlers/ResponseHandler.kt)：部分失败以 HTTP 200 的 `Error: ...` 内容返回，客户端已将其视为失败。

前期源码及平台条款分析见 [可行性报告](ai-group-summary-feasibility.zh-CN.md)，其中“尚未实现”描述的是分析阶段状态；本文件描述本地实现状态。
