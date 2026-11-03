# DotCtrl release process

正式 APK 使用长期 release keystore 签名。请把 JKS 文件、别名、keystore 密码、key 密码以及证书指纹一起加密备份；丢失其中任一项会影响同一 application ID 的后续升级。

## 本机构建

先根据 [`examples/keystore.properties.example`](../examples/keystore.properties.example) 在仓库根目录创建 `keystore.properties`，然后使用 JDK 17 执行：

```bash
./gradlew --no-daemon --no-configuration-cache clean :core:test :app:lintDebug :app:assembleRelease
```

APK 输出在 `app/build/outputs/apk/release/app-release.apk`。分发前使用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查签名，并记录 APK SHA-256。

## GitHub Actions

`.github/workflows/build-release.yml` 支持手动构建，也在推送 `v*` tag 时自动发布。tag 必须与 `versionName` 对应，例如版本 `1.1.0` 使用 `v1.1.0`；每次发布必须递增 `versionCode`。仓库需要配置以下 Actions secrets：

- `SIGNING_KEY`：完整 JKS 文件的 base64 内容
- `KEY_STORE_PASSWORD`：keystore 密码
- `ALIAS`：签名 key 别名
- `KEY_PASSWORD`：签名 key 密码

配置完成后，推送版本 tag 即会生成 APK artifact，并在 tag 对应的 GitHub Release 中上传 APK。

每个正式 Release 只上传一个通用正式签名 APK，文件名为 `DotCtrl-版本号-release.apk`。应用通过 GitHub `/releases/latest` 检查正式版本，不接收草稿和预发布版本；缺失 APK 或存在多个 APK 时只提供发布页入口。下载完成后校验包名、版本、签名，以及 GitHub 提供的 SHA-256（如有），再进入系统安装流程。更新功能无需业务服务器。

Debug 和正式版使用不同证书。已经安装 debug 包的设备，需要先卸载 debug 包才能安装正式版；卸载会清除应用本地数据。

## 更新验证

本地运行 `:app:testDebugUnitTest :core:test :app:lintDebug :app:assembleDebug`。设备集成测试 `UpdateApkDeviceTest` 还可校验真实 APK：将比已安装版本更高的同签名 APK 和另一证书签名的 APK 分别命名为 `fixture-valid.apk`、`fixture-other-signature.apk`，写入 Debug 应用的内部目录 `files/update-fixtures/`（可通过 `adb exec-in run-as tech.hawkon.dotctrl` 写入）。安装 `:app:assembleDebugAndroidTest` 生成的测试 APK 后，执行：

```bash
adb shell am instrument -w \
  -e class 'tech.hawkon.dotctrl.updates.UpdateApkDeviceTest#verifiesRealApksAndSharesTheAcceptedFileWithTheInstaller' \
  -e fixtureVersion 1.1.1 \
  tech.hawkon.dotctrl.test/androidx.test.runner.AndroidJUnitRunner
```

该方法只校验和分享文件，不执行安装；没有 fixture 参数时跳过。在测试设备上，还可单独执行 `stagesCompletedUpdateForManualInstallerTest`，同时传入 `stageInstaller=true` 和 `fixtureVersion`，生成本地已完成的 DownloadManager 任务。重新打开应用后，可通过真实“立即安装”入口验证授权往返、取消重试和同签名覆盖升级，不必发布测试 Release。准备方法拒绝覆盖已有更新任务或文件。结束后单独执行 `cleansUpManualInstallerFixture`，传入 `cleanupFixture=true` 和相同版本，清理测试任务和 fixture。保留设备上的本地配置，不卸载主应用。
