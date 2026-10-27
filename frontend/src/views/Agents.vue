<script setup lang="ts">
import {message} from 'ant-design-vue';
import {computed,onMounted,ref,watch} from 'vue';
import dayjs from 'dayjs';
import {api,stamp,match,page,fail,confirm,stateName,stateColor,type DataRow,type User} from '../api';
const props=defineProps<{user:User;initialDetailId?:string}>();
const maxRowsPerQuery=ref(200);
async function loadQueryLimit(){try{const limits=await api<{maxRowsPerQuery:number}>('/api/security/limits');maxRowsPerQuery.value=limits.maxRowsPerQuery}catch(e){fail(e)}}
const rows=ref<DataRow[]>([]),sources=ref<DataRow[]>([]),users=ref<DataRow[]>([]),selected=ref<DataRow|null>(null),search=ref(''),tab=ref('grants'),secret=ref(''),createOpen=ref(false),form=ref({name:'',ownerId:'',expiresAt:'2099-01-31T23:59:59'}),grants=ref<DataRow>({tables:[],columns:[]}),
 sourceId=ref(''),tableName=ref(''),columnName=ref(''),expiry=ref(30),testResult=ref<DataRow|null>(null),testBusy=ref(false),rotationExpiry=ref('2099-01-31T23:59:59'),usage=ref<DataRow>({stats:{},recent:[]}),editingGrants=ref(false),grantBusy=ref(false),grantOptions=ref<DataRow[]>([]),grantFields=ref<Record<string,string[]>>({}),grantOriginal=ref<Record<string,string[]>>({}),viewMode=ref<'view'|'edit'>('view'),expandedSources=ref<string[]>([]),grantSource=ref(''),selectedGrantTable=ref(''),grantSearch=ref('');
const mcpEndpoint=window.location.origin+'/mcp';
const restEndpoint=window.location.origin+'/api/v1/agent';
const exampleTab=ref('mcp');
const mcpExample=computed(()=>`codex mcp add dataguard --url ${mcpEndpoint} --bearer-token-env-var DATAGUARD_TOKEN`);
const javaExample=computed(()=>`// Java 21 · 使用环境变量 DATAGUARD_TOKEN\nvar client = java.net.http.HttpClient.newHttpClient();\nvar req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("${restEndpoint}/datasources"))\n  .header("Authorization", "Bearer " + System.getenv("DATAGUARD_TOKEN"))\n  .GET().build();\nvar res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());\nSystem.out.println(res.statusCode() + " " + res.body());`);
const pythonExample=computed(()=>`# pip install requests\nimport os, requests\nbase = "${restEndpoint}"\nheaders = {"Authorization": "Bearer " + os.environ["DATAGUARD_TOKEN"]}\nr = requests.get(base + "/datasources", headers=headers, timeout=15)\nr.raise_for_status()\nprint(r.json())`);
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['name','ownerName'])));
const sourceName=(id:string)=>sources.value.find(s=>s.id===id)?.name||'数据源';
const groupedGrants=computed(()=>{const m=new Map<string,{sourceId:string;name:string;tables:DataRow[]}>();for(const t of (viewMode.value==='edit'?grantOptions.value:grants.value.tables||[])){const id=String(t.sourceId);if(!m.has(id))m.set(id,{sourceId:id,name:sourceName(id),tables:[]});m.get(id)!.tables.push(t)}return [...m.values()]});
const activeGrantGroup=computed(()=>groupedGrants.value.find(g=>g.sourceId===grantSource.value));
const visibleGrantTables=computed(()=>activeGrantGroup.value?.tables.filter(x=>String(x.tableName).toLowerCase().includes(grantSearch.value.toLowerCase()))||[]);
const activeGrantRow=computed(()=>activeGrantGroup.value?.tables.find(x=>x.tableName===selectedGrantTable.value));
const grantKey=(source:string,table:string)=>source+'::'+table;
const columnsFor=(t:DataRow)=>(grants.value.columns||[]).filter((c:DataRow)=>c.sourceId===t.sourceId&&c.tableName===t.tableName);
const activeKey=computed(()=>grantKey(grantSource.value,selectedGrantTable.value));
const activeOption=computed(()=>grantOptions.value.find(t=>t.sourceId===grantSource.value&&t.tableName===selectedGrantTable.value));
const selectedColumns=computed(()=>grantFields.value[activeKey.value]||[]);
const selectedAll=computed(()=>!!activeOption.value?.columns?.length&&selectedColumns.value.length===activeOption.value.columns.length);
const activeTableEnabled=computed(()=>selectedColumns.value.length>0);
function setGrantField(column:string,enabled:boolean){if(!activeTableEnabled.value)return;const current=grantFields.value[activeKey.value]||[];grantFields.value[activeKey.value]=enabled?[...new Set([...current,column])]:current.filter(c=>c!==column)}
const grantDirty=computed(()=>Object.keys(grantFields.value).some(k=>JSON.stringify([...(grantFields.value[k]||[])].sort())!==JSON.stringify([...(grantOriginal.value[k]||[])].sort())));
function selectGrantTable(name:string){selectedGrantTable.value=name}
function toggleGrantTable(t:DataRow,enabled:boolean){const k=grantKey(t.sourceId,t.tableName);grantFields.value[k]=enabled?[...t.columns]:[];}
function toggleGrantAll(){if(!activeOption.value||!activeTableEnabled.value)return;grantFields.value[activeKey.value]=selectedAll.value?[]:[...activeOption.value.columns]}
async function loadGrantOptions(){if(!selected.value||props.user.roleCode!=='ADMIN')return;grantBusy.value=true;
 try{const all=await api<DataRow[]>('/api/agents/'+selected.value.id+'/grant-options');grantOptions.value=all;
  grantOriginal.value={};grantFields.value={};for(const t of all){const k=grantKey(t.sourceId,t.tableName);grantOriginal.value[k]=[...t.selectedColumns];grantFields.value[k]=[...t.selectedColumns]}
 }catch(e){fail(e)}finally{grantBusy.value=false}}
