<template><dialog ref="dialog" aria-labelledby="team-import-title" @close="$emit('update:open',false)"><h2 id="team-import-title">导入队伍</h2><p>支持 JSON、Showdown TXT、CSV、DOCX、文本 PDF（1 MB）。扫描件和 Excel 暂不支持。</p><button type="button" :disabled="busy" @click="$emit('file')">选择文件</button><label>或粘贴 Showdown 配置<textarea v-model="text" rows="9" maxlength="20000" aria-label="粘贴队伍配置" :disabled="busy" /></label><p v-if="error" role="alert">{{ error }}</p><footer><button type="button" :disabled="busy" @click="dialog.close()">取消</button><button type="button" :disabled="busy||!text.trim()" @click="$emit('text',text)">{{ busy?'解析中…':'解析并导入' }}</button></footer></dialog></template>
<script setup>
import {ref,watch,nextTick} from 'vue'
const props=defineProps({open:Boolean,busy:Boolean,error:{type:String,default:''}})
defineEmits(['update:open','file','text'])
const dialog=ref(null),text=ref('')
watch(()=>props.open,async value=>{await nextTick();if(value&&!dialog.value.open)dialog.value.showModal();else if(!value&&dialog.value.open)dialog.value.close()})
</script>
<style scoped>dialog{width:min(600px,90vw);max-height:85vh;overflow:auto;margin:auto;padding:24px;border:1px solid #cdded3;border-radius:14px;color:#284b39}dialog::backdrop{background:#12362666}h2{font-size:20px}p{font-size:13px;margin:10px 0}label{display:grid;gap:8px;margin:16px 0}textarea{width:100%;padding:10px;border:1px solid #cdded3;border-radius:8px;font:13px monospace}footer{display:flex;justify-content:flex-end;gap:12px}button{padding:8px 16px;background:#e7f1eb;border:1px solid #cdded3;border-radius:7px;color:#28614a;cursor:pointer}button:disabled{opacity:.5}p[role=alert]{color:#a43131}</style>
