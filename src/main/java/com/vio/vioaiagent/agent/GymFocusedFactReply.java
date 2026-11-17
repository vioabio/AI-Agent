package com.vio.vioaiagent.agent;

import java.util.Optional;

/** A focused NPC ace lookup, with identity/stage from the existing conversation plan. */
public final class GymFocusedFactReply {
    private GymFocusedFactReply() { }
    static boolean requestsSpecialty(String question) {
        return question != null && question.length() <= 100 && focusedText(question).matches(
                "^(?:请)?(?:(?:只|仅)(?:回答|告诉我))?(?:阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏|这位馆主|这个馆主|他|她)"
                + "(?:的道馆|的|道馆)?(?:擅长|使用|主打)(?:什么|哪种|哪一种|哪一类)?属性(?:是什么)?[？?。！!\\s]*$");
    }
    private static String focusedText(String question) {
        return question.strip().replaceFirst("^(?:请)?(?:只|仅)(?:用)?(?:一|1)(?:句话|句)(?:回答)?[，,：:]?", "")
                .replaceFirst("[。？?；;](?:不要|不用|无需)(?:列出|列|展开)(?:队伍|阵容|其他内容)[。！!]*$", "");
    }
    static boolean requestsHighestLevel(String question) {
        return question != null && question.length() <= 150
                && question.matches("(?s).*(最高等级|等级最高).*")
                && !question.matches("(?s).*(伤害|计算|打法|策略|我方|我的|我当前|当前账号|已保存|保存的|推荐|出招|修改|预览).*" )
                && !question.matches("(?s).*(不要|不看|无需).{0,4}(最高等级|等级最高).*" );
    }
    static boolean npcLevelFollowup(com.vio.vioaiagent.run.AgentRunContext context) {
        if (!requestsHighestLevel(context.run().prompt())) return false;
        if (!GymConversationContext.names(context.run().prompt()).isEmpty()) return true;
        var history = context.dialogueHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (!(history.get(i) instanceof org.springframework.ai.chat.messages.UserMessage)) continue;
            String previous = history.get(i).getText();
            if (previous.matches("(?s).*(我的|我当前|当前账号|已保存|保存的).{0,40}(队伍|阵容).*")) return false;
            if (!GymConversationContext.names(previous).isEmpty() || requestsBattleMode(previous)) return true;
            if (previous.matches("(?s).*读取.{0,40}(队伍|阵容).*")) return false;
        }
        return false;
    }
    static boolean requestsBattleMode(String question) {
        if (question == null || question.length() > 100) return false;
        String focused = question.strip()
                .replaceFirst("^(?:现在|当前)?(?:切回|切换到|改看)(?:初战|视察重赛|重赛)[，,。 ]*", "")
                .replaceFirst("[？?，,。 ]*(?:只|仅)给结论[。！!]*$", "");
        return focused.matches("(?s).*(?:只|仅)回答对战模式[。？?！!]*")
                || focused.matches(
                "^(?:请问)?(?:这场|这次对战|这场对战|这个馆主|这位馆主|他|她|阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏)"
                + "(?:的)?(?:初战|视察重赛|重赛)?(?:是|采用|使用)(?:单打还是双打|单打或双打|什么对战模式|哪种对战模式)(?:呢|吗)?[？?。]?$" );
    }
    public static Optional<String> reply(GymTurnPlan plan,String question){
        if(plan.intent()!=GymTurnPlan.Intent.CATALOG||plan.target().isEmpty()||question==null||question.length()>300
                ||question.contains("\n")||question.contains("```"))return Optional.empty();
        if(question.matches("(?:当前|现在|刚才)(?:谈的|讨论的|说的|看的)(?:是)?(?:哪位|哪个)馆主[？?，,。 ]*(?:只回答名字[。！! ]*)?"))
            return Optional.of(plan.target().orElseThrow().name());
        if (question.matches("(?s).*(前面|上文|之前|刚才).*(目标|馆主).*(名字|姓名|属性).*")) return specialty(plan);
        if (question.matches("(?s).*(王牌|太晶成员).*(多少级|几级|等级).*") && GymConversationContext.comparesStages(question))
            return compareAceLevels(plan);
        if (requestsBattleMode(question)) return battleMode(plan);
        if (requestsSpecialty(question)) return specialty(plan);
        if(requestsHighestLevel(question))
            return highestLevels(plan, question);
        if(!question.matches("(?s).*(太晶成员|太晶宝可梦|王牌).*" )
                ||question.matches("(?s).*(招式|特性|伤害|为什么|为何|机制|原因|对比|比较|以及|同时|还有|全部|完整|整队配置).*" )
                ||question.matches("(?s).*(不要|不看|无需|别).{0,8}(太晶|王牌).*"))return Optional.empty();
        var target=plan.target().orElseThrow();
        if(!"pokemon-scarlet-violet".equals(plan.gameReleaseId())||!java.util.Set.of("initial","rematch").contains(target.phase())||!GymConversationContext.IDS.contains(target.gymId()))return Optional.empty();
        try{
            var catalog=GymCatalogAnswer.load(target.phase());com.fasterxml.jackson.databind.JsonNode selected=null;
            for(var gym:catalog.path("gyms"))if(target.gymId().equals(gym.path("id").asText()))selected=gym;
            if(selected==null)throw new IllegalArgumentException();
            var aces=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for(var row:selected.path("opponents"))if(row.hasNonNull("teraType"))aces.add(row);
            if(aces.size()!=1)throw new IllegalArgumentException();
            var ace=aces.getFirst();var types=new java.util.ArrayList<String>();ace.path("types").forEach(t->types.add(t.asText()));
            String name=ace.path("nameZh").asText(ace.path("name").asText());
            return Optional.of("### "+target.name()+" · "+(target.phase().equals("initial")?"初战":"视察重赛")+"太晶成员\n\n"
                    +"是 **"+EvidenceText.inline(name)+"**，资料等级 **"+ace.path("level").asInt()+"**；原属性 **"+String.join("／",types)+"**，太晶属性 **"+ace.path("teraType").asText()+"**。\n\n"
                    +(target.defaultStage()?"未明确阶段，先按初战资料回答。\n\n":"")
                    +(target.phase().equals("rematch")?"查看资料不代表已解锁视察重赛。\n\n":"")
                    +"来源：[馆主资料](https://www.serebii.net/scarletviolet/"+(target.phase().equals("initial")?"gyms":"gymrematches")+".shtml)，随包核对日期 "+catalog.path("reviewedAt").asText()+"。");
        }catch(Exception unavailable){return Optional.of("本次未能从已校验的目标馆主资料唯一确认太晶成员，不使用模型记忆补写。");}
    }

    private static Optional<String> compareAceLevels(GymTurnPlan plan) {
        try {
            var answer = new StringBuilder(plan.target().orElseThrow().name() + "的王牌等级对比\n\n");
            for (String phase : java.util.List.of("initial", "rematch")) {
                var catalog = GymCatalogAnswer.load(phase);
                for (var gym : catalog.path("gyms")) {
                    if (!gym.path("id").asText().equals(plan.target().orElseThrow().gymId())) continue;
                    for (var foe : gym.path("opponents")) if (foe.hasNonNull("teraType"))
                        answer.append(phase.equals("initial") ? "初战" : "视察重赛").append("：")
                                .append(foe.path("nameZh").asText()).append(" ").append(foe.path("level").asInt()).append("级。\n");
                }
            }
            return Optional.of(answer + "\n来源 [初战资料](https://www.serebii.net/scarletviolet/gyms.shtml)及[视察重赛资料](https://www.serebii.net/scarletviolet/gymrematches.shtml)。");
        } catch (Exception unavailable) { return Optional.of("本次未取得两阶段可核对的王牌资料，请重试。"); }
    }

    private static Optional<String> specialty(GymTurnPlan plan) {
        var target = plan.target().orElseThrow();
        try {
            var catalog = GymCatalogAnswer.load(target.phase());
            for (var gym : catalog.path("gyms")) {
                if (!target.gymId().equals(gym.path("id").asText())) continue;
                String type = gym.path("theme").asText();
                if (type.isBlank()) break;
                return Optional.of(target.name() + "擅长**" + EvidenceText.inline(type) + "属性**。"
                        + "来源 [Serebii馆主资料](https://www.serebii.net/scarletviolet/"
                        + (target.phase().equals("rematch") ? "gymrematches" : "gyms")
                        + ".shtml)，随包核对日期 " + catalog.path("reviewedAt").asText() + "。");
            }
        } catch (Exception unavailable) { /* Only the verified catalog can supply this field. */ }
        return Optional.of("本次未能从目标馆主资料确认擅长属性，请重试资料读取。");
    }

    private static Optional<String> battleMode(GymTurnPlan plan) {
        var target = plan.target().orElseThrow();
        try {
            var catalog = GymCatalogAnswer.load(target.phase());
            for (var gym : catalog.path("gyms")) {
                if (!target.gymId().equals(gym.path("id").asText())) continue;
                String mode = gym.path("battleMode").asText();
                if (!java.util.Set.of("Singles", "Doubles").contains(mode)) break;
                return Optional.of("**" + target.name() + "的" + (target.phase().equals("rematch") ? "视察重赛" : "初战")
                        + "是" + (mode.equals("Doubles") ? "双打" : "单打") + "。**\n\n"
                        + "来源：[Serebii馆主资料](https://www.serebii.net/scarletviolet/"
                        + (target.phase().equals("rematch") ? "gymrematches" : "gyms")
                        + ".shtml)，随包核对日期 " + catalog.path("reviewedAt").asText() + "。\n"
                        + (target.defaultStage() ? "未明确阶段，先按初战资料回答。" : ""));
            }
            return Optional.of("本次未能从目标馆主资料确认对战模式，请重试资料读取。");
        } catch (Exception unavailable) {
            return Optional.of("本次未能从已校验的馆主资料读取对战模式，请重试。");
        }
    }

    private static Optional<String> highestLevels(GymTurnPlan plan, String question) {
        var target = plan.target().orElseThrow();
        boolean both = question.contains("初战") && question.contains("重赛")
                && !question.matches("(?s).*(不要初战|不看初战|只看重赛|不要重赛|不看重赛|只看初战).*" );
        var phases = both
                ? java.util.List.of("initial", "rematch") : java.util.List.of(target.phase());
        try {
            var answer = new StringBuilder("### " + target.name() + " · 最高等级\n\n");
            for (String phase : phases) {
                var catalog = GymCatalogAnswer.load(phase);
                com.fasterxml.jackson.databind.JsonNode selected = null;
                for (var gym : catalog.path("gyms"))
                    if (target.gymId().equals(gym.path("id").asText())) selected = gym;
                if (selected == null || selected.path("opponents").isEmpty()) throw new IllegalStateException();
                int maximum = 0;
                for (var opponent : selected.path("opponents")) maximum = Math.max(maximum, opponent.path("level").asInt());
                if (maximum < 1) throw new IllegalStateException();
                var names = new java.util.ArrayList<String>();
                for (var opponent : selected.path("opponents"))
                    if (opponent.path("level").asInt() == maximum)
                        names.add(EvidenceText.inline(opponent.path("nameZh").asText(opponent.path("name").asText())));
                answer.append("- **").append(phase.equals("initial") ? "初战" : "视察重赛")
                        .append("**：").append(maximum).append("级，成员为").append(String.join("、", names)).append("。")
                        .append("[资料来源](https://www.serebii.net/scarletviolet/")
                        .append(phase.equals("initial") ? "gyms" : "gymrematches")
                        .append(".shtml)，随包核对日期 ").append(catalog.path("reviewedAt").asText()).append("。\n");
            }
            return Optional.of(answer.toString());
        } catch (Exception unavailable) {
            return Optional.of("本次未能从已校验的目标馆主资料确认最高等级，请重试资料读取。");
        }
    }
}
