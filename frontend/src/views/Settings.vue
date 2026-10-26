<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,fail,match,page,confirm,type DataRow,type User} from '../api';
const props=defineProps<{user:User}>();
const emit=defineEmits<{changePassword:[]}>();
const active=ref('profile'),rows=ref<DataRow[]>([]),search=ref(''),createOpen=ref(false),
 form=ref({username:'',displayName:'',roleCode:'READER',password:''});
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['username','displayName'])));
const ops=ref<DataRow|null>(null),opsBusy=ref(false);
async function loadOps(){try{opsBusy.value=true;ops.value=await api('/api/ops/overview')}catch(e){fail(e)}finally{opsBusy.value=false}}
watch(active,next=>{if(next==='ops')void loadOps()});
async function refresh(){if(props.user.roleCode==='ADMIN')try{rows.value=await api('/api/users')}catch(e){fail(e)}}
async function save(){try{await api('/api/users','POST',form.value);createOpen.value=false;await refresh()}catch(e){fail(e)}}
async function toggle(row:DataRow){try{await api('/api/users/'+row.id+'/toggle','POST');await refresh()}catch(e){fail(e)}}
onMounted(()=>void refresh());defineExpose({refresh});
</script>
<template>
 <a-tabs v-model:active-key="active" class="settings-tabs">
  <a-tab-pane key="profile" tab="账户信息"><section class="panel"><div class="panel-head"><h2>当前账户</h2><a-button type="primary" @click="emit('changePassword')">修改密码</a-button></div>
   <a-descriptions :column="1" size="small" style="padding:14px"><a-descriptions-item label="姓名">{{user.displayName}}</a-descriptions-item><a-descriptions-item label="用户名">{{user.username}}</a-descriptions-item><a-descriptions-item label="角色">{{user.roleCode==='ADMIN'?'管理员':user.roleCode==='APPROVER'?'审批人':'普通用户'}}</a-descriptions-item></a-descriptions>
  </section></a-tab-pane>
  <a-tab-pane v-if="user.roleCode==='ADMIN'" key="ops" tab="运行监控"><section class="panel">
  <div class="panel-head"><h2>运行监控</h2><a-button :loading="opsBusy" @click="loadOps">刷新指标</a-button></div>
  <a-descriptions :column="1" bordered size="small" style="padding:14px" v-if="ops">
   <a-descriptions-item label="启用 Agent">{{ops.totalAgents}}</a-descriptions-item>
   <a-descriptions-item label="启用数据源">{{ops.totalSources}}</a-descriptions-item>
   <a-descriptions-item label="待审批">{{ops.pendingApprovals}}</a-descriptions-item>
   <a-descriptions-item label="24 小时访问">{{ops.readsLast24h}}</a-descriptions-item>
   <a-descriptions-item label="24 小时失败或拒绝">{{ops.failedLast24h}}</a-descriptions-item>
   <a-descriptions-item label="24 小时 MCP 错误">{{ops.mcpErrorsLast24h}}</a-descriptions-item>
   <a-descriptions-item label="锁定账户">{{ops.loginLockedAccounts}}</a-descriptions-item>
  </a-descriptions></section></a-tab-pane>
 <a-tab-pane v-if="user.roleCode==='ADMIN'" key="users" tab="用户管理"><section class="panel">
   <div class="panel-head"><h2>系统用户</h2></div>
   <div class="toolbar"><a-input-search v-model:value="search" class="search" allow-clear placeholder="搜索姓名或用户名"/><a-button type="primary" @click="form={username:'',displayName:'',roleCode:'READER',password:''};createOpen=true">＋ 新增用户</a-button><a-button class="toolbar-refresh" @click="refresh">刷新</a-button></div>
   <a-table :data-source="filtered" row-key="id" :pagination="page" :scroll="{x:670}">
    <a-table-column title="姓名" data-index="displayName"/><a-table-column title="用户名" data-index="username"/>
    <a-table-column title="角色"><template #default="{record}">{{record.roleCode==='ADMIN'?'管理员':record.roleCode==='APPROVER'?'审批人':'普通用户'}}</template></a-table-column>
    <a-table-column title="状态"><template #default="{record}"><a-tag :color="record.enabled?'green':'red'">{{record.enabled?'正常':'停用'}}</a-tag></template></a-table-column>
    <a-table-column title="操作"><template #default="{record}"><a-button type="link" :disabled="record.username==='admin'||record.username===user.username" @click="confirm('确认变更此用户状态？',()=>toggle(record))">{{record.enabled?'停用':'启用'}}</a-button></template></a-table-column>
   </a-table></section></a-tab-pane>
  <a-tab-pane v-if="user.roleCode==='ADMIN'" key="security" tab="安全参数">
   <section class="panel"><div class="panel-head"><h2>安全参数</h2></div>
    <a-descriptions :column="1" bordered style="padding:14px">
     <a-descriptions-item label="数据库访问">仅 MySQL / StarRocks 只读账号</a-descriptions-item>
     <a-descriptions-item label="授权方式">平台表字段白名单 ∩ Agent 授权</a-descriptions-item>
     <a-descriptions-item label="单次安全上限">200 行</a-descriptions-item>
     <a-descriptions-item label="未匹配规则">默认拒绝</a-descriptions-item>
     <a-descriptions-item label="审批机制">指定真实系统审批人，不允许自己审批</a-descriptions-item>
    </a-descriptions>
   </section>
  </a-tab-pane>
 </a-tabs>
 <a-modal v-model:open="createOpen" title="新增内部用户" ok-text="创建" @ok="save">
  <a-form layout="vertical"><a-form-item label="姓名"><a-input v-model:value="form.displayName"/></a-form-item>
   <a-form-item label="用户名"><a-input v-model:value="form.username"/></a-form-item>
   <a-form-item label="角色"><a-select v-model:value="form.roleCode" :options="[{value:'ADMIN',label:'管理员'},{value:'APPROVER',label:'审批人'},{value:'READER',label:'普通用户'}]"/></a-form-item>
   <a-form-item label="首次登录密码"><a-input-password v-model:value="form.password" autocomplete="new-password"/></a-form-item></a-form>
 </a-modal>
</template>
