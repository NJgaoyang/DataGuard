<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,stamp,match,page,fail,stateName,stateColor,type DataRow,type User} from '../api';
const props=defineProps<{user:User;initialScope?:string;initialDetailId?:string}>();
const tab=ref(props.initialScope||(props.user.roleCode==='ADMIN'?'all':props.user.roleCode==='READER'?'mine':'pending')),rows=ref<DataRow[]>([]),search=ref(''),detail=ref<DataRow|null>(null),approvalMode=ref(false),reason=ref(''),busy=ref(false);
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['id','requesterName','agentName','tableName','sourceName'])));
const canApprove=computed(()=>detail.value?.status==='PENDING'&&(detail.value.approverUsername===props.user.username||props.user.roleCode==='ADMIN')
 &&detail.value.ownerUsername!==props.user.username&&new Date(detail.value.expiresAt).getTime()>Date.now());
async function refresh(){try{rows.value=await api('/api/requests?scope='+tab.value)}catch(e){fail(e)}}
function selectTab(t:string){tab.value=t;detail.value=null;void refresh()}
async function decide(action:'approve'|'reject'){if(!detail.value)return;try{if(!reason.value.trim())throw Error('必须填写审批意见');busy.value=true;
 await api('/api/requests/'+detail.value.id+'/'+action,'POST',{reason:reason.value.trim()});
 detail.value=null;reason.value='';await refresh()}catch(e){fail(e)}finally{busy.value=false}}
const emit=defineEmits<{'detail-change':[value:{open:boolean;name:string;id:string}]}>();
watch(detail,value=>emit('detail-change',{open:Boolean(value),name:value?'申请 #'+String(value.id||''):'',id:String(value?.id||'')}));
onMounted(async()=>{await refresh();if(props.initialDetailId){try{detail.value=await api('/api/requests/'+props.initialDetailId)}catch(e){fail(e)}}});defineExpose({refresh});
</script>
<template>
<section v-if="!detail" class="panel">
 <div class="toolbar stack">
 <a-radio-group :value="tab" button-style="solid" @change="selectTab($event.target.value)">
  <a-radio-button v-if="user.roleCode==='ADMIN'" value="all">全部申请</a-radio-button>
  <a-radio-button v-if="user.roleCode!=='READER'" value="pending">待我审批</a-radio-button>
  <a-radio-button value="mine">我发起的</a-radio-button>
  <a-radio-button v-if="user.roleCode!=='READER'" value="handled">已处理</a-radio-button>
 </a-radio-group>
 <a-input-search v-model:value="search" class="search" allow-clear placeholder="搜索申请内容、Agent 或表名"/>
 <a-button class="toolbar-refresh" @click="refresh">刷新</a-button>
 </div>
 <a-table :data-source="filtered" :pagination="page" row-key="id" :scroll="{x:850}">
  <a-table-column title="申请时间"><template #default="{record}">{{stamp(record.createdAt)}}</template></a-table-column>
  <a-table-column title="申请人" data-index="requesterName"/>
  <a-table-column title="Agent" data-index="agentName"/>
  <a-table-column title="访问对象" data-index="tableName"/>
  <a-table-column title="申请行数" data-index="requestedRows"/>
  <a-table-column title="状态"><template #default="{record}"><a-tag :color="stateColor(record.status)">{{stateName[record.status]||record.status}}</a-tag></template></a-table-column>
  <a-table-column title="操作" :width="190"><template #default="{record}"><a-space size="small" class="table-row-actions"><a-button class="row-view-btn" @click="detail=record;approvalMode=false;reason=''">查看</a-button><a-dropdown v-if="(record.approverUsername===user.username||user.roleCode==='ADMIN')&&record.ownerUsername!==user.username&&record.status==='PENDING'" :trigger="['click']"><a-button class="row-more-btn">更多 <svg class="row-more-chevron" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="m4 6 4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg></a-button><template #overlay><a-menu class="row-more-menu"><a-menu-item @click="detail=record;approvalMode=true;reason=''"><span class="row-menu-icon">◉</span>处理审批</a-menu-item></a-menu></template></a-dropdown></a-space></template></a-table-column>
 </a-table>
</section>
<section v-else class="panel detail-page" aria-label="审批详情页面">
 <teleport to="#workspace-actions"><a-button @click="detail=null">返回审批列表</a-button></teleport>
 <div class="detail-content">
 <template v-if="detail">
  <div class="drawer-heading"><strong>{{detail.tableName}} 数据读取申请</strong><a-tag :color="stateColor(detail.status)">{{stateName[detail.status]||detail.status}}</a-tag></div>
  <a-descriptions :column="1" size="small" bordered>
   <a-descriptions-item label="申请编号">{{detail.id}}</a-descriptions-item>
   <a-descriptions-item label="申请人">{{detail.requesterName}}</a-descriptions-item>
   <a-descriptions-item label="Agent">{{detail.agentName}}</a-descriptions-item>
   <a-descriptions-item label="数据源">{{detail.sourceName}}</a-descriptions-item>
   <a-descriptions-item label="目标表">{{detail.tableName}}</a-descriptions-item>
   <a-descriptions-item label="读取字段">{{detail.columnsRequested}}</a-descriptions-item>
   <a-descriptions-item label="申请行数">{{detail.requestedRows}} 行</a-descriptions-item>
   <a-descriptions-item label="命中规则">{{detail.ruleName||'未匹配规则'}}</a-descriptions-item>
   <a-descriptions-item label="规则动作">{{detail.action==='APPROVAL'?'需要审批':detail.action==='DENY'?'直接拒绝':'直接放行'}}</a-descriptions-item>
   <a-descriptions-item label="审批人">{{detail.approverName||'—'}}</a-descriptions-item>
   <a-descriptions-item label="申请时间">{{stamp(detail.createdAt)}}</a-descriptions-item>
   <a-descriptions-item label="有效期">{{stamp(detail.expiresAt)}}</a-descriptions-item>
   <a-descriptions-item label="处理意见">{{detail.reason||'—'}}</a-descriptions-item>
  </a-descriptions>
  <template v-if="approvalMode&&canApprove"><a-form layout="vertical" style="margin-top:20px"><a-form-item label="审批意见" required><a-textarea v-model:value="reason" :rows="3" :maxlength="512"/></a-form-item></a-form>
   <div class="approval-buttons"><a-button type="primary" :loading="busy" @click="decide('approve')">通过</a-button><a-button :loading="busy" @click="decide('reject')">拒绝</a-button></div>

</template>
  <a-button v-else-if="canApprove" style="margin-top:18px" @click="approvalMode=true">处理审批</a-button>
  <a-alert v-else-if="detail.status==='APPROVED'" style="margin-top:20px" type="info" message="审批已通过，Agent 可重试完全相同的请求一次。"/>
 </template>
</div>
</section>

</template>