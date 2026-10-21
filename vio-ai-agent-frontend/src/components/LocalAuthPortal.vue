<template>
  <main class="local-auth-page" data-testid="local-auth-portal">
    <header class="local-auth-header"><span><VioMark />VIO 宝可梦助手</span><small>本地账户</small></header>
    <section class="local-auth-card">
      <div class="local-auth-art">
        <h1 class="sr-only">欢迎回到你的宝可梦工作区</h1>
        <p class="sr-only">连续对话 · 资料查询 · 队伍搭配</p>
        <!-- Display the exact illustration region of the supplied reference, without re-generating it. -->
        <svg class="login-reference-art" :data-loaded="artLoaded" viewBox="55 237 366 606" preserveAspectRatio="xMidYMid meet" role="img" aria-label="杰尼龟与妙蛙种子在花园里的欢迎插画">
          <defs><clipPath id="login-illustration-window"><rect x="55" y="237" width="366" height="606" /></clipPath></defs>
          <image :href="loginReference" width="1698" height="926" clip-path="url(#login-illustration-window)" @load="artLoaded = true" />
        </svg>
      </div>
      <div class="local-auth-form">
        <template v-if="recoveryCode"><h2>请保存你的恢复码</h2><p>这是找回本机账号的凭证，只显示这一次。请保存到安全位置，不要分享给他人。</p><code class="recovery-code">{{ recoveryCode }}</code><button class="account-secondary" type="button" @click="copyCode">复制恢复码</button><label class="account-check"><input v-model="codeSaved" type="checkbox" />我已妥善保存恢复码</label><button class="account-primary" type="button" :disabled="!codeSaved" @click="finishRecovery">返回登录</button></template>
        <template v-else>
          <div class="account-tabs"><button type="button" :aria-pressed="mode === 'login'" @click="setMode('login')">登录</button><button type="button" :aria-pressed="mode === 'register'" @click="setMode('register')">创建账户</button></div>
          <h2>{{ mode === 'register' ? '创建你的账户' : mode === 'recover' ? '找回本机账号' : '欢迎回来' }}</h2>
          <p>{{ mode === 'recover' ? '使用注册时保存的恢复码重设密码。' : '登录后继续管理你的对话与队伍' }}</p>
          <form @submit.prevent="submit">
            <label>用户名<input v-model="username" name="username" autocomplete="username" placeholder="3～32 位字母、数字或下划线" minlength="3" maxlength="32" pattern="[A-Za-z0-9_]+" required :disabled="busy" /></label>
            <label v-if="mode === 'register'">昵称<input v-model="nickname" autocomplete="nickname" maxlength="24" placeholder="如何称呼你" :disabled="busy" /></label>
            <label v-if="mode === 'recover'">恢复码<input v-model="recovery" autocomplete="off" maxlength="64" required :disabled="busy" /></label>
            <label>{{ mode === 'recover' ? '新密码' : '密码' }}<div class="account-password"><input v-model="password" :aria-label="mode === 'recover' ? '新密码' : '密码'" :type="showPassword ? 'text' : 'password'" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :minlength="mode === 'login' ? 1 : 10" maxlength="72" required :disabled="busy" placeholder="请输入密码" /><button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</button></div></label>
            <label v-if="mode !== 'login'">确认密码<input v-model="confirmation" type="password" autocomplete="new-password" maxlength="72" required :disabled="busy" /></label>
            <small v-if="mode !== 'login'">密码至少 10 位，最多 72 字节。</small>
            <button v-if="mode === 'login'" class="account-link" type="button" @click="setMode('recover')">忘记密码？</button>
            <p v-if="error" class="account-error" role="alert">{{ error }}</p>
            <button class="account-primary" type="submit" :disabled="busy">{{ busy ? '请稍候…' : mode === 'register' ? '创建账户' : mode === 'recover' ? '重设密码' : '登录' }}</button>
          </form>
        </template>
        <p v-if="notice" class="account-status" role="status">{{ notice }}</p>
        <footer>账户数据保存在本机，不会跨设备同步</footer>
      </div>
    </section>
  </main>
</template>
<script setup>
import { ref } from 'vue'
import VioMark from './VioMark.vue'
import loginReference from '@/assets/auth/login-reference-board.png'
import { fetchAuthSession, localAccountRequest } from '@/api'
import { useAuthSession } from '@/composables/useAuthSession'
const { refresh } = useAuthSession()
const artLoaded=ref(false)
const mode=ref('login'), username=ref(''),nickname=ref(''),password=ref(''),confirmation=ref(''),recovery=ref('')
const busy=ref(false),error=ref(''),notice=ref(''),showPassword=ref(false),recoveryCode=ref(''),codeSaved=ref(false)
function setMode(value) { if(busy.value)return;mode.value=value;password.value='';confirmation.value='';recovery.value='';error.value='';notice.value='' }
async function submit() {
  error.value='';notice.value=''
  if(mode.value!=='login' && password.value!==confirmation.value){error.value='两次密码不一致';return}
  busy.value=true
  try {
    await fetchAuthSession()
    if(mode.value==='register') {
      const result=await localAccountRequest('register',{username:username.value,password:password.value,nickname:nickname.value})
      recoveryCode.value=result.recoveryCode;codeSaved.value=false
    }else if(mode.value==='recover') {
      const result=await localAccountRequest('recover',{username:username.value,recoveryCode:recovery.value,newPassword:password.value})
      recoveryCode.value=result.recoveryCode;codeSaved.value=false
    }else { await localAccountRequest('login',{username:username.value,password:password.value});await refresh() }
  }catch(e){error.value=e.message}
  finally { busy.value=false;password.value='';confirmation.value='';recovery.value='' }
}
async function copyCode(){try{await navigator.clipboard.writeText(recoveryCode.value);notice.value='恢复码已复制'}catch{notice.value='请手动选中恢复码保存'}}
function finishRecovery(){recoveryCode.value='';codeSaved.value=false;setMode('login');notice.value='已完成，请使用密码登录'}
</script>
