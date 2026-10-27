package com.vio.vioaiagent.agent;

import java.util.regex.Pattern;

/** Bounded raw-question classification; attached facts, saved preferences and write tasks are not instructions. */
public final class ProjectGuideRequest {
    public static final String VERSION="project-source-request-v2";
    public static final String ORIGIN="PROGRAM_PROJECT_SOURCE";
    public static final String SOURCE_PRESENTATION="TOP_DOCUMENT_FULL_TEXT_MAX_2000_CODEPOINTS";
    public static final int MAX_SOURCE_CODE_POINTS=2000;
    private static final Pattern PROJECT=Pattern.compile("本项目|当前项目|这个项目|本系统|这个助手|我们的项目");
    private static final Pattern EXPLAIN=Pattern.compile("^(?:请帮我|请|麻烦|帮我|我想|想|能否|可以|只|仅)?(?:用.{0,12})?(?:说明|解释|介绍|了解|讲解|讲讲|告诉我)|(?:有什么区别|有何区别|是什么关系|如何处理|怎么处理)");
    private static final Pattern TOPIC=Pattern.compile("种族值|努力值|个体值|性格|(?:等级|培养).{0,20}默认|默认.{0,20}(?:等级|培养)|队伍.{0,45}(?:预览|确认|保存|取消|并发)|(?:预览|确认).{0,30}队伍");
    private static final Pattern ACTION=Pattern.compile("readCurrentTeam|previewTeamChanges|calculateBattleDamage|(?:请|帮我)(?:先|再)?(?:读取|修改|保存|调整|生成)|(?:把|将).{0,40}(?:改为|改成|设为|调到|保存)");
    private static final Pattern DATA=Pattern.compile("```|(?:^|\\n)\\s*[\\[{]|(?:以下|下面|附上).{0,16}(?:资料|接口|工具|数据|原文|引用)");
    private ProjectGuideRequest(){ }
    public static boolean requiresSource(String rawQuestion) {
        if(rawQuestion==null||rawQuestion.isBlank()||rawQuestion.length()>500)return false;
        String text=rawQuestion.strip();
        String head=text.substring(0,Math.min(100,text.length()));
        return PROJECT.matcher(head).find()&&EXPLAIN.matcher(head).find()&&TOPIC.matcher(text).find()
                &&!ACTION.matcher(text).find()&&!DATA.matcher(text).find();
    }
}