async function saveGrantChanges(){if(!selected.value||!grantDirty.value)return;grantBusy.value=true;
 try{await api('/api/agents/'+selected.value.id+'/grants','PUT',{tables:grantOptions.value.filter(t=>grantFields.value[grantKey(t.sourceId,t.tableName)]?.length).map(t=>({sourceId:t.sourceId,tableName:t.tableName,columns:grantFields.value[grantKey(t.sourceId,t.tableName)]})),expectedColumns:(grants.value.columns||[]).map((c:DataRow)=>c.sourceId+'::'+c.tableName+'::'+c.columnName).sort()});
  grants.value=await api('/api/agents/'+selected.value.id+'/grants');await loadGrantOptions();
 }catch(e){fail(e)}finally{grantBusy.value=false}}
const status=(r:DataRow)=>r.enabled?'启用':'已停用';
async function refresh(){try{rows.value=await api('/api/agents')}catch(e){fail(e)}}
async function open(row:DataRow,mode:'view'|'edit'='view'){if(mode==='edit'&&row.enabled){message.warning('请先停用 Agent，再编辑授权');return}viewMode.value=mode;selected.value=row;tab.value='grants';editingGrants.value=mode==='edit'&&props.user.roleCode==='ADMIN';expandedSources.value=[];grantSource.value='';selectedGrantTable.value='';grantSearch.value='';usage.value={stats:{},recent:[]};try{grants.value=await api('/api/agents/'+row.id+'/grants');if(props.user.roleCode==='ADMIN'){sources.value=await api('/api/sources');if(mode==='edit')await loadGrantOptions()}}catch(e){fail(e)}}
async function testAgent(){if(!selected.value)return;try{testBusy.value=true;testResult.value=await api('/api/agents/'+selected.value.id+'/check')}catch(e){fail(e)}finally{testBusy.value=false}}
async function rotate(){if(!selected.value)return;const id=selected.value.id;try{
 const updated=await api<DataRow>('/api/agents/'+id+'/rotate','POST',{expiresAt:rotationExpiry.value});
 secret.value=updated.token;await refresh();
}catch(e){fail(e)}}
async function loadUsage(){if(!selected.value)return;try{usage.value=await api('/api/agents/'+selected.value.id+'/usage')}catch(e){fail(e)}}
watch(tab,next=>{if(next==='usage')void loadUsage()});
async function openCreate(){try{if(props.user.roleCode==='ADMIN')users.value=await api('/api/users');form.value={name:'',ownerId:'',expiresAt:'2099-01-31T23:59:59'};createOpen.value=true}catch(e){fail(e)}}
async function save(){try{const data=await api<DataRow>('/api/agents','POST',{...form.value,ownerId:form.value.ownerId||null});createOpen.value=false;secret.value=data.token;await refresh()}catch(e){fail(e)}}
async function copyExample(){try{await navigator.clipboard.writeText(exampleTab.value==='java'?javaExample.value:exampleTab.value==='python'?pythonExample.value:mcpExample.value);message.success('样例已复制')}catch{message.error('复制失败，请手动选择代码')}}
async function copySecret(){try{await navigator.clipboard.writeText(secret.value);message.success('令牌已复制')}catch{message.error('复制失败，请手动复制令牌')}}
async function deleteAgent(row:DataRow){try{await api('/api/agents/'+row.id,'DELETE');if(selected.value?.id===row.id)selected.value=null;await refresh()}catch(e){fail(e)}}
async function toggleAgent(row:DataRow){try{const data=await api<DataRow>('/api/agents/'+row.id+'/toggle','POST');if(data.token)secret.value=data.token;await refresh()}catch(e){fail(e)}}
const emit=defineEmits<{'detail-change':[value:{open:boolean;name:string;id:string}]}>();
watch(selected,value=>emit('detail-change',{open:Boolean(value),name:String(value?.name||''),id:String(value?.id||'')}));
onMounted(async()=>{await loadQueryLimit();await refresh();if(props.initialDetailId){const item=rows.value.find(x=>x.id===props.initialDetailId);if(item)await open(item,'view')}});defineExpose({refresh});
</script>
<template>
<section v-if="!selected" class="panel">
 <div class="toolbar"><a-button type="primary" @click="openCreate">＋ 新建 Agent</a-button><a-input-search v-model:value="search" class="search" allow-clear placeholder="搜索 Agent 名称或所属用户"/><a-button class="toolbar-refresh" @click="refresh">刷新</a-button></div>
 <a-table :data-source="filtered" :pagination="page" row-key="id" :scroll="{x:770}">
  <a-table-column title="序号" :width="72"><template #default="{record}">{{filtered.findIndex(item=>item.id===record.id)+1}}</template></a-table-column>
 <a-table-column title="Agent 名称"><template #default="{record}"><a-button type="link" class="name-link" @click="open(record,'view')">♧ {{record.name}}</a-button></template></a-table-column>
 <a-table-column title="所属用户" data-index="ownerName"/>
 <a-table-column title="状态"><template #default="{record}"><a-tag :color="record.enabled?'green':'default'">{{status(record)}}</a-tag></template></a-table-column>
 <a-table-column title="最近访问"><template #default="{record}">{{stamp(record.lastAccess)}}</template></a-table-column>
 <a-table-column title="操作" :width="190"><template #default="{record}"><a-space size="small" class="table-row-actions"><a-button class="row-view-btn" @click="open(record,'view')">查看</a-button><a-dropdown v-if="user.roleCode==='ADMIN'" :trigger="['click']"><a-button class="row-more-btn">更多 <svg class="row-more-chevron" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="m4 6 4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg></a-button><template #overlay><a-menu class="row-more-menu">
  <a-menu-item :disabled="!!record.enabled" @click="open(record,'edit')"><span class="row-menu-icon">✎</span>编辑授权</a-menu-item>
  <a-menu-item @click="confirm(record.enabled?'停用 Agent 后无法使用原 Token 访问，确定停用？':'启用此 Agent？若 Token 已撤销或过期，将签发新 Token（仅展示一次）。',()=>toggleAgent(record))"><span class="row-menu-icon">◷</span>{{record.enabled?'停用 Agent':'启用 Agent'}}</a-menu-item>
  <a-menu-divider/>
  <a-menu-item danger :disabled="!!record.enabled" @click="confirm('删除 Agent「'+record.name+'」？将立即禁用令牌并移除授权，列表不再显示，历史审批和审计记录继续保留。',()=>deleteAgent(record))"><span class="row-menu-icon">♜</span>删除 Agent</a-menu-item>
 </a-menu></template></a-dropdown></a-space></template></a-table-column>
 </a-table></section>
 <section v-else class="panel detail-page" aria-label="Agent 详情页面">
 <teleport to="#workspace-actions"><a-button @click="selected=null">返回 Agent 列表</a-button></teleport>
 <div class="detail-content">
  <template v-if="selected"><div class="drawer-heading"><strong>{{selected.name}}</strong><a-tag :color="selected.enabled?'green':'default'">{{status(selected)}}</a-tag></div>
   <a-tabs v-model:active-key="tab">
    <a-tab-pane key="info" tab="基本信息"><a-descriptions :column="1" size="small" bordered>
     <a-descriptions-item label="Agent ID">{{selected.id}}</a-descriptions-item>
     <a-descriptions-item label="所属用户">{{selected.ownerName}}</a-descriptions-item>
     <a-descriptions-item label="到期时间">{{stamp(selected.expiresAt)}}<a-tag v-if="new Date(selected.expiresAt).getTime()<=Date.now()" color="orange">Token 已过期</a-tag></a-descriptions-item>
     <a-descriptions-item label="MCP 服务地址">{{mcpEndpoint}}</a-descriptions-item><a-descriptions-item label="REST API 地址">{{restEndpoint}}</a-descriptions-item><a-descriptions-item label="Agent 身份">{{selected.id}}（通过独立 Bearer Token 认证）</a-descriptions-item>
    </a-descriptions>
    <div class="drawer-actions" style="align-items:center;flex-wrap:wrap">
     <a-button :loading="testBusy" @click="testAgent">接入检测</a-button>
     <a-date-picker v-if="user.roleCode==='ADMIN'" :value="dayjs(rotationExpiry)" show-time format="YYYY-MM-DD HH:mm:ss" @change="(date:any)=>rotationExpiry=date?date.format('YYYY-MM-DDTHH:mm:ss'):''"/>
     <a-button v-if="user.roleCode==='ADMIN'" @click="confirm('轮换 Token 后，原凭据立即永久失效，新 Token 仅展示一次。确定继续？',rotate)">轮换 Token</a-button>
    </div>
    <a-alert v-if="testResult" :type="testResult.ready?'success':'warning'" show-icon
      :message="testResult.ready?'Agent 授权就绪':'Agent 尚未满足接入条件'"
      :description="'有效授权字段：'+(testResult.authorizedColumns??0)+' · Agent 使用独立令牌访问共享 MCP 地址：'+mcpEndpoint"
      style="margin-top:12px"/>
    </a-tab-pane>
    <a-tab-pane key="grants" tab="访问授权">
     <div class="drawer-section-title">可访问的数据源和表</div>
     <div v-if="viewMode==='view'&&!(grants.tables||[]).length" class="subtle">尚未为当前 Agent 授权数据表。</div>
     <div class="agent-grant-picker">
      <div class="agent-grant-pick-source"><span>数据源</span><a-select :value="grantSource||undefined" placeholder="请选择数据源" :options="groupedGrants.map(x=>({value:x.sourceId,label:x.name}))" @change="(value:string)=>{grantSource=value;selectedGrantTable='';grantSearch=''}"/></div>
      <div class="picker-two-pane">
       <div class="picker-tables"><div class="picker-pane-head">数据表 <span>{{visibleGrantTables.length}}</span></div><a-input v-model:value="grantSearch" class="picker-search" allow-clear placeholder="搜索数据表"/>
        <div class="picker-list"><div v-for="item in visibleGrantTables" :key="item.tableName" class="source-table-choice" :class="{active:selectedGrantTable===item.tableName}">
         <a-checkbox v-if="viewMode==='edit'" :checked="!!grantFields[grantKey(item.sourceId,item.tableName)]?.length" :disabled="grantBusy" @change="toggleGrantTable(item,$event.target.checked)"/>
         <span v-else class="picker-checkbox">{{columnsFor(item).length?'✓':''}}</span>
         <button type="button" @click="selectGrantTable(item.tableName)">{{item.tableName}}</button>
         <small v-if="(viewMode==='edit'?grantFields[grantKey(item.sourceId,item.tableName)]?.length:columnsFor(item).length)">已授权</small>
        </div><a-empty v-if="!visibleGrantTables.length" :image="undefined" description="暂无数据表"/></div>
       </div>
       <div class="picker-columns"><div class="picker-pane-head"><span>字段 <span>{{viewMode==='edit'?selectedColumns.length:activeGrantRow?columnsFor(activeGrantRow).length:0}} / {{viewMode==='edit'?(activeOption?.columns||[]).length:activeGrantRow?columnsFor(activeGrantRow).length:0}}</span></span>
        <a-button v-if="viewMode==='edit'" type="link" size="small" :disabled="!activeOption||!activeTableEnabled||grantBusy" @click="toggleGrantAll">{{selectedAll?'取消全选':'全选'}}</a-button></div>
        <div v-if="activeGrantRow" class="source-field-list">
         <template v-if="viewMode==='edit'"><div v-for="column in activeOption?.columns||[]" :key="column" class="source-field-row"><a-checkbox :checked="selectedColumns.includes(column)" :disabled="!activeTableEnabled||grantBusy" @change="setGrantField(column,$event.target.checked)">{{column}}</a-checkbox></div></template>
         <template v-else><div v-for="c in columnsFor(activeGrantRow)" :key="c.columnName" class="source-field-row"><a-checkbox checked disabled>{{c.columnName}}</a-checkbox></div></template>
        </div><a-empty v-else :image="undefined" description="请选择左侧数据表"/>
       </div>
      </div>
     </div>
     <div class="source-grant-footer"><span class="subtle">最大读取行数：{{maxRowsPerQuery}} 行 · 取消表勾选将清空字段，点击保存后生效。</span><a-button v-if="viewMode==='edit'" type="primary" :loading="grantBusy" :disabled="!grantDirty" @click="saveGrantChanges">保存</a-button></div>
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
  <a-table-column title="序号" :width="72"><template #default="{index}">{{index+1}}</template></a-table-column>
  <a-table-column title="时间"><template #default="{record}">{{stamp(record.createdAt)}}</template></a-table-column>
  <a-table-column title="数据表" data-index="tableName"/>
  <a-table-column title="行数" data-index="requestedRows"/>
  <a-table-column title="结果"><template #default="{record}"><a-tag :color="stateColor(record.status)">{{stateName[record.status]||'未知状态'}}</a-tag></template></a-table-column>
 </a-table>
 </a-tab-pane>
    <a-tab-pane key="examples" tab="接入样例">
     <div class="drawer-section-title">Agent 接入示例</div>
     <p class="subtle">接入前请在 Agent 管理中复制 Token，设置为环境变量 DATAGUARD_TOKEN；不要写入代码或提交到 GitHub。</p>
     <a-tabs v-model:active-key="exampleTab" size="small">
      <a-tab-pane key="mcp" tab="MCP / Codex"><p class="subtle">MCP 地址：{{mcpEndpoint}}</p><pre class="agent-example-code">{{mcpExample}}</pre></a-tab-pane>
      <a-tab-pane key="java" tab="REST / Java"><p class="subtle">基于 Java 21 HttpClient，读取当前 Agent 可见数据源。</p><pre class="agent-example-code">{{javaExample}}</pre></a-tab-pane>
      <a-tab-pane key="python" tab="REST / Python"><p class="subtle">使用 requests 获取授权的数据源列表。</p><pre class="agent-example-code">{{pythonExample}}</pre></a-tab-pane>
     </a-tabs>
     <a-button @click="copyExample">复制当前示例</a-button>
     <p class="subtle" style="margin-top:16px">查询：POST {{restEndpoint}}/queries · 审批查询：GET {{restEndpoint}}/requests/{id}</p>
    </a-tab-pane>
   </a-tabs>
  </template>
 </div>
