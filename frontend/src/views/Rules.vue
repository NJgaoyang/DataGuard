<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,match,page,fail,confirm,type DataRow} from '../api';
const props=defineProps<{initialDetailId?:string}>();
const maxRowsPerQuery=ref(200);
async function loadQueryLimit(){try{const limits=await api<{maxRowsPerQuery:number}>('/api/security/limits');maxRowsPerQuery.value=limits.maxRowsPerQuery}catch(e){fail(e)}}
const rows=ref<DataRow[]>([]),sources=ref<DataRow[]>([]),agents=ref<DataRow[]>([]),approvers=ref<DataRow[]>([]),search=ref(''),selected=ref<DataRow|null>(null),createOpen=ref(false),editId=ref<string|null>(null),busy=ref(false),ruleTables=ref<DataRow[]>([]),ruleColumns=ref<DataRow[]>([]);
const defaultRule=()=>({name:'',sourceId:'',agentId:'',tableName:'*',columnName:'',minRows:1,maxRows:50,action:'ALLOW',priority:100,approverId:''});
const form=ref(defaultRule());
const labels:Record<string,string>={ALLOW:'直接放行',APPROVAL:'需要审批',DENY:'直接拒绝'};
const filtered=computed(()=>rows.value.filter(x=>match(x,search.value,['name','tableName','sourceName','agentName'])));
async function refresh(){try{rows.value=await api('/api/rules')}catch(e){fail(e)}}
async function loadOptions(){[sources.value,agents.value,approvers.value]=await Promise.all([api('/api/sources'),api('/api/agents'),api('/api/approvers')]);}
async function loadRuleTables(){ruleTables.value=[];ruleColumns.value=[];form.value.tableName='*';form.value.columnName='';if(!form.value.sourceId)return;try{ruleTables.value=(await api<DataRow[]>('/api/sources/'+form.value.sourceId+'/tables')).filter(x=>x.enabled)}catch(e){fail(e)}}
async function loadRuleColumns(){ruleColumns.value=[];form.value.columnName='';if(!form.value.sourceId||form.value.tableName==='*')return;try{ruleColumns.value=(await api<DataRow[]>('/api/sources/'+form.value.sourceId+'/tables/'+encodeURIComponent(form.value.tableName)+'/columns')).filter(x=>x.enabled)}catch(e){fail(e)}}
async function openCreate(){try{await loadOptions();form.value=defaultRule();editId.value=null;createOpen.value=true}catch(e){fail(e)}}
async function edit(row:DataRow){try{
 await loadOptions();editId.value=row.id;
 form.value={name:row.name,sourceId:row.sourceId||'',agentId:row.agentId||'',tableName:row.tableName,
  columnName:row.columnName||'',minRows:Number(row.minRows),maxRows:Number(row.maxRows),
  action:row.action,priority:Number(row.priority),approverId:row.approverId||''};
 if(form.value.sourceId){await loadRuleTables();form.value.tableName=row.tableName;if(row.tableName!=='*'){await loadRuleColumns();form.value.columnName=row.columnName||''}}
 selected.value=null;createOpen.value=true;
 }catch(e){fail(e)}}
async function save(){try{
 if(!form.value.name.trim())throw Error('请输入规则名称');
 if(form.value.action==='APPROVAL'&&!form.value.approverId)throw Error('请选择审批人');
 busy.value=true;await api(editId.value?'/api/rules/'+editId.value:'/api/rules',editId.value?'PUT':'POST',{...form.value,sourceId:form.value.sourceId||null,agentId:form.value.agentId||null,
  columnName:form.value.columnName||null,approverId:form.value.action==='APPROVAL'?form.value.approverId:null});
 createOpen.value=false;await refresh();
 }catch(e){fail(e)}finally{busy.value=false}}
