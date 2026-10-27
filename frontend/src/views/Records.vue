<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import dayjs from 'dayjs';
import {api,stamp,fail,stateName,stateColor,type DataRow} from '../api';
import DgDataTable from '../components/DgDataTable.vue';
import DgFilterBar from '../components/DgFilterBar.vue';
import DgSqlViewer from '../components/DgSqlViewer.vue';
import DgSqlEditor from '../components/DgSqlEditor.vue';
import DgDetailPanel from '../components/DgDetailPanel.vue';

const props=defineProps<{initialRecordId?:string|null;initialStatus?:string}>();
const emit=defineEmits<{closeDetail:[];'detail-change':[value:{open:boolean;name:string}]}>();
type DateRange=[string,string];
type Filters={status:string;agent:string;source:string;search:string;date:DateRange|null};
const today=()=>dayjs().format('YYYY-MM-DD');
const defaultRange=():DateRange=>{const d=today();return [d,d]};
const initialFilters=():Filters=>({status:props.initialStatus||'all',agent:'all',source:'all',search:'',date:defaultRange()});
const draft=ref<Filters>(initialFilters());
const applied=ref<Filters>({...draft.value,date:defaultRange()});
const rows=ref<DataRow[]>([]),agents=ref<string[]>([]),sources=ref<string[]>([]),
 detail=ref<DataRow|null>(null),busy=ref(false),
 currentPage=ref(1),total=ref(0),detailBusy=ref(false);
let latestRequest=0;
const showMonaco=ref(false);
const pagination=computed(()=>({current:currentPage.value,pageSize:10,total:total.value,
 showSizeChanger:false,showTotal:(n:number)=>'共 '+n+' 条'}));
const agentOptions=computed(()=>[{value:'all',label:'全部 Agent'},...agents.value.map(v=>({value:v,label:v}))]);
const sourceOptions=computed(()=>[{value:'all',label:'全部数据源'},...sources.value.map(v=>({value:v,label:v}))]);
async function loadOptions(){
 try{
  const options=await api<{agents:string[];sources:string[]}>('/api/requests/filters');
  agents.value=options.agents;sources.value=options.sources;
 }catch(e){fail(e)}
}
async function refresh(){
 const request=++latestRequest;
 const filters=applied.value;
 try{
  busy.value=true;
  const params=new URLSearchParams({page:String(currentPage.value)});
  if(filters.status!=='all')params.set('status',filters.status);
  if(filters.agent!=='all')params.set('agent',filters.agent);
  if(filters.source!=='all')params.set('source',filters.source);
  if(filters.search.trim())params.set('search',filters.search.trim());
  if(filters.date?.[0])params.set('from',filters.date[0]);
  if(filters.date?.[1])params.set('to',filters.date[1]);
  const response=await api<{items:DataRow[];total:number}>('/api/requests/page?'+params.toString());
  if(request!==latestRequest)return;
  rows.value=response.items;total.value=response.total;
 }catch(e){if(request===latestRequest){rows.value=[];total.value=0;fail(e)}}
 finally{if(request===latestRequest)busy.value=false}
}
function onRangeChange(_values:unknown,dateStrings:string[]){
 draft.value.date=dateStrings?.[0]&&dateStrings?.[1]?[dateStrings[0],dateStrings[1]]:null;
}
function query(){
 applied.value={...draft.value,date:draft.value.date?[...draft.value.date] as DateRange:null};
 currentPage.value=1;void refresh();
}
function reset(){draft.value=initialFilters();}
function changePage(p:{current?:number}){currentPage.value=p.current||1;void refresh();}
async function openRecord(id:string){
 try{
  detailBusy.value=true;
  detail.value=await api<DataRow>('/api/requests/'+encodeURIComponent(id));
 }catch(e){fail(e);detail.value=null}finally{detailBusy.value=false}
}
function closeRecord(){detail.value=null;showMonaco.value=false;emit('closeDetail');}
watch([detail,detailBusy],()=>emit('detail-change',{open:Boolean(detail.value||detailBusy.value),name:detail.value?'访问 #'+String(detail.value.id||''):''}));
watch(()=>props.initialRecordId,id=>{if(id)void openRecord(id);else detail.value=null});
watch(()=>props.initialStatus,next=>{draft.value.status=next||'all';query()});
onMounted(()=>{void loadOptions();void refresh();if(props.initialRecordId)void openRecord(props.initialRecordId)});
defineExpose({refresh});
</script>
<template>
<DgDetailPanel v-if="detail||detailBusy" aria-label="访问详情页面" back-label="返回访问记录" @back="closeRecord">
 
 <a-spin :spinning="detailBusy">
  <div v-if="detail" class="detail-content">
   <div class="drawer-heading"><strong>{{detail.tableName}} · 访问记录</strong><a-tag :color="stateColor(detail.status)">{{stateName[detail.status]||detail.status}}</a-tag></div>
   <a-descriptions :column="1" size="small" bordered>
    <a-descriptions-item label="请求编号">{{detail.id}}</a-descriptions-item>
    <a-descriptions-item label="访问时间">{{stamp(detail.createdAt)}}</a-descriptions-item>
    <a-descriptions-item label="Agent">{{detail.agentName}}</a-descriptions-item>
    <a-descriptions-item label="数据源">{{detail.sourceName}}</a-descriptions-item>
    <a-descriptions-item label="目标表">{{detail.tableName}}</a-descriptions-item>
    <a-descriptions-item label="请求字段">{{detail.columnsRequested}}</a-descriptions-item>
    <a-descriptions-item label="查询 SQL（已脱敏）">
     <DgSqlViewer :sql="detail.sqlTemplate"/>
     <div v-if="detail.sqlTemplate" class="dg-sql-editor-toggle">
      <a-button size="small" @click="showMonaco=!showMonaco">{{showMonaco?'关闭 SQL 编辑器':'代码编辑器查看'}}</a-button>
      <span class="subtle">只读审计模板，不可直接执行</span>
     </div>
     <DgSqlEditor v-if="showMonaco&&detail.sqlTemplate" :model-value="detail.sqlTemplate" :read-only="true"/>

    </a-descriptions-item>
    <a-descriptions-item label="请求行数">{{detail.requestedRows}}</a-descriptions-item>
    <a-descriptions-item label="返回行数">{{detail.returnedRows??'—'}}</a-descriptions-item>
    <a-descriptions-item label="执行耗时">{{detail.durationMs==null?'—':detail.durationMs+' 毫秒'}}</a-descriptions-item>
    <a-descriptions-item label="规则动作">{{detail.action==='APPROVAL'?'需要审批':detail.action==='DENY'?'直接拒绝':'直接放行'}}</a-descriptions-item>
    <a-descriptions-item label="命中规则">{{detail.ruleName||'未匹配规则（默认拒绝）'}}</a-descriptions-item>
    <a-descriptions-item label="审批人">{{detail.approverName||'—'}}</a-descriptions-item>
    <a-descriptions-item label="审批意见">{{detail.reason||'—'}}</a-descriptions-item>
    <a-descriptions-item label="执行结果"><a-tag :color="stateColor(detail.status)">{{stateName[detail.status]||detail.status}}</a-tag></a-descriptions-item>
   </a-descriptions>
   <p class="subtle" style="margin-top:15px">SQL 为安全审计模板，已遮蔽 SQL 字面量和绑定参数值；不保留查询结果。旧记录无法补录执行时的 SQL。</p>
  </div>
 </a-spin>