</section>
 <a-modal v-model:open="createOpen" title="新建 Agent" ok-text="创建" @ok="save">
  <a-form layout="vertical"><a-form-item label="Agent 名称" required><a-input v-model:value="form.name"/></a-form-item>
   <a-form-item v-if="user.roleCode==='ADMIN'" label="所属用户"><a-select v-model:value="form.ownerId" placeholder="不选则归属本人" allow-clear :options="users.map(u=>({value:u.id,label:u.displayName}))"/></a-form-item>
   <a-form-item label="有效截止时间" required><a-date-picker :value="dayjs(form.expiresAt)" show-time format="YYYY-MM-DD HH:mm:ss" style="width:100%" :disabled-date="(date:any)=>date.isBefore(dayjs(),'day')||date.isAfter(dayjs('2099-12-31'),'day')" @change="(date:any)=>form.expiresAt=date?date.format('YYYY-MM-DDTHH:mm:ss'):''"/></a-form-item>
  </a-form>
 </a-modal>
 <a-modal :open="!!secret" title="Agent 访问令牌" :mask-closable="false" :closable="false" :footer="null">
  <a-alert type="warning" show-icon message="令牌仅展示一次，请复制并安全保存，关闭后无法再次查看。"/>
  <div class="agent-secret-value" role="textbox" aria-label="只读 Agent 访问令牌" aria-readonly="true">{{secret}}</div>
  <div class="agent-secret-actions"><a-button type="primary" @click="copySecret">复制令牌</a-button><a-button @click="secret=''">关闭</a-button></div>
 </a-modal>
</template>