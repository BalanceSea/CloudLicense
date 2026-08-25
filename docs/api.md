# CloudLicense API v1

基础路径为 `/api/v1`。管理接口要求管理员 Bearer 密钥，用户接口要求登录返回的用户 Bearer Token。错误响应统一为：

```json
{
  "code": "MACHINE_READABLE_CODE",
  "message": "可读消息",
  "timestamp": "2026-08-25T12:00:00+08:00"
}
```

## 公开接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/licenses/verify` | 验证卡密并在首次成功时绑定请求 IP |
| `GET` | `/public/plugins` | 可领取插件目录与最新版本 |
| `GET` | `/public/plugins/{slug}/versions` | 公开版本列表 |
| `GET` | `/public/plugins/{slug}/latest` | 最新版元数据 |
| `GET` | `/public/plugins/{slug}/latest/download` | 已禁用，返回 `401 LOGIN_REQUIRED` |

### 验证授权

```http
POST /api/v1/licenses/verify
Content-Type: application/json

{
  "plugin": "cloudfashion",
  "licenseKey": "CLD-XXXXX-XXXXX-XXXXX-XXXXX"
}
```

成功：

```json
{
  "valid": true,
  "status": "VALID",
  "message": "CloudFashion 授权验证通过",
  "expiresAt": "2026-09-24T12:00:00+08:00",
  "plugin": "cloudfashion"
}
```

业务验证失败仍返回 HTTP 200，`valid=false`，`status` 为 `LICENSE_NOT_FOUND`、`LICENSE_REVOKED`、`LICENSE_EXPIRED` 或 `IP_MISMATCH`。这样插件 SDK 能稳定区分网络失败和授权拒绝。接口默认按来源 IP 限制为每分钟 120 次。

### 查询最新版

```http
GET /api/v1/public/plugins/cloudfashion/latest
```

```json
{
  "plugin": "cloudfashion",
  "version": "1.4.0",
  "sha256": "...",
  "sizeBytes": 245760,
  "changelog": "修复菜单同步",
  "publishedAt": "2026-08-25T12:00:00+08:00",
  "downloadUrl": "/download.html"
}
```

## 用户接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/users/register` | 注册并返回 7 天用户会话 |
| `POST` | `/users/login` | 登录并返回 7 天用户会话 |
| `GET` | `/user/me` | 当前用户资料 |
| `POST` | `/user/logout` | 注销当前会话 |
| `POST` | `/user/licenses/claim` | 将未领取卡密绑定到当前账号 |
| `GET` | `/user/plugins` | 查看已领取插件、授权和最新版本 |
| `POST` | `/user/licenses/{id}/unbind` | 解除当前用户所属授权的 IP 绑定 |
| `GET` | `/user/plugins/{slug}/download` | 下载已拥有且授权有效的最新版 JAR |

注册用户名为 3 到 32 位字母、数字或下划线，密码为 8 到 64 个字符。密码使用 BCrypt 保存；会话 Token 只在登录或注册响应中出现，数据库仅保存 SHA-256 摘要。

卡密领取请求：

```json
{
  "plugin": "cloudfashion",
  "licenseKey": "CLD-XXXXX-XXXXX-XXXXX-XXXXX"
}
```

同一卡密只能属于一个账号。用户下载还要求至少一个所属授权处于 `ACTIVE` 且未过期，前端按钮状态不作为权限依据。

## 管理接口

| 方法 | 路径 | 用途 | 幂等性 |
| --- | --- | --- | --- |
| `GET` | `/admin/plugins` | 插件列表、卡密数和最新版本 | 是 |
| `POST` | `/admin/plugins` | 创建自定义授权列表 | 否，slug 唯一 |
| `GET` | `/admin/plugins/{id}/licenses?page=1&pageSize=20` | 分页授权列表 | 是 |
| `POST` | `/admin/plugins/{id}/licenses` | 批量生成卡密 | 否，禁止自动重试 |
| `PATCH` | `/admin/licenses/{id}/status` | `ACTIVE`/`REVOKED` 状态切换 | 是 |
| `POST` | `/admin/licenses/{id}/unbind` | 解除 IP 绑定 | 是 |
| `GET` | `/admin/plugins/{id}/versions` | 全部版本列表 | 是 |
| `POST` | `/admin/plugins/{id}/versions` | 上传、JNI 混淆并发布 JAR | 否，plugin+version 唯一 |

### 生成卡密

`durationDays` 为 `null` 表示永久，范围为 1 到 3650 天；单次最多 100 个。卡密明文只在这次响应中出现，数据库仅保存 HMAC-SHA256 摘要。

```json
{
  "count": 10,
  "durationDays": 30,
  "customMessage": "欢迎使用授权版本"
}
```

### 上传版本

请求为 `multipart/form-data`：

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `file` | JAR | 最大 100 MB，必须含 `plugin.yml` 或 `paper-plugin.yml` |
| `version` | string | 1 到 64 字符，字母数字开头，可含 `._-` |
| `changelog` | string | 最长 2000 字符 |
| `isPublic` | boolean | 是否作为用户中心可下载版本 |

成功后返回版本、SHA-256、文件大小和发布时间。JNI 未配置返回 HTTP 503，非法 JAR 返回 HTTP 400，原生处理失败返回 HTTP 422，版本冲突返回 HTTP 409。

## 状态码

| HTTP | 场景 |
| --- | --- |
| `200` | 查询、验证或更新成功 |
| `400` | 参数或上传格式错误 |
| `401` | 管理密钥错误、用户会话失效或访问旧匿名下载入口 |
| `403` | 当前用户没有插件有效授权 |
| `404` | 插件、授权或版本不存在 |
| `409` | slug、版本重复或卡密已被其他用户领取 |
| `422` | JAR 混淆失败 |
| `429` | 验证接口超过频率限制 |
| `503` | JNI 原生库未加载 |
