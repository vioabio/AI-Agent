# 本地安装交接

这是公开源码与固定资料快照，不含模型Key、账号数据库、node_modules、JDK或构建产物。打开本目录的PowerShell。

1. 安装JDK21（含javac）、设置JAVA_HOME；准备Node20.x至少20.19或22.12及以上与npm。
2. 执行 npm --prefix tools/pokemon-engine ci 和 npm --prefix vio-ai-agent-frontend ci。
3. 执行 node tools/campus/demo-readiness.mjs --bundle reference-bundle --out readiness-new.json。未安装依赖时返回2并提示缺项；报告必须是新文件。
4. 执行 node tools/reference-cache.mjs import --bundle reference-bundle --out pokeapi-cache。目标必须不存在，资料读取日期保留原值。
5. 执行 .\mvnw.cmd -s settings/public-user-settings.xml -gs settings/public-global-settings.xml -Dmaven.test.skip=true package。本交接目录不含测试源码，不能声称执行了后端测试。
6. 本地模型设置请在本机环境中配置，不把Key写入交接目录；公开application-local.example.yml是配置参考。无有效Key时不能验收真实问答。
7. 前端 npm --prefix vio-ai-agent-frontend run dev；后端默认8123/api。启动前检查是否已有服务，勿结束不属于自己的进程。

需要重新下载资料时使用 tools/campus/prepare-demo-reference.mjs 的新artifacts目录；有明确本机代理时可用 download-demo-via-proxy.mjs --proxy 参数（需Node支持--use-env-proxy）。不要把附带快照当作以后重新联网下载。

预检通过仅证明公开输入与安装元数据，不代表已构建、已启动、模型回答正确或全新机器验收。
