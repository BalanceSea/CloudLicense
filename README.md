# CloudLicense

CloudLicense 是面向 Minecraft Bukkit/Paper 插件的授权、版本发布和下载系统。仓库包含 Spring Boot API、PostgreSQL 数据库、Vue 3 管理端、用户插件中心、JNI ClassFile 变换器和 Java 插件 SDK。

## 功能

- 上传插件 JAR，经 JNI 移除 ClassFile 调试元数据后写入版本仓库
- 自定义插件授权列表，内置 CloudFashion 与 CloudChest
- 批量生成 7 天、30 天、自定义天数或永久卡密
- 首次验证原子绑定来源 IP，后续仅允许相同 IP
- 卡密停用、恢复、IP 解绑、分页查询和自定义验证消息
- 用户注册登录、卡密领取、所属授权自助解绑和鉴权下载
- 插件版本列表、SemVer 最新版 API、已拥有插件最新版下载
- Java SDK 同步/异步验证、版本检查和下载地址
- 管理端 `/` 与用户插件中心 `/download.html`
- OpenAPI JSON 与 Swagger UI

## 目录

```text
backend/             Spring Boot API、PostgreSQL/H2 测试数据库、文件仓库
frontend/            Vue 管理端与用户插件中心
sdk/                 插件侧 Java SDK
native-obfuscator/   JNI C++ ClassFile 变换器
docs/api.md          API 契约
docs/deployment-linux.md  Linux Docker 部署与运维
```

## Linux 一键部署

要求一台安装了 Docker Engine 与 Docker Compose 插件的 Linux 服务器。域名部署会由 Caddy 自动申请和续期 HTTPS 证书：

```bash
git clone https://github.com/BalanceSea/CloudLicense.git CloudLicense
cd CloudLicense
sudo bash deploy/deploy.sh license.example.com
```

部署脚本会自动生成 `.env` 中的管理密钥、卡密 pepper 和 PostgreSQL 密码，构建 Java、Vue 与 JNI 镜像，启动 PostgreSQL、API 和 Caddy，并等待数据库健康检查。部署完成后访问：

- 管理端：`https://license.example.com/`
- 用户插件中心：`https://license.example.com/download.html`
- Swagger UI：`https://license.example.com/api-docs`

无域名测试可传入 `http://服务器IP`，但生产环境必须使用 HTTPS。完整的安装、升级、备份、恢复、回滚和监控说明见 [Linux Docker 部署](docs/deployment-linux.md)。

## 本地运行

要求：JDK 21+、Node.js 20+。只有上传混淆功能额外需要 CMake 3.20+ 和 C++17 编译器。

```powershell
# 1. 后端
$env:CLOUDLICENSE_ADMIN_KEY = 'replace-with-a-long-random-secret'
$env:CLOUDLICENSE_LICENSE_PEPPER = 'replace-with-another-long-random-secret'
.\mvnw.cmd -pl backend spring-boot:run

# 2. 前端（新终端）
cd frontend
npm install
npm run dev
```

打开：

- 管理端：`http://localhost:5173/`
- 用户插件中心：`http://localhost:5173/download.html`
- Swagger UI：`http://localhost:8080/api-docs`
- OpenAPI：`http://localhost:8080/api/v1/openapi`

开发环境未设置密钥时使用可预测默认值，仅为首次启动方便。生产环境必须显式设置两个密钥。

## JNI 混淆器

当前原生变换器执行兼容优先的 ClassFile 硬化：删除 `SourceFile`、`SourceDebugExtension`、`LineNumberTable`、`LocalVariableTable` 和 `LocalVariableTypeTable`，同时移除已失效的 JAR 签名文件并保留 Manifest。它不会重命名 Bukkit/Paper 入口类，也不会修改业务字节码。

Linux/macOS：

```bash
cmake -S native-obfuscator -B native-obfuscator/build -DCMAKE_BUILD_TYPE=Release
cmake --build native-obfuscator/build --config Release
export CLOUDLICENSE_NATIVE_LIBRARY="$PWD/native-obfuscator/build/libcloudlicense_obfuscator.so"
```

