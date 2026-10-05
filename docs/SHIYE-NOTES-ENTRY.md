# 拾页笔记首页与指纹入口

版本：12.10.6-mnn.7116。

首次打开、进程重启或锁定到期后，进入本地笔记。右上角加号可新增，点笔记可编辑，编辑页提供完成和删除。笔记自动保存，也会在离开页面时提交最新草稿；写入失败会保留当前编辑内容并提示重试。笔记仅存本机，不同步到 Telegram，不参加 Android 自动备份。卸载或清除应用数据会删除笔记。

默认长按顶部“拾页”标题 2 秒，随后使用指纹进入原应用页面。解锁后在设置的“拾页入口”可改成连续点按标题 5 次。首页和编辑页都可使用该手势；首页不显示聊天入口或解锁说明。

在「设置 → 拾页入口 → 后台重新验证」选择等待时间。默认 30 秒，提供立即（0 秒）、30 秒、60 秒、5 分钟及自定义 0–86400 秒；输入自定义秒数后点保存。在等待时间内，从最近任务、桌面图标、通知或外部应用返回会直接恢复有效会话；到期后需从笔记页重新验证。息屏和应用进程重启仍立即锁定。相机、相册或文件选择返回的结果在允许恢复时继续处理。已有的 Telegram 密码锁独立保留，按其自身规则验证。

只支持指纹，不提供人脸、设备密码或 PIN 回退。Android 6.0 以下、无指纹硬件、未录入指纹或指纹暂时锁定时，仍可使用笔记，但不能进入聊天。未录入时可从主动触发的验证提示打开系统安全设置；返回后仍需重新验证指纹。

## 实现边界

- 六个已有桌面图标 alias 保留原组件名和启用状态，统一进入 `ShiyeNotesActivity`。
- 通知、分享、深链接、聊天气泡、弹出回复、Passport 和桌面组件配置等 Activity 入口受同一进程内授权控制。Intent 或恢复状态不能授予授权。
- 指纹采用独立 Android Keystore 每次使用认证密钥；认证成功后还需校验随机挑战、当前宿主、认证代次、前台及亮屏状态。旧回调、取消、错误和进程重建不能解锁。
- 切到后台立即遮盖 Activity（包括等待时间内），隐藏独立对话框和媒体浮窗，再取消验证。Android 13 以上禁止聊天 Activity 的最近任务截图；更早版本仅在遮盖期间给聊天窗口设置 `FLAG_SECURE`，认证后恢复正常截图。不同系统的任务快照时机仍需真机验证。
- 等待时间使用 `elapsedRealtime` 单调计时，同一次后台期间的重复暂停/停止不延长截止时间。后台会话不允许异步弹窗或媒体重新出现；恢复和超时回调都重新校验有效期，旧计时回调不能撤销新会话。认证资格不写入磁盘。
- AI 前台服务和总结任务继续运行。通知正文、系统快捷回复和现有桌面组件内容沿用原来的隐私设置；本功能不是通知脱敏、聊天数据库加密或系统级隐藏应用功能。
- 本地笔记使用 `noBackupFilesDir` 中的独立 `AtomicFile`，与 Telegram 账号无关；写入串行执行，坏文件不会被当作空笔记集覆盖。

## 验证

自动化覆盖后台到期边界、重复暂停、单调时间/溢出、设置持久化及保存失败、认证会话及取消竞态、指纹驱动错误与迟到回调、笔记 CRUD、写入顺序、Unicode、容量边界、坏文件和原子写入失败恢复：

```bash
bash tests/notes-gate/run.sh
bash tests/notes-gate/preferences/run.sh
bash tests/notes-store/run.sh
```

Release 和 Debug Actions 都执行这些检查。Android 编译验证资源和全部 Activity 接入，Release 构建继续校验原签名、包名、版本及 ARM64 对齐。

真实指纹传感器、屏下指纹提示、系统最近任务和多窗口行为需在手机上验证，不能由 JVM 测试代替。安装后应检查：冷启动、两种手势、取消/失败/成功、Home/最近任务/息屏、通知/链接/分享、附件选择返回、已有 Telegram 密码锁及后台总结。

## 7115 构建记录

- 源码：`2144124a29ee5d1910ae426c1c7c13b352cf5855`，分支 `feature/mnn-group-summary`。
- [GitHub Actions 37260922632](https://github.com/Naza3/Telegram/actions/runs/37260922632) 成功；Release 编译用时 17 分 14 秒。
- [下载签名安装包](https://github.com/Naza3/Telegram/actions/runs/37260922632/artifacts/11324693940)：`Shiye-12.10.6-mnn.7115-arm64-release.apk`，36,826,504 字节，APK versionCode `71159`。Actions 产物保留至 2026-10-19 04:10 UTC；需要登录 GitHub 下载并解压。
- APK SHA-256：`a6cca26da4bf1fd9d4bdf57fd95e36db2c57d7cb922cc0d0db75bb7707b28608`。
- 原签名证书 SHA-256：`c8715ba0c50510b9c1cc4d52b0fd2b0aed82dd55675f89c4f48d68978b7df313`；v1/v2 签名校验均通过。
- 保持 `org.telegram.messenger.beta` 包名和“拾页”名称，Release 非调试包、仅 ARM64、16 KiB 对齐检查通过。
- 认证专项 63 条断言、笔记存储 125 条断言、35 项构建配置测试，以及 AI 总结、控制器、后台服务、发布实体、群消息过滤和本地撤回记录回归检查均通过。
- 本次为手动触发的 Release 构建产物，未创建正式 tag 或发布 GitHub Release。

## 7116 构建记录

- 源码：`a659291a27ea44c789a30a5e2988a771197e6895`，分支 `feature/mnn-group-summary`。增加可配置的后台重新验证等待时间，默认 30 秒。
- [GitHub Actions 37264041823](https://github.com/Naza3/Telegram/actions/runs/37264041823) 成功；Release 编译用时 21 分 25 秒。
- [下载签名安装包](https://github.com/Naza3/Telegram/actions/runs/37264041823/artifacts/11325887730)：`Shiye-12.10.6-mnn.7116-arm64-release.apk`，36,831,971 字节，APK versionCode `71169`。Actions 产物保留至 2026-10-19 05:00 UTC；需要登录 GitHub 下载并解压。
- APK SHA-256：`1ebd6658881a38573d4e887f90ccc79ebc7447594646e78f753fc8026bcf0534`。
- 原签名证书 SHA-256：`c8715ba0c50510b9c1cc4d52b0fd2b0aed82dd55675f89c4f48d68978b7df313`；v1/v2 签名校验均通过，可覆盖安装原签名版本。
- 保持 `org.telegram.messenger.beta` 包名和“拾页”名称，Release 非调试包、仅 ARM64、16 KiB 对齐检查通过。
- 认证会话、超时和设置专项共 295 条断言、笔记存储 125 条断言、35 项构建配置测试，以及 AI 总结、控制器、后台服务、发布实体、群消息过滤和本地撤回记录回归检查均通过。真实指纹及 Android 前后台切换仍需手机验证。
- 本次为手动触发的 Release 构建产物，未创建正式 tag 或发布 GitHub Release。