async function deleteRule(row:DataRow){try{await api('/api/rules/'+row.id,'DELETE');if(selected.value?.id===row.id)selected.value=null;await refresh()}catch(e){fail(e)}}
async function toggleSelected(){if(selected.value)await toggle(selected.value)}
async function toggle(row:DataRow){try{await api('/api/rules/'+row.id+'/toggle','POST');selected.value=null;await refresh()}catch(e){fail(e)}}
const condition=(row:DataRow)=>'读取 '+row.minRows+'～'+row.maxRows+' 行'+(row.columnName?' · '+row.columnName:'');
const emit=defineEmits<{'detail-change':[value:{open:boolean;name:string;id:string}]}>();
watch(selected,value=>emit('detail-change',{open:Boolean(value),name:String(value?.name||''),id:String(value?.id||'')}));
onMounted(async()=>{await loadQueryLimit();await refresh();const row=rows.value.find(x=>x.id===props.initialDetailId);if(row)selected.value=row});defineExpose({refresh});
</script>
<template>
 <section v-if="!selected" class="panel">
 <div class="toolbar"><a-button type="primary" @click="openCreate">＋ 新建规则</a-button><a-input-search v-model:value="search" allow-clear class="search" placeholder="搜索规则名称或适用范围"/><a-button class="toolbar-refresh" @click="refresh">刷新</a-button></div>
 <a-table :data-source="filtered" row-key="id" :pagination="page" :scroll="{x:850}">
 <a-table-column title="规则名称"><template #default="{record}"><a-button type="link" class="name-link" @click="selected=record">{{record.name}}</a-button></template></a-table-column>
 <a-table-column title="适用范围"><template #default="{record}">{{record.tableName==='*'?'全部表':record.tableName}}</template></a-table-column>
 <a-table-column title="条件"><template #default="{record}">{{condition(record)}}</template></a-table-column>
 <a-table-column title="动作"><template #default="{record}"><a-tag :color="record.action==='ALLOW'?'green':record.action==='APPROVAL'?'orange':'red'">{{labels[record.action]}}</a-tag></template></a-table-column>
 <a-table-column title="状态"><template #default="{record}"><a-tag :color="record.enabled?'green':'default'">{{record.enabled?'正常':'已停用'}}</a-tag></template></a-table-column>
 <a-table-column title="优先级" data-index="priority"/>
 <a-table-column title="操作" :width="190"><template #default="{record}"><a-space size="small" class="table-row-actions"><a-button class="row-view-btn" @click="selected=record">查看</a-button><a-dropdown :trigger="['click']"><a-button class="row-more-btn">更多 <svg class="row-more-chevron" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="m4 6 4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg></a-button><template #overlay><a-menu class="row-more-menu"><a-menu-item @click="edit(record)"><span class="row-menu-icon">✎</span>编辑规则</a-menu-item><a-menu-item @click="confirm('确认变更规则状态？此操作会使关联待审批授权失效。',()=>toggle(record))"><span class="row-menu-icon">◷</span>{{record.enabled?'停用':'启用'}}</a-menu-item><a-menu-divider/><a-menu-item danger :disabled="!!record.enabled" @click="confirm('确定永久删除访问规则「'+record.name+'」？此操作不可恢复；有审批或访问历史的规则只能停用。',()=>deleteRule(record))"><span class="row-menu-icon">♜</span>删除规则</a-menu-item></a-menu></template></a-dropdown></a-space></template></a-table-column>
 </a-table></section>
 <section v-else class="panel detail-page" aria-label="规则详情页面">
 <teleport to="#workspace-actions"><a-button @click="selected=null">返回规则列表</a-button></teleport>
 <div class="detail-content">
 <a-descriptions v-if="selected" :column="1" size="small" bordered>
 <a-descriptions-item label="规则名称">{{selected.name}}</a-descriptions-item>
 <a-descriptions-item label="优先级">{{selected.priority}}</a-descriptions-item>
 <a-descriptions-item label="适用对象">{{selected.sourceName||'全部数据源'}} · {{selected.tableName}}</a-descriptions-item>
 <a-descriptions-item label="Agent">{{selected.agentName||'全部 Agent'}}</a-descriptions-item>
 <a-descriptions-item label="触发条件">{{condition(selected)}}</a-descriptions-item>
 <a-descriptions-item label="动作">{{labels[selected.action]}}</a-descriptions-item>
 <a-descriptions-item v-if="selected.action==='APPROVAL'" label="审批人">{{selected.approverName}}</a-descriptions-item>
 </a-descriptions>
 </div>
</section>
 <a-modal v-model:open="createOpen" :title="editId?'编辑访问规则':'新建访问规则'" :ok-text="editId?'保存修改':'创建规则'" :confirm-loading="busy" :width="610" @ok="save">
 <a-form layout="vertical"><a-form-item label="规则名称" required><a-input v-model:value="form.name"/></a-form-item>
 <div class="form-grid">
 <a-form-item label="数据源"><a-select v-model:value="form.sourceId" @change="loadRuleTables" :options="[{value:'',label:'全部数据源'},...sources.map(s=>({value:s.id,label:s.name}))]"/></a-form-item>
 <a-form-item label="Agent"><a-select v-model:value="form.agentId" :options="[{value:'',label:'全部 Agent'},...agents.map(a=>({value:a.id,label:a.name}))]"/></a-form-item>
 <a-form-item label="数据表"><a-select v-model:value="form.tableName" :disabled="!form.sourceId" @change="loadRuleColumns" :options="[{value:'*',label:'全部表'},...ruleTables.map(t=>({value:t.tableName,label:t.tableName}))]"/></a-form-item>
 <a-form-item label="指定字段（可选）"><a-select v-model:value="form.columnName" :disabled="!form.sourceId||form.tableName==='*'" :options="[{value:'',label:'全部字段'},...ruleColumns.map(c=>({value:c.columnName,label:c.columnName}))]"/></a-form-item>
 <a-form-item label="最少读取行数"><a-input-number v-model:value="form.minRows" :min="1" :max="maxRowsPerQuery" style="width:100%"/></a-form-item>
 <a-form-item label="最多读取行数"><a-input-number v-model:value="form.maxRows" :min="1" :max="maxRowsPerQuery" style="width:100%"/></a-form-item>
 <a-form-item label="动作"><a-select v-model:value="form.action" :options="Object.entries(labels).map(([value,label])=>({value,label}))"/></a-form-item>
 <a-form-item label="优先级"><a-input-number v-model:value="form.priority" :min="1" style="width:100%"/></a-form-item>
 <a-form-item v-if="form.action==='APPROVAL'" class="full" label="审批人" required><a-select v-model:value="form.approverId" :options="approvers.map(a=>({value:a.id,label:a.displayName}))"/></a-form-item>
 </div></a-form></a-modal>
</template>
