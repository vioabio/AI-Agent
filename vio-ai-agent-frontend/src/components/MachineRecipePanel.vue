<template>
  <div class="machine-recipe-panel">
    <button type="button" :disabled="loading" :aria-expanded="open" @click="toggle">{{ loading ? '读取配方中…' : open ? '收起配方参考' : '查看配方参考' }}</button>
    <span v-if="error" role="alert">{{ error }}</span>
    <section v-if="recipe && open" aria-label="学习器配方参考">
      <template v-if="recipe.status === 'CONSISTENT_REFERENCE'">
        <strong>{{ recipe.label }} {{ recipe.nameZh }} · {{ recipe.leaguePoints.toLocaleString('zh-CN') }} LP</strong>
        <ul><li v-for="material in recipe.materials" :key="material.name">{{ material.name }} × {{ material.quantity }}</li></ul>
        <p>两份社区资料的配方记录一致；材料名称保留来源英文，待人工及游戏内核验。</p>
      </template>
      <p v-else-if="recipe.status === 'SOURCE_DISAGREEMENT'">来源差异待核对，本次不提供材料或数量。请以游戏内制作界面为准。</p>
      <p v-else>本批配方参考未收录该招式。</p>
      <div v-if="recipe.sources.length" class="recipe-sources"><a v-for="source in recipe.sources" :key="source.id" :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.publisher }}</a></div>
      <small>核对于 {{ recipe.checkedAt }}；不证明已解锁、已备齐资源或该成员能学习。获取地点与解锁条件尚未核验。</small>
    </section>
  </div>
</template>
<script setup>
import {onBeforeUnmount,ref,watch} from 'vue'
import {fetchMachineRecipe} from '@/api'
import {normalizeMachineRecipe} from '@/api/machine-recipe'
const props=defineProps({moveSlug:{type:String,required:true},gameReleaseId:{type:String,required:true},label:{type:String,required:true}})
const recipe=ref(null),open=ref(false),loading=ref(false),error=ref('')
let sequence=0,active=null,disposed=false
function reset(){sequence++;active?.abort();active=null;recipe.value=null;open.value=false;loading.value=false;error.value=''}
watch(()=>[props.moveSlug,props.gameReleaseId,props.label],reset)
async function toggle(){
  if(recipe.value){open.value=!open.value;return}
  const expected={moveSlug:props.moveSlug,gameReleaseId:props.gameReleaseId,label:props.label},ticket=++sequence,controller=new AbortController();active=controller;loading.value=true;error.value=''
  try{const raw=await fetchMachineRecipe(expected.moveSlug,expected.gameReleaseId,{signal:controller.signal});if(disposed||controller.signal.aborted||sequence!==ticket||expected.moveSlug!==props.moveSlug||expected.gameReleaseId!==props.gameReleaseId||expected.label!==props.label)return;recipe.value=normalizeMachineRecipe(raw,expected);open.value=true}
  catch(e){if(!disposed&&!controller.signal.aborted&&sequence===ticket)error.value=e.message||'配方参考暂不可用，请重试'}
  finally{if(sequence===ticket){loading.value=false;active=null}}
}
onBeforeUnmount(()=>{disposed=true;reset()})
</script>
<style scoped>
.machine-recipe-panel{margin-top:6px}.machine-recipe-panel button{padding:3px 7px;border:1px solid #cbded3;border-radius:5px;background:#fff;color:#2f6b54;cursor:pointer}.machine-recipe-panel button:disabled{opacity:.5}.machine-recipe-panel section{margin-top:6px;padding:8px;border:1px solid #dce9e1;border-radius:6px;background:#f7faf8;overflow-wrap:anywhere}.machine-recipe-panel ul{padding-left:18px;margin:5px 0}.machine-recipe-panel p{margin:5px 0}.recipe-sources{display:flex;flex-wrap:wrap;gap:10px}.recipe-sources a{color:#30735b}.machine-recipe-panel small{display:block;color:#76847d}.machine-recipe-panel [role=alert]{display:block;color:#9a443c}
</style>
