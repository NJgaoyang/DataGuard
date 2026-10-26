<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,stamp,fail,stateName,stateColor,type DataRow} from '../api';

const props=defineProps<{initialRecordId?:string|null}>();
const emit=defineEmits<{closeDetail:[]}>();
const rows=ref<DataRow[]>([]),search=ref(''),status=ref('all'),agent=ref('all'),source=ref('all'),
 date=ref<[string,string]|null>(null),detail=ref<DataRow|null>(null),busy=ref(false),
 currentPage=ref(1),total=ref(0),detailBusy=ref(false);
const agents=computed(()=>[...new Set(rows.value.map(r=>r.agentName))].filter(Boolean));
const sources=computed(()=>[...new Set(rows.value.map(r=>r.sourceName))].filter(Boolean));
const pagination=computed(()=>({current:currentPage.value,pageSize:10,total:total.value,
 showSizeChanger:false,showTotal:(n:number)=>`共 ${n} 条`}));
async function refresh(){
 try{
  busy.value=true;
  const params=new URLSearchParams({page:String(currentPage.value)});
  if(status.value!=='all')params.set('status',status.value);
  if(agent.value!=='all')params.set('agent',agent.value);
  if(source.value!=='all')params.set('source',source.value);
  if(search.value.trim())params.set('search',search.value.trim());
  if(date.value?.[0])params.set('from',date.value[0]);
  if(date.value?.[1])params.set('to',date.value[1]);
  const response=await api<{items:DataRow[];total:number}>('/api/requests/page?'+params.toString());
  rows.value=response.items;total.value=response.total;
 }catch(e){fail(e)}finally{busy.value=false}
}
function onRangeChange(_values:unknown,dateStrings:string[]){date.value=dateStrings?.[0]&&dateStrings?.[1]?[dateStrings[0],dateStrings[1]]:null;}
function changePage(p:{current?:number}){currentPage.value=p.current||1;void refresh();}
async function openRecord(id:string){
 try{
  detailBusy.value=true;
  detail.value=await api<DataRow>('/api/requests/'+encodeURIComponent(id));
 }catch(e){fail(e);detail.value=null}finally{detailBusy.value=false}
}
function closeRecord(){detail.value=null;emit('closeDetail');}
watch([search,status,agent,source,date],()=>{currentPage.value=1;void refresh()});
watch(()=>props.initialRecordId,id=>{if(id)void openRecord(id);else detail.value=null});
onMounted(()=>{void refresh();if(props.initialRecordId)void openRecord(props.initialRecordId)});
defineExpose({refresh});
</script>
<template>
<section v-if="detail||detailBusy" class="panel detail-page" aria-label="访问详情页面">
 <div class="panel-head"><h2>访问详情</h2><a-button @click="closeRecord">返回访问记录</a-button></div>
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
    <a-descriptions-item label="请求行数">{{detail.requestedRows}}</a-descriptions-item>
    <a-descriptions-item label="返回行数">{{detail.returnedRows??'—'}}</a-descriptions-item>
    <a-descriptions-item label="执行耗时">{{detail.durationMs==null?'—':detail.durationMs+' 毫秒'}}</a-descriptions-item>
    <a-descriptions-item label="规则动作">{{detail.action==='APPROVAL'?'需要审批':detail.action==='DENY'?'直接拒绝':'直接放行'}}</a-descriptions-item>
    <a-descriptions-item label="命中规则">{{detail.ruleName||'未匹配规则（默认拒绝）'}}</a-descriptions-item>
    <a-descriptions-item label="审批人">{{detail.approverName||'—'}}</a-descriptions-item>
    <a-descriptions-item label="审批意见">{{detail.reason||'—'}}</a-descriptions-item>
    <a-descriptions-item label="执行结果"><a-tag :color="stateColor(detail.status)">{{stateName[detail.status]||detail.status}}</a-tag></a-descriptions-item>
   </a-descriptions>
   <p class="subtle" style="margin-top:15px">仅记录查询元信息和不可逆请求指纹，不保留敏感过滤值或查询结果。</p>
  </div>
 </a-spin>
</section>
<section v-else class="panel">
 <div class="panel-head"><h2>访问记录</h2><span class="subtle">共 {{total}} 条</span></div>
 <div class="toolbar record-filters">
  <a-select v-model:value="status" style="width:135px" :options="[{value:'all',label:'全部状态'},...Object.entries(stateName).map(([value,label])=>({value,label}))]"/>
  <a-select v-model:value="agent" style="width:140px" :options="[{value:'all',label:'全部 Agent'},...agents.map(v=>({value:v,label:v}))]"/>
  <a-select v-model:value="source" style="width:150px" :options="[{value:'all',label:'全部数据源'},...sources.map(v=>({value:v,label:v}))]"/>
  <a-range-picker :value="date" format="YYYY-MM-DD" value-format="YYYY-MM-DD" :placeholder="['开始日期','结束日期']" @change="onRangeChange"/>
  <a-input-search v-model:value="search" allow-clear class="search" placeholder="搜索数据表或请求编号"/>
 </div>
 <a-table :data-source="rows" :loading="busy" row-key="id" :pagination="pagination" :scroll="{x:1050}" @change="changePage">
  <a-table-column title="序号" :width="65"><template #default="{index}">{{(currentPage-1)*10+index+1}}</template></a-table-column>
  <a-table-column title="访问时间" :width="175"><template #default="{record}">{{stamp(record.createdAt)}}</template></a-table-column>
  <a-table-column title="Agent" data-index="agentName"/>
  <a-table-column title="访问对象" data-index="tableName"/>
  <a-table-column title="请求行数" data-index="requestedRows"/>
  <a-table-column title="返回行数"><template #default="{record}">{{record.returnedRows??'—'}}</template></a-table-column>
  <a-table-column title="状态"><template #default="{record}"><a-tag :color="stateColor(record.status)">{{stateName[record.status]||record.status}}</a-tag></template></a-table-column>
  <a-table-column title="耗时"><template #default="{record}">{{record.durationMs==null?'—':record.durationMs+' 毫秒'}}</template></a-table-column>
  <a-table-column title="操作"><template #default="{record}"><a-button type="link" @click="openRecord(record.id)">查看</a-button></template></a-table-column>
 </a-table>
</section>
</template>
