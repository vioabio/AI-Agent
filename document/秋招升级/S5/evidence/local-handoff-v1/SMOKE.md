# 隔离的无模型基础启动

先完成HANDOFF.md的安装、预检、导入及打包。在本目录的PowerShell执行下列命令；18259被占用时先核对归属，不结束别人的进程。此命令使用无效模型Key，只用于登录/缓存图鉴/队伍/引擎基础检查，不用于真实问答。

~~~powershell
& "$env:JAVA_HOME\bin\java.exe" -jar target/vio-ai-agent-0.0.1-SNAPSHOT.jar '--spring.config.location=classpath:/application.yml,classpath:/application-local.example.yml' --spring.profiles.active=local --server.address=127.0.0.1 --server.port=18259 --spring.ai.dashscope.api-key=smoke-placeholder-not-real-key "--vio.pokemon.reference.path=$PWD/pokeapi-cache" --vio.pokemon.reference.proxy=http://127.0.0.1:9 '--vio.http.cors.allowed-origins[0]=http://localhost:3000' '--vio.http.cors.allowed-origins[1]=http://127.0.0.1:13059' --spring.ai.mcp.client.enabled=false --vio.tools.search.enabled=false --vio.tools.high-risk.enabled=false --vio.agent.v1.enabled=false --vio.run.durable-resume=false --vio.run.model-cache.enabled=false
~~~

另开本目录终端执行 npm --prefix vio-ai-agent-frontend run dev:demo，访问 http://127.0.0.1:13059 。仅验证固定资料和基础操作，缺少的招式/道具等资源会明确失败。停止时在各自前台终端按Ctrl+C。不要在后端运行期间重新编译。

真实问答需要另行在本机配置受支持模型，此交接目录没有私有模型profile或Key。不要把本命令冒充真实模型验收。
