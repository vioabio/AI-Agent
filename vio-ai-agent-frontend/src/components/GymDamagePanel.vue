<template>
  <details class="gym-damage"><summary>单次伤害计算 · 核对具体出招</summary>
    <p>NPC性格、努力值、个体值未知；下方默认中性／0EV／31IV均为可调整的假设，不是馆主实际隐藏数值。</p>
    <form @submit.prevent="calculate">
      <div class="fields">
        <label>我的成员<select v-model.number="member"><option v-for="(m,i) in challenge.members" :key="i" :value="i+1">第{{ i+1 }}位 · Lv.{{ m.build.level }}</option></select></label>
        <label>对手<select v-model.number="opponent"><option v-for="(f,i) in gym.opponents || []" :key="i" :value="i+1">{{ f.name }} · Lv.{{ f.level }}</option></select></label>
        <label>攻击方向<select v-model="direction"><option value="player">我的成员 → 馆主</option><option value="npc">馆主 → 我的成员</option></select></label>
        <label>招式<select v-model="move" aria-label="计算招式"><option value="">请选择招式</option><option v-for="m in moves" :key="m" :value="m">{{ m }}</option></select></label>
        <label>我的剩余HP %<input v-model.number="player.hpPercent" type="number" min="1" max="100" required /></label>
        <label>对手剩余HP %<input v-model.number="npc.hpPercent" type="number" min="1" max="100" required /></label>
        <label>我的异常状态<select v-model="player.status"><option v-for="s in statusChoices" :key="s[0]" :value="s[0]">{{ s[1] }}</option></select></label>
        <label>对手异常状态<select v-model="npc.status"><option v-for="s in statusChoices" :key="s[0]" :value="s[0]">{{ s[1] }}</option></select></label>
        <label>天气<select v-model="weather"><option value="">无</option><option value="Sun">晴天</option><option value="Rain">雨天</option><option value="Sand">沙暴</option><option value="Snow">雪天</option></select></label>
        <label>场地<select v-model="terrain"><option value="">无</option><option value="Electric">电气</option><option value="Grassy">青草</option><option value="Psychic">精神</option><option value="Misty">薄雾</option></select></label>
      </div>
      <label><input v-model="player.teraActive" type="checkbox" />我的成员已太晶化（需先配置太晶属性）</label>
      <label><input v-model="npc.teraActive" type="checkbox" />对手已太晶化（仅王牌有已核实属性）</label>
      <details><summary>NPC数值假设、能力变化与其他条件</summary>
        <div class="fields"><label>NPC性格<select v-model="nature"><option value="hardy">勤奋（中性）</option><option value="bold">大胆</option><option value="calm">温和</option><option value="modest">内敛</option><option value="adamant">固执</option><option value="timid">胆小</option></select></label><label>NPC六项个体值假设<input v-model.number="iv" type="number" min="0" max="31" required /></label></div>
        <div v-for="s in stats" :key="s[0]" class="fields"><label>NPC{{ s[1] }}努力值<input v-model.number="evs[s[0]]" type="number" min="0" max="252" required /></label><template v-if="s[0]!=='hp'"><label>我的{{ s[1] }}能力变化<input v-model.number="player.boosts[s[0]]" type="number" min="-6" max="6" required /></label><label>对手{{ s[1] }}能力变化<input v-model.number="npc.boosts[s[0]]" type="number" min="-6" max="6" required /></label></template></div>
        <label><input v-model="critical" type="checkbox" />本次命中要害</label><label><input v-model="reflect" type="checkbox" />防守方有反射壁</label><label><input v-model="screen" type="checkbox" />防守方有光墙</label><label><input v-model="help" type="checkbox" />攻击方本次受到帮助</label>
        <label>高级条件覆盖（JSON，可选）<textarea v-model="advanced" placeholder='{"playerState":{"abilityOn":true},"field":{"isGravity":true}}' /></label><p>只接受 playerState、npcState、field、hits；用于重力、特性开关、队友倒下数、极光幕、友情防守、顺风、守住及连击次数。未知字段拒绝计算。</p>
      </details>
      <button type="submit" :disabled="busy||!move">{{ busy?'计算中…':'计算本次伤害' }}</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="result" aria-label="道馆伤害结果"><strong>伤害 {{ result.range.minimum }}–{{ result.range.maximum }} · {{ result.percent.minimum.toFixed(1) }}%–{{ result.percent.maximum.toFixed(1) }}%</strong><p>{{ result.assumptionsNotice }}</p><p>引擎 {{ result.engine }} {{ result.engineVersion }}</p><details><summary>查看本次完整计算条件</summary><pre>{{ JSON.stringify(result.assumptions,null,2) }}</pre></details></div>
  </details>
