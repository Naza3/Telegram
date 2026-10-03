# 在 Telegram 群聊中使用 MNN Chat 本机总结

开发分支：[`feature/mnn-group-summary`](https://github.com/Naza3/Telegram/tree/feature/mnn-group-summary)。基于 Telegram Android `12.10.6 (7112)`，上游提交 `f2908b14133bbffbf7ab04f641ecb5bfaf533242`。远程仓库为用户创建的 [Naza3/Telegram](https://github.com/Naza3/Telegram)，本地 `origin` 指向该 Fork，`upstream` 保留官方仓库。

功能的阶段状态、验收证据和待测项目见 [实施计划](mnn-group-summary-roadmap.zh-CN.md)。已完成代码验证的功能仍需 [真机验收](mnn-device-validation.zh-CN.md)，不能以模拟测试代替实际 MNN 模型效果。

## 使用方式

1. 在同一手机的 MNN Chat 中打开已下载的文本模型，进入聊天页。
2. 打开菜单 → API 设置 → 启动本机 API，等状态变为“可用”，复制 API 密钥。
3. 打开此修改版 Telegram 的普通群、超级群或具体 Topic，在右上角菜单选择 **AI 总结群聊**。
4. 进入 **MNN API 设置**：

   | 设置 | 默认/填写方式 |
   | --- | --- |
   | 服务地址 | `http://127.0.0.1:8080/v1`；也接受完整的 `/v1/chat/completions` 地址 |
   | 模型名称 | `mnn-local` |
   | API Key | 粘贴 MNN Chat 中复制的密钥；仅在 MNN 关闭鉴权时留空 |
   | 最大输出 token | `512`，可设置为 64–8192；服务端支持该参数时生效 |
   | 流式输出 | 可选；不支持 SSE 时可手动切回普通模式 |
   | 上下文字符预算 | 默认 `6000`，可设置为 2048–32000；包含规则、方向、消息及输出预留，不是精确 token 数 |

5. 可先用 **测试连接** 检查服务、鉴权与回复格式；该测试不读取聊天消息。
6. 选择消息范围、总结模板和补充要求，再开始总结。默认仍为最近 N 条文字消息和通用模板。
7. 查看 **话题、结论、待办**。点击 `[m1]` 等引用会先重新核验和预览原文，再选择跳回群聊。

请保持 MNN Chat 的本机 API 可用。更改过端口时以 MNN 设置页为准。支持普通和 SSE 流式 Chat Completions；多分块时先显示分段、合并进度，仅最终回答显示正文流。未完成正文不开放引用，断流后可明确选择普通模式重试。

MNN 使用当前已加载的模型，填写模型名不会替 MNN 切换模型。核验的 MNN 版本会忽略部分生成参数，因此 `max_tokens=512` 不是客户端对实际生成长度的硬保证。API Key 在 Android 6.0+ 使用 AndroidKeyStore 加密保存；旧版本或加密保存失败时仅本次运行有效，界面会提示。

## 总结方向与追问

提供通用总结、项目进展、决策与争议、待办跟进四种模板，以及最多 1000 个 Unicode 字符的补充要求。例如：

> 重点整理发布进度、阻塞和已确认决定。待办列出明确负责人和截止时间，没有提到的写“未指定”；保留反对意见及原消息引用。

方向可仅本次使用，或保存为该群／Topic 的偏好、账号默认值。优先级为本次覆盖、群／Topic、账号、内置默认。每个分块和每轮合并使用同一份配置快照。改方向只影响下一次任务，不重置增量进度；可以明确选择重做上次范围。

“突出与我相关”只改变关注点；“实际筛选”先在客户端按真实发送者、明确提及或已确认回复关系筛选，并显示额外保留的上下文数量。可组合成员 ID 和关键词（不区分大小写的字面子串）条件。没有明确提及不代表讨论与本人无关；筛选后的任务不推进通用增量进度。

成功结果可继续追问，最多五轮，问题最多 500 个 Unicode 字符。每一轮都从本次消息快照取证，不自动查询更多群历史。问题和旧回答不作为群聊事实；上下文不足或超预算时明确提示。问答不改变总结进度，也不会向群内发送内容。更换范围、方向或来源失效时开启新会话。

## 消息范围

- “最近 N 条”统计已发送、可读取、未受保护的文字消息，不是屏幕上已有的 N 行。允许带链接预览的文字，但不读取网页正文。
- “当日”使用点击开始时手机时区的零点及截止时间，不使用服务器 UTC 零点。Telegram 消息时间精度为秒。
- “上次总结之后”首次明确用最近 N 条初始化，之后从保存位置向新消息连续分批。每批上限按原始历史消息计算（包含被跳过的非文字位置），完整成功才推进；可继续补齐大量积压。普通最近 N 条、当日、未读、重做与实际筛选不移动已有增量位置。
- “进入聊天时的未读”固定进入时的边界，新到达消息不混入。论坛群需要进入具体 Topic；无法确认边界时禁用，不以最近 N 条替代。
- 完成记录只持久保存范围、进度和结果摘要哈希，不保存聊天原文或摘要正文。重启后可从原进度继续，并按上次记录范围重新生成；重新读取时原文可能已编辑或删除。
- 在具体 Topic 中只读取该 Topic；在群的全部消息视图中覆盖该群的所有 Topic。
- 跳过媒体 caption、图片/语音/文件内容、服务消息、临时或未发送消息、受保护和限时内容；不处理私聊、秘密聊天、广播频道及频道私信。
- 当日最多纳入 2000 条有效文字，最多扫描 100 页/10000 条历史。达到限制或游标无法继续时显示“部分结果”和实际覆盖范围，不宣称已总结全部。
- 当前版不拼接超级群迁移前的旧群历史，结果范围说明会标明。后续页加载失败时直接报错，不把失败伪装为完整结果。
- 历史请求不修改已读状态。消息内容只发送到设置中的模型接口；结果只在摘要窗口显示，不自动发回群。

长内容按有界字符预算分块，再合并摘要，引用始终使用原始消息编号。超出总输入预算或无法完整合并会明确失败，不静默截断。模型引用只允许映射到本次消息集合；无有效引用或越界引用会报错，避免错误跳转。引用存在并不保证模型的结论正确，仍应以原文为准。

## 预算、缓存和来源有效性

字符预算是保守估计，不能替代模型 tokenizer；预算不足会在请求前提示，原文过长会分块并保留稳定引用。回复链尽量放在相邻分块中；同一个模型端点只允许一个活动任务。客户端取消停止等待及后续请求，但是否终止服务端推理仍取决于 MNN。

成功摘要可在当前面板明确选择“查看已有结果”，同时显示生成时间；正常生成和重新生成不会自动复用缓存。缓存只驻留内存，最多八条／2 MiB；`mnn-local` 可对应后来切换的实际模型，因此不能把缓存当作最新推理结果。已观察到的消息编辑、删除、权限变化和退出账号会使相关结果失效。

引用预览与跳转前会重新读取原消息。删除、编辑、受保护、账号或范围变化时不展示旧正文，需重新选择范围生成。校验引用存在不能证明模型结论有事实支持，仍需结合原文核对。

## 代码位置

| 文件 | 职责 |
| --- | --- |
| `ui/ChatActivity.java` | 群聊入口、原消息定位、Fragment 销毁时取消 |
| `ui/Components/GroupSummarySheet.java` | 范围、设置、加载、错误和结果交互 |
| `messenger/ai/SummaryHistoryLoader.java` | 独立 GUID 的 MTProto 历史分页、范围/账号/Topic 检查 |
| `messenger/ai/SummaryMessage.java` | 原消息位置与正文 |
| `messenger/ai/AiSummaryPrompt.java` | JSONL 输入、分块、合并与引用校验 |
| `messenger/ai/AiSummaryClient.java`、`AiSummarySse.java` | 普通／流式请求、诊断、端点并发限制、取消及问答 |
| `messenger/ai/PromptOptions.java`、`PromptPreferences.java` | 不可变方向配置、按身份和范围保存偏好 |
| `messenger/ai/SummaryStateStore.java` | 完整范围记录和增量进度的原子提交 |
| `messenger/ai/SummarySourceVerifier.java` | 预览／跳转前重新核验消息及权限 |
| `messenger/ai/SummaryFilter.java`、`SummaryResultCache.java` | 明确筛选、有界且显式使用的内存缓存 |
| `messenger/ai/SummaryQuestionPrompt.java`、`ui/Components/SummaryQuestionSheet.java` | 本次原文问答的预算、事实边界和界面 |
| `messenger/ai/AiSummarySettings.java` | 按账号保存接口配置 |
| `messenger/ai/AiSummarySecretStore.java` | API Key 加密持久化及失败时的内存回退 |

Java 路径相对于 `TMessagesProj/src/main/java/org/telegram/`。未添加外部 App 运行时依赖，也未改变 MTProto 协议实现。

## 开发验证

核心逻辑有独立 JVM 测试，使用模拟 Telegram/Android 适配层和本机 HTTP 测试服务，不访问真实聊天或 MNN 服务：

```bash
ECJ_JAR=/path/to/ecj.jar JSON_JAR=/path/to/json.jar bash tests/ai-summary/run.sh
```

本工作区依赖位于 `/workspace/ai-summary-tools/`。具体测试数量及阶段证据见实施计划；测试涵盖 HTTP／SSE、分页与增量、Prompt、偏好、游标、来源核验、预算、缓存、筛选和问答。测试编译并运行实际历史加载、提示词和 HTTP 客户端代码；UI 和密钥存储另以 Android 36 的 `android.jar` 核对编译。模拟适配层不能替代完整 Telegram 构建、真机 UI、真实 MTProto 和 AndroidKeyStore 验证。

本工作区已补全检出和 15 个子模块，并安装 JDK 21、Android SDK 36 / Build Tools 36.0.0、NDK 27.2.12479018、CMake 3.22.1 和 platform-tools。其他新克隆在构建前需要补全子模块：

```bash
git submodule update --init --recursive --depth=1
```

本地 ARM64 调试构建：

```bash
export JAVA_HOME=/workspace/toolchains/jdk-21
export ANDROID_HOME=/workspace/android-sdk
export GRADLE_USER_HOME=/workspace/gradle-cache
bash tools/build-mnn-debug.sh
```

如果网络要求代理，按执行环境设置 Java/Gradle 的 HTTP、HTTPS 代理，并保留可信 CA 校验。本工作区代理为 `proxy:8080`。构建脚本使用 `tools/mnn-debug.init.gradle` 限制 ARM64，自动创建 `.local-build/debug.keystore`，仅覆盖 debug 签名。生成目录为 `TMessagesProj_App/build/outputs/apk/afat/debug/`，包名为 `org.telegram.messenger.beta`；该目录及本地密钥/缓存不入 Git。

当前构建沿用仓库自带测试配置，不作为正式发行配置。正式使用按仓库 README 配置自有 `api_id`、签名和 Firebase 等参数。ADB 当前未检测到连接设备；真机验收仍需覆盖普通群、Topic、跨午夜、带链接文字、取消、错误 Key、MNN 未加载模型，以及引用跳转到尚未加载的消息。

## GitHub Actions 构建与下载

工作流位于 [`.github/workflows/mnn-debug-apk.yml`](../.github/workflows/mnn-debug-apk.yml)。推送到 `feature/mnn-group-summary` 后自动构建 ARM64 调试 APK；工作流也声明了 `workflow_dispatch`，但 GitHub 的手动运行入口需要该工作流存在于默认分支。若 Fork 的 Actions 尚未启用，先在仓库 **Actions** 页面启用，再推送新的提交或运行工作流。

1. 打开仓库的 [Actions 页面](https://github.com/Naza3/Telegram/actions)，进入对应提交的构建记录。
2. 等构建成功，在记录底部的 **Artifacts** 下载 APK 压缩包；GitHub 通常要求登录后下载。
3. 解压得到 `.apk` 和 `.sha256`，将 APK 传到 ARM64 安卓手机安装。产物保留时间以工作流的 `retention-days` 为准，过期后可重新构建。

CI 使用 Ubuntu 24.04、JDK 21 和与本地相同的 SDK/NDK/CMake 版本，递归检出子模块，执行完整 Gradle 构建、签名与 16 KB 对齐校验。APK 上传前计算 SHA-256。Actions 构建不连接 Telegram 账号或 MNN 服务，仍需真机验收。

CI 独立生成调试签名，并通过 Actions 缓存在后续构建间复用；不上传 keystore 到产物。缓存失效或被清理后会生成新的调试签名。CI 签名与之前的本地 APK 不同；如果手机安装提示签名冲突，需要先卸载旧的 Telegram Beta（卸载会清除该应用的本地数据）。正式分发应另行配置持久的私有签名密钥。

## S0–S7 集成构建记录

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
