# S0：冻结范围与建立基线

日期：2026-10-23。S0 负责把已有能力、配置、演示输入和真实问题固定下来，不把“能跑完”当成“答案正确”。本阶段不实现 S1 的 Agent 确认写入，也不提前宣称完成 S2 质量评测。

## 交付文件

| 文件 | 用途 |
|---|---|
| [baseline.json](baseline.json) | 固定 profile、模型配置、提示词来源、版本和工具范围 |
| [cases.json](cases.json) | 三条固定任务、期望结果、完整队伍与伤害输入 |
| [验证记录](验证记录_2026-10-23.md) | 实际环境、成功与失败证据、人工复查、遗留问题 |
| [HTTP 基线脚本](../../../tools/campus/s0-baseline.mjs) | 本地注册登录、图鉴、队伍回读、计算和可选真实模型任务 |
| [独立目录验证脚本](../../../tools/campus/s0-clean-room.ps1) | 复制非忽略源码，重新安装、构建、启动和验证；自动停止自己启动的进程 |

## 1. 冻结范围

| 状态类别 | 能力与证据 | 边界 |
|---|---|---|
| 本地演示已验证 | 真实 HTTP 注册/登录/退出、队伍保存和重新登录后回读；独立目录构建启动；真实图鉴 API、本地伤害引擎 | 使用专用演示账号，不读取旧账号资料 |
| 本地执行已验证，回答质量需改进 | 三条真实模型任务均完成并观察到所需工具；历史正文回读一致 | 队伍建议有错误机制结论；不能据完成状态宣传准确率 |
| 有实现待专门实测 | 版本化知识门面、混合检索、PGVector、生产持久化、租约/恢复扩展、真实 usage/费用采集 | 默认本地配置未启用完整生产或向量链路 |
| 实验兼容模块 | 多智能体、自研 MCP、旧入口 | 不作为本轮面试主线，也不宣传已验收集群 |
| 本轮规划 | S1 读取队伍→预览→确认保存；S2 正式评测；S3 资料检索；S4 成本上下文；S5 展示材料 | 本阶段只固定边界，不开发后续业务 |

默认演示工具：`lookupPokemonReference`、`lookupPokemonResource`、`calculateBattleDamage`、`searchPokemonGuides`、`checkTypeEffectiveness`、`getTypeChart`、`doTerminate`。搜索、高风险工具、MCP、版本化知识门面、模型缓存均不作为 S0 必需依赖。内置攻略仍为 4 篇原创笔记与关键词检索。

## 2. 从新目录启动

环境：JDK 21；本次 Node.js 24.19.0 / npm 11.17.0；Wrapper Maven 3.9.16。Windows 使用 PowerShell。已有机器上的 JDK 和 Node 是显式前置条件，独立目录验证不等同于新装操作系统。

```powershell
git clone https://github.com/vioabio/AI-Agent.git
cd AI-Agent
npm --prefix tools/pokemon-engine ci
npm --prefix vio-ai-agent-frontend ci
if (!(Test-Path src/main/resources/application-local.yml)) {
  Copy-Item src/main/resources/application-local.example.yml src/main/resources/application-local.yml
}

$env:DEEPSEEK_API_KEY = '在本机填写，不能提交或发到聊天中'
$env:DEEPSEEK_MODEL = 'deepseek-flash'
$env:VIO_TOOLS_SEARCH_ENABLED = 'false'
$env:VIO_TOOLS_HIGH_RISK_ENABLED = 'false'
$env:VIO_MCP_ENABLED = 'false'
$env:VIO_POKEMON_REFERENCE_ENABLED = 'true'
$env:VIO_DAMAGE_ENGINE_ENABLED = 'true'
$env:VIO_LOCAL_GUIDES_ENABLED = 'true'
$env:VIO_LOCAL_ACCOUNTS_ENABLED = 'true'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,deepseek"
```

在另一个项目根目录终端执行 `npm --prefix vio-ai-agent-frontend run dev`，访问 `http://localhost:3000`。后端为 `http://localhost:8123/api`。注册专用演示账号、保存恢复码并登录即可。

模型 ID 是选定的配置基线，不是对供应商当前权限/可用性的保证。如账号只支持其他模型，修改 `DEEPSEEK_MODEL`，并在演示报告注明；不要把源文件默认值当成已运行进程模型 ID 的证据。真实 Key、数据库与缓存均保留在本机忽略目录。

## 3. 三条固定演示

### S0-01 图鉴查询

输入：请调用图鉴工具查询 squirtle（杰尼龟），用中文说明属性、种族值及来源边界。只介绍已查到的事实，不判断当前热门程度。

