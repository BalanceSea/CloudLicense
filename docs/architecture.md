# 架构与边界

```text
Vue 管理端 ─Admin Bearer─> Admin Controllers ───────────> JDBC/H2
用户插件中心 ─User Bearer─> User Controllers ─> Services ├─> 版本仓库
Minecraft 插件 SDK ───────> 验证/版本 API                └─> JNI ClassFile 变换器
```

- `plugins` 持有授权列表和默认验证消息。
- `users` 保存 BCrypt 密码摘要；`user_sessions` 只保存随机 Token 的 SHA-256 摘要并在 7 天后失效。
- `licenses` 保存卡密 HMAC、前缀、状态、有效期、绑定 IP 和可空的用户归属。
- `plugin_versions` 持有版本元数据，JAR 本体归 `storage/` 管理。
- `LicenseService.verify()` 是 IP 首次绑定的唯一写入边界，使用数据库事务和 `SELECT ... FOR UPDATE` 防止并发双绑。
- `StorageService` 是上传路径和仓库路径的唯一持有者，所有最终文件名由服务端生成。
- `UserService.claim()` 使用事务与行锁保证卡密只能被一个账号领取；解绑 SQL 同时约束授权 ID 和用户 ID。
- 用户插件中心有独立 Vite HTML 入口，不引用管理端组件；下载必须携带内存中的用户 Bearer Token，并由后端复核有效授权。

## 关键决策

| 决策 | 选择 | 代价与撤销条件 |
| --- | --- | --- |
| 数据库 | PostgreSQL（Docker）/H2（测试） | PostgreSQL 提供事务和并发能力；本地测试使用内存 H2，生产不使用 H2 文件模式 |
| 管理鉴权 | Bearer 管理密钥 | 部署简单；需要多管理员/审计时替换为 OIDC + RBAC |
| 用户鉴权 | BCrypt + 7 天随机 Bearer 会话 | 无 Cookie CSRF 面；刷新页面需重新登录，未来可替换短期 Access Token + HttpOnly Refresh Token |
| 卡密存储 | HMAC-SHA256 + pepper | 无法找回明文；重置 pepper 会使既有卡密失效 |
| IP 来源 | 默认 socket remote address | 防伪造；代理部署时需显式信任 XFF 并限制直连 |
| 混淆策略 | JNI 私有成员重命名 + 调试元数据剥离 | 公共 Bukkit/Paper API 保持不变；依赖私有成员原名反射的插件需要调整或关闭混淆 |
