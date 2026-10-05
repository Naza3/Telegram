# Android Release 构建与发布

工作流：[mnn-release-apk.yml](../.github/workflows/mnn-release-apk.yml)。仅在 `Naza3/Telegram` 运行构建：

- 推送 `mnn-v*` 标签：校验、构建和验收通过后，新建 GitHub Release 并上传 APK、SHA-256 和公开构建信息。
- `workflow_dispatch`：使用输入的 `release_tag` 试构建，只上传 Actions artifact，不创建标签或 GitHub Release。
- 功能分支只修改此工作流时可产生注册运行；构建 job 明确排除分支 push。

本轮仅准备工作流和工具，不创建发布标签。应用显示名称和图标以所构建源码为准，工作流不覆盖品牌资源。

## 版本规则

标签格式为 `mnn-v<major.minor.patch>-<baseVersionCode>`。标签必须与该提交的 `gradle.properties` **完全一致**：

```properties
APP_VERSION_NAME=12.10.6
APP_VERSION_CODE=7113
```

对应标签 `mnn-v12.10.6-7113`，派生：

| 字段 | 示例 |
| --- | --- |
| `MNN_RELEASE_VERSION_NAME` | `12.10.6-mnn.7113` |
| `MNN_RELEASE_VERSION_CODE`（基础值） | `7113` |
| APK 实际 `versionCode`（Afat：基础值 × 10 + 9） | `71139` |

每次新发布应先在源码中递增 `APP_VERSION_CODE`，再提交、推送，最后给该提交打标签。Debug 与 Release 共用这个基础版本号，避免安装 Release 后，后续 Debug 因版本号较低而无法覆盖。`versionName` 的可读文字不决定 Android 升级顺序。

[check-release-tag.py](../tools/check-release-tag.py) 拒绝前导零、空白、附加后缀、非 ASCII 数字和命令片段。版本各分量为 `0` 或无前导零的最多 6 位数字，基础版本号为 `1`–`209999999`，确保 Afat 的实际版本号不超过 Android/Play 的 `2100000000` 上限。

工作流拉取所有标签，并拒绝低于已有 `mnn-v` 标签的版本号，以及不同标签复用同一基础版本号。同一标签可重试构建；如果它的 GitHub Release 已存在，发布步骤仍会失败，不覆盖 Release 或附件。保留给本工作流使用的 `mnn-v` 命名空间中存在格式错误的标签也会阻止校验，需先人工检查。不要移动已发布的标签。

## 签名与安装身份

沿用现有 6 个 repository Actions Secrets，不新增或替换签名身份：

- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

签名密钥由现有 [prepare-android-signing.py](../tools/prepare-android-signing.py) 写入 runner 临时目录，先验证证书和私钥密码，再交给构建。工作流结束时始终执行临时密钥清理；缺失或错误的配置会使构建失败，不生成替代密钥，不从缓存恢复其他身份。密钥、密码和 API 配置不会加入构建信息附件。

最终 APK 必须满足：

| 检查 | 必须值 |
| --- | --- |
| 包名 | `org.telegram.messenger.beta` |
| 证书 SHA-256 | `c8715ba0c50510b9c1cc4d52b0fd2b0aed82dd55675f89c4f48d68978b7df313` |
| 构建变体 | `AfatRelease` |
| `debuggable` / JNI 调试 | 关闭；Gradle 配置另检查 JNI 调试 |
| R8 / 资源压缩 | 开启；由 Gradle 配置校验 |
| 原生 ABI | 仅 `arm64-v8a` |
| APK 签名 | `apksigner verify` 成功，证书固定，v1/v2 均验证成功 |
| ZIP 对齐 | `zipalign -c -P 16 4` 成功 |
| APK 版本 | `aapt dump badging` 与已校验标签派生版本一致 |

包名和证书与此前此分支的同签名 Debug 安装包保持一致。实际覆盖安装还要求设备当前包确实使用上述证书、设备支持 ARM64，且版本号不更高；工作流不能验证手机当前安装包。正式版 Telegram 或使用其他证书的同包名 APK 不会因此获得签名兼容。

## 先试构建，再发布

先把代码和版本号提交并推送到目标分支。确认工作流已在仓库中注册后，在 Actions 中选择 **Build and publish MNN release APK**，选择要测试的分支，填写与源码一致的标签文字。示例仅用于手动试构建：

```sh
gh workflow run mnn-release-apk.yml --repo Naza3/Telegram \
  --ref feature/mnn-group-summary -f release_tag=mnn-v12.10.6-7113
```

手动输入标签文字不会创建该标签，也不会触发发布 job。若 GitHub 尚未提供手动运行入口，先检查工作流是否已被仓库识别及默认分支的 `workflow_dispatch` 注册要求；不要用创建正式标签替代试构建。

构建通过后，从该次运行的 Summary 下载 artifact。文件名示例为：

```text
Telegram-MNN-mnn-v12.10.6-7113-arm64-release.apk
Telegram-MNN-mnn-v12.10.6-7113-arm64-release.apk.sha256
release-metadata.json
```

校验和对应 **原始 APK**，不是 Actions 下载 ZIP 的哈希。`release-metadata.json` 包含源码 commit、版本、包名、公开证书摘要、APK 字节数和 SHA-256、已验证的签名方案与对齐信息。Actions artifact 保留 14 天；成功发布后的 GitHub Release assets 不使用这个 artifact 过期时间。

确认 APK 功能和升级安装正常、并决定公开发布后，再给已验证提交创建符合格式的标签并推送。标签推送会重新构建该提交；只有所有门禁成功，独立 publish job 才取得 `contents: write` 权限创建新 Release。它会核对远端标签仍指向构建 commit、下载附件的校验和一致，并检查该标签没有现存 Release。已有 Release、附件冲突或上传失败都不会自动覆盖；应检查实际状态后再决定处理方式。

例如，先确认本地 `HEAD` 正是要发布的已验证提交，且其源码版本仍为上面的 `12.10.6` / `7113`，然后执行以下命令即可触发首次发布（本轮未执行）：

```sh
git tag -a mnn-v12.10.6-7113 -m "MNN Android 12.10.6-mnn.7113"
git push origin mnn-v12.10.6-7113
```

后续发布递增源码版本号并使用新标签，不重复使用上述示例。

## 构建和验证范围

构建环境固定为 Ubuntu 24.04、JDK 21、Android SDK 36、Build Tools 36.0.0、NDK 27.2.12479018、CMake 3.22.1。源码检出包含提交固定的递归子模块。Gradle 下载缓存与原 Debug 构建共用缓存规则，签名密钥不在缓存内。Gradle 使用 6 GiB heap、1 GiB metaspace、2 workers，并关闭并行项目构建；实际 Release 的 R8 内存和耗时仍以 Actions 日志为准。

Release 工作流保留现有纯 JVM、controller、foreground-service、fixture、群消息及真实 TL 序列化回归。涉及真实编译 TL 类的测试明确读取 **Release** 库的 `classes.jar`，不依赖曾构建过 Debug。发布依赖实际 `AfatRelease` APK 的签名、manifest、ABI 和对齐检查，单独完成配置校验不代表 APK 构建成功。

可本地运行无真实密钥的标签回归：

```sh
python3 -B -m unittest discover -s tests/build-config -p test_release_tag.py -v
```

本轮已执行 6 个标签校验测试方法，覆盖正常版本、Afat 上界、无效/注入输入、源码不匹配、旧标签回退/复用和 Actions 输出安全。工作流已通过 `actionlint` 静态检查。实际 Release APK 构建、R8 运行和手机覆盖安装尚待试构建及设备验证，不能据此宣称已经通过。
