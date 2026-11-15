import {randomBytes} from 'node:crypto'
import {localBase} from './evaluation.mjs'

/** Local evaluation accounts. Credentials and cookies remain inside this closure. */
export function localEvaluationSession(url) {
  const base = localBase(url), cookies = new Map()
  let loginCredentials = null
  async function api(path, body, expected=200, timeoutMs=30000) {
    if(!Number.isInteger(timeoutMs)||timeoutMs<1||timeoutMs>150000)throw Error('BOUNDED_TIMEOUT_REQUIRED')
    const response = await fetch(base+path,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(timeoutMs),
      headers:{Origin:'http://localhost:3000','Content-Type':'application/json',
        Cookie:[...cookies].map(([key,value])=>key+'='+value).join('; '),
        ...(cookies.has('CSRF-TOKEN')?{'X-CSRF-TOKEN':decodeURIComponent(cookies.get('CSRF-TOKEN'))}:{})},
      ...(body===undefined?{}:{body:JSON.stringify(body)})})
    for(const line of response.headers.getSetCookie()) {
      const [key,...value]=line.split(';')[0].split('=');cookies.set(key,value.join('='))
    }
    if(response.status!==expected)throw Error('HTTP_'+response.status)
    if(response.status===204)return null
    const json=await response.json()
    return json&&Object.hasOwn(json,'data')&&Object.hasOwn(json,'code')?json.data:json
  }
  async function login(nickname) {
    if((await api('/auth/session')).mode!=='local')throw Error('LOCAL_ACCOUNT_REQUIRED')
    const credentials={username:'u0_'+randomBytes(8).toString('hex'),password:randomBytes(24).toString('base64url'),nickname}
    await api('/auth/local/register',credentials,201)
    await api('/auth/local/login',credentials)
    loginCredentials = credentials
  }
  async function reauthenticate() {
    if (!loginCredentials) throw Error('REGISTERED_TEST_ACCOUNT_REQUIRED')
    if ((await api('/auth/session')).mode !== 'local') throw Error('LOCAL_ACCOUNT_REQUIRED')
    await api('/auth/local/login',loginCredentials)
  }
  async function logout() {
    await api('/logout',{},204)
    loginCredentials = null
  }
  return {api,login,reauthenticate,logout}
}
