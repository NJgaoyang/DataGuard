<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,stamp,match,page,fail,stateName,stateColor,type DataRow,type User} from '../api';
const props=defineProps<{user:User;initialScope?:string}>();
const tab=ref(props.initialScope||(props.user.roleCode==='READER'?'mine':'pending')),rows=ref<DataRow[]>([]),search=ref(''),detail=ref<DataRow|null>(null),reason=ref(''),busy=ref(false),requestHelpOpen=ref(false),preapplyBusy=ref(false),preapplyAgents=ref<DataRow[]>([]),preapplySources=ref<DataRow[]>([]),preapplyForm=ref({agentId:'',sourceId:'',tableName:'',columns:'',requestedRows:50});
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['id','requesterName','agentName','tableName','sourceName'])));
const canApprove=computed(()=>detail.value?.status==='PENDING'&&detail.value.approverUsername===props.user.username
 &&detail.value.ownerUsername!==props.user.username&&new Date(detail.value.expiresAt).getTime()>Date.now());
async function openPreapply(){try{
 [preapplyAgents.value,preapplySources.value]=await Promise.all([api('/api/agents'),api('/api/sources')]);
 requestHelpOpen.value=true;
}catch(e){fail(e)}}
async function submitPreapply(){try{
 preapplyBusy.value=true;
 const parts=preapplyForm.value.columns.split(',').map(x=>x.trim()).filter(Boolean);
 const result=await api<{id:string}>('/api/requests/preapply','POST',{...preapplyForm.value,columns:parts});
 requestHelpOpen.value=false;selectTab('mine');
 const record=await api<DataRow>('/api/requests/'+result.id);detail.value=record;
}catch(e){fail(e)}finally{preapplyBusy.value=false}}
async function refresh(){try{rows.value=await api('/api/requests?scope='+tab.value)}catch(e){fail(e)}}
function selectTab(t:string){tab.value=t;detail.value=null;void refresh()}
async function decide(action:'approve'|'reject'){if(!detail.value)return;try{if(!reason.value.trim())throw Error('必须填写审批意见');busy.value=true;
 await api('/api/requests/'+detail.value.id+'/'+action,'POST',{reason:reason.value.trim()});
 detail.value=null;reason.value='';await refresh()}catch(e){fail(e)}finally{busy.value=false}}
const emit=defineEmits<{'detail-change':[value:{open:boolean;name:string}]}>();
watch(detail,value=>emit('detail-change',{open:Boolean(value),name:value?'申请 #'+String(value.id||''):''}));
onMounted(()=>void refresh());defineExpose({refresh});
</script>
<template>
<section v-if="!detail" class="panel">
 <div class="toolbar stack">
 <a-button type="primary" @click="openPreapply">＋ 发起访问申请</a-button>
 <a-radio-group :value="tab" button-style="solid" @change="selectTab($event.target.value)">
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
  <a-table-column title="操作"><template #default="{record}"><a-button type="link" @click="detail=record;reason=''">{{record.approverUsername===user.username&&record.status==='PENDING'?'审批':'查看'}}</a-button></template></a-table-column>
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
  <template v-if="canApprove"><a-form layout="vertical" style="margin-top:20px"><a-form-item label="审批意见" required><a-textarea v-model:value="reason" :rows="3" :maxlength="512"/></a-form-item></a-form>
   <div class="approval-buttons"><a-button type="primary" :loading="busy" @click="decide('approve')">通过</a-button><a-button :loading="busy" @click="decide('reject')">拒绝</a-button></div>

</template>
  <a-alert v-else-if="detail.status==='APPROVED'" style="margin-top:20px" type="info" message="审批已通过，Agent 可重试完全相同的请求一次。"/>
 </template>
</div>
</section>
 <a-modal v-model:open="requestHelpOpen" title="提前申请访问审批" ok-text="提交申请" :confirm-loading="preapplyBusy" @ok="submitPreapply">
  <a-form layout="vertical">
   <a-form-item label="Agent" required><a-select v-model:value="preapplyForm.agentId" placeholder="选择本人 Agent" :options="preapplyAgents.map(x=>({value:x.id,label:x.name}))"/></a-form-item>
   <a-form-item label="数据源" required><a-select v-model:value="preapplyForm.sourceId" placeholder="选择数据源" :options="preapplySources.map(x=>({value:x.id,label:x.name}))"/></a-form-item>
   <a-form-item label="数据表" required><a-input v-model:value="preapplyForm.tableName" placeholder="输入已授权的表名"/></a-form-item>
   <a-form-item label="读取字段（逗号分隔）" required><a-input v-model:value="preapplyForm.columns" placeholder="例如 id,name；顺序须与实际读取一致"/></a-form-item>
   <a-form-item label="最大读取行数" required><a-input-number v-model:value="preapplyForm.requestedRows" :min="1" :max="200"/></a-form-item>
  </a-form>
  <p class="subtle">必须命中“需要审批”的访问规则。通过后，仅允许同一 Agent 在 24 小时内按相同字段顺序、行数及无筛选排序参数执行一次。不会在申请时执行 SQL。</p>
 </a-modal>
</template>
