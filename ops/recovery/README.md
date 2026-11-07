# VIO v1.0 恢复与回滚 Runbook

本目录把 M7 的恢复演练变成可复查的操作契约。脚本默认只做预检；任何会删除
容器、覆盖数据库或切换索引的动作都必须由值班人员在隔离环境显式确认，并把
输出保存到发布证据目录。仓库不包含密码、备份文件或真实服务地址。

## 1. 演练前冻结

记录以下值（不要只记录镜像 tag）：

```text
release_id=
git_commit=
backend_image_digest=
frontend_image_digest=
flyway_schema_version=
data_release_id=
knowledge_release_id=
index_release_id=
backup_object_uri=
operator=
started_at=
```

演练环境必须与生产使用相同的 Flyway 迁移、Compose 拓扑和健康探针；生产密钥
通过 Secret Store/CI 注入，不能写入命令行日志或本文件。

## 2. 备份与校验

在数据库节点执行（示例使用外部 `PG*` 环境变量）：

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
pg_dump --format=custom --no-owner --file="artifacts/v1.0/rollback/vio-$stamp.dump" "$env:SPRING_DATASOURCE_URL"
pg_restore --list "artifacts/v1.0/rollback/vio-$stamp.dump" | Select-Object -First 5
```

备份文件应加密、限制读取权限并上传到受控对象存储；只把 URI、checksum 和保留
期限写进演练记录。不要把 `SPRING_DATASOURCE_PASSWORD` 拼进 URL。

## 3. 固定业务探针

恢复后按顺序执行：

```powershell
curl.exe -f "$env:VIO_BASE_URL/api/actuator/health/readiness"
curl.exe -f "$env:VIO_BASE_URL/api/health"
curl.exe -f -H "X-API-Key: $env:VIO_API_KEY" "$env:VIO_BASE_URL/api/metrics"
```

随后用一条已冻结版本的事实题、一个确定性计算题和一个引用题创建 Run，保存
`runId`、事件游标、`traceId`、终态和 citations。API Key 只从进程环境读取，脚本
不会回显请求头。

## 4. 故障注入与恢复顺序

1. 记录故障开始时间，停止/删除**演练环境**的应用容器，确认 liveness/readiness
   告警分别触发。
2. 模拟数据库不可用，确认 readiness 变为非 200，流量不会切入未就绪实例。
3. 恢复 PostgreSQL 容器和备份，按 Flyway 顺序执行迁移；不得用手工 SQL 覆盖
   已发布事实。
4. 启动后端和前端，等待 readiness，再执行固定探针。
5. 查询恢复前已完成 Run、会话消息、反馈工单和 `active_index_release`；确认
   用户隔离、唯一终态和事件游标仍成立。
6. 如候选知识/向量索引不合格，只切换 `active_index_release` 到上一版本，不能
   删除当前仍被线上请求使用的索引。

## 5. RPO/RTO 记录表

| 项目 | 目标 | 实测 | 证据 |
|---|---:|---:|---|
| 数据 RPO | 按备份策略填写 | | 备份 checksum/时间 |
| 服务 RTO | 按规格书填写 | | readiness 时间线 |
| 会话可恢复 | 100% 固定探针 | | conversation/run 查询 |
| 索引指针可回退 | 1 次原子切换 | | active_index_release |
| 告警到达 | 按值班 SLO | | Alertmanager/Grafana |

没有填写“实测”和证据时，只能说“具备演练脚本”，不能在发布单或简历中声称
“已验证可恢复”。

## 6. 回滚决策

- readiness 未通过：停止切流，保留当前失败版本日志。
- 代码/镜像回滚：使用上一份 release manifest 中的 digest，不使用浮动 tag。
- 数据回滚：优先向前兼容迁移；确需恢复备份时，先隔离写流量并记录数据丢失窗口。
- 知识回滚：只更新 `active_index_release`/knowledge release 指针，保留候选版本
  供事后分析。
- 完成后再次跑固定事实、计算、引用和取消探针，并关闭演练临时凭据。

详细执行模板仍保留在 `document/正式版v1.0开发计划/M6-M7交付说明.md` 的证据
章节；本目录脚本是其可执行补充。