Windows（Developer PowerShell）：

```powershell
cmake -S native-obfuscator -B native-obfuscator/build -A x64
cmake --build native-obfuscator/build --config Release
$env:CLOUDLICENSE_NATIVE_LIBRARY = (Resolve-Path 'native-obfuscator/build/Release/cloudlicense_obfuscator.dll')
```

没有配置或加载原生库时，其他功能照常运行，上传接口返回 `503 OBFUSCATOR_UNAVAILABLE`。系统不会复制原文件并冒充混淆结果。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CLOUDLICENSE_PORT` | `8080` | API 端口 |
| `CLOUDLICENSE_ADMIN_KEY` | 仅开发默认值 | 管理 API Bearer 密钥 |
| `CLOUDLICENSE_LICENSE_PEPPER` | 仅开发默认值 | 卡密 HMAC-SHA256 服务端 pepper |
| `CLOUDLICENSE_DB_URL` | 本地默认 H2；Docker 使用 PostgreSQL | JDBC 地址 |
| `CLOUDLICENSE_DB_NAME` | `cloudlicense` | PostgreSQL 数据库名（Docker） |
| `CLOUDLICENSE_DB_USER` | 本地 `sa`；Docker `cloudlicense` | 数据库用户 |
| `CLOUDLICENSE_DB_PASSWORD` | 空 | 数据库密码 |
| `CLOUDLICENSE_SCHEMA` | `classpath:schema.sql` | 初始化脚本；Docker 使用 `schema-postgres.sql` |
| `CLOUDLICENSE_STORAGE_ROOT` | `./storage` | 混淆后 JAR 仓库 |
| `CLOUDLICENSE_NATIVE_LIBRARY` | 空 | JNI 动态库绝对路径 |
| `CLOUDLICENSE_TRUST_FORWARDED_FOR` | `false` | 是否信任首个 `X-Forwarded-For` |
| `CLOUDLICENSE_VERIFY_RATE_LIMIT` | `120` | 单 IP 每分钟验证上限 |
| `CLOUDLICENSE_ALLOWED_ORIGINS` | 本机 Vite 地址 | 逗号分隔的前端来源 |

只有在 API 仅能由受信任反向代理访问时才能开启 `CLOUDLICENSE_TRUST_FORWARDED_FOR`，否则客户端可以伪造绑定 IP。Docker 部署默认使用 PostgreSQL；插件文件仍保存在单机目录，横向扩容前需迁移到共享对象存储。

## 插件 SDK

先构建 SDK：

```powershell
.\mvnw.cmd -pl sdk package
```

在 Paper/Bukkit 插件的异步任务中调用：

```java
CloudLicenseClient client = CloudLicenseClient.builder()
        .baseUri("https://license.example.com/")
        .plugin("cloudfashion")
        .licenseKey(getConfig().getString("license-key"))
        .build();

client.verifyAsync().thenAccept(result -> {
    if (!result.valid()) {
        getLogger().severe(result.status() + ": " + result.message());
        getServer().getPluginManager().disablePlugin(this);
        return;
    }
    getLogger().info(result.message());
});

CloudLicenseClient.UpdateResult update = client.checkForUpdate(getDescription().getVersion());
if (update.checked() && update.updateAvailable()) {
    getLogger().info("发现新版本 " + update.latest().version());
}
```

不要在 Minecraft 主线程执行同步 `verify()` 或 `checkForUpdate()`。SDK 的连接超时默认 3 秒，请求超时默认 5 秒，可通过 Builder 调整。

## 构建与测试

```powershell
.\mvnw.cmd clean test
cd frontend
npm ci
npm run build
npm audit --audit-level=high
```

生产发布前先执行 `deploy/backup.sh`，同时备份 PostgreSQL 自定义 dump 和 `runtime/storage/`。回滚应用代码时不要覆盖数据库或已发布 JAR。

Docker 部署的 PostgreSQL 数据位于命名卷 `postgres_data`，插件文件位于 `runtime/storage/`；应使用 `sudo bash deploy/backup.sh` 创建一致性备份。
