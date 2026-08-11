# 正式版签名说明

正式安装到家人手机前，应使用长期保管的发布密钥，不要把调试版当作正式版。

## 首次创建密钥

在当前项目电脑上运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\create-release-keystore.ps1
```

脚本会生成随机强密码，不在终端打印，并创建：

- `.tooling/signing/family-ledger-release.jks`
- `keystore.properties`

两个文件均被 Git 忽略。请把密钥文件和配置文件分别备份到两个可靠位置；丢失密钥后，手机上的现有正式版将无法覆盖升级。脚本发现文件已存在时会拒绝覆盖。

下面的手动命令仅供不使用脚本时参考。

示例命令（执行时请自行输入密码，不要把密码写在命令行里）：

```powershell
New-Item -ItemType Directory -Force .tooling\signing
keytool -genkeypair -v -keystore .tooling\signing\family-ledger-release.jks -alias family-ledger -keyalg RSA -keysize 4096 -validity 10950
```

## 构建正式 APK

双击 `build-release-apk.cmd`。成功后安装包位于：

```text
dist\family-ledger-cloud-v0.4.0-release.apk
```

如果手机当前安装的是文件名带 `debug` 的验收版，正式发布密钥与调试密钥不同，Android 不允许直接覆盖安装。请先在验收版中导出 JSON 备份，确认云同步成功，再卸载验收版、安装正式版、重新填写家庭同步码并同步；必要时再导入 JSON 备份。

`keystore.properties` 和密钥文件都已加入 Git 忽略列表，不应提交到代码仓库。
