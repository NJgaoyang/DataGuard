<script setup lang="ts">
import {computed,onMounted,ref} from 'vue';
import {api,stamp,match,page,fail,stateName,stateColor,type DataRow} from '../api';
const rows=ref<DataRow[]>([]),search=ref(''),status=ref('all'),agent=ref('all'),source=ref('all'),date=ref<[string,string]|null>(null),detail=ref<DataRow|null>(null);
const agents=computed(()=>[...new Set(rows.value.map(r=>r.agentName))].filter(Boolean));
const sources=computed(()=>[...new Set(rows.value.map(r=>r.sourceName))].filter(Boolean));
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['id','agentName','tableName','sourceName','requesterName'])
 &&(status.value==='all'||r.status===status.value)
 &&(agent.value==='all'||r.agentName===agent.value)
 &&(source.value==='all'||r.sourceName===source.value)
 &&(!date.value||(String(r.createdAt).slice(0,10)>=date.value[0]&&String(r.createdAt).slice(0,10)<=date.value[1]))));
async function refresh(){try{rows.value=await api('/api/requests?scope=all')}catch(e){fail(e)}}
function onRangeChange(_values:unknown,dateStrings:string[]){date.value=dateStrings?.[0]&&dateStrings?.[1]?[dateStrings[0],dateStrings[1]]:null;}
onMounted(()=>void refresh());defineExpose({refresh});
</script>
<template>
<section class="panel"><div class="panel-head"><h2>访问记录</h2></div>
 <div class="toolbar record-filters">
  <a-select v-model:value="status" style="width:135px" :options="[{value:'all',label:'全部状态'},...Object.entries(stateName).map(([value,label])=>({value,label}))]"/>
  <a-select v-model:value="agent" style="width:140px" :options="[{value:'all',label:'全部 Agent'},...agents.map(v=>({value:v,label:v}))]"/>
  <a-select v-model:value="source" style="width:150px" :options="[{value:'all',label:'全部数据源'},...sources.map(v=>({value:v,label:v}))]"/>
  <a-range-picker :value="date" format="YYYY-MM-DD" value-format="YYYY-MM-DD" @change="onRangeChange"/>
  <a-input-search v-model:value="search" allow-clear class="search" placeholder="搜索表名或请求编号"/>
 </div>
 <a-table :data-source="filtered" row-key="id" :pagination="page" :scroll="{x:930}">
 <a-table-column title="时间"><template #default="{record}">{{stamp(record.createdAt)}}</template></a-table-column>
 <a-table-column title="Agent" data-index="agentName"/>
 <a-table-column title="访问对象" data-index="tableName"/>
 <a-table-column title="请求行数" data-index="requestedRows"/>
 <a-table-column title="返回行数"><template #default="{record}">{{record.returnedRows??'—'}}</template></a-table-column>
 <a-table-column title="状态"><template #default="{record}"><a-tag :color="stateColor(record.status)">{{stateName[record.status]||record.status}}</a-tag></template></a-table-column>
 <a-table-column title="耗时"><template #default="{record}">{{record.durationMs==null?'—':record.durationMs+' ms'}}</template></a-table-column>
 <a-table-column title="操作"><template #default="{record}"><a-button type="link" @click="detail=record">查看</a-button></template></a-table-column>
 </a-table>
</section>
<a-drawer :open="!!detail" title="访问详情" width="430" @close="detail=null">
 <template v-if="detail">
 <a-descriptions :column="1" size="small" bordered>
  <a-descriptions-item label="请求编号">{{detail.id}}</a-descriptions-item>
  <a-descriptions-item label="时间">{{stamp(detail.createdAt)}}</a-descriptions-item>
  <a-descriptions-item label="Agent">{{detail.agentName}}</a-descriptions-item>
  <a-descriptions-item label="数据源">{{detail.sourceName}}</a-descriptions-item>
  <a-descriptions-item label="目标表">{{detail.tableName}}</a-descriptions-item>
  <a-descriptions-item label="请求字段">{{detail.columnsRequested}}</a-descriptions-item>
  <a-descriptions-item label="请求行数">{{detail.requestedRows}}</a-descriptions-item>
  <a-descriptions-item label="实际返回">{{detail.returnedRows??'—'}}</a-descriptions-item>
  <a-descriptions-item label="耗时">{{detail.durationMs==null?'—':detail.durationMs+' ms'}}</a-descriptions-item>
  <a-descriptions-item label="规则动作">{{detail.action}}</a-descriptions-item>
  <a-descriptions-item label="命中规则">{{detail.ruleId||'未匹配规则（默认拒绝）'}}</a-descriptions-item>
  <a-descriptions-item label="审批人">{{detail.approverName||'—'}}</a-descriptions-item>
  <a-descriptions-item label="执行结果"><a-tag :color="stateColor(detail.status)">{{stateName[detail.status]||detail.status}}</a-tag></a-descriptions-item>
 </a-descriptions>
 <a-collapse style="margin-top:16px"><a-collapse-panel key="params" header="请求参数"><p class="subtle">请求指纹保存在后端，不记录敏感过滤值或查询结果内容。</p></a-collapse-panel></a-collapse>
 </template>
</a-drawer>
</template>