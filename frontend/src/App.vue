<script setup lang="ts">
import {computed,onMounted,onUnmounted,reactive,ref,watch} from 'vue';
import {
 DashboardOutlined,DatabaseOutlined,RobotOutlined,SafetyCertificateOutlined,
 AuditOutlined,FileSearchOutlined,SettingOutlined,MenuOutlined,ReloadOutlined,
 BellOutlined,LogoutOutlined,DownOutlined,LockOutlined,UserOutlined,EyeOutlined,EyeInvisibleOutlined,SecurityScanOutlined
} from '@ant-design/icons-vue';
import {api,fail,initCsrf,login,logout,type User,type DataRow} from './api';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import {useRoute,useRouter} from 'vue-router';
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
  components:{Table:{headerBg:'#f9fbfd',headerColor:'#728097',cellPaddingBlock:9,cellPaddingInline:12},
   Button:{borderRadius:5},Menu:{itemSelectedBg:'#eaf2ff'}}};
const route=useRoute(),router=useRouter();
const allowedPages=['dashboard','sources','agents','rules','approvals','records','settings'];
function routePage(){const value=String(route.name||'dashboard');return value==='record-detail'?'records':allowedPages.includes(value)?value:'dashboard';}
const user=ref<User|null>(null),ready=ref(false),page=ref(routePage()),
  loading=ref(false),error=ref(''),
  currentPassword=ref(''),nextPassword=ref(''),changeOpen=ref(false),passwordBusy=ref(false),
  menuOpen=ref(false),reloadKey=ref(0);
