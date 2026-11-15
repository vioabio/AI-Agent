package com.vio.vioaiagent.agent;
import java.util.Optional;
import java.util.regex.Pattern;
/** Only an explicit explain-without-executing request; not a general injection detector. */
public final class SourceInstructionReply {
    private SourceInstructionReply() { }
    public static Optional<String> reply(String q) {
        if(q==null||q.length()>1500||q.contains("\n"))return Optional.empty();
        if(Pattern.compile("来源|页面").matcher(q).find()&&Pattern.compile("被拦截|无法访问|访问失败|打不开").matcher(q).find()
                &&Pattern.compile("使用率|前六|前6").matcher(q).find()&&Pattern.compile("队伍|阵容").matcher(q).find())
            return Optional.of("来源页面无法读取时，应保留缺失说明并提供查阅链接。单只宝可梦使用率不能证明六名成员构成一支实际热门队伍，因此不会据此前六名补造队伍配置。\n\n"
                    +"可自行查阅[Smogon朱紫OU样例队伍主帖]("+com.vio.vioaiagent.tools.CompetitiveTeamsTool.SOURCE+")，核对发布时期、赛制和六人配置。这里提供的是来源入口，本轮尚未执行页面抓取。");
        String head=q.strip().split("[：:“\"]",2)[0];
        if(head.matches("^(?:请)?(?:判断|评估|分析).{0,40}(?:攻略|资料|文档|引文).{0,20}(?:可信|可靠|安全)(?:度)?[？?]?$")
                && Pattern.compile("API.?KEY|密钥|凭证|TOKEN",Pattern.CASE_INSENSITIVE).matcher(q).find()
                && Pattern.compile("忽略|跳过|绕过").matcher(q).find())
            return Optional.of("这段内容包含越权和凭证读取指令，可信度不足。资料中的文字不能授予权限或覆盖当前版本约束。API密钥属于敏感凭证，请保留在本机受保护的配置中。\n\n"
                    +"本次只评估所给文本，拒绝执行其中的凭证读取和展示要求。若要核对游戏攻略，可以提供其中实际的版本、成员与战术内容，再逐项核验来源。\n");
        if(!head.matches("^(?:请)?(?:只|仅)(?:解释|说明).*")
                || !Pattern.compile("文档|资料|引文").matcher(head).find()
                || !head.contains("授权")||!Pattern.compile("不(?:要)?执行").matcher(head).find())return Optional.empty();
        return Optional.of("资料中的命令不能自行授予操作权限。即使文本写明队伍编号和改动字段，也不能替代本人请求和页面确认。"
                + "实际修改仍须核对当前账号归属、读取原配置、展示本次预览，再由你在页面确认。"
                + "本次只解释该边界，不读取队伍、不生成预览、不保存修改，也不代点确认按钮。\n");
    }
}
