<template>
  <div class="machine-reference-panel" role="group" :aria-label="`${learning.moveSlug} 学习器查询`">
    <button type="button" class="machine-lookup" :disabled="loading || !available" @click="lookup">{{ loading ? '读取中…' : '查看学习器编号' }}</button>
    <span v-if="error" role="alert">{{ error }}</span>
    <div v-else-if="result" role="status" aria-label="学习器编号结果">
      <template v-if="result.status === 'MATCHED'"><strong>{{ result.machine.label }}</strong> · 本成员有学习器记录
        <div class="machine-sources"><a :href="result.machine.moveUrl" target="_blank" rel="noopener noreferrer">招式来源</a> · <a :href="result.machine.machineUrl" target="_blank" rel="noopener noreferrer">学习器来源</a> · <a :href="result.machine.itemUrl" target="_blank" rel="noopener noreferrer">道具来源</a></div>
        <small>获取地点、游戏内配方与解锁状态尚未核验；不代表组合或赛制合法。</small>
        <MachineRecipePanel :move-slug="learning.moveSlug" :game-release-id="learning.gameReleaseId" :label="result.machine.label" />
        <MachineAcquisitionPanel :move-slug="learning.moveSlug" :game-release-id="learning.gameReleaseId" :label="result.machine.label" />
        <MachineCraftingPanel :game-release-id="learning.gameReleaseId" />
      </template>
      <template v-else>{{ machineReferenceLabels[result.status] }}</template>
    </div>
  </div>
</template>
<script setup>
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {fetchPokemonMachine} from '@/api'
import {machineLearningIdentity,normalizeMachineReference,machineReferenceLabels} from '@/api/machine-reference'
import MachineCraftingPanel from './MachineCraftingPanel.vue'
import MachineRecipePanel from './MachineRecipePanel.vue'
import MachineAcquisitionPanel from './MachineAcquisitionPanel.vue'
const props=defineProps({learning:{type:Object,required:true}})
const result=ref(null),error=ref(''),loading=ref(false)
const available=computed(()=>props.learning.dataReleaseId==='pokeapi-reference-v1'&&props.learning.gameReleaseId==='pokemon-scarlet-violet')
let active=null,sequence=0,disposed=false
function reset(){sequence++;active?.abort();active=null;result.value=null;error.value='';loading.value=false}
watch(()=>machineLearningIdentity(props.learning),reset)
async function lookup(){
  reset();const expected=JSON.parse(JSON.stringify(props.learning)),identity=machineLearningIdentity(expected),ticket=sequence,controller=new AbortController();active=controller;loading.value=true
  try{const raw=await fetchPokemonMachine(expected,{signal:controller.signal});if(disposed||controller.signal.aborted||ticket!==sequence||identity!==machineLearningIdentity(props.learning))return;result.value=normalizeMachineReference(raw,expected)}
  catch(e){if(!disposed&&!controller.signal.aborted&&ticket===sequence)error.value=e.message||'学习器查询失败，请重试'}
  finally{if(ticket===sequence){active=null;loading.value=false}}
}
onBeforeUnmount(()=>{disposed=true;reset()})
</script>
<style scoped>
.machine-reference-panel{margin:5px 0 8px;font-size:11px;line-height:1.6;color:#486453}.machine-lookup{padding:3px 7px;border:1px solid #cbded3;border-radius:5px;background:#fff;color:#2f6b54;cursor:pointer}.machine-lookup:disabled{opacity:.5;cursor:default}.machine-sources a{color:#30735b}.machine-reference-panel small{display:block;color:#76847d}.machine-reference-panel [role=alert]{display:block;color:#9a443c}
</style>