const recordId=ref<string|null>(null);
const recordStatus=ref('all'),approvalScope=ref('');
// The list has exactly one title. Only an opened record/entity uses a breadcrumb.
const detailOpen=ref(false);
const detailName=ref('');
function onDetailChange(value:{open:boolean;name?:string}){detailOpen.value=value.open;detailName.value=value.open?(value.name||''):'';}
const sectionTitle=computed(()=>items.find(i=>i.id===page.value)?.label||'工作台');
const detailTitle=computed(()=>detailName.value||({
 sources:'数据源详情',agents:'Agent 详情',rules:'规则详情',
 approvals:'审批详情',records:'访问详情'
} as Record<string,string>)[page.value]||'详情');
const notices=ref<DataRow[]>([]),unread=ref(0);
async function fetchNotices(){
 if(!user.value)return;
 try{const v=await api<{unread:number;items:DataRow[]}>('/api/notifications');unread.value=v.unread;notices.value=v.items;}
 catch{unread.value=0;notices.value=[];}
}
async function readNotice(id:number){
 try{await api('/api/notifications/'+id+'/read','POST');await fetchNotices();navigate('approvals',{scope:'pending'});}
 catch(e){fail(e);}
}
let noticeInterval:ReturnType<typeof setInterval>|undefined;
watch(user,v=>{if(v)void fetchNotices();else{unread.value=0;notices.value=[]}});
onUnmounted(()=>{if(noticeInterval)clearInterval(noticeInterval)});
const loginModel=reactive({username:'',password:''});
const passwordVisible=ref(false);
const items=[
 {id:'dashboard',label:'工作台',icon:DashboardOutlined},
 {id:'sources',label:'数据源',icon:DatabaseOutlined},
 {id:'agents',label:'Agent 管理',icon:RobotOutlined},
 {id:'rules',label:'访问规则',icon:SafetyCertificateOutlined},
 {id:'approvals',label:'审批中心',icon:AuditOutlined},
 {id:'records',label:'访问记录',icon:FileSearchOutlined},
 {id:'settings',label:'系统设置',icon:SettingOutlined}
];
const title=computed(()=>detailOpen.value?detailTitle.value:sectionTitle.value);
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
async function signin(){loading.value=true;error.value='';try{user.value=await login(loginModel.username,loginModel.password);loginModel.password='';changeOpen.value=Boolean(user.value.mustChangePassword)}catch(e){error.value=e instanceof Error?e.message:'登录失败'}finally{loading.value=false}}
async function changePassword(){
 try{passwordBusy.value=true;const result=await api<{reauthenticate:boolean}>('/api/password','POST',{currentPassword:currentPassword.value,newPassword:nextPassword.value});
  if(result.reauthenticate){user.value=null;changeOpen.value=false;currentPassword.value='';nextPassword.value='';await initCsrf();}
 }catch(e){fail(e)}finally{passwordBusy.value=false}
}
function navigate(name:string,condition?:{status?:string;scope?:string}){
 // Navigating to the current section from a detail breadcrumb must remount its view,
 // otherwise the child's local selected record would remain open under the list heading.
 if(name===page.value&&detailOpen.value)reloadKey.value++;
 detailOpen.value=false;detailName.value='';recordId.value=null;recordStatus.value=condition?.status||'all';approvalScope.value=condition?.scope||'';
 page.value=name;menuOpen.value=false;
 void router.push({name:name as 'dashboard',query:condition?.scope?{scope:condition.scope}:condition?.status?{status:condition.status}:{}});
}
function openRecord(id:string){detailOpen.value=true;detailName.value='';recordId.value=id;page.value='records';menuOpen.value=false;void router.push({name:'record-detail',params:{id}})}
watch(()=>route.fullPath,()=>{
 const next=routePage();const id=route.name==='record-detail'?String(route.params.id||''):'';
 if(next!==page.value||id!==recordId.value){page.value=next;recordId.value=id||null;reloadKey.value++;}
 if(!id){detailOpen.value=false;detailName.value='';}
 recordStatus.value=String(route.query.status||'all');approvalScope.value=String(route.query.scope||'');
 menuOpen.value=false;
});
function refresh(){if(route.name==='record-detail')void router.replace({name:'records'});detailOpen.value=false;detailName.value='';recordId.value=null;reloadKey.value++}
onMounted(()=>{if(route.name==='record-detail'){recordId.value=String(route.params.id||'');detailOpen.value=true;}approvalScope.value=String(route.query.scope||'');recordStatus.value=String(route.query.status||'all');void start();noticeInterval=setInterval(()=>{if(user.value)void fetchNotices()},60000)});
</script>
<template>
<a-config-provider :theme="theme" :locale="zhCN">
 <div v-if="!ready" class="app-loading"><a-spin size="large"/></div>
 <main v-else-if="!user" class="auth-layout">
  <section class="auth-brand-panel" aria-label="DataGuard 产品介绍">
   <img class="auth-scene" src="/assets/dataguard-login-illustration.svg?v=2" alt="" aria-hidden="true"/>
   <div class="auth-brand-top">
    <img class="auth-brand-mark" src="/assets/dataguard-mark.svg" alt="DataGuard" width="54" height="59"/>
    <span class="auth-brand-copy"><strong class="brand-wordmark">Data<span>Guard</span></strong><small>数据守卫</small></span>
   </div>
   <div class="auth-brand-body">
    <div class="auth-eyebrow">DATA ACCESS GOVERNANCE</div>
    <h1>让每一次数据访问<br/>安全、可控、可追溯</h1>
    <p>面向 Agent 与企业数据源的统一安全访问管理平台</p>
    <div class="auth-capabilities">
     <div class="auth-capability"><span class="capability-icon">01</span><div><strong>访问权限治理</strong><small>Agent 身份与数据源访问控制</small></div></div>
     <div class="auth-capability"><span class="capability-icon">02</span><div><strong>SQL 安全防护</strong><small>读取权限校验与执行审计</small></div></div>
     <div class="auth-capability"><span class="capability-icon">03</span><div><strong>读取授权审批</strong><small>数据表读取申请与审批记录</small></div></div>
    </div>
   </div>
   <div class="auth-brand-foot">DataGuard 数据守卫</div>
  </section>
  <section class="auth-form-panel">
   <div class="auth-login-card">
    <div class="auth-login-heading"><h2>登录管理平台</h2><p>请输入管理员分配的账号和密码</p></div>
    <form class="auth-form" @submit.prevent="signin">
     <div class="field"><label for="login-username">账号</label><div class="auth-field-control"><UserOutlined class="auth-field-icon"/><input id="login-username" name="username" v-model="loginModel.username" autocomplete="username" placeholder="请输入账号" required/></div></div>
     <div class="field"><label for="login-password">密码</label><div class="auth-field-control"><LockOutlined class="auth-field-icon"/><input id="login-password" name="password" :type="passwordVisible?'text':'password'" v-model="loginModel.password" autocomplete="current-password" placeholder="请输入密码" required/><button type="button" class="auth-password-toggle" :aria-label="passwordVisible?'隐藏密码':'显示密码'" :aria-pressed="passwordVisible" @click="passwordVisible=!passwordVisible"><EyeOutlined v-if="passwordVisible"/><EyeInvisibleOutlined v-else/></button></div></div>
     <button class="auth-submit" type="submit" :disabled="loading">{{loading?'登录中…':'登录'}}</button>
     <div class="auth-error" role="alert" aria-live="polite">{{error}}</div>
    </form>
    <p class="auth-support">如需开通账号或重置密码，请联系系统管理员</p>
   </div>
   <div class="auth-copyright">© 2026 DataGuard</div>
  </section>
 </main>
 <div v-else class="app-root">
  <button v-if="menuOpen" class="mobile-mask" aria-label="关闭导航" @click="menuOpen=false"/>
  <aside class="sidebar" :class="{'open':menuOpen}">
   <button class="brand" @click="navigate('dashboard')"><span class="brand-mark"><img class="sidebar-brand-mark" src="/assets/dataguard-mark.svg" alt=""/></span><strong>DataGuard</strong></button>
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
   <!-- Global bar: account and alerts only. The page title is owned by the content header. -->
   <div class="topbar-actions">
    <a-dropdown :trigger="['click']" placement="bottomRight">
     <a-badge :count="unread" :overflow-count="99" size="small">
      <button class="icon-button" aria-label="查看审批通知"><BellOutlined/></button>
     </a-badge>
     <template #overlay><a-menu class="notice-menu">
      <a-menu-item v-for="notice in notices.slice(0,8)" :key="notice.id" @click="readNotice(Number(notice.id))">
       <span :style="{fontWeight:notice.readAt?400:600}">{{notice.content}}</span>
      </a-menu-item>
      <a-menu-item v-if="!notices.length" disabled>暂无新通知</a-menu-item>
      <a-menu-divider/>
      <a-menu-item @click="navigate('approvals',{scope:'pending'})">打开审批中心</a-menu-item>
     </a-menu></template>
    </a-dropdown>
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
   <div class="workspace-head">
    <nav v-if="detailOpen" class="page-breadcrumb" aria-label="页面路径">
     <button class="breadcrumb-parent" @click="navigate(page)">{{sectionTitle}}</button>
     <span class="crumb-divider" aria-hidden="true">/</span>
     <h1>{{detailTitle}}</h1>
    </nav>
    <h1 v-else>{{sectionTitle}}</h1>
    <div class="workspace-actions"><div id="workspace-actions"></div><a-button class="refresh-action" @click="refresh"><ReloadOutlined/> 刷新</a-button></div>
   </div>
   <Dashboard v-if="page==='dashboard'" :key="'dashboard'+reloadKey" @navigate="navigate" @open-record="openRecord"/>
   <Sources v-else-if="page==='sources'&&admin" :key="'sources'+reloadKey" @detail-change="onDetailChange"/>
   <Agents v-else-if="page==='agents'" :key="'agents'+reloadKey" :user="user" @detail-change="onDetailChange"/>
   <Rules v-else-if="page==='rules'&&admin" :key="'rules'+reloadKey" @detail-change="onDetailChange"/>
   <Approvals v-else-if="page==='approvals'" :key="'approvals'+reloadKey" :user="user" :initial-scope="approvalScope" @detail-change="onDetailChange"/>
   <Records v-else-if="page==='records'" :key="'records'+reloadKey" :initial-record-id="recordId" :initial-status="recordStatus" @close-detail="recordId=null;void router.push({name:'records'})" @detail-change="onDetailChange"/>
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
