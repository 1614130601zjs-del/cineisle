# Railway 部署说明

本项目是一个混合仓库：根目录包含 Android Gradle 工程，真正需要部署到 Railway 的后端在 `server/` 目录。

如果 Railway 直接扫描根目录，可能会看到 `build.gradle`，然后误判成 Gradle/Android 项目，出现类似：

```text
未找到脚本 start.sh
Railpack 无法确定如何构建应用程序。
```

本包已经加入 Railway 专用配置：

- `Dockerfile`：只复制并运行 `server/` 后端，避免 Railway 误扫 Android 工程。
- `railway.json`：明确指定使用 Dockerfile 构建，并配置 `/api/health` 健康检查。
- `start.sh`：兼容有人在 Railway 面板里手动填了 `sh start.sh` 的情况。
- 根目录 `package.json`：兼容手动使用 Railpack/npm 脚本时的构建和启动命令。

## Railway 推荐操作

1. 把本 ZIP 解压覆盖到 GitHub 仓库。
2. Railway 重新从 GitHub 部署。
3. 在 Variables 里设置：

```text
CINEISLE_TOKEN=自己设置一个长一点的口令
NODE_ENV=production
```

4. 部署成功后打开：

```text
https://你的 Railway 地址/api/health
```

看到 `ok: true` 就说明后端成功。

## 如果 Railway 仍旧使用 Railpack

在 Railway Service Settings 里手动设置：

```text
Build Command: npm run railway:build
Start Command: npm run railway:start
```

或者：

```text
Build Command: cd server && npm install --package-lock=false --no-audit --no-fund && npm run check
Start Command: cd server && npm start
```

## 常见问题

- 只发失败卡片不够，需要看 Build Logs 里第一段真正的 error。
- 不要把 `CINEISLE_TOKEN` 截图发到公开场合。
- `render.yaml` 是给 Render 用的；Railway 主要看 `railway.json` / `Dockerfile` / 面板设置。
