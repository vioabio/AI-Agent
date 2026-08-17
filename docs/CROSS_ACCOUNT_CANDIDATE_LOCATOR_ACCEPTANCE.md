# 跨账号候选人来源定位：验收说明

## 目标

在不同猎聘招聘账号的相同关键词结果不一致时，尽可能保留原候选人的定位证据，并在再次
发现候选人时可靠判断是否为同一份简历。

## 已实现的三重索引

1. 分享链接（主路径）：AI 判定匹配后，自动执行“详情页右上角第 2 个按钮 → 更多 → 复制链接”，保存猎聘正式简历分享链接。
2. 平台定位（兜底）：从授权 UI 层级中提取明确标注的候选人 ID、简历 ID 或猎聘深链；不猜测普通数字。
3. 来源定位：保存账号别名、设备、岗位配置、搜索词、搜索包、结果轮次/排名、候选卡片文本、详情 Activity 和截图。
4. 内容确认：继续使用候选人指纹和完整简历正文 `experience_hash`，用于跨账号最终确认。

数据库表：`candidate_resume_source_locators`。

## 验收前配置

在 `.env` 为每个猎聘账号设置不含密码的稳定标签，例如：

```dotenv
LIEPIN_ACCOUNT_ALIAS=recruiter_a
```

切换账号后改为 `recruiter_b`。账号登录凭据不得写入定位表。

## 验收步骤

1. 用账号 A 运行至少一个会进入详情页的搜索任务。
2. 查询最新定位记录：

   ```powershell
   .\.venv\Scripts\python.exe scripts\find_candidate_locator.py --candidate-fingerprint <候选人指纹>
   ```

3. 检查输出中是否包含账号标签、搜索词、卡片文本、结果轮次/排名、详情 Activity、截图路径和正文哈希。
4. 匹配候选人成功复制分享链接时，输出策略应为 `share_link_then_verify`；否则有平台 ID/深链时为 `platform_locator_then_verify`，都没有时为 `search_hints_then_verify`。
5. 换账号 B 后按输出策略打开或重搜；进入详情后，必须用 `experience_hash` 确认是同一份简历。

账号 B 打开正式分享链接：

```powershell
.\.venv\Scripts\python.exe scripts\open_candidate_share_link.py --candidate-fingerprint <候选人指纹>
```

真机临时验收也可以直接使用手机剪贴板中刚复制的链接：

```powershell
.\.venv\Scripts\python.exe scripts\open_candidate_share_link.py --from-clipboard
```

## 自动化测试

```powershell
.\.venv\Scripts\python.exe -m pytest
```

覆盖显式平台 ID/深链提取、数字误识别防护、降级回查策略、数据库写入与查询，以及原有
搜索、分析和可靠性回归。

## 已知边界

- 平台 ID 或深链是否能被另一个账号打开取决于猎聘对该账号的简历可见范围。
- 正式分享链接是优先路径，但链接有效期和目标账号可见性仍由猎聘控制。
- Android UI 未暴露平台 ID 时，系统不会逆向接口或伪造链接，而是保存搜索定位证据并依赖正文哈希确认。
- 历史简历没有采集过的平台 ID 无法事后补回；现有正文和指纹仍可用于内容匹配。
