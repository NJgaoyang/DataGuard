<script setup lang="ts">
import {computed,onMounted,ref} from 'vue';
import {
 DashboardOutlined,DatabaseOutlined,RobotOutlined,SafetyCertificateOutlined,
 AuditOutlined,FileSearchOutlined,SettingOutlined,MenuOutlined,ReloadOutlined,
 BellOutlined,LogoutOutlined,DownOutlined,LockOutlined,SecurityScanOutlined
} from '@ant-design/icons-vue';
import {api,fail,initCsrf,login,logout,type User} from './api';
import Dashboard from './views/Dashboard.vue';
import Sources from './views/Sources.vue';
import Agents from './views/Agents.vue';
import Rules from './views/Rules.vue';
import Approvals from './views/Approvals.vue';
import Records from './views/Records.vue';
import Settings from './views/Settings.vue';

const theme={token:{colorPrimary:'#1766e8',colorSuccess:'#18a76e',colorError:'#e04757',
  colorWarning:'#d59529',colorText:'#203147',colorTextSecondary:'#75839a',
  colorBorder:'#e5eaf1',colorBgLayout:'#f7f9fc',borderRadius:6,fontSize:13,motion:false},
  components:{Table:{headerBg:'#f9fbfd',headerColor:'#728097',cellPaddingBlock:12,cellPaddingInline:14},
   Button:{borderRadius:5},Menu:{itemSelectedBg:'#eaf2ff'}}};
const user=ref<User|null>(null),ready=ref(false),page=ref('dashboard'),
  loading=ref(false),error=ref(''),username=ref(''),password=ref(''),
  currentPassword=ref(''),nextPassword=ref(''),changeOpen=ref(false),passwordBusy=ref(false),
  menuOpen=ref(false),reloadKey=ref(0);
