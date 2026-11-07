<template>
  <section class="team-build-check-panel" aria-label="整队配置核对">
    <div class="toolbar"><strong>整队配置核对 · {{ request.members.length }}/6</strong><button type="button" :disabled="!available || loading" @click="check">{{ loading ? '整队核对中…' : '检查整队配置' }}</button></div>
    <p v-if="error" role="alert">{{ error }}</p><p v-else-if="notice" role="status">{{ notice }}</p>
    <details v-if="report" open aria-label="整队配置核对结果">
      <summary>{{ report.members.length }} 位已核对 · 联合可得性未验证</summary>
      <p>逐项匹配 {{ report.counts.REFERENCE_MATCHED }} 位 · 有缺项 {{ report.counts.INCOMPLETE }} 位 · 资料不足 {{ report.counts.REFERENCE_UNAVAILABLE }} 位 · 参考不一致 {{ report.counts.REFERENCE_MISMATCH }} 位 · 输入问题 {{ report.counts.INVALID_INPUT }} 位</p>
      <p v-for="slots in report.duplicateFormSlots" :key="slots.join(',')">相同形态出现在第 {{ slots.join('、') }} 位；仅记录重复，不据此判定违规。</p>
      <article v-for="member in report.members" :key="member.slot">
        <strong>第 {{ member.slot }} 位 · {{ member.report.source?.nameZh || '成员资料待补' }}：{{ buildCheckLabels[member.report.status] }}</strong>
        <p v-if="member.report.missingFields.length">待补：{{ missingBuildFields(member.report.missingFields).join('、') }}</p>
        <p>特性：{{ buildCheckAbilityLabels[member.report.abilityStatus] }}</p>
        <ul><li v-for="move in member.report.moves" :key="move.slot">{{ move.supplied || '未填写招式' }}：{{ buildCheckMoveLabels[move.status] }}</li></ul>
        <a v-if="member.report.source" :href="member.report.source.sourceUrl" target="_blank" rel="noopener noreferrer">该成员参考来源</a>
      </article>
    </details>
    <button v-if="report" type="button" :disabled="loading || busy" @click="emit('ask', JSON.parse(JSON.stringify(report.request)))">让 AI 核对整队配置</button>
    <small>只汇总当前表单的逐项结果，不验证四招与特性共同可得、整队协同或赛制合法性，不保存队伍。首次读取可能较慢。</small>
  </section>
</template>
<script setup>
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {checkPokemonTeamBuild} from '@/api'
import {normalizeTeamBuildCheck,teamBuildCheckIdentity} from '@/api/team-build-check'
import {buildCheckLabels,buildCheckMoveLabels,buildCheckAbilityLabels,missingBuildFields} from '@/api/build-check'
const props=defineProps({request:{type:Object,required:true},busy:{type:Boolean,default:false}})
const emit=defineEmits(['ask'])
const report=ref(null),loading=ref(false),error=ref(''),notice=ref('')
const available=computed(()=>props.request.dataReleaseId==='pokeapi-reference-v1'&&props.request.gameReleaseId==='pokemon-scarlet-violet'&&props.request.members.length>0&&props.request.members.length<=6)
let sequence=0,active=null,disposed=false
function reset(){sequence++;active?.abort();active=null;report.value=null;loading.value=false;error.value=''}
watch(()=>teamBuildCheckIdentity(props.request),()=>{reset();notice.value='队伍配置已改变，请重新检查。'})
async function check(){
 reset();notice.value='';const request=JSON.parse(JSON.stringify(props.request)),identity=teamBuildCheckIdentity(request),ticket=sequence,controller=new AbortController();active=controller;loading.value=true
 try{const raw=await checkPokemonTeamBuild(request,{signal:controller.signal});if(disposed||controller.signal.aborted||sequence!==ticket||identity!==teamBuildCheckIdentity(props.request))return;report.value=normalizeTeamBuildCheck(raw,request)}
 catch(e){if(!disposed&&!controller.signal.aborted&&sequence===ticket)error.value=e.message||'整队核对失败，请重试'}
 finally{if(sequence===ticket){loading.value=false;active=null}}
}
onBeforeUnmount(()=>{disposed=true;reset()})
</script>
<style scoped>
.team-build-check-panel{margin:12px 0;padding:10px 12px;border:1px solid #dce8e1;border-radius:10px;background:#f7faf8;font-size:12px;color:#344d40}.toolbar{display:flex;justify-content:space-between;align-items:center;gap:8px}.toolbar button{padding:6px 10px;border:1px solid #cbded3;border-radius:6px;background:#fff;color:#2f6b54;cursor:pointer}.toolbar button:disabled{opacity:.5;cursor:default}.team-build-check-panel details{max-height:300px;overflow:auto;overflow-wrap:anywhere;margin:8px 0;line-height:1.6}.team-build-check-panel article{border-top:1px solid #dce8e1;padding:8px 0}.team-build-check-panel p{margin:6px 0}.team-build-check-panel ul{padding-left:18px;margin:5px 0}.team-build-check-panel a{color:#30735b}.team-build-check-panel small{display:block;color:#76847d}.team-build-check-panel [role=alert]{color:#9a443c}
</style>
