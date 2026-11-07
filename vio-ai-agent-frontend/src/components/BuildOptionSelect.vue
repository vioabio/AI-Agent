<template>
  <div ref="root" class="build-option-select" @focusout="leave" @keydown="keyboard">
    <div class="build-option-input">
      <input :id="id" :value="typing ? query : displayValue" :aria-label="label" :title="modelValue && !known.has(modelValue) ? '当前值尚未匹配候选，展开后可核对或重新选择' : displayValue" :aria-describedby="!open && modelValue && !known.has(modelValue) ? `${id}-unverified` : undefined" role="combobox" aria-autocomplete="list" :aria-expanded="open" :aria-controls="`${id}-options`" :aria-activedescendant="highlight >= 0 ? `${id}-option-${highlight}` : undefined" :placeholder="placeholder" maxlength="80" :disabled="disabled" @focus="show" @click="!open && show()" @input="search" />
      <button type="button" class="build-option-toggle" :aria-label="`展开${label}选项`" :disabled="disabled" @click="toggle">⌄</button>
    </div>
    <div v-if="open" class="build-option-popup">
      <p v-if="loading" role="status">正在读取候选…</p>
      <p v-if="error" role="alert">{{ error }} <button type="button" @click="reload(false)">重试</button></p>
      <div :id="`${id}-options`" role="listbox" :aria-label="`${label}候选`">
        <button type="button" role="option" :aria-selected="!modelValue" class="build-option-row empty-option" @click="choose(null)">{{ kind === 'item' ? '不携带道具' : '暂不配置' }}</button>
        <button v-for="(option, index) in options" :id="`${id}-option-${index}`" :key="option.slug" type="button" role="option" class="build-option-row" :class="{ highlighted: highlight === index }" :aria-selected="modelValue === option.slug" :disabled="excluded(option.slug)" @click="choose(option)">
          <span><strong>{{ option.nameZh }}</strong><small>{{ option.slug }}</small></span>
          <small v-if="kind === 'ability' && option.hidden">隐藏特性</small>
          <small v-if="kind === 'ability'" class="build-option-description">{{ option.description || '当前目录未收录朱／紫中文说明。' }}</small>
          <small v-if="kind === 'move'">{{ types[option.typeKey] || '未知属性' }} · {{ classes[option.damageClass] || '参数未收录' }}<template v-if="option.damageClass"> · 威力 {{ option.power ?? '—' }} · 命中 {{ option.accuracy ?? '—' }}</template></small>
          <small v-if="excluded(option.slug)">其他槽位已选择</small>
        </button>
      </div>
      <p v-if="!loading && !error && !options.length">未找到匹配候选。可搜索中文名或英文名。</p>
      <button v-if="nextOffset != null && !error" type="button" class="build-option-more" :disabled="loading" @click="reload(true)">加载更多</button>
      <p class="build-option-hint">{{ typing && /[^\x20-\x7e]/.test(query) ? '请选择下拉候选以确认中文输入。' : '参考候选不代表当前可获得性或赛制合法性。' }}</p>
    </div>
    <small v-if="!open && modelValue && !known.has(modelValue)" :id="`${id}-unverified`" class="build-option-unverified" title="当前值尚未匹配候选，展开后可核对或重新选择">待核对</small>
  </div>