const items=[
 {id:'dashboard',label:'工作台',icon:DashboardOutlined},
 {id:'sources',label:'数据源',icon:DatabaseOutlined},
 {id:'agents',label:'Agent 管理',icon:RobotOutlined},
 {id:'rules',label:'访问规则',icon:SafetyCertificateOutlined},
 {id:'approvals',label:'审批中心',icon:AuditOutlined},
 {id:'records',label:'访问记录',icon:FileSearchOutlined},
 {id:'settings',label:'系统设置',icon:SettingOutlined}
];
const title=computed(()=>items.find(i=>i.id===page.value)?.label||'工作台');
const admin=computed(()=>user.value?.roleCode==='ADMIN');
const visibleItems=computed(()=>items.filter(i=>admin.value||!['sources','rules'].includes(i.id)));
async function start(){
 ready.value=false;error.value='';
 try{
  await initCsrf();
  user.value=await api<User>('/api/session');
  changeOpen.value=Boolean(user.value.mustChangePassword);
 }catch{user.value=null}finally{ready.value=true}
}
async function signin(){loading.value=true;error.value='';try{user.value=await login(username.value,password.value);password.value='';changeOpen.value=Boolean(user.value.mustChangePassword)}catch(e){error.value=e instanceof Error?e.message:'登录失败'}finally{loading.value=false}}
async function changePassword(){
 try{passwordBusy.value=true;const result=await api<{reauthenticate:boolean}>('/api/password','POST',{currentPassword:currentPassword.value,newPassword:nextPassword.value});
  if(result.reauthenticate){user.value=null;changeOpen.value=false;currentPassword.value='';nextPassword.value='';await initCsrf();}
 }catch(e){fail(e)}finally{passwordBusy.value=false}
}
function navigate(name:string){page.value=name;menuOpen.value=false}
function refresh(){reloadKey.value++}
onMounted(()=>void start());
</script>
<template>
<a-config-provider :theme="theme">
 <div v-if="!ready" class="app-loading"><a-spin size="large"/></div>
 <main v-else-if="!user" class="login-shell">
  <div class="login-panel">
   <div class="login-brand"><SecurityScanOutlined/> <strong>MCP DataGuard</strong></div>
   <h1>登录管理平台</h1><p>AI 数据访问安全网关</p>
   <a-alert v-if="error" :message="error" type="error" class="login-error"/>
   <a-form layout="vertical" @finish="signin">
    <a-form-item label="用户名"><a-input v-model:value="username" size="large" autofocus autocomplete="username"/></a-form-item>
    <a-form-item label="密码"><a-input-password v-model:value="password" size="large" autocomplete="current-password"/></a-form-item>
    <a-button type="primary" block size="large" html-type="submit" :loading="loading">登录</a-button>
   </a-form>
  </div>
 </main>
 <div v-else class="app-root">
  <button v-if="menuOpen" class="mobile-mask" aria-label="关闭导航" @click="menuOpen=false"/>
  <aside class="sidebar" :class="{'open':menuOpen}">
   <button class="brand" @click="navigate('dashboard')"><span class="brand-mark"><SecurityScanOutlined/></span><strong>MCP DataGuard</strong></button>
   <nav aria-label="主导航">
    <template v-for="(item,index) in visibleItems" :key="item.id">
     <div v-if="item.id==='settings'" class="nav-divider"></div>
     <button class="nav-item" :class="{active:page===item.id}" :aria-current="page===item.id?'page':undefined" @click="navigate(item.id)">
      <component :is="item.icon"/><span>{{item.label}}</span>
     </button>
    </template>
   </nav>
  </aside>
  <header class="topbar">
   <button class="mobile-toggle" @click="menuOpen=!menuOpen" aria-label="打开导航"><MenuOutlined/></button>
   <span class="topbar-context"></span>
   <div class="topbar-actions">
    <a-tooltip title="待审批请求"><button class="icon-button" aria-label="打开审批中心" @click="navigate('approvals')"><BellOutlined/></button></a-tooltip>
    <a-dropdown :trigger="['click']">
     <button class="account-button"><span class="avatar">{{(user.displayName||user.username).slice(0,1)}}</span><span>{{user.displayName||user.username}}</span><DownOutlined/></button>
     <template #overlay><a-menu>
      <a-menu-item disabled>{{user.roleCode==='ADMIN'?'管理员':user.roleCode==='APPROVER'?'审批人':'普通用户'}}</a-menu-item>
      <a-menu-item @click="changeOpen=true"><LockOutlined/> 修改密码</a-menu-item>
      <a-menu-item @click="logout"><LogoutOutlined/> 退出登录</a-menu-item>
     </a-menu></template>
    </a-dropdown>
   </div>
  </header>
  <main class="workspace">
   <div class="workspace-head"><h1>{{title}}</h1><a-button @click="refresh"><ReloadOutlined/> 刷新</a-button></div>
   <Dashboard v-if="page==='dashboard'" :key="'dashboard'+reloadKey" @navigate="navigate"/>
   <Sources v-else-if="page==='sources'&&admin" :key="'sources'+reloadKey"/>
   <Agents v-else-if="page==='agents'" :key="'agents'+reloadKey" :user="user"/>
   <Rules v-else-if="page==='rules'&&admin" :key="'rules'+reloadKey"/>
   <Approvals v-else-if="page==='approvals'" :key="'approvals'+reloadKey" :user="user"/>
   <Records v-else-if="page==='records'" :key="'records'+reloadKey"/>
   <Settings v-else-if="page==='settings'" :key="'settings'+reloadKey" :user="user" @change-password="changeOpen=true"/>
  </main>
 </div>
 <a-modal v-if="user" v-model:open="changeOpen" :closable="!user.mustChangePassword" :mask-closable="false" :keyboard="!user.mustChangePassword"
  :title="user.mustChangePassword?'首次登录安全设置':'修改登录密码'" ok-text="修改并重新登录" :confirm-loading="passwordBusy" @ok="changePassword">
  <a-alert type="info" message="密码至少 12 位，需包含大小写字母、数字与特殊字符。"/>
  <a-form layout="vertical" style="margin-top:20px">
   <a-form-item label="当前密码" required><a-input-password v-model:value="currentPassword" autocomplete="current-password"/></a-form-item>
   <a-form-item label="新密码" required><a-input-password v-model:value="nextPassword" autocomplete="new-password"/></a-form-item>
  </a-form>
 </a-modal>
</a-config-provider>
</template>
