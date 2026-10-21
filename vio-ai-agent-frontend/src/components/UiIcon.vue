<template>
  <svg class="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path v-for="(d, index) in (paths[name] || paths.chat)" :key="index" :d="d" />
  </svg>
</template>
<script setup>
defineProps({ name: { type: String, default: 'chat' } })
const paths = {
  chat: ['M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-2 2V11.5a9.5 9.5 0 0 1 19 0Z'],
  book: ['M12 5v16M12 5C9 2 5 2 2 3v16c4-1 7-1 10 2 3-3 6-3 10-2V3c-3-1-7-1-10 2Z'],
  team: ['M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM2 21v-3a7 7 0 0 1 14 0v3H2ZM17 4a4 4 0 0 1 0 7M19 14a6 6 0 0 1 3 5v2h-3'],
  star: ['m12 2 3 6 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1 3-6Z'],
  plus: ['M12 5v14M5 12h14'],
  send: ['m3 3 19 9-19 9 4-9-4-9ZM7 12h15'],
  edit: ['m15 4 5 5M4 20l1-6L16 3a2 2 0 0 1 3 0l2 2a2 2 0 0 1 0 3L10 19l-6 1Z'],
  copy: ['M8 8h13v13H8V8ZM16 5V2H2v14h3'],
  settings: ['m9 3 1-2h4l1 2 3 2 3 1v4l-2 2v3l-1 3h-4l-2-2H9l-3 1-2-3v-3l-2-2 2-4 3-1 2-3Z', 'M15 11a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z'],
  chevron: ['m6 9 6 6 6-6'],
  arrow: ['M4 12h16m-6-6 6 6-6 6'],
  menu: ['M4 6h16M4 12h16M4 18h16'],
  close: ['m6 6 12 12M6 18 18 6'],
  chart: ['M4 20V10h3v10H4ZM10 20V4h3v16h-3ZM16 20V7h3v13h-3'],
  search: ['M16 10a6 6 0 1 1-12 0 6 6 0 0 1 12 0Zm-2 4 6 6'],
  history: ['M3 10a9 9 0 1 1 1 8M3 4v6h6M12 7v5l3 2'],
  check: ['m5 12 4 4L19 6']
}
</script>
<style scoped>.ui-icon { width: 19px; height: 19px; flex: 0 0 auto; vertical-align: middle; }</style>
