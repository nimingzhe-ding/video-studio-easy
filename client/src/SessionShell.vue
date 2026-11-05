<script setup>
import { onMounted, onUnmounted, ref } from 'vue';
import App from './App.vue';
import { api } from './api';
const account=ref(null), loading=ref(true), busy=ref(false), error=ref(''), registering=ref(false);
const registration=ref(false), username=ref(''), password=ref('');
async function initialize() {
  loading.value=true; error.value='';
  try {
    const config=await api.csrf(); registration.value=config.registrationEnabled;
    try { account.value=await api.me(); } catch(e) { if(e.status!==401) throw e; }
  } catch(e) { error.value=e.message; }
  finally { loading.value=false; }
}
async function submit() {
  if(busy.value) return;
  busy.value=true; error.value='';
  try {
    if(registering.value) await api.register({username:username.value,password:password.value});
    account.value=await api.login({username:username.value,password:password.value});
    password.value=''; await api.csrf();
  } catch(e) { error.value=e.message; }
  finally { busy.value=false; }
}
async function logout() {
  try { await api.logout(); account.value=null; await api.csrf(); }
  catch(e) { error.value=e.message; }
}
function expired() { account.value=null; error.value='登录已过期，请重新登录'; api.csrf().catch(() => {}); }
onMounted(() => { window.addEventListener('auth-required',expired); initialize(); });
onUnmounted(() => window.removeEventListener('auth-required',expired));
</script>

<template>
  <App v-if="account" :account="account" @logout="logout" />
  <main v-else class="access-page">
    <div class="access-editorial" aria-hidden="true">
      <span class="access-index">VIDEO STUDIO / 01</span>
      <div class="access-rule"></div>
      <p class="access-kicker">每一份理解，都有出处。</p>
      <h1>让内容<br>成为知识。</h1>
      <p class="access-caption">从原始影像到结构化报告。<br>提取证据，连接观点，留下可以追溯的思考。</p>
      <span class="access-stamp">墨室档案</span>
    </div>
    <section class="access-form">
      <span class="access-index">工作空间</span>
      <h2>{{ registering ? '创建你的账户' : '回到你的档案' }}</h2>
      <p class="muted">{{ registering ? '建立个人素材库，保存每一次分析。' : '登录后继续处理素材、查看报告与追问。' }}</p>
      <p v-if="loading" role="status">正在连接工作空间…</p>
      <form v-else @submit.prevent="submit">
        <label for="account-name">用户名</label>
        <input id="account-name" v-model="username" autocomplete="username" required minlength="3" maxlength="64" placeholder="输入用户名" />
        <label for="account-password">密码</label>
        <input id="account-password" v-model="password" type="password" :autocomplete="registering ? 'new-password' : 'current-password'" required minlength="12" maxlength="72" placeholder="至少 12 位" />
        <p v-if="error" class="notice err" role="alert">{{ error }}</p>
        <button class="primary access-submit" type="submit" :disabled="busy">{{ busy ? '正在验证…' : registering ? '创建账户并进入' : '进入工作空间' }}</button>
        <button v-if="registration" type="button" class="ghost" @click="registering=!registering; error=''">{{ registering ? '已有账户，去登录' : '创建新账户' }}</button>
        <p v-else class="access-footnote">账户由管理员开通。如需访问，请联系你的管理员。</p>
      </form>
    </section>
  </main>
</template>

<style scoped>
.access-page{min-height:100vh;display:grid;grid-template-columns:1.15fr 1fr;background:var(--bg);color:var(--text)}
.access-editorial{position:relative;display:flex;flex-direction:column;justify-content:center;padding:10vw 9vw;border-right:1px solid var(--border);background:rgba(136,105,67,.035)}
.access-index{font:11px ui-monospace,monospace;letter-spacing:.18em;color:var(--muted)}
.access-rule{height:1px;width:56px;margin:48px 0 24px;background:#ac7448}
.access-kicker{font-size:14px;letter-spacing:.12em;color:var(--muted)}
.access-editorial h1{font-family:"Noto Serif SC","Songti SC","SimSun",serif;font-weight:500;font-size:clamp(46px,4.5vw,80px);line-height:1.28;letter-spacing:-.04em;margin:12px 0 30px}
.access-caption{font-size:14px;line-height:2;color:var(--muted)}
.access-stamp{position:absolute;bottom:8vh;right:6vw;border:1px solid #a25e41;color:#a25e41;writing-mode:vertical-rl;font-family:serif;padding:10px 7px;letter-spacing:.2em}
.access-form{align-self:center;justify-self:center;width:min(360px,calc(100% - 48px));padding:64px 0}
.access-form h2{font:500 32px "Noto Serif SC","Songti SC","SimSun",serif;margin:24px 0 14px}
.access-form p{font-size:13px;line-height:1.8}.access-form form{display:grid;gap:12px;margin-top:36px}
.access-form label{font-size:12px;margin-top:12px}.access-form input{width:100%;min-height:46px;padding:12px;background:var(--panel);color:var(--text);border:1px solid var(--border-strong);border-radius:var(--radius-sm);font:inherit}
.access-form input:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.access-submit{width:100%;min-height:46px;margin-top:18px}.access-footnote{color:var(--muted);margin-top:18px}
@media(max-width:740px){.access-page{grid-template-columns:1fr}.access-editorial{padding:40px 28px;border-right:0;border-bottom:1px solid var(--border)}.access-editorial h1{font-size:40px}.access-rule,.access-caption,.access-stamp{display:none}.access-form{padding:40px 0}.access-kicker{margin-top:24px}}
@media(prefers-reduced-motion:reduce){*{animation:none!important}}
</style>
