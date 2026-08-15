# GitHub 工作流说明

## Build Android APK

文件：`.github/workflows/build-debug-apk.yml`

用途：自动构建 Android debug APK。

路径：`Actions → Build Android APK → Run workflow`

产物：`cineisle-android-debug-apk`

## Package source ZIP

文件：`.github/workflows/package-source-zip.yml`

用途：把当前仓库打成干净源码 ZIP，排除 `.git`、`node_modules`、Android build 产物和旧 ZIP。

路径：`Actions → Package source ZIP → Run workflow`

产物：`cineisle-source-zip`

## Unpack ZIP and overwrite repo

文件：`.github/workflows/unpack-zip-overwrite.yml`

用途：把你上传到仓库里的 ZIP 解压并覆盖旧仓库，然后自动提交。

使用：

1. 上传 ZIP 到仓库根目录，例如 `cineisle-update.zip`。
2. 运行 `Actions → Unpack ZIP and overwrite repo → Run workflow`。
3. 输入 `zip_file=cineisle-update.zip`。
4. 等待 workflow 自动提交。
