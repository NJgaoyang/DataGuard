<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,stamp,match,page,fail,confirm,type DataRow,type User} from '../api';
const props=defineProps<{user:User}>();
const rows=ref<DataRow[]>([]),sources=ref<DataRow[]>([]),users=ref<DataRow[]>([]),selected=ref<DataRow|null>(null),search=ref(''),tab=ref('grants'),secret=ref(''),createOpen=ref(false),form=ref({name:'',ownerId:'',validDays:30}),grants=ref<DataRow>({tables:[],columns:[]}),
 sourceId=ref(''),tableName=ref(''),columnName=ref(''),expiry=ref(30),testResult=ref<DataRow|null>(null),testBusy=ref(false),rotationDays=ref(30),usage=ref<DataRow>({stats:{},recent:[]}),editingGrants=ref(false);
const mcpEndpoint=window.location.origin+'/mcp';
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['name','ownerName'])));
const sourceName=(id:string)=>sources.value.find(s=>s.id===id)?.name||'数据源';
const columnsFor=(r:DataRow)=>(grants.value.columns||[]).filter((c:DataRow)=>c.sourceId===r.sourceId&&c.tableName===r.tableName);
const status=(r:DataRow)=>r.revokedAt||!r.enabled?'已停用':new Date(r.expiresAt).getTime()<Date.now()?'已过期':'正常';
async function refresh(){try{rows.value=await api('/api/agents')}catch(e){fail(e)}}
async function open(row:DataRow){selected.value=row;tab.value='grants';editingGrants.value=false;usage.value={stats:{},recent:[]};try{grants.value=await api('/api/agents/'+row.id+'/grants');if(props.user.roleCode==='ADMIN')sources.value=await api('/api/sources')}catch(e){fail(e)}}
async function testAgent(){if(!selected.value)return;try{testBusy.value=true;testResult.value=await api('/api/agents/'+selected.value.id+'/check')}catch(e){fail(e)}finally{testBusy.value=false}}
async function rotate(){if(!selected.value)return;const id=selected.value.id;try{
 const updated=await api<DataRow>('/api/agents/'+id+'/rotate','POST',{validDays:rotationDays.value});
 secret.value=updated.token;await refresh();
}catch(e){fail(e)}}
async function loadUsage(){if(!selected.value)return;try{usage.value=await api('/api/agents/'+selected.value.id+'/usage')}catch(e){fail(e)}}
watch(tab,next=>{if(next==='usage')void loadUsage()});
async function openCreate(){try{if(props.user.roleCode==='ADMIN')users.value=await api('/api/users');form.value={name:'',ownerId:'',validDays:30};createOpen.value=true}catch(e){fail(e)}}
async function save(){try{const data=await api<DataRow>('/api/agents','POST',{...form.value,ownerId:form.value.ownerId||null});createOpen.value=false;secret.value=data.token;await refresh()}catch(e){fail(e)}}
async function revoke(){if(!selected.value)return;try{await api('/api/agents/'+selected.value.id+'/revoke','POST');selected.value=null;await refresh()}catch(e){fail(e)}}
async function grantTable(){if(!selected.value||!sourceId.value||!tableName.value)return;try{await api('/api/agents/'+selected.value.id+'/tables','POST',{sourceId:sourceId.value,tableName:tableName.value});await open(selected.value)}catch(e){fail(e)}}
async function grantColumn(){if(!selected.value||!sourceId.value||!tableName.value||!columnName.value)return;try{await api('/api/agents/'+selected.value.id+'/columns','POST',{sourceId:sourceId.value,tableName:tableName.value,columnName:columnName.value});await open(selected.value)}catch(e){fail(e)}}
async function removeTable(r:DataRow){if(!selected.value)return;try{await api('/api/agents/'+selected.value.id+'/tables/'+r.sourceId+'/'+r.tableName,'DELETE');await open(selected.value)}catch(e){fail(e)}}
async function removeColumn(r:DataRow){if(!selected.value)return;try{await api('/api/agents/'+selected.value.id+'/columns/'+r.sourceId+'/'+r.tableName+'/'+r.columnName,'DELETE');await open(selected.value)}catch(e){fail(e)}}
const emit=defineEmits<{'detail-change':[open:boolean]}>();
watch(selected,value=>emit('detail-change',Boolean(value)));
onMounted(()=>void refresh());defineExpose({refresh});
</script>
<template>
<section v-if="!selected" class="panel"><teleport to="#workspace-actions"><a-button type="primary" @click="openCreate">＋ 新建 Agent</a-button></teleport>
 <div class="toolbar"><a-input-search v-model:value="search" class="search" allow-clear placeholder="搜索 Agent 名称或所属用户"/></div>
 <a-table :data-source="filtered" :pagination="page" row-key="id" :scroll="{x:770}">
 <a-table-column title="Agent 名称"><template #default="{record}"><a-button type="link" class="name-link" @click="open(record)">♧ {{record.name}}</a-button></template></a-table-column>
 <a-table-column title="所属用户" data-index="ownerName"/>
 <a-table-column title="状态"><template #default="{record}"><a-tag :color="status(record)==='正常'?'green':'red'">{{status(record)}}</a-tag></template></a-table-column>
 <a-table-column title="最近访问"><template #default="{record}">{{stamp(record.lastAccess)}}</template></a-table-column>
 <a-table-column title="操作"><template #default="{record}"><a-button type="link" @click="open(record)">查看</a-button></template></a-table-column>
 </a-table></section>
 <section v-else class="panel detail-page" aria-label="Agent 详情页面">
 <teleport to="#workspace-actions"><a-button @click="selected=null">返回 Agent 列表</a-button></teleport>
 <div class="detail-content">
  <template v-if="selected"><div class="drawer-heading"><strong>{{selected.name}}</strong><a-tag :color="status(selected)==='正常'?'green':'red'">{{status(selected)}}</a-tag></div>
   <a-tabs v-model:active-key="tab">
    <a-tab-pane key="info" tab="基本信息"><a-descriptions :column="1" size="small" bordered>
     <a-descriptions-item label="Agent ID">{{selected.id}}</a-descriptions-item>
     <a-descriptions-item label="所属用户">{{selected.ownerName}}</a-descriptions-item>
     <a-descriptions-item label="到期时间">{{stamp(selected.expiresAt)}}</a-descriptions-item>
     <a-descriptions-item label="MCP 地址">{{mcpEndpoint}}</a-descriptions-item>
    </a-descriptions>
    <div class="drawer-actions" style="align-items:center;flex-wrap:wrap">
     <a-button :loading="testBusy" @click="testAgent">接入检测</a-button>
     <a-input-number v-model:value="rotationDays" :min="1" :max="90" :step="1" style="width:90px"/>
     <a-button @click="confirm('令牌轮换后，原 Token 会立即失效，确认？',rotate)">轮换 Token</a-button>
     <a-button danger @click="confirm('撤销后令牌立即失效，确认？',revoke)">撤销 Agent</a-button>
    </div>
    <a-alert v-if="testResult" :type="testResult.ready?'success':'warning'" show-icon
      :message="testResult.ready?'Agent 授权就绪':'Agent 尚未满足接入条件'"
      :description="'有效授权字段：'+(testResult.authorizedColumns??0)+' · MCP 地址：'+mcpEndpoint"
      style="margin-top:12px"/>
    </a-tab-pane>
    <a-tab-pane key="grants" tab="访问授权">
     <div class="drawer-section-title">可访问的数据源和表</div>
     <div v-if="!(grants.tables||[]).length" class="subtle">尚未为当前 Agent 授权数据表。</div>
     <div v-for="r in grants.tables||[]" :key="r.sourceId+r.tableName" class="grant-tree">
      <div class="grant-tree-source">⌄ {{sourceName(r.sourceId)}}</div>
      <div class="grant-tree-table">
       <a-checkbox :checked="true" :disabled="!editingGrants" @change="confirm('确认撤销整张表的 Agent 授权？',()=>removeTable(r))">{{r.tableName}}</a-checkbox>
      </div>
      <div v-for="column in columnsFor(r)" :key="column.columnName" class="grant-tree-column">
       <a-checkbox :checked="true" :disabled="!editingGrants" @change="confirm('确认撤销字段授权？',()=>removeColumn(column))">{{column.columnName}}</a-checkbox>
      </div>
     </div>
     <div class="grant-limit">
      <span>最大读取行数</span><strong>200 行</strong>
      <a-button v-if="user.roleCode==='ADMIN'" size="small" @click="editingGrants=!editingGrants">{{editingGrants?'完成编辑':'编辑授权'}}</a-button>
     </div>
     <a-form v-if="user.roleCode==='ADMIN'&&editingGrants" class="grant-editor" layout="vertical">
      <a-form-item label="数据源"><a-select v-model:value="sourceId" placeholder="选择已配置的数据源" :options="sources.map(s=>({value:s.id,label:s.name}))"/></a-form-item>
      <a-form-item label="表名"><a-input v-model:value="tableName" placeholder="先配置平台表白名单"/></a-form-item>
      <a-button @click="grantTable">授权该表</a-button>
      <a-form-item label="字段名" style="margin-top:18px"><a-input v-model:value="columnName" placeholder="先配置平台字段白名单"/></a-form-item>
      <a-button type="primary" @click="grantColumn">授权该字段</a-button>
     </a-form>
     <p class="subtle">Agent 只能读取平台白名单与自身授权的交集。</p>
    </a-tab-pane>
    <a-tab-pane key="usage" tab="使用情况">
 <a-descriptions :column="1" bordered size="small">
  <a-descriptions-item label="累计访问">{{usage.stats?.requests??0}} 次</a-descriptions-item>
  <a-descriptions-item label="成功">{{usage.stats?.completed??0}} 次</a-descriptions-item>
  <a-descriptions-item label="已拒绝">{{usage.stats?.denied??0}} 次</a-descriptions-item>
  <a-descriptions-item label="最近访问">{{stamp(usage.stats?.lastAccess)}}</a-descriptions-item>
 </a-descriptions>
 <div class="drawer-section-title" style="margin-top:20px">最近访问</div>
 <a-table :data-source="usage.recent||[]" row-key="id" size="small" :pagination="false">
  <a-table-column title="时间"><template #default="{record}">{{stamp(record.createdAt)}}</template></a-table-column>
  <a-table-column title="数据表" data-index="tableName"/>
  <a-table-column title="行数" data-index="requestedRows"/>
  <a-table-column title="结果" data-index="status"/>
 </a-table>
 </a-tab-pane>
   </a-tabs>
  </template>
 </div>
</section>
 <a-modal v-model:open="createOpen" title="新建 Agent" ok-text="创建" @ok="save">
  <a-form layout="vertical"><a-form-item label="Agent 名称" required><a-input v-model:value="form.name"/></a-form-item>
   <a-form-item v-if="user.roleCode==='ADMIN'" label="所属用户"><a-select v-model:value="form.ownerId" placeholder="不选则归属本人" allow-clear :options="users.map(u=>({value:u.id,label:u.displayName}))"/></a-form-item>
   <a-form-item label="有效期（天）"><a-input-number v-model:value="form.validDays" :min="1" :max="90"/></a-form-item>
  </a-form>
 </a-modal>
 <a-modal :open="!!secret" title="保存 Agent 访问令牌" :mask-closable="false" :closable="false" ok-text="我已保存" @ok="secret=''">
  <a-alert type="warning" show-icon message="令牌仅展示一次，请在安全位置保存，不能提交到代码仓库。"/>
  <a-textarea :value="secret" readonly :rows="3" style="margin-top:16px"/>
 </a-modal>
</template>
