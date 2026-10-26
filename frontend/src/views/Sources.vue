<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,match,page,fail,confirm,type DataRow} from '../api';
const props=defineProps<{initialDetailId?:string}>();
const maxRowsPerQuery=ref(200);
async function loadQueryLimit(){try{const limits=await api<{maxRowsPerQuery:number}>('/api/security/limits');maxRowsPerQuery.value=limits.maxRowsPerQuery}catch(e){fail(e)}}
const rows=ref<DataRow[]>([]),search=ref(''),selected=ref<DataRow|null>(null),tab=ref('fields'),
  sourceOpen=ref(false),viewMode=ref<'view'|'edit'>('view'),form=ref({name:'',type:'MYSQL',host:'',port:3306,databaseName:'',username:'',password:''}),
  tables=ref<DataRow[]>([]),columns=ref<DataRow[]>([]),activeTable=ref(''),newTable=ref(''),newColumn=ref(''),busy=ref(false),connectBusy=ref(false),connectResult=ref<DataRow|null>(null),credentialOpen=ref(false),tableSearch=ref(''),selectedFields=ref<string[]>([]),credentialForm=ref({username:'',password:''});
const originalTables=ref<Record<string,boolean>>({});
const originalColumns=ref<Record<string,Record<string,boolean>>>({});
const draftColumns=ref<Record<string,DataRow[]>>({});
const savedMasks=ref<Record<string,Record<string,boolean>>>({});
const shownTables=computed(()=>tables.value.filter(t=>String(t.tableName).toLowerCase().includes(tableSearch.value.toLowerCase())));
const allFieldsSelected=computed(()=>columns.value.length>0&&columns.value.every(c=>c.enabled));
const fieldsBusy=ref(false);
const dirtyGrants=computed(()=>tables.value.some(t=>{
 const name=String(t.tableName);if(Boolean(t.enabled)!==Boolean(originalTables.value[name]))return true;
 return (draftColumns.value[name]||[]).some(c=>Boolean(c.enabled)!==Boolean(originalColumns.value[name]?.[c.columnName]));
}));
function toggleTableGrant(item:DataRow){if(fieldsBusy.value)return;
 item.enabled=!item.enabled;
 const name=String(item.tableName);
 if(!item.enabled){for(const c of draftColumns.value[name]||[])c.enabled=false;}
 else if(draftColumns.value[name]?.length&&!(draftColumns.value[name]||[]).some(c=>c.enabled))for(const c of draftColumns.value[name])c.enabled=true;
}
function setFieldGrant(row:DataRow,grant:boolean){row.enabled=grant;}
function toggleAllFields(){if(fieldsBusy.value)return;const grant=!allFieldsSelected.value;for(const c of columns.value)c.enabled=grant;}
async function saveGrants(){if(!selected.value||fieldsBusy.value||!dirtyGrants.value)return;
 try{fieldsBusy.value=true;const source=selected.value.id;
  for(const t of tables.value){const name=String(t.tableName);const old=Boolean(originalTables.value[name]);const enabled=Boolean(t.enabled);
   const tableUrl='/api/sources/'+source+'/tables/'+encodeURIComponent(name);
   if(!enabled){if(old)await api(tableUrl,'DELETE');continue;}
   if(!old)await api('/api/sources/'+source+'/tables','POST',{name});
   const list=draftColumns.value[name]||[];
   for(const c of list){const before=Boolean(originalColumns.value[name]?.[c.columnName]);if(Boolean(c.enabled)===before&&!(!old&&c.enabled))continue;
    const path=tableUrl+'/columns';if(c.enabled)await api(path,'POST',{name:c.columnName});else if(before)await api(path+'/'+encodeURIComponent(c.columnName),'DELETE');
   }
  }
  await loadTables();for(const name of Object.keys(draftColumns.value)){
   const original=await fetchColumns(name);draftColumns.value[name]=original;
   originalColumns.value[name]=Object.fromEntries(original.map(c=>[c.columnName,Boolean(c.enabled)]));
  }
  if(activeTable.value)columns.value=draftColumns.value[activeTable.value]||[];
  await refresh();
 }catch(e){fail(e);await loadTables();originalColumns.value={};draftColumns.value={};if(activeTable.value)await loadColumns(activeTable.value)}finally{fieldsBusy.value=false}
}
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['name','type','host'])));
async function refresh(){try{rows.value=await api('/api/sources')}catch(e){fail(e)}}
async function open(row:DataRow,mode:'view'|'edit'='view'){viewMode.value=mode;selected.value=row;tab.value='fields';activeTable.value='';columns.value=[];draftColumns.value={};originalColumns.value={};tableSearch.value='';await loadTables()}
async function loadTables(){if(!selected.value)return;try{tables.value=await api('/api/sources/'+selected.value.id+'/physical-tables')}catch{try{tables.value=await api('/api/sources/'+selected.value.id+'/tables')}catch(e){fail(e)}}originalTables.value=Object.fromEntries(tables.value.map(t=>[t.tableName,Boolean(t.enabled)]));}
async function fetchColumns(table:string):Promise<DataRow[]>{
 const base='/api/sources/'+selected.value?.id+'/tables/'+encodeURIComponent(table);
 try{const physical=await api<DataRow[]>(base+'/physical-columns');if(physical.length)return physical;}catch{}
 return api<DataRow[]>(base+'/columns');
}
async function loadColumns(table:string){
 activeTable.value=table;
 if(!draftColumns.value[table])try{
  const loaded=await fetchColumns(table);draftColumns.value[table]=loaded.map(c=>({...c,enabled:tables.value.find(t=>t.tableName===table)?.enabled?Boolean(c.enabled):false}));
  originalColumns.value[table]=Object.fromEntries(loaded.map(c=>[c.columnName,Boolean(c.enabled)]));
 }catch(e){fail(e);draftColumns.value[table]=[]}
 columns.value=draftColumns.value[table];
}
function create(){form.value={name:'',type:'MYSQL',host:'',port:3306,databaseName:'',username:'',password:''};sourceOpen.value=true}
async function editSource(row:DataRow){await open(row,'edit')}
async function toggleSource(row:DataRow){try{await api('/api/sources/'+row.id+'/toggle','POST');await refresh()}catch(e){fail(e)}}
async function deleteSource(row:DataRow){try{await api('/api/sources/'+row.id,'DELETE');if(selected.value?.id===row.id)selected.value=null;await refresh()}catch(e){fail(e)}}
async function save(){try{busy.value=true;await api('/api/sources','POST',form.value);sourceOpen.value=false;await refresh()}catch(e){fail(e)}finally{busy.value=false}}
async function testConnection(){if(!selected.value)return;try{
 connectBusy.value=true;connectResult.value=await api('/api/sources/'+selected.value.id+'/test','POST');
}catch(e){connectResult.value={ok:false};fail(e)}finally{connectBusy.value=false}}
async function updateCredentials(){if(!selected.value)return;try{
 await api('/api/sources/'+selected.value.id+'/credentials','POST',credentialForm.value);
 credentialOpen.value=false;connectResult.value=null;credentialForm.value={username:'',password:''};
}catch(e){fail(e)}}
async function toggle(){if(!selected.value)return;try{await api('/api/sources/'+selected.value.id+'/toggle','POST');selected.value=null;await refresh()}catch(e){fail(e)}}
async function addTable(){if(!selected.value||!newTable.value.trim())return;try{await api('/api/sources/'+selected.value.id+'/tables','POST',{name:newTable.value.trim()});newTable.value='';await loadTables();await refresh()}catch(e){fail(e)}}
async function removeTable(table:string){if(!selected.value)return;try{await api('/api/sources/'+selected.value.id+'/tables/'+table,'DELETE');await loadTables()}catch(e){fail(e)}}
async function addColumn(){if(!selected.value||!activeTable.value||!newColumn.value.trim())return;try{await api('/api/sources/'+selected.value.id+'/tables/'+activeTable.value+'/columns','POST',{name:newColumn.value.trim()});newColumn.value='';await loadColumns(activeTable.value)}catch(e){fail(e)}}
async function toggleAllowed(row:DataRow){
 if(row.enabled){confirm('撤销该字段的 AI 读取权限？',()=>removeColumn(row.columnName))}
 else {newColumn.value=row.columnName;await addColumn()}
}
async function toggleMask(column:string){
 if(!selected.value)return;
 try{await api('/api/sources/'+selected.value.id+'/tables/'+activeTable.value+'/columns/'+column+'/mask','POST');await loadColumns(activeTable.value)}
 catch(e){fail(e)}
}
async function removeColumn(column:string){if(!selected.value)return;try{await api('/api/sources/'+selected.value.id+'/tables/'+activeTable.value+'/columns/'+column,'DELETE');await loadColumns(activeTable.value)}catch(e){fail(e)}}
const emit=defineEmits<{'detail-change':[value:{open:boolean;name:string;id:string}]}>();
watch(selected,value=>emit('detail-change',{open:Boolean(value),name:String(value?.name||''),id:String(value?.id||'')}));
onMounted(async()=>{await loadQueryLimit();await refresh();const row=rows.value.find(x=>x.id===props.initialDetailId);if(row)await open(row,'view')});defineExpose({refresh});
</script>
<template>
<section v-if="!selected" class="panel">
 <div class="toolbar"><a-button type="primary" @click="create">＋ 新增数据源</a-button><a-input-search class="search" v-model:value="search" allow-clear placeholder="搜索数据库名称、类型或主机"/><a-button class="toolbar-refresh" @click="refresh">刷新</a-button></div>
 <a-table :data-source="filtered" row-key="id" :pagination="page" :scroll="{x:740}">
  <a-table-column title="名称"><template #default="{record}"><a-button type="link" class="name-link" @click="open(record,'view')">{{record.name}}</a-button></template></a-table-column>
  <a-table-column title="类型" data-index="type"/>
  <a-table-column title="主机地址"><template #default="{record}">{{record.host}}:{{record.port}}</template></a-table-column>
  <a-table-column title="授权表数" data-index="authorizedTables"/>
  <a-table-column title="状态"><template #default="{record}"><a-tag :color="record.enabled?'green':'red'">{{record.enabled?'正常':'已停用'}}</a-tag></template></a-table-column>
  <a-table-column title="操作" :width="190"><template #default="{record}"><a-space size="small" class="table-row-actions"><a-button class="row-view-btn" @click="open(record,'view')">查看</a-button><a-dropdown :trigger="['click']"><a-button class="row-more-btn">更多 <svg class="row-more-chevron" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="m4 6 4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg></a-button><template #overlay><a-menu class="row-more-menu"><a-menu-item @click="editSource(record)"><span class="row-menu-icon">✎</span>编辑授权</a-menu-item><a-menu-item @click="confirm(record.enabled?'确认停用数据源？Agent 将无法读取。':'确认启用数据源？恢复后仍需平台与 Agent 授权。',()=>toggleSource(record))"><span class="row-menu-icon">◷</span>{{record.enabled?'停用数据源':'启用数据源'}}</a-menu-item><a-menu-divider/><a-menu-item danger :disabled="!!record.enabled" @click="confirm('删除数据源（不可恢复）「'+record.name+'」？如存在审批历史或访问规则，将拒绝删除，请先停用。',()=>deleteSource(record))"><span class="row-menu-icon">♜</span>删除数据源</a-menu-item></a-menu></template></a-dropdown></a-space></template></a-table-column>
 </a-table>
