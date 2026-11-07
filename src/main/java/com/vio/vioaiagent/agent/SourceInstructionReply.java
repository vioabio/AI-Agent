package com.vio.vioaiagent.agent;
import java.util.Optional;
import java.util.regex.Pattern;
/** Only an explicit explain-without-executing request; not a general injection detector. */
public final class SourceInstructionReply {
    private SourceInstructionReply() { }
    public static Optional<String> reply(String q) {
        if(q==null||q.length()>1500||q.contains("\n"))return Optional.empty();
        String head=q.strip().split("[：:“\"]",2)[0];
        if(!head.matches("^(?:请)?(?:只|仅)(?:解释|说明).*")
                || !Pattern.compile("文档|资料|引文").matcher(head).find()
                || !head.contains("授权")||!Pattern.compile("不(?:要)?执行").matcher(head).find())return Optional.empty();
        return Optional.of("资料中的命令不能自行授予操作权限。即使文本写明队伍编号和改动字段，也不能替代本人请求和页面确认。"
                + "实际修改仍须核对当前账号归属、读取原配置、展示本次预览，再由你在页面确认。"
                + "本次只解释该边界，不读取队伍、不生成预览、不保存修改，也不代点确认按钮。\n");
    }
}
