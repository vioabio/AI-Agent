<template>
  <div class="machine-acquisition-panel">
    <button type="button" :disabled="loading" :aria-expanded="open" @click="toggle">{{ loading ? '读取区域中…' : open ? '收起获取区域' : '查看获取区域' }}</button>
    <span v-if="error" role="alert">{{ error }}</span>
    <section v-if="reference && open" aria-label="学习器获取区域参考">
      <template v-if="reference.status === 'COMMON_REGION_REFERENCE'">
        <strong>{{ reference.label }} {{ reference.nameZh }} · 获取区域线索</strong>
        <ul><li v-for="region in reference.regions" :key="region.id">{{ region.nameEn }}</li></ul>
        <p>两份社区资料共同列出的大区域；原地点列表并不完全一致，区域名保留英文。</p>
      </template>
      <p v-else-if="reference.status === 'NOT_CATALOGUED'">本批未收录该招式的获取区域参考。</p>
      <p v-else>未取得可交叉对应的区域资料，不能据此断言没有获取地点。</p>
      <div class="acquisition-sources"><a v-for="source in reference.sources" :key="source.id" :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.publisher }}</a></div>
      <small>核对于 {{ reference.checkedAt }} · 待人工及游戏内核验。精确坐标、NPC条件、剧情解锁及路线尚未核验，请在游戏内确认。</small>
    </section>
  </div>
</template>
<script setup>
import {onBeforeUnmount,ref,watch} from 'vue'
import {fetchMachineAcquisition} from '@/api'
import {normalizeMachineAcquisition} from '@/api/machine-acquisition'
const props=defineProps({moveSlug:{type:String,required:true},gameReleaseId:{type:String,required:true},label:{type:String,required:true}})
const reference=ref(null),open=ref(false),loading=ref(false),error=ref('')
let sequence=0,active=null,disposed=false
function reset(){sequence++;active?.abort();active=null;reference.value=null;open.value=false;loading.value=false;error.value=''}
watch(()=>[props.moveSlug,props.gameReleaseId,props.label],reset)
async function toggle(){
  if(reference.value){open.value=!open.value;return}
  const expected={moveSlug:props.moveSlug,gameReleaseId:props.gameReleaseId,label:props.label},ticket=++sequence,controller=new AbortController();active=controller;loading.value=true;error.value=''
  try{const raw=await fetchMachineAcquisition(expected.moveSlug,expected.gameReleaseId,{signal:controller.signal});if(disposed||controller.signal.aborted||sequence!==ticket||expected.moveSlug!==props.moveSlug||expected.gameReleaseId!==props.gameReleaseId||expected.label!==props.label)return;reference.value=normalizeMachineAcquisition(raw,expected);open.value=true}
  catch(e){if(!disposed&&!controller.signal.aborted&&sequence===ticket)error.value=e.message||'获取区域参考暂不可用，请重试'}
  finally{if(sequence===ticket){loading.value=false;active=null}}
}
onBeforeUnmount(()=>{disposed=true;reset()})
</script>
<style scoped>
.machine-acquisition-panel{margin-top:6px}.machine-acquisition-panel button{padding:3px 7px;border:1px solid #cbded3;border-radius:5px;background:#fff;color:#2f6b54;cursor:pointer}.machine-acquisition-panel button:disabled{opacity:.5}.machine-acquisition-panel section{margin-top:6px;padding:8px;border:1px solid #dce9e1;border-radius:6px;background:#f7faf8;overflow-wrap:anywhere}.machine-acquisition-panel ul{padding-left:18px;margin:5px 0}.machine-acquisition-panel p{margin:5px 0}.acquisition-sources{display:flex;flex-wrap:wrap;gap:10px}.acquisition-sources a{color:#30735b}.machine-acquisition-panel small{display:block;color:#76847d}.machine-acquisition-panel [role=alert]{display:block;color:#9a443c}
</style>
