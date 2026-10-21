<template>
  <div class="team-roster">
    <div class="roster-toolbar">
      <label class="roster-name"><span class="sr-only">队伍名称</span><input :value="name" :form="formId" maxlength="128" required placeholder="我的休闲队伍" @input="$emit('update:name', $event.target.value)" /><UiIcon name="edit" /></label>
      <span class="roster-save-state" :class="{ saved }"><UiIcon v-if="saved" name="check" />{{ saving ? '保存中…' : saved ? '已保存' : '未保存草稿' }}</span>
      <div class="roster-actions"><button class="primary-btn" type="submit" :form="formId" :disabled="saving">{{ saving ? '保存中…' : editing ? '更新队伍' : '保存队伍' }}</button><button class="roster-reset" type="button" :disabled="saving" @click="$emit('reset')">{{ editing ? '取消编辑' : '清空草稿' }}</button></div>
    </div>
    <div class="team-slot-strip" aria-label="六人队伍">
      <button v-for="(card, index) in cards" :key="index" type="button" class="team-slot" :class="{ selected: card.id && selectedMember === index, vacant: !card.id }" :aria-label="card.id ? `选择第 ${index + 1} 位成员` : `从图鉴添加第 ${index + 1} 位成员`" @click="card.id ? $emit('select', index) : $emit('add')">
        <template v-if="card.id"><PokemonPortrait :form-id="card.id" :name="card.name" /><strong>{{ card.name }}</strong><small>Lv.{{ card.level }}</small></template>
        <template v-else><UiIcon name="plus" /><span>添加成员</span></template>
      </button>
    </div>
  </div>
</template>
<script setup>
import PokemonPortrait from '@/components/PokemonPortrait.vue'
import UiIcon from '@/components/UiIcon.vue'
defineProps({ name:{type:String,default:''}, cards:{type:Array,required:true}, selectedMember:{type:Number,default:0}, saved:Boolean, saving:Boolean, editing:Boolean, formId:{type:String,required:true} })
defineEmits(['update:name','select','add','reset'])
</script>
