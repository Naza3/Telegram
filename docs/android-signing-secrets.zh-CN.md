# 将现有 Telegram 签名迁入 Actions Secrets

使用已经备份的 Telegram 密钥库，不是 MNN Chat 密钥，也不是 `recipient-private.pem`。证书必须继续匹配 `c8715ba0c50510b9c1cc4d52b0fd2b0aed82dd55675f89c4f48d68978b7df313`，才能覆盖现有 Actions APK。

## 网页配置

打开 [Naza3/Telegram → Settings → Secrets and variables → Actions](https://github.com/Naza3/Telegram/settings/secrets/actions)，选择 **New repository secret**，逐项添加以下四个 Repository Secrets（不是 Variables 或 Environment Secrets）：

| Name | Secret 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `telegram-current-signing.keystore` 的完整 Base64 文本 |
| `ANDROID_KEYSTORE_PASSWORD` | `android` |
| `ANDROID_KEY_ALIAS` | `androiddebugkey` |
| `ANDROID_KEY_PASSWORD` | `android` |

上述密码和别名来自当前测试签名的原有配置，不是新生成的密钥。已有的 `TELEGRAM_API_ID`、`TELEGRAM_API_HASH` 保持原样。

Base64 是编码，不是加密；文件内容等同于私钥材料。复制时不要加引号、Markdown 代码围栏或文件名。不要用在线转换网站，不要把文本提交到 Git 或发到公开聊天。

如果手边只有备份 ZIP，先解压，使用其中的 `telegram-current-signing.keystore`。在文件所在目录离线生成文本即可：

```bash
# Linux / macOS：兼容带换行的 Base64，内容只写入文件。
umask 077
base64 < telegram-current-signing.keystore > ANDROID_KEYSTORE_BASE64.txt
```

Windows PowerShell：

```powershell
[IO.File]::WriteAllText("$PWD\ANDROID_KEYSTORE_BASE64.txt", [Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\telegram-current-signing.keystore")))
```

打开生成的文本文件，复制全部内容到第一个 Secret。GitHub 保存后不能重新显示 Secret 内容，需要保留自己的完整私密备份。

本次提供的 `Telegram-ANDROID_KEYSTORE_BASE64.txt` 去掉换行后为 3536 个字符，对应 2650 字节的原密钥库。请从下载后的完整文本文件全选复制，避免仅复制聊天预览中的部分内容；这是本次备份的核对信息，不是其他有效密钥库的长度限制。

电脑也可使用已登录仓库所有者账号的 GitHub CLI，直接从本地文本文件写入，避免剪贴板传输：`gh secret set ANDROID_KEYSTORE_BASE64 --repo Naza3/Telegram < ANDROID_KEYSTORE_BASE64.txt`。这条重定向写法用于 cmd、Git Bash 或 macOS/Linux shell。配套私密录入 ZIP 包中还提供逐项写入四个 Secrets 的 Windows `set-secrets.cmd` 和 Bash `set-secrets.sh`；密码和别名文件没有尾随换行，不会把额外换行写入密码。脚本不触发构建，也不修改 Telegram API Secrets。

## 构建行为

当前 APK 工作流先运行合成配置测试，再恢复并验证 Secrets 中的密钥，通过后继续 AI 回归测试与 Android 构建。缺少任何一项、Base64 无效、密码或别名错误、没有私钥或证书不匹配都会停止；不会回退到 Cache，也不会生成新签名。

恢复工具先验证证书，再用临时 JAR 试签名，提前发现私钥密码错误。Gradle 从环境变量读取密码和别名，不把密码拼进命令行；生成 APK 后再次检查证书。临时密钥库位于 `.local-build/signing/current.keystore`，工作流结束时删除，上传产物只有 APK 与校验文件。原备份和旧缓存本轮不删除。

仅添加或修改 Secrets 不会触发构建。首次需要对包含本次迁移改动的提交启动构建；重跑旧提交仍会执行旧的缓存签名流程。正式切换成功以新工作流通过并核对 APK 证书为准。

手动触发 Debug 构建可使用已登录的 GitHub CLI：`gh workflow run mnn-debug-apk.yml --repo Naza3/Telegram --ref feature/mnn-group-summary`，运行结果在 [Build MNN debug APK](https://github.com/Naza3/Telegram/actions/workflows/mnn-debug-apk.yml) 页面查看。推送到该分支也会触发构建，带 `[skip ci]` 的提交除外。另已配置沿用相同 Secrets 的 `AfatRelease` 工作流，支持手动试构建与 `mnn-v*` 标签触发发布，详见 [Android Release 构建与发布](android-release.zh-CN.md)。

## 验证结果

本地的 22 项签名合成测试与 7 项 API 配置测试，共 29 项通过。原密钥经 Base64 恢复后与备份字节一致，证书校验及试签名通过；`:TMessagesProj_App:validateSigningAfatDebug --rerun-tasks` 通过，缺失密钥时构建脚本拒绝继续且不生成新密钥。

2026-10-05，用户重新填写 Secrets 后，[第 25 次构建](https://github.com/Naza3/Telegram/actions/runs/37246221440) 全部成功。29 项配置测试、从 Secrets 恢复原密钥及试签名、AI 总结与群消息回归测试、ARM64 APK 编译均通过。最终 `apksigner verify` 验证签名有效，证书 SHA-256 与本文开头的原证书完全一致，16 KiB 页面 ZIP 对齐检查通过，临时密钥清理成功。当前签名已成功迁入 Actions Secrets。

[第 25 次 APK 附件](https://github.com/Naza3/Telegram/actions/runs/37246221440/artifacts/11319402708) 仅包含 `Telegram-MNN-arm64-debug.apk` 与其 SHA-256 校验文件，需登录 GitHub 下载，2026-10-19 08:21:42（北京时间）到期。本次产物来自提交 `a746050306356351cb0abea531ce700ac96dfa48`，仍为 `AfatDebug`；后续签名说明文档更新不改变 APK 内容。

## 本地构建

在私有环境中设置同名四个环境变量，再执行：

```bash
export ANDROID_SIGNING_KEYSTORE_PATH="$PWD/.local-build/signing/current.keystore"
python3 tools/prepare-android-signing.py --output "$ANDROID_SIGNING_KEYSTORE_PATH"
bash tools/build-mnn-debug.sh
```

本地仍需自有 Telegram API 环境变量与 Android 工具链。原 `mnn-debug.init.gradle` 直接编译调用也需要三个签名配置环境变量；只做不产出可安装 APK 的编译时可使用明确的合成测试值，不要为此输出真实私钥。
