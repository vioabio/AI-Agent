<template>
  <div class="build-check-panel">
    <div class="build-check-toolbar"><strong>配招逐项核对</strong><button class="secondary-btn" type="button" :disabled="!available || loading" @click="check">{{ loading ? '核对中…' : '检查当前配招' }}</button></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-else-if="notice" role="status">{{ notice }}</p>
    <details v-if="report" open class="build-check-result" aria-label="配招核对结果">
      <summary>{{ buildCheckLabels[report.status] }}</summary>
      <p v-if="report.missingFields.length">待补：{{ missingBuildFields(report.missingFields).join('、') }}</p>
      <ul><li>特性：{{ buildCheckAbilityLabels[report.abilityStatus] }}</li><li v-for="move in report.moves" :key="move.slot">{{ move.slot }}. {{ move.supplied }}：{{ buildCheckMoveLabels[move.status] }}
        <ul v-if="move.status === 'RECORDED'" class="learning-notes" :aria-label="`${move.supplied} 学习记录`"><li v-for="note in buildLearningNotes(move, report.request.build?.level)" :key="note">{{ note }}</li></ul>
        <MachineReferencePanel v-if="move.status === 'RECORDED' && move.learning?.entries?.some(entry => entry.methodKey === 'machine')" :learning="move.learning" />
      </li></ul>
      <a v-if="report.source" :href="report.source.sourceUrl" target="_blank" rel="noopener noreferrer">查看参考来源</a>
      <p class="build-check-limits">逐项匹配仍不证明四招共同可得、学习等级相容或正式赛制合法。道具与性格效果、完整例外待另查；本次不会保存或修改队伍。</p>
    </details>
    <p v-else-if="!error && !notice" class="build-check-limits">{{ available ? '核对当前表单的四招、特性及学习记录；表单默认值不代表你已明确选择。' : '当前资料版本暂不提供此项核对。' }}</p>
  </div>
</template>
<script setup>
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {checkPokemonBuild} from '@/api'
import {normalizeBuildCheck,buildCheckIdentity,buildCheckLabels,buildCheckMoveLabels,buildCheckAbilityLabels,missingBuildFields} from '@/api/build-check'
import {buildLearningNotes} from '@/api/build-learning'
import MachineReferencePanel from '@/components/MachineReferencePanel.vue'
const props=defineProps({request:{type:Object,required:true}})
const report=ref(null),error=ref(''),notice=ref(''),loading=ref(false)
const available=computed(()=>props.request.dataReleaseId==='pokeapi-reference-v1'&&props.request.gameReleaseId==='pokemon-scarlet-violet'&&Number.isSafeInteger(props.request.pokemonId)&&props.request.pokemonId>0)
let active=null,sequence=0,disposed=false
function invalidate(){sequence++;active?.abort();active=null;report.value=null;error.value='';loading.value=false}
watch(()=>buildCheckIdentity(props.request),()=>{invalidate();notice.value='配置已改变，请重新检查。'})
async function check(){
  invalidate();notice.value='';const request=JSON.parse(JSON.stringify(props.request)),identity=buildCheckIdentity(request),ticket=sequence,controller=new AbortController();active=controller;loading.value=true
  try{const raw=await checkPokemonBuild(request,{signal:controller.signal});if(disposed||controller.signal.aborted||ticket!==sequence||identity!==buildCheckIdentity(props.request))return;report.value=normalizeBuildCheck(raw,request)}
  catch(e){if(!disposed&&!controller.signal.aborted&&ticket===sequence)error.value=e.message||'配招核对失败，请重试。'}
  finally{if(ticket===sequence){loading.value=false;active=null}}
}
onBeforeUnmount(()=>{disposed=true;invalidate()})
</script>
<style scoped>
.build-check-panel{margin-top:12px;padding:10px 12px;border:1px solid #dce8e1;border-radius:10px;background:#f7faf8;font-size:12px;color:#344d40}.build-check-toolbar{display:flex;align-items:center;justify-content:space-between;gap:8px}.build-check-toolbar .secondary-btn{font-size:12px;padding:6px 10px;min-height:30px}.build-check-result{max-height:230px;overflow:auto;margin-top:8px;line-height:1.6}.build-check-result summary{font-weight:600;cursor:pointer}.build-check-result ul{padding-left:18px;margin:7px 0}.build-check-result a{color:#30735b}.build-check-limits{font-size:11px;color:#76847d;margin:7px 0 0}.build-check-result p{margin:6px 0}
</style>
