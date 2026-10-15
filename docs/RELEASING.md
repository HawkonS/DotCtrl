# DotCtrl release process

正式 APK 使用长期 release keystore 签名。请把 JKS 文件、别名、keystore 密码、key 密码以及证书指纹一起加密备份；丢失其中任一项会影响同一 application ID 的后续升级。

## 本机构建

先根据 [`examples/keystore.properties.example`](../examples/keystore.properties.example) 在仓库根目录创建 `keystore.properties`，然后使用 JDK 17 执行：

```bash
./gradlew --no-daemon --no-configuration-cache clean :core:test :app:lintDebug :app:assembleRelease
```

APK 输出在 `app/build/outputs/apk/release/app-release.apk`。分发前使用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查签名，并记录 APK SHA-256。

## GitHub Actions

`.github/workflows/build-release.yml` 仅在手动运行时构建。仓库需要配置以下 Actions secrets：

- `SIGNING_KEY`：完整 JKS 文件的 base64 内容
- `KEY_STORE_PASSWORD`：keystore 密码
- `ALIAS`：签名 key 别名
- `KEY_PASSWORD`：签名 key 密码

配置完成后，推送版本 tag 即会生成 APK artifact，并在 tag 对应的 GitHub Release 中上传 APK。

Debug 和正式版使用不同证书。已经安装 debug 包的设备，需要先卸载 debug 包才能安装正式版；卸载会清除应用本地数据。
