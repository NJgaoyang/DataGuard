<script setup lang="ts">
import {computed,onMounted,ref} from 'vue';
import {api,match,page,fail,confirm,type DataRow} from '../api';
const rows=ref<DataRow[]>([]),search=ref(''),selected=ref<DataRow|null>(null),tab=ref('fields'),
  sourceOpen=ref(false),form=ref({name:'',type:'MYSQL',host:'',port:3306,databaseName:'',username:'',password:''}),
  tables=ref<DataRow[]>([]),columns=ref<DataRow[]>([]),activeTable=ref(''),newTable=ref(''),newColumn=ref(''),busy=ref(false);
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['name','type','host'])));
async function refresh(){try{rows.value=await api('/api/sources')}catch(e){fail(e)}}
async function open(row:DataRow){selected.value=row;tab.value='fields';activeTable.value='';await loadTables()}
async function loadTables(){if(!selected.value)return;try{tables.value=await api('/api/sources/'+selected.value.id+'/tables')}catch(e){fail(e)}}
async function loadColumns(table:string){
 activeTable.value=table;
 const base='/api/sources/'+selected.value?.id+'/tables/'+table;
 try{
  const physical=await api<DataRow[]>(base+'/physical-columns');
  if(!physical.length)throw Error('No metadata available');
  columns.value=physical;
 }catch{
  try{columns.value=await api(base+'/columns')}catch(e){fail(e)}
 }
}
function create(){form.value={name:'',type:'MYSQL',host:'',port:3306,databaseName:'',username:'',password:''};sourceOpen.value=true}
async function save(){try{busy.value=true;await api('/api/sources','POST',form.value);sourceOpen.value=false;await refresh()}catch(e){fail(e)}finally{busy.value=false}}
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
onMounted(()=>void refresh());defineExpose({refresh});
</script>
<template>
<section class="panel">
 <div class="panel-head"><h2>数据源</h2><a-button type="primary" @click="create">＋ 新增数据源</a-button></div>
 <div class="toolbar"><a-input-search class="search" v-model:value="search" allow-clear placeholder="搜索数据库名称、类型或主机"/></div>
 <a-table :data-source="filtered" row-key="id" :pagination="page" :scroll="{x:740}">
  <a-table-column title="名称"><template #default="{record}"><a-button type="link" class="name-link" @click="open(record)">{{record.name}}</a-button></template></a-table-column>
  <a-table-column title="类型" data-index="type"/>
  <a-table-column title="主机地址"><template #default="{record}">{{record.host}}:{{record.port}}</template></a-table-column>
  <a-table-column title="授权表数" data-index="authorizedTables"/>
  <a-table-column title="状态"><template #default="{record}"><a-tag :color="record.enabled?'green':'red'">{{record.enabled?'正常':'已停用'}}</a-tag></template></a-table-column>
  <a-table-column title="操作"><template #default="{record}"><a-button type="link" @click="open(record)">查看</a-button></template></a-table-column>
 </a-table>
</section>
<a-drawer :mask="false" :open="!!selected" title="数据源详情" width="495" @close="selected=null">
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
    <div class="drawer-actions"><a-button :danger="selected.enabled" @click="confirm('确认更改数据源状态？',toggle)">{{selected.enabled?'停用数据源':'启用数据源'}}</a-button></div>
   </a-tab-pane>
   <a-tab-pane key="fields" tab="字段权限">
    <template v-if="!activeTable">
     <div class="inline-entry"><a-input v-model:value="newTable" placeholder="输入需要开放的数据表名"/><a-button type="primary" @click="addTable">授权</a-button></div>
     <div v-for="t in tables" :key="t.tableName" class="grant-row"><div><strong>{{t.tableName}}</strong><small>{{t.authorizedColumns}} 个字段可读</small></div><a-button v-if="t.enabled" type="link" @click="loadColumns(t.tableName)">字段权限</a-button><a-button v-else type="link" @click="newTable=t.tableName;addTable()">恢复</a-button><a-button v-if="t.enabled" type="text" danger @click="confirm('撤销该表所有读取权限？',()=>removeTable(t.tableName))">撤销</a-button></div>
     <a-empty v-if="!tables.length" description="尚未授权数据表"/>
    </template>
    <template v-else>
     <a-button type="link" @click="activeTable=''">← 返回数据表</a-button><h3>{{activeTable}} 的可读字段</h3>
     <div v-if="!columns.some(c=>c.dataType)" class="inline-entry"><a-input v-model:value="newColumn" placeholder="输入字段名"/><a-button type="primary" @click="addColumn">授权</a-button></div>
     <a-table :data-source="columns" row-key="columnName" size="small" :pagination="false">
  <a-table-column title="字段名" data-index="columnName"/>
  <a-table-column title="字段类型"><template #default="{record}">{{record.dataType||'—'}}</template></a-table-column>
  <a-table-column title="AI 可读取"><template #default="{record}"><a-checkbox :checked="!!record.enabled" @change="toggleAllowed(record)"/></template></a-table-column>
  <a-table-column title="脱敏"><template #default="{record}"><a-checkbox :checked="!!record.masked" :disabled="!record.enabled" @change="toggleMask(record.columnName)"/></template></a-table-column>
  <a-table-column title="操作"><template #default="{record}"><a-button v-if="record.enabled" type="link" danger @click="confirm('确认撤销字段授权？',()=>removeColumn(record.columnName))">撤销</a-button><a-button v-else type="link" @click="newColumn=record.columnName;addColumn()">恢复</a-button></template></a-table-column>
 </a-table>
 <p class="subtle">脱敏字段会以 [REDACTED] 返回，不能作为筛选或排序条件。</p>
    </template>
   </a-tab-pane>
   <a-tab-pane key="limit" tab="访问限制"><a-descriptions :column="1" bordered size="small">
    <a-descriptions-item label="平台安全上限">200 行 / 次</a-descriptions-item>
    <a-descriptions-item label="实际规则">在访问规则中配置放行、审批和拒绝区间</a-descriptions-item>
   </a-descriptions></a-tab-pane>
  </a-tabs>
 </template>
</a-drawer>
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
</template>