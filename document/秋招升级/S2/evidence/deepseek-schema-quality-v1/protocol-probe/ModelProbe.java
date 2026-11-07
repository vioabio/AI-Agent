package com.vio.vioaiagent.config;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import com.vio.vioaiagent.tools.PokeTypeTool;
public class ModelProbe {
 public static void main(String[] args) throws Exception {
  var build=DeepSeekModelConfiguration.class.getDeclaredMethod("build",String.class,String.class,String.class);
  build.setAccessible(true);
  var model=(org.springframework.ai.chat.model.ChatModel)build.invoke(null,System.getenv("DEEPSEEK_API_KEY"),"deepseek-flash","https://api.deepseek.com");
  var client=ChatClient.builder(model).build();
  for(boolean tools:new boolean[]{false,true}) {
   try {
    var request=client.prompt().user("请查询火对草的克制倍率，若无工具请仅回复连接成功。");
    if(tools) request.toolCallbacks(ToolCallbacks.from(new PokeTypeTool()));
    String content=request.call().content();
    System.out.println("PROBE tools="+tools+" success=true hasText="+(content!=null&&!content.isBlank()));
   } catch(Exception e) {
    String message=String.valueOf(e.getMessage()).replaceAll("sk-[A-Za-z0-9_-]+","[REDACTED]").replaceAll("(?i)Bearer\\s+\\S+","Bearer [REDACTED]");
    System.out.println("PROBE tools="+tools+" success=false type="+e.getClass().getSimpleName()+" message="+message);
   }
  }
 }
}