预期：调用 `lookupPokemonReference`；水属性，种族值与图鉴接口逐项一致；不宣称实时热门程度或比赛合法性。记录工具失败与额外无来源断言。中文名解析依赖索引，固定英文 slug 可避免把名称转换和问答质量混在一起。

### S0-02 队伍保存、回读与分析

1. 查询杰尼龟、小火龙，各加入一个队伍槽位。
2. 按 [cases.json](cases.json) 填写性格、特性、道具、四个招式、等级、EV/IV，保存为“S0 休闲练习队伍”。这些配置是待讨论的练习输入，不预先认定招式合法或阵容合理。
3. 退出再登录，确认配置没有丢失，点击“让 AI 分析”。也可复制 cases.json 中的固定问题。
4. 预期读取两名成员资料，指出条件缺口；只提建议，不自动修改队伍。保存/回读是程序验证，建议是否正确要人工对照证据。

目前 Agent 并没有“读取当前账号保存队伍并确认写入变更”的完整闭环。该任务由界面/脚本先保存和提供配置，再触发模型分析；不能把它宣传为已经完成 S1。

### S0-03 伤害计算

输入条件：第九世代、单打；杰尼龟用 `surf` 攻击小火龙；双方 50 级、满 HP、IV 均 31，EV 为 HP4/特攻252/速度252，其余为 0；攻击方 `modest/torrent/leftovers`，防御方 `timid/blaze/leftovers`；无天气/地形/强化/状态/墙/场地伤害/太晶。完整输入见 cases.json。

预期：调用 `calculateBattleDamage`；本地 `@smogon/calc 0.12.0` 本次固定输入返回 164～194 HP、防御方最大 HP115、约142.61%～168.70%。该结果只适用于上述完整条件，不是胜率或合法性证明。不能仅展示一段模型生成的数字。

## 4. 自动采集

只验证当前本地服务的 API（不请求模型）：

```powershell
node tools/campus/s0-baseline.mjs --base-url http://127.0.0.1:8123/api --output artifacts/campus/s0/my-api-baseline
```

需要真实问答时增加 `--with-model`，会产生模型调用费用；必须使用自己授权运行的本地演示实例：

```powershell
node tools/campus/s0-baseline.mjs --base-url http://127.0.0.1:8123/api --with-model --model-id deepseek-flash --runtime-source your-build-id --output artifacts/campus/s0/my-model-baseline
```

`--model-id`、`--runtime-source` 是操作者声明，不是脚本从供应商或运行包自动认证的值；未确认时保留默认 `unverified-*`。仅复查一条任务可加 `--case S0-01-reference`。

脚本只允许本机 HTTP 地址，拒绝重定向和覆盖已有 report。每次新建专用账号，不认领旧资料；密码、恢复码、Cookie 不进入报告。演示账号与数据仍留在目标本地实例，不自动删除；建议针对隔离目录执行。原始事件含演示问题与答案，应按本地测试数据保管，不把真实用户数据混入报告。

完全独立源码目录、空 npm 缓存和空 Maven 仓库验证：

```powershell
.\tools\campus\s0-clean-room.ps1 -FreshMavenRepository -WarmReferenceData
```

脚本不拷贝 `.local-data`、`application-local.yml`、`target`、`node_modules`，重新安装依赖并编译，使用 18123/13000 端口启动隔离服务。`-WarmReferenceData` 使用已有预热脚本从官方 PokéAPI 当次下载杰尼龟、小火龙的资料，不使用开发机旧缓存；首次网络读取不稳定时可选用此步骤。模型仅用明确标注的启动占位值，不请求真实模型；真实模型报告必须另外采集。无参数时允许复用用户级 Maven 下载缓存，报告会明确标注，不能冒充空 Maven 仓库验收。

## 5. 故障处理和验收含义

| 故障 | 应观察到的行为 |
|---|---|
| 未配置模型 Key | 启动失败并明确提示 `DEEPSEEK_API_KEY`，不伪装模型可用 |
| 网络不可达或资料无缓存 | 显示事实读取失败；不能用猜测资料替代真实返回 |
| 数据版本不匹配 | 拒绝读取，不能为了成功擅自切换版本 |
| 不支持的招式/计算条件 | 计算失败并说明配置问题，不编造伤害数值 |
| 搜索未配置或失败 | 独立搜索不是模型 Key 能替代的；默认演示关闭搜索，不能假装已验证最新资讯 |
| Run 超时 | 保留未完成标记与已输出内容，不冒充成功 |

S0 完成的是范围冻结、可运行步骤和带失败样例的基线。真实模型答案仍需人工复核；下一步优先处理验证记录中的 P0 答案可信性问题，再推进 S1 任务闭环，不靠降低质量标准取得“通过”。