</section>
<section v-else class="panel detail-page" aria-label="数据源详情页面">
 <teleport to="#workspace-actions"><a-button @click="selected=null">返回数据源列表</a-button></teleport>
 <div class="detail-content">
 <template v-if="selected">
  <div class="drawer-heading"><strong>{{selected.name}}</strong><a-tag :color="selected.enabled?'green':'red'">{{selected.enabled?'已开放':'已停用'}}</a-tag></div>
  <p class="subtle">{{selected.name}} · {{selected.type}} · {{selected.databaseName}}</p>
  <a-tabs v-model:active-key="tab">
   <a-tab-pane key="info" tab="基本信息">
    <a-descriptions :column="1" size="small" bordered>
     <a-descriptions-item label="连接地址">{{selected.host}}:{{selected.port}}</a-descriptions-item>
     <a-descriptions-item label="数据库">{{selected.databaseName}}</a-descriptions-item>
     <a-descriptions-item label="账号">{{selected.username}}</a-descriptions-item>
     <a-descriptions-item label="状态">{{selected.enabled?'正常':'已停用'}}</a-descriptions-item>
    </a-descriptions>
    <div class="drawer-actions" style="align-items:center;flex-wrap:wrap">
      <a-button :loading="connectBusy" @click="testConnection">测试连接</a-button>
      <a-button v-if="viewMode==='edit'" @click="credentialForm={username:selected.username,password:''};credentialOpen=true">更新凭据</a-button>
      <a-button v-if="viewMode==='edit'" :danger="selected.enabled" @click="confirm('确认更改数据源状态？',toggle)">{{selected.enabled?'停用数据源':'启用数据源'}}</a-button>
    </div>
    <a-alert v-if="connectResult" :type="connectResult.ok?'success':'error'" show-icon
      :message="connectResult.ok?'连接成功':'连接失败'"
      :description="connectResult.ok?'耗时 '+connectResult.latencyMs+' 毫秒':'请检查数据源地址、TLS 和只读凭据'"
      style="margin-top:12px"/>
   </a-tab-pane>
   <a-tab-pane key="fields" tab="字段权限">
    <a-alert v-if="viewMode==='view'" message="只读查看 · 需要更改表或字段授权，请返回列表点击编辑" type="info" show-icon style="margin-bottom:12px"/>
    <div class="picker-two-pane">
     <div class="picker-tables"><div class="picker-pane-head">数据表 <span>{{shownTables.length}}</span></div><a-input v-model:value="tableSearch" class="picker-search" allow-clear placeholder="搜索数据表"/>
      <div class="picker-list"><div v-for="item in shownTables" :key="item.tableName" class="source-table-choice" :class="{active:activeTable===item.tableName}">
       <a-checkbox :checked="!!item.enabled" :disabled="viewMode==='view'||fieldsBusy" @change="toggleTableGrant(item)"/>
       <button type="button" @click="loadColumns(item.tableName)">{{item.tableName}}</button>
       <small v-if="item.enabled">已授权</small>
      </div><a-empty v-if="!shownTables.length" :image="undefined" description="暂无数据表"/></div>
     </div>
     <div class="picker-columns"><div class="picker-pane-head"><span>字段 <span>{{columns.filter(c=>c.enabled).length}} / {{columns.length}}</span></span><a-button type="link" size="small" :disabled="viewMode==='view'||!activeTable||!columns.length||fieldsBusy||!tables.find(t=>t.tableName===activeTable)?.enabled" @click="toggleAllFields">{{allFieldsSelected?'取消全选':'全选'}}</a-button></div>
      <template v-if="activeTable"><div class="source-field-list"><div v-for="c in columns" :key="c.columnName" class="source-field-row"><a-checkbox :checked="!!c.enabled" :disabled="viewMode==='view'||fieldsBusy||!tables.find(t=>t.tableName===activeTable)?.enabled" @change="setFieldGrant(c,$event.target.checked)">{{c.columnName}}</a-checkbox><a-checkbox :checked="!!c.masked" :disabled="viewMode==='view'||!c.enabled||fieldsBusy" @change="toggleMask(c.columnName)">脱敏</a-checkbox></div></div></template><a-empty v-else :image="undefined" description="请选择左侧数据表"/>
     </div>
    </div>
    <div v-if="viewMode==='edit'" class="source-grant-footer"><span class="subtle">勾选表或字段后，点击保存才会更新权限。</span><a-button type="primary" :loading="fieldsBusy" :disabled="!dirtyGrants" @click="saveGrants">保存</a-button></div>
   </a-tab-pane>
   <a-tab-pane key="limit" tab="访问限制"><a-descriptions :column="1" bordered size="small">
    <a-descriptions-item label="平台安全上限">{{maxRowsPerQuery}} 行 / 次</a-descriptions-item>
    <a-descriptions-item label="实际规则">在访问规则中配置放行、审批和拒绝区间</a-descriptions-item>
   </a-descriptions></a-tab-pane>
  </a-tabs>
 </template>