</template>
<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchPokemonBuildOptions } from '@/api'
const props = defineProps({ modelValue:{type:String,default:''}, kind:{type:String,required:true}, pokemonId:{type:[String,Number],required:true}, dataReleaseId:{type:String,required:true}, gameReleaseId:{type:String,required:true}, label:{type:String,required:true}, id:{type:String,required:true}, active:Boolean, disabled:Boolean, excludedSlugs:{type:Array,default:()=>[]}, placeholder:{type:String,default:'搜索或选择'} })
const emit = defineEmits(['update:modelValue'])
const root=ref(null), open=ref(false), typing=ref(false), query=ref(''), options=ref([]), loading=ref(false), error=ref(''), nextOffset=ref(null), highlight=ref(-1)
const known=ref(new Map())
const displayValue=computed(()=>known.value.get(props.modelValue)?.nameZh || props.modelValue)
const identity=computed(()=>JSON.stringify([props.kind,props.pokemonId,props.dataReleaseId,props.gameReleaseId,props.active]))
const types={normal:'一般',fighting:'格斗',flying:'飞行',poison:'毒',ground:'地面',rock:'岩石',bug:'虫',ghost:'幽灵',steel:'钢',fire:'火',water:'水',grass:'草',electric:'电',psychic:'超能力',ice:'冰',dragon:'龙',dark:'恶',fairy:'妖精'}
const classes={physical:'物理',special:'特殊',status:'变化'}
let timer, controller, ticket=0, disposed=false
const normalize=value=>value.toLowerCase().replaceAll(' ','-')
function excluded(slug){return props.excludedSlugs.some(value=>normalize(value)===normalize(slug))}
function cancel(){clearTimeout(timer);controller?.abort();ticket++;loading.value=false}
async function load(append=false, lookup=false){
  cancel()
  if(!props.active || props.disabled) return
  const current=++ticket, snapshot=identity.value, q=lookup?props.modelValue:query.value
  controller=new AbortController();loading.value=true;error.value='';highlight.value=-1
  if(!append){options.value=[];nextOffset.value=null}
  try{
    const result=await fetchPokemonBuildOptions({kind:props.kind,pokemonId:Number(props.pokemonId),dataReleaseId:props.dataReleaseId,gameReleaseId:props.gameReleaseId,query:q,offset:append?nextOffset.value:0},{signal:controller.signal})
    if(disposed || current!==ticket || snapshot!==identity.value) return
    if(result.kind!==props.kind || result.dataReleaseId!==props.dataReleaseId || result.gameReleaseId!==props.gameReleaseId || (props.kind!=='item'&&Number(result.pokemonId)!==Number(props.pokemonId)) || !Array.isArray(result.items) || result.items.some(value=>typeof value.slug!=='string'||typeof value.nameZh!=='string')) throw new Error('候选结果与当前成员或版本不一致')
    options.value=append?[...options.value,...result.items]:result.items
    nextOffset.value=result.nextOffset
    const cache=new Map(known.value)
    for(const value of result.items) cache.set(value.slug,value)
    known.value=cache
  }catch(e){if(!disposed && current===ticket && e.name!=='AbortError') error.value=e.message || '候选加载失败'}
  finally{if(current===ticket) loading.value=false}
}
function show(){if(props.disabled)return;open.value=true;typing.value=false;query.value='';load()}
function reload(append){root.value?.querySelector('.build-option-toggle')?.focus();load(append)}
function toggle(){if(open.value)close();else{root.value?.querySelector('input')?.focus();if(!open.value)show()}}
function search(event){
  typing.value=true;query.value=event.target.value;open.value=true
  // Preserve existing manual English configurations; Chinese searches commit only on selection.
  if([...query.value].every(char=>char.charCodeAt(0)>=32&&char.charCodeAt(0)<=126)) emit('update:modelValue',query.value)
  cancel();timer=setTimeout(()=>load(),200)
}
function choose(option){if(option && excluded(option.slug))return;emit('update:modelValue',option?.slug || '');close()}
function close(){open.value=false;typing.value=false;query.value='';highlight.value=-1;cancel()}
function leave(event){if(!root.value?.contains(event.relatedTarget))close()}
function keyboard(event){
  if(event.key==='Escape'&&open.value){event.preventDefault();event.stopPropagation();close();return}
  if(event.target.tagName!=='INPUT')return
  if(event.key==='Enter'&&open.value){event.preventDefault();if(highlight.value>=0)choose(options.value[highlight.value]);return}
  if(!['ArrowDown','ArrowUp'].includes(event.key))return
  event.preventDefault();if(!open.value){show();return}
  const direction=event.key==='ArrowDown'?1:-1
  for(let i=0,index=highlight.value<0&&direction<0?0:highlight.value;i<options.value.length;i++){index=(index+direction+options.value.length)%options.value.length;if(!excluded(options.value[index].slug)){highlight.value=index;break}}
}
watch(identity,()=>{close();known.value=new Map();if(props.active&&props.modelValue)load(false,true)},{immediate:true})
watch(()=>props.modelValue,()=>{if(!open.value&&props.active&&props.modelValue&&!known.value.has(props.modelValue))load(false,true)})
onBeforeUnmount(()=>{disposed=true;cancel()})
</script>
<style scoped>
.build-option-select{position:relative;min-width:0;width:100%;font-weight:400}.build-option-input{position:relative;width:100%}.build-option-input input{width:100%;padding-right:30px!important}.build-option-toggle{position:absolute;right:5px;top:50%;transform:translateY(-50%);border:0;background:transparent;color:#52715f;cursor:pointer;padding:4px 6px}.build-option-popup{position:absolute;top:calc(100% + 4px);left:0;z-index:15;width:max(100%,280px);max-width:min(440px,calc(100vw - 48px));max-height:320px;overflow:auto;background:#fff;border:1px solid #c9dbd0;border-radius:9px;box-shadow:0 8px 24px #12362620;padding:5px}.build-option-row{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:4px 10px;width:100%;border:0;border-radius:5px;padding:8px;text-align:left;background:#fff;color:#284a39;cursor:pointer;font-size:13px}.build-option-row span{display:flex;flex-direction:column;gap:2px}.build-option-row small{font-size:11px;color:#63796c}.build-option-row.highlighted,.build-option-row:hover:not(:disabled),.build-option-row[aria-selected=true]{background:#eaf4ee}.build-option-row:disabled{opacity:.45;cursor:not-allowed}.build-option-popup p{font-size:12px;line-height:1.5;margin:6px;padding:3px;color:#617468}.build-option-popup p[role=alert]{color:#a43e36}.build-option-more{width:100%;padding:8px;background:#f1f7f3;border:0;color:#2d6952;cursor:pointer}.build-option-unverified{position:absolute;right:27px;top:10px;font-size:10px;color:#7b6a48;line-height:1.4;pointer-events:none;background:#fff8e7;padding:0 3px;border-radius:3px}.build-option-select:has(.build-option-unverified) input{padding-right:76px!important}.build-option-row .build-option-description{flex-basis:100%;line-height:1.5}.empty-option{color:#63796c}.build-option-hint{border-top:1px solid #e6eee9}
</style>
