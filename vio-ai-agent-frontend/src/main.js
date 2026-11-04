import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { createHead } from '@unhead/vue/client'
import './styles/workspace.css'
import './styles/reference-layout.css'
import './styles/accounts.css'
import './styles/reading-workspace.css'

const app = createApp(App)
const head = createHead()

app.use(router)
app.use(head)
app.mount('#app')
