import com.vio.vioaiagent.VioAiAgentApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
public class AppProbe {
 public static void main(String[] args) {
  try(var context=SpringApplication.run(VioAiAgentApplication.class,args)) {
   var model=context.getBean(ChatModel.class);
   var tools=context.getBean("allTools",ToolCallback[].class);
   System.out.println("PROBE registeredTools="+tools.length);
   try {
    var response=ChatClient.builder(model).build().prompt().user("请查询杰尼龟的种族值，只使用提供的查询工具。").toolCallbacks(tools).call().chatResponse();
    System.out.println("PROBE success=true toolCalls="+response.getResult().getOutput().getToolCalls().size());
   }catch(Exception e) {
    var message=String.valueOf(e.getMessage()).replaceAll("sk-[A-Za-z0-9_-]+","[REDACTED]").replaceAll("(?i)Bearer\\s+\\S+","Bearer [REDACTED]");
    System.out.println("PROBE success=false type="+e.getClass().getSimpleName()+" message="+message);
   }
  }
 }
}