</template>
<script setup>
import {computed,ref,reactive,watch,onBeforeUnmount} from 'vue'
import {calculateGymDamage} from '@/api'
const props=defineProps({challenge:{type:Object,required:true},gym:{type:Object,required:true}}),emit=defineEmits(['result'])
const member=ref(1),opponent=ref(1),direction=ref('player'),move=ref(''),weather=ref(''),terrain=ref(''),nature=ref('hardy'),iv=ref(31),critical=ref(false),reflect=ref(false),screen=ref(false),help=ref(false),advanced=ref('')
const stats=[['hp','HP'],['atk','攻击'],['def','防御'],['spa','特攻'],['spd','特防'],['spe','速度']],statusChoices=[['','无'],['brn','灼伤'],['par','麻痹'],['psn','中毒'],['tox','剧毒'],['slp','睡眠'],['frz','冰冻']]
const evs=reactive(Object.fromEntries(stats.map(s=>[s[0],0]))),state=()=>({hpPercent:100,status:'',teraActive:false,boosts:{atk:0,def:0,spa:0,spd:0,spe:0}}),player=reactive(state()),npc=reactive(state())
const result=ref(null),error=ref(''),busy=ref(false),moves=computed(()=>direction.value==='player'?(props.challenge.members[member.value-1]?.build.moves||[]):(props.gym.opponents?.[opponent.value-1]?.moves||[]))
let active,sequence=0,disposed=false
function reset(){sequence++;active?.abort();result.value=null;error.value='';busy.value=false;emit('result',null)}
watch(()=>JSON.stringify([props.challenge,member.value,opponent.value,direction.value,move.value,weather.value,terrain.value,nature.value,iv.value,critical.value,reflect.value,screen.value,help.value,advanced.value,player,npc,evs]),reset)
watch(moves,()=>{move.value=''})
async function calculate(){reset();const ticket=sequence;try{const extra=advanced.value.trim()?JSON.parse(advanced.value):{};if(!extra||Array.isArray(extra)||typeof extra!=='object'||Object.keys(extra).some(k=>!['playerState','npcState','field','hits'].includes(k)))throw Error('高级条件包含未知字段');active=new AbortController();busy.value=true;const value=await calculateGymDamage({challenge:props.challenge,memberSlot:member.value,opponentSlot:opponent.value,direction:direction.value,move:move.value,npcStats:{nature:nature.value,evs:{...evs},ivs:Object.fromEntries(stats.map(s=>[s[0],iv.value]))},playerState:{...player,...extra.playerState},npcState:{...npc,...extra.npcState},field:{weather:weather.value||null,terrain:terrain.value||null,attackerSide:{isHelpingHand:help.value},defenderSide:{isReflect:reflect.value,isLightScreen:screen.value},...extra.field},critical:critical.value,...(extra.hits==null?{}:{hits:extra.hits})},{signal:active.signal});if(!disposed&&ticket===sequence){result.value=value;emit('result',value)}}catch(e){if(!disposed&&ticket===sequence&&e.name!=='AbortError')error.value=e.message}finally{if(ticket===sequence)busy.value=false}}
onBeforeUnmount(()=>{disposed=true;reset()})
</script>
<style scoped>
.gym-damage{border:1px solid #dbe7df;border-radius:8px;padding:10px;margin-block:12px}summary{font-weight:600;cursor:pointer}.fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}label{display:grid;gap:5px;margin-block:8px}label:has([type=checkbox]){display:flex;align-items:center}input,select,textarea{min-width:0;padding:7px;border:1px solid #ceded3;border-radius:6px}p{font-size:13px;line-height:1.8}pre{font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere}[role=alert]{color:#a33434}button{padding:8px 12px;background:#edf5ef;border:1px solid #bfd2c6;border-radius:7px;color:#285c46;cursor:pointer}button:disabled{opacity:.5}details details{margin-block:10px}@media(max-width:480px){.fields{grid-template-columns:1fr}}
</style>