</DgDetailPanel>
<section v-else class="panel">
 <DgFilterBar class="record-filters" @query="query" @reset="reset">
  <a-select v-model:value="draft.status" style="width:135px" :options="[{value:'all',label:'全部状态'},{value:'DENIED_OR_REJECTED',label:'已拒绝（含审批拒绝）'},...Object.entries(stateName).map(([value,label])=>({value,label}))]"/>
  <a-select v-model:value="draft.agent" show-search option-filter-prop="label" style="width:140px" :options="agentOptions"/>
  <a-select v-model:value="draft.source" show-search option-filter-prop="label" style="width:150px" :options="sourceOptions"/>
  <a-range-picker :value="draft.date" format="YYYY-MM-DD" value-format="YYYY-MM-DD" :placeholder="['开始日期','结束日期']" @change="onRangeChange"/>
  <a-input v-model:value="draft.search" allow-clear class="search" placeholder="搜索数据表、SQL 或请求编号"/>
 </DgFilterBar>
 <DgDataTable :data-source="rows" :loading="busy" row-key="id" :pagination="pagination" :scroll="{x:1350}" @change="changePage">
  <a-table-column title="序号" :width="65"><template #default="{index}">{{(currentPage-1)*10+index+1}}</template></a-table-column>
  <a-table-column title="访问时间" :width="175"><template #default="{record}">{{stamp(record.createdAt)}}</template></a-table-column>
  <a-table-column title="Agent" data-index="agentName"/>
  <a-table-column title="访问对象" data-index="tableName"/>
  <a-table-column title="SQL 摘要" :width="260">
   <template #default="{record}"><span class="record-sql-preview" :title="String(record.sqlTemplate||'')">{{record.sqlTemplate||'—'}}</span></template>
  </a-table-column>
  <a-table-column title="请求行数" data-index="requestedRows"/>
  <a-table-column title="返回行数"><template #default="{record}">{{record.returnedRows??'—'}}</template></a-table-column>
  <a-table-column title="状态"><template #default="{record}"><a-tag :color="stateColor(record.status)">{{stateName[record.status]||record.status}}</a-tag></template></a-table-column>
  <a-table-column title="耗时"><template #default="{record}">{{record.durationMs==null?'—':record.durationMs+' 毫秒'}}</template></a-table-column>
  <a-table-column title="操作" :width="110"><template #default="{record}"><a-space class="table-row-actions"><a-button class="row-view-btn" @click="openRecord(record.id)">查看</a-button></a-space></template></a-table-column>
 </DgDataTable>
</section>
</template>