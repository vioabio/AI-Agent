# 猎聘美妆人才自动搜索与页面采集项目

当前仓库已经恢复到迁移后的可运行状态，并覆盖了以下能力：

- Python 项目骨架与本地 `.venv`
- 统一配置模块与 JSON 日志
- `DeviceClient` 设备连接封装
- ADB 与 `uiautomator2` 诊断链路
- 页面状态识别与控件探索
- 冻结品牌/岗位搜索任务生成
- 搜索只读流程、结果页筛选流程
- 候选人详情页“展开全部 + 滑动到底 + 简历采集”
- 基于历史样本的单元测试
- 一键迁移恢复与自检脚本

## 当前阶段

当前已具备：

- 首页、搜索页、结果页、详情页的真机回归能力
- 候选人详情页采集后的结构化 JSON 输出
- 迁移后环境恢复、自检、回归验证能力

当前不包含：

- 自动沟通
- 验证码绕过
- 私有接口逆向

## Windows 环境准备

1. 创建并激活虚拟环境

```powershell
C:\Users\MCsir\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -m venv .venv
.\.venv\Scripts\Activate.ps1
```

2. 安装依赖

```powershell
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -e .[dev]
```

3. 准备 `.env`

```powershell
Copy-Item .env.example .env
```

4. 检查 ADB

```powershell
adb version
adb devices -l
```

## 迁移恢复

迁移到新目录后，优先执行：

```powershell
.\scripts\rebuild_venv.ps1
.\.venv\Scripts\python.exe scripts\migration_selfcheck.py
```

如果你只想跳过详情页采集阶段：

```powershell
.\.venv\Scripts\python.exe scripts\migration_selfcheck.py --skip-live-profile
```

迁移 runbook 见 [MIGRATION_RUNBOOK.md](/D:/lx/jianlizhuanqu/docs/MIGRATION_RUNBOOK.md)。

## 数据库连接方式

当前项目约定使用 MySQL + `pymysql`，并从环境变量读取连接信息。

### 环境变量

```env
MYSQL_HOST=your-host
MYSQL_PORT=3306
MYSQL_USER=your-user
MYSQL_PASSWORD=your-password
MYSQL_DATABASE=phone-rpa
MYSQL_CHARSET=utf8mb4
```

### Python 连接示例

```python
from liepin_rpa.config import get_settings
from liepin_rpa.db import build_mysql_connection_kwargs

settings = get_settings()
kwargs = build_mysql_connection_kwargs(settings)

# import pymysql
# conn = pymysql.connect(**kwargs)
```

## 设备诊断

```powershell
.\.venv\Scripts\python.exe scripts\device_diagnose.py
```

如需指定设备：

```powershell
$env:DEVICE_ID="your-device-serial"
.\.venv\Scripts\python.exe scripts\device_diagnose.py
```

输出默认写入 `artifacts/device_diagnose/`。

## 页面识别与控件探索

检测当前 XML 样本：

```powershell
.\.venv\Scripts\python.exe scripts\inspect_current_page.py --xml-path outputs\navigation_signatures\03_result_list\hierarchy.xml
```

探索当前设备页面控件：

```powershell
.\.venv\Scripts\python.exe scripts\explore_current_controls.py --output-dir outputs\control_explorer\current_page
```

采集当前页面样本：

```powershell
.\.venv\Scripts\python.exe scripts\capture_page_sample.py --name home_recommend
```

选择器配置目录：

```text
config/selectors/
  common.yaml
  home.yaml
  jobs.yaml
  search.yaml
  result.yaml
  profile.yaml
  chat.yaml
  exceptions.yaml
```

## 冻结业务配置

品牌与岗位关键词固定维护在：

```text
config/brands.yaml
config/roles.yaml
```

当前约束：

- 只使用 53 个品牌关键词
- 只使用 12 个岗位关键词
- 不自动扩词
- 默认任职范围是 `CURRENT_AND_FORMER`
- 当前组合总数为 `53 x 12 = 636`

预览搜索任务：

```powershell
.\.venv\Scripts\python.exe scripts\preview_search_tasks.py
```

## 真机流程脚本

只读搜索流程：

```powershell
.\.venv\Scripts\python.exe scripts\run_search_workflow.py --brand 珀莱雅 --role 达播总监 --output-dir outputs\search_workflow
```

结果页筛选流程：

```powershell
.\.venv\Scripts\python.exe scripts\filter_current_result_page.py --brand 珀莱雅 --role 达播总监 --output-dir outputs\result_filter
```

详情页简历采集：

```powershell
.\.venv\Scripts\python.exe scripts\collect_current_profile_resume.py --brand 珀莱雅 --role 达播总监 --search-task-id task_000123 --output-dir outputs\profile_collection\current_profile
```

## 候选人简历 JSON 输出

`collect_current_profile_resume.py` 现在默认输出两类文件：

- `report.json`
  结构化候选人简历，遵循候选人简历 JSON 方案
- `collection_debug.json`
  原始采集调试信息，保留每次滑动、截图、层级 XML 与可见文本证据

### `report.json` 结构

```json
{
  "schema_version": "1.0",
  "candidate_id": "candidate_xxxxxxxx",
  "source": {
    "platform": "liepin",
    "collected_at": "2026-07-08T14:30:00+08:00",
    "search_task_id": "task_000123",
    "search_brand_keyword": "珀莱雅",
    "search_role_keyword": "达播总监"
  },
  "basic_info": {
    "name": "李女士",
    "gender": null,
    "age": 37,
    "city": "上海",
    "work_years": 14,
    "education": "本科",
    "current_company": "珀莱雅",
    "current_position": "抖音达播负责人",
    "expected_position": "销售总监",
    "resume_updated_at": null
  },
  "work_experiences": [],
  "education_experiences": [],
  "skills": [],
  "self_description": "...",
  "raw_resume": {
    "full_text": "...",
    "screenshots": [],
    "xml_files": []
  },
  "parse_metadata": {
    "parse_status": "SUCCESS",
    "parser_version": "resume-parser-1.0.0",
    "warnings": [],
    "requires_manual_review": false
  }
}
```

### 规则

- 单值缺失字段输出 `null`
- 列表缺失字段输出 `[]`
- 日期优先使用 `YYYY-MM`
- 采集时间使用 ISO 8601
- 每段工作经历独立输出公司、岗位、时间、职责、业绩和页面证据
- 原始全文、截图路径、XML 路径继续保留在 `raw_resume`

详细规范来源于你提供的“候选人简历代码输出 JSON 规范”，当前实现已经按这个方向输出。

## 运行测试

```powershell
.\.venv\Scripts\python.exe -m pytest
```

当前测试覆盖：

- 冻结任务生成数量
- 页面识别样本
- 结果页候选卡片抽取
- 详情页文本/展开控件解析
- 候选人简历 schema 构建

## 目录结构

```text
src/liepin_rpa/                核心源码
src/liepin_rpa/device/         ADB 与设备连接封装
src/liepin_rpa/page_detection/ 页面识别
src/liepin_rpa/search_execution/ 搜索与结果页流程
src/liepin_rpa/profile_collection/ 详情页采集与简历 JSON 生成
scripts/                       脚本入口
tests/unit/                    单元测试
artifacts/                     设备诊断产物
outputs/                       真机回归和页面采样输出
logs/                          运行日志
docs/                          迁移与流程说明
```
