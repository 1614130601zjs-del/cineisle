# Android 固定签名说明

本包加入了固定 debug 签名：

```text
android/signing/cineisle-debug.jks
```

并在 `android/app/build.gradle` 的 `debug` 构建里固定使用它。这样 GitHub Actions 每次打出来的 debug APK 会使用同一个签名证书，之后覆盖安装时更稳定，不容易因为每次随机 debug 签名不同而要求卸载。

注意：

1. 这个签名是公开 debug 签名，只用于自部署/测试/公开包的 debug APK，不要当成商店 release 私钥。
2. 如果用户手机里已经安装过旧随机签名的 APK，第一次切换到固定签名版时，Android 可能仍然不允许直接覆盖安装，需要先卸载旧版；从这次固定签名版以后再升级，才会保持同一签名。
3. 如果已经有重要本地数据，第一次切换前先在 App 内记录好后端地址、Token、房间号等信息。
