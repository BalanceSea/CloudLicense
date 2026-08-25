# CloudLicense Linux Docker 部署

本文面向单台 Linux 服务器上的生产部署。Compose 会运行两个容器：Caddy 提供 HTTPS、Vue 静态页面和 API 反向代理，Spring Boot 容器运行授权 API 与 JNI 混淆器。H2 数据库和插件文件保存在宿主机 `runtime/` 下。

```text
Internet -> Caddy :80/:443 -> Spring Boot :8080 (仅 Docker 内网)
                              |-> runtime/data
                              |-> runtime/storage
                              |-> JNI libcloudlicense_obfuscator.so
```

## 1. 服务器准备

建议使用 Ubuntu 22.04/24.04 或 Debian 12，至少 2 核 CPU、2 GB 内存和 10 GB 可用磁盘。生产环境需要：

- 域名 A/AAAA 记录指向服务器公网 IP
- 云安全组和主机防火墙开放 TCP 80、TCP 443、UDP 443
- Git、Docker Engine 和 Docker Compose v2 插件
- 服务器上没有其他程序占用 80/443 端口

可通过 Docker 官方安装脚本快速安装；安全要求较高的环境应按 Docker 官方仓库安装文档逐步安装并审查脚本内容：

```bash
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo docker version
sudo docker compose version
```

## 2. 一键部署

```bash
git clone https://github.com/BalanceSea/CloudLicense.git CloudLicense
cd CloudLicense
sudo bash deploy/deploy.sh license.example.com
```

参数是对外服务域名。首次执行时脚本会：

1. 创建权限为仅管理员可读的 `.env`，并生成三个独立的 256 位随机值。
2. 创建 `runtime/data`、`runtime/storage` 和 `runtime/backups`。
3. 构建 Spring Boot、Vue、Caddy 和 Linux JNI 混淆器镜像。
4. 启动容器并等待数据库查询健康检查通过。

访问地址：

```text
管理端        https://license.example.com/
用户中心      https://license.example.com/download.html
Swagger UI    https://license.example.com/api-docs
OpenAPI       https://license.example.com/api/v1/openapi
```

仅用于内网测试或尚无域名时，可以执行：

```bash
sudo bash deploy/deploy.sh http://203.0.113.10
```

HTTP 会暴露 Bearer Token，不得用于互联网生产环境。域名模式下，如果证书申请失败，先检查 DNS 是否已生效以及 80/443 端口是否可从公网访问。

## 3. 配置与密钥

首次部署自动生成的配置保存在项目根目录 `.env`，该文件已被 Git 和 Docker 构建上下文忽略。查看管理密钥时只在受控终端执行：

```bash
sudo sed -n 's/^CLOUDLICENSE_ADMIN_KEY=//p' .env
```

重要规则：

- `CLOUDLICENSE_ADMIN_KEY` 泄露后应立即轮换并重启服务。
- `CLOUDLICENSE_LICENSE_PEPPER` 必须长期保留；更换后所有既有明文卡密都无法再匹配数据库摘要。
- `.env`、`runtime/data` 和 `runtime/storage` 不得提交到 GitHub。
- `CLOUDLICENSE_TRUST_FORWARDED_FOR=true` 仅因为后端只连接受信任的 Caddy 内网；不要把后端 8080 端口映射到公网。

修改 `.env` 后应用配置：

```bash
sudo docker compose up -d --force-recreate
```

## 4. 日常操作

```bash
# 服务状态
sudo docker compose ps

# 实时日志
sudo docker compose logs -f --tail=200

# 后端健康检查
curl -fsS https://license.example.com/api/v1/public/plugins

# 重启
sudo docker compose restart
```

建议至少配置以下监控和告警：

| 指标 | 建议阈值 | 处理 |
| --- | --- | --- |
| 容器健康状态 | 连续 2 分钟 unhealthy/restarting | 查看后端日志并停止发布 |
| HTTP 5xx 比例 | 5 分钟内超过 1% | 检查 Caddy 与后端日志 |
| 磁盘使用率 | 超过 80% | 扩容或迁移旧备份 |
| 备份新鲜度 | 24 小时无成功备份 | 立即执行备份并排查定时任务 |
| TLS 证书 | 剩余少于 14 天 | 检查 80/443 和 Caddy 日志 |

建议每日执行一次备份、每周检查容器日志和磁盘、每月在隔离环境做一次恢复演练。

## 5. 备份与恢复

H2 文件与插件仓库必须作为同一个恢复点备份。脚本会短暂停止后端，避免复制正在写入的 H2 文件：

```bash
sudo bash deploy/backup.sh
ls -lh runtime/backups/
```

恢复前先再做一次当前状态备份。然后停止服务，将现有目录改名保留，再解压指定备份：

```bash
sudo docker compose down
stamp=$(date -u +%Y%m%dT%H%M%SZ)
sudo mv runtime/data "runtime/data.before-restore-$stamp"
sudo mv runtime/storage "runtime/storage.before-restore-$stamp"
sudo tar -xzf runtime/backups/cloudlicense-YYYYMMDDTHHMMSSZ.tar.gz
sudo chown -R 10001:10001 runtime/data runtime/storage
sudo docker compose up -d
sudo docker compose ps
```

确认用户登录、卡密验证和插件下载正常后，才清理 `before-restore` 目录。

## 6. 更新与回滚

更新前先备份，再拉取代码并重建镜像：

```bash
sudo bash deploy/backup.sh
git pull --ff-only
sudo bash deploy/deploy.sh license.example.com
```

部署脚本发现 `.env` 已存在时不会覆盖任何密钥。若新版本健康检查失败，在无本地改动的部署目录中切回上一个已知正常提交并重建；不要替换或回滚 `runtime/`：

```bash
git log --oneline -10
git switch --detach <last-good-commit>
sudo docker compose build
sudo docker compose up -d
sudo docker compose ps
```

恢复代码版本后仍异常时，保留现场日志并使用第 5 节的数据备份恢复。应用代码回滚和数据恢复是两个独立动作，只有确认数据库或文件仓库损坏时才恢复数据。

## 7. 单节点限制

当前数据库是 H2 文件数据库，只允许一个后端容器读写，不能执行 `docker compose up --scale backend=2`。需要高可用或横向扩容时，应先迁移到 PostgreSQL，并将插件文件迁移到共享对象存储；在完成迁移前，不要在多个服务器挂载同一个 H2 文件。

## 资料来源

- `compose.yaml`：容器网络、持久化、健康检查和运行时安全配置
- `deploy/deploy.sh`：首次配置生成与部署流程
- `deploy/backup.sh`：H2 和插件仓库一致性备份流程
- `backend/src/main/resources/application.yml`：后端环境变量与代理信任设置