</div>
</section>
<a-modal v-model:open="sourceOpen" title="新增数据源" :confirm-loading="busy" ok-text="保存" :width="610" @ok="save">
 <a-form layout="vertical"><div class="form-grid">
 <a-form-item label="名称" required><a-input v-model:value="form.name"/></a-form-item>
 <a-form-item label="数据库类型"><a-select v-model:value="form.type" :options="[{value:'MYSQL',label:'MySQL'},{value:'STARROCKS',label:'StarRocks'}]" @change="form.port=form.type==='STARROCKS'?9030:3306"/></a-form-item>
 <a-form-item label="IP 或主机名" required><a-input v-model:value="form.host"/></a-form-item>
 <a-form-item label="端口"><a-input-number v-model:value="form.port" :min="1" :max="65535" style="width:100%"/></a-form-item>
 <a-form-item label="数据库名"><a-input v-model:value="form.databaseName"/></a-form-item>
 <a-form-item label="只读用户名"><a-input v-model:value="form.username"/></a-form-item>
 <a-form-item class="full" label="密码"><a-input-password v-model:value="form.password" autocomplete="new-password"/></a-form-item></div></a-form>
</a-modal>
<a-modal v-model:open="credentialOpen" title="更新只读账号" ok-text="保存" @ok="updateCredentials">
  <a-form layout="vertical">
   <a-form-item label="只读用户名" required><a-input v-model:value="credentialForm.username"/></a-form-item>
   <a-form-item label="新密码" required><a-input-password v-model:value="credentialForm.password" autocomplete="new-password"/></a-form-item>
  </a-form>
 </a-modal>
</template>
