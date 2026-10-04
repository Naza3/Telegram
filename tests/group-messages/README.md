# 群消息管理回归

使用 JDK 17+；`AI_SUMMARY_TOOLS_DIR` 指向包含 `ecj.jar` 和 `json.jar` 的目录（可用 `tests/ai-summary/bootstrap-tools.sh` 准备）。`ANDROID_HOME` 指向 Android SDK。

```bash
bash tests/group-messages/run-filter.sh
bash tests/group-messages/run-settings.sh
bash tests/group-messages-store/run.sh
bash tests/group-messages/run-revoked-boundary.sh
```

原生 TL 资格测试在 Android 库构建后执行；也可通过 `TLRPC_CLASSES_JAR` 指定该库的 `classes.jar`：

```bash
bash tests/group-messages/run-revoked.sh
```

测试直接运行生产匹配器、规则存储、AES 文件存储和异步副本管理器。Android 边界用最小替身控制账号、持久偏好和队列；原生消息资格使用真实 `TLRPC` 类型。它们不代替手机上的 Keystore、RecyclerView、网络同步及生命周期测试。

真机验收重点：普通群和话题群、UID/关键词及相册折叠、点击展开、整页隐藏后恢复、编辑后的重新匹配、主动删除/清历史/删除话题、重复撤回、重启、账号切换及退出。范围与当前交付状态见 [功能说明](../../docs/group-message-management.zh-CN.md)。
