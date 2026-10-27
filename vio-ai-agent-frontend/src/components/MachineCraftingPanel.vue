<template>
  <div class="machine-crafting-panel">
    <button type="button" :disabled="loading" :aria-expanded="open" @click="toggle">{{ loading ? '读取中…' : open ? '收起制作说明' : '查看制作说明' }}</button>
    <span v-if="error" role="alert">{{ error }}</span>
    <section v-if="guide && open" aria-label="学习器通用制作说明">
      <strong>{{ guide.title }}</strong>
      <ul><li v-for="fact in guide.facts" :key="fact">{{ fact }}</li></ul>
      <p>下一步：{{ guide.nextCheck }}</p>
      <p class="limitation">{{ guide.limitation }}</p>
      <a :href="guide.sourceUrl" target="_blank" rel="noopener noreferrer">宝可梦官方说明</a>
      <small>核对于 {{ guide.checkedAt }} · 项目整理，待人工复核</small>
    </section>
  </div>
</template>
<script setup>
import {onBeforeUnmount,ref,watch} from 'vue'
import {fetchMachineCraftingGuide} from '@/api'
import {normalizeMachineCraftingGuide} from '@/api/machine-crafting'
const props=defineProps({gameReleaseId:{type:String,required:true}})
const guide=ref(null),open=ref(false),loading=ref(false),error=ref('')
let active=null,sequence=0,disposed=false
function reset(){sequence++;active?.abort();active=null;guide.value=null;open.value=false;loading.value=false;error.value=''}
watch(()=>props.gameReleaseId,reset)
async function toggle(){
  if(guide.value){open.value=!open.value;return}
  const ticket=++sequence,game=props.gameReleaseId,controller=new AbortController();active=controller;loading.value=true;error.value=''
  try{const raw=await fetchMachineCraftingGuide(game,{signal:controller.signal});if(disposed||controller.signal.aborted||sequence!==ticket||game!==props.gameReleaseId)return;guide.value=normalizeMachineCraftingGuide(raw,game);open.value=true}
  catch(e){if(!disposed&&!controller.signal.aborted&&sequence===ticket)error.value=e.message||'制作说明暂不可用，请重试'}
  finally{if(sequence===ticket){loading.value=false;active=null}}
}
onBeforeUnmount(()=>{disposed=true;reset()})
</script>
<style scoped>
.machine-crafting-panel{margin-top:6px}.machine-crafting-panel button{padding:3px 7px;border:1px solid #cbded3;border-radius:5px;background:#fff;color:#2f6b54;cursor:pointer}.machine-crafting-panel button:disabled{opacity:.5}.machine-crafting-panel section{margin-top:6px;padding:8px;border:1px solid #dce9e1;border-radius:6px;background:#f7faf8;overflow-wrap:anywhere}.machine-crafting-panel ul{padding-left:18px;margin:5px 0}.machine-crafting-panel p{margin:5px 0}.machine-crafting-panel a{color:#30735b}.machine-crafting-panel small{display:block;color:#76847d}.limitation{color:#76847d}.machine-crafting-panel [role=alert]{display:block;color:#9a443c}
</style>
