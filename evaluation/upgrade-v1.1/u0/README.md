# U0 冻结题集与基线工具

日期：2026-11-12。当前交付为离线题集/判据冻结、配置指纹与基线准备；**不是已经完成真实模型基线采集**。

## 文件

- `cases.json`：40道新输入，资料、检索、连续对话、计算、授权/缺项各8道；每类4道dev、4道heldout。
- `rubric.json`：内容判据、硬失败与指标定义。
- `manifest.json`：LF规范化文件摘要、原语料摘要与正式版基线引用。
- `tools/campus/upgrade-baseline.mjs`：复用既有evaluation.mjs的摘要能力，不复制模型用量账本。

题目由开发助手生成，不是独立用户流量或第三方盲审。heldout在版本库中可见，封存依靠流程约束，不是技术访问隔离；后续调试不得使用该部分题目。资料标签目前绑定文档ID，实际片段ID与语义标注复核尚待真实基线补齐，不能宣称检索指标已验收。

## 操作

在项目根目录执行：

```powershell
node tools/campus/upgrade-baseline.mjs validate
node --test tools/campus/upgrade-baseline.test.mjs
node tools/campus/upgrade-baseline.mjs prepare artifacts/u0-baseline-新的唯一目录 dev
```

prepare不连接后端、不访问模型、不登录账号、不读取私有配置；仅创建20条NOT_RUN记录及Git/题集/语料身份。目录必须位于artifacts，存在时拒绝覆盖。不要把准备记录当作回答结果。

publicFingerprint仅接受白名单诊断字段，丢弃Key、Cookie及嵌套任意字段；upgrade-collect.mjs现已接入运行时白名单采集；未提供运行时数据的prepare记录仍不得冒充运行配置已核验。保存的sourceStateSha256是Git跟踪文件差异摘要，不含未跟踪文件；执行前应提交评测工具或单独登记producer摘要。

## 后续完成U0的步骤

1. 人工/AI辅助复核题目判据，确认来源适用性，登记审核方式；AI复核不写成人工通过。
2. 实现基于既有采集器的适配：隔离账号、逐轮发送、真实事件与正文、终态、事件摘要及清理，禁止只读COMPLETED判通过。
3. 采集受保护runtime的白名单字段与依赖/producer摘要；冻结模型、profile、Prompt、语料和索引状态。禁止读取或打印.run和私有application-local.yml。
4. 使用dev采集升级前结果；每题所有轮次保持同会话，题间隔离。缺失队伍不替换为个人test1；跨账号与写入项必须使用专门fixture。
5. 补齐相关片段标注，逐项核对正文与真实工具输出；环境失败记BLOCKED，未运行保留NOT_RUN。
6. 冻结性能阈值与基线报告后进入U1；heldout留到U6首次验收，不提前调试。

## 本轮验证

5项Node测试通过：分层平衡、冻结篡改拒绝、嵌套凭证剔除、开关指纹变化与输出范围/分组拒绝。已生成一份20项NOT_RUN本机准备记录，真实模型成绩为“未采集”。本轮未改业务回答路径，未重跑既有68题。


## 2026-11-13 采集器补齐与U1基础开发

新增命令：

```powershell
node tools/campus/upgrade-collect.mjs artifacts/u0-新的唯一目录 http://127.0.0.1:8123/api 20
```

仅采集dev，新建随机隔离账号；每题单独会话、多轮共用会话。密码/Cookie只存在进程内，不保存；逐轮保存实际事件及摘要、正文、TTFT和总耗时，终态复用原evaluation.mjs判定。执行结果默认PENDING_REVIEW，不自动判内容正确。等待审批只取消，不执行确认。

本轮20道开发题均已采集，封存集未执行。环境为local/deepseek、deepseek-flash、专用角色开启。记录在artifacts/u0-dev-20261113，当前是COLLECTED_NOT_REVIEWED；发现胖丁属性缺少妖精、冰打草/钢没有给合成1倍等质量观察，不能报20/20通过，后续需逐项复核及运行版本核对。

首次采集器注销路径错误，报告保留LOGOUT_UNVERIFIED，不能将该轮视为清理完整的发布基线。已修正为实际/logout接口，另用artifacts/u0-cleanup-probe-20261113的一题探针验证cleanupErrors为空；不改写原记录。旧账号无持久凭据，留待本机账户维护，不触碰其他用户数据。

U0仍待：完整判据/片段标签复核、供应商usage提取、运行类指纹比对、正式清理完整的基线采集及阈值冻结。U1只启动默认不启用的代码基础，不以此绕过U0验收门禁。
