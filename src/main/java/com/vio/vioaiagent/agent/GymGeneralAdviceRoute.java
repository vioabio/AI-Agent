package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.AgentRunContext;
import com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator;
import java.util.*;

/** Contextual generic advice uses the verified local type table, not guessed acquisition data. */
public final class GymGeneralAdviceRoute {
    private GymGeneralAdviceRoute() { }
    public static Optional<String> reply(AgentRunContext context){
        String question=context.run().prompt();
        if(!"pokemon-scarlet-violet".equals(context.run().gameReleaseId())||GymConversationContext.savedTeamRequest(question)||GymQuestionPolicy.wantsDamage(question)
                ||!Set.of(GymTurnPlan.Intent.RECOMMEND_TEAM,GymTurnPlan.Intent.GENERAL_ADVICE).contains(GymTurnPlan.classify(question))||question.matches("(?is).*(Lv\\.?\\s*\\d+|\\d+\\s*级|\"level\"\\s*:).*"))return Optional.empty();
        var target=GymConversationContext.resolve(question,context.dialogueHistory());if(target.isEmpty())return Optional.empty();
        try{
            var selection=target.get();com.fasterxml.jackson.databind.JsonNode gym=null;for(var row:GymCatalogAnswer.load(selection.phase()).path("gyms"))if(row.path("id").asText().equals(selection.gymId()))gym=row;
            if(gym==null)return Optional.empty();
            var calculator=new TypeEffectivenessCalculator();String[] types={"一般","火","水","电","草","冰","格斗","毒","地面","飞行","超能力","虫","岩石","幽灵","龙","恶","钢","妖精"};
            var attacks=new ArrayList<String>();for(String attack:types){boolean strong=true;for(var foe:gym.path("opponents")){var defending=new ArrayList<String>();if(foe.hasNonNull("teraType"))defending.add(foe.path("teraType").asText());else foe.path("types").forEach(t->defending.add(t.asText()));double effect=calculator.calculate(attack,defending.toArray(String[]::new));if(attack.equals("地面")&&foe.path("ability").asText().equalsIgnoreCase("Levitate"))effect=0;if(effect<2)strong=false;}if(strong)attacks.add(attack);}
            var resistances=new ArrayList<String>();for(String defending:types){double effect=calculator.calculate(gym.path("theme").asText(),defending);if(effect<1)resistances.add(defending+"（"+effect+"倍）");}
            var text=new StringBuilder("### 承接上文：应对").append(selection.name()).append(selection.phase().equals("initial")?"初战":"视察重赛").append("\n");
            text.append("这里先给通用应对方向，不把馆主队伍当成你的队伍。\n\n**进攻覆盖：** ").append(attacks.isEmpty()?"成员属性不同，没有一种招式属性可简单视为全队统一弱点，请逐只核对。":String.join("、",attacks)+"属性攻击对已列成员有有利相性（按王牌已太晶后的防御属性检查）。").append("属性倍率不是伤害或胜率，也不保证你已拥有对应招式。\n\n");
            text.append("**承伤方向：** 单属性成员承受").append(gym.path("theme").asText()).append("属性招式时，可考虑").append(String.join("、",resistances)).append("。这只说明该属性招式的相性，馆主其他属性招式和特性仍需另查。\n");
            if(gym.path("theme").asText().equals("虫"))text.append("岩石招式克制虫，但岩石属性并不抵抗虫招式（1倍）；不能把进攻克制当成防守抗性。\n");
            if(selection.gymId().equals("iono"))text.append("梦妖魔电太晶后仍有飘浮，不能直接靠地面招式收尾。\n");
            if(selection.gymId().equals("ryme"))text.append("这是双打，群体招式需检查是否波及友方，不能凭空补出第二位首发。\n");
            if(GymTurnPlan.classify(question)==GymTurnPlan.Intent.RECOMMEND_TEAM) {
                var state = TrainingIntentState.resolve(context.dialogueHistory(), question);
                var game = state.declared("游戏");
                if (game.isEmpty() && !state.conflicted()
                        && !question.matches("(?s).*朱(?:版本|版).*紫(?:版本|版).*|(?s).*紫(?:版本|版).*朱(?:版本|版).*")) {
                    var explicit = java.util.regex.Pattern.compile("^(?:我(?:玩的是|正在玩|玩)|在|针对|基于|请基于)?(?:宝可梦)?(朱|紫)(?:版本|版)").matcher(AnswerPresentation.visibleRequest(question).strip());
                    if (explicit.find()) game = Optional.of(explicit.group(1));
                }
                var missing = new ArrayList<String>();
                if (game.isEmpty()) missing.add("朱或紫");
                else text.append("\n已承接你声明的").append(game.get()).append("版。");
                if (state.declared("进度").isEmpty() && state.declared("徽章").isEmpty()) missing.add("当前徽章与地图进度");
                missing.add("使用现有成员还是重新捕捉");
                text.append("\n**队伍推荐的缺口：** 请补充").append(String.join("、", missing)).append("。已有队伍可提供保存名称，成员和资源获取条件需继续核对。\n");
            }
            text.append("\n**具体成员与队伍：** 图鉴存在不等于当前进度可获得；当前没有覆盖全地图的捕获解锁数据，所以不把未核验的新成员拼成可执行队伍。你可以告诉我已保存队伍的名称，我会直接读取成员、等级和招式，再给出首发与出招候选，不需要重新手填一遍。\n\n来源：[馆主资料](https://www.serebii.net/scarletviolet/").append(selection.phase().equals("initial")?"gyms":"gymrematches").append(".shtml)及本地属性相性表。");
            return Optional.of(text.toString());
        }catch(Exception unavailable){return Optional.of("已识别你在继续询问上文馆主，但资料读取失败，本次不猜测队伍或属性结论。请稍后重试。");}
    }
}
