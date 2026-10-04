# 当前 Telegram APK 签名备份

本轮保全第 20 次 Actions APK 使用的现有签名，不生成或替换签名，也不切换 Release 构建。

- 证书 SHA-256：`c8715ba0c50510b9c1cc4d52b0fd2b0aed82dd55675f89c4f48d68978b7df313`。
- 原签名缓存：`mnn-debug-keystore-1402824680-v1`，作用域 `refs/heads/feature/mnn-group-summary`。
- 备份工作流恢复路径与 APK 工作流保持一致；缓存缺失、条目不含私钥或证书不符时直接失败，不生成替代签名。
- 收件公钥提交到仓库，恢复私钥只保存在用户工作区的仓库外。使用 AES-256-GCM 加密密钥库，以 RSA-OAEP-SHA256 包装随机加密密钥；元信息作为认证数据。
- GitHub artifact 只包含加密 JSON，不包含明文密钥库或恢复私钥。密文 artifact 有效期 30 天，不作为唯一长期备份。
- 私密交付包必须由用户下载后单独保存；不要上传到公开仓库或 Release。证书指纹和已签名 APK 不能替代私钥备份。

备份工具的 18 项合成回归检查覆盖准确还原、证书验证、缺失密钥、篡改及错误恢复私钥拒绝、已有输出保护和文件权限。

## 本轮结果

- [备份工作流](https://github.com/Naza3/Telegram/actions/runs/37211439744) 成功；源提交 `05dd95ebda0df9b285fb58140bfe003be40e4871`。缓存恢复、固定证书核验、加密与上传均通过。
- [密文 artifact](https://github.com/Naza3/Telegram/actions/runs/37211439744/artifacts/11306772738) 只含 `telegram-signing-key.encrypted.json`，2026-11-03 23:01:49（北京时间）到期。没有对应恢复私钥时，该 artifact 不能恢复签名。
- 本地解密成功，导出证书 SHA-256 与第 20 次 APK 一致；使用恢复出的私钥签署临时测试 JAR，并通过 `jarsigner -verify -strict` 验证。恢复文件权限为 `0600`。
- 用户工作区已生成 `Telegram-current-signing-private-backup-20261004.zip`，包含原密钥库、加密备份、恢复私钥、配套工具及配置和说明。已校验 ZIP 完整性及每个文件的字节一致性；此包含私钥，只通过用户工作区交付，不上传 GitHub。
- 需要用户将完整私密包下载到自己的长期安全存储。当前会话工作区及 Actions artifact 均不能替代用户自己的备份。
- 本轮没有迁移到 Secrets，没有修改原 APK 签名来源。备份提交额外触发的 APK 构建已取消，未发布新 APK。
