<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,stamp,match,page,fail,stateName,stateColor,type DataRow,type User} from '../api';
const props=defineProps<{user:User;initialScope?:string}>();
const tab=ref(props.initialScope||(props.user.roleCode==='READER'?'mine':'pending')),rows=ref<DataRow[]>([]),search=ref(''),detail=ref<DataRow|null>(null),reason=ref(''),busy=ref(false),requestHelpOpen=ref(false),preapplyBusy=ref(false),preapplyAgents=ref<DataRow[]>([]),preapplyOptions=ref<DataRow[]>([]),preapplyLoading=ref(false),tableSearch=ref(''),preapplyForm=ref({agentId:'',sourceId:'',tableName:'',columns:[] as string[],requestedRows:50});
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['id','requesterName','agentName','tableName','sourceName'])));
const canApprove=computed(()=>detail.value?.status==='PENDING'&&detail.value.approverUsername===props.user.username
 &&detail.value.ownerUsername!==props.user.username&&new Date(detail.value.expiresAt).getTime()>Date.now());
const sourceOptions=computed(()=>[...new Map(preapplyOptions.value.map(x=>[x.sourceId,{value:x.sourceId,label:x.sourceName}])).values()]);
const tableOptions=computed(()=>[...new Set(preapplyOptions.value.filter(x=>x.sourceId===preapplyForm.value.sourceId).map(x=>String(x.tableName)))].map(value=>({value,label:value})));
const searchedTables=computed(()=>tableOptions.value.filter(x=>x.label.toLowerCase().includes(tableSearch.value.toLowerCase())));
const allColumnsSelected=computed(()=>columnOptions.value.length>0&&preapplyForm.value.columns.length===columnOptions.value.length);
const columnToggleLabel=computed(()=>allColumnsSelected.value?'取消全选':'全选');
function toggleColumns(){preapplyForm.value.columns=allColumnsSelected.value?[]:[...columnOptions.value];}
const columnOptions=computed(()=>preapplyOptions.value.filter(x=>x.sourceId===preapplyForm.value.sourceId&&x.tableName===preapplyForm.value.tableName).map(x=>String(x.columnName)));
function resetTable(){preapplyForm.value.tableName='';preapplyForm.value.columns=[];}
function changeTable(value:string){preapplyForm.value.tableName=value;preapplyForm.value.columns=[...columnOptions.value];}
async function changeAgent(value:string){
 preapplyForm.value.agentId=value;preapplyForm.value.sourceId='';tableSearch.value='';resetTable();preapplyOptions.value=[];
 if(!value)return;
 try{preapplyLoading.value=true;preapplyOptions.value=await api<DataRow[]>('/api/requests/preapply/options?agentId='+encodeURIComponent(value));}
 catch(e){fail(e)}finally{preapplyLoading.value=false}
}
async function openPreapply(){try{
 preapplyAgents.value=await api<DataRow[]>('/api/requests/preapply/agents');
 preapplyForm.value={agentId:'',sourceId:'',tableName:'',columns:[],requestedRows:50};preapplyOptions.value=[];
 requestHelpOpen.value=true;
}catch(e){fail(e)}}
async function submitPreapply(){try{
 preapplyBusy.value=true;
 const parts=[...preapplyForm.value.columns];
 if(!preapplyForm.value.agentId||!preapplyForm.value.sourceId||!preapplyForm.value.tableName||!parts.length)throw Error('请选择 Agent、数据源、数据表和至少一个字段');
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
 <a-modal v-model:open="requestHelpOpen" title="提前申请访问审批" :width="960" ok-text="提交申请" :confirm-loading="preapplyBusy" @ok="submitPreapply">
  <a-form layout="vertical" class="preapply-form">
   <div class="preapply-form-grid">
    <a-form-item label="Agent" required><a-select :value="preapplyForm.agentId||undefined" placeholder="选择本人 Agent" :options="preapplyAgents.map(x=>({value:x.id,label:x.name}))" @change="changeAgent"/></a-form-item>
    <a-form-item label="数据源" required><a-select v-model:value="preapplyForm.sourceId" placeholder="选择已授权数据源" :loading="preapplyLoading" :disabled="!preapplyForm.agentId" :options="sourceOptions" @change="resetTable"/></a-form-item>
   </div>
   <div class="picker-two-pane">
    <div class="picker-tables"><div class="picker-pane-head">数据表 <span>{{tableOptions.length}}</span></div><a-input v-model:value="tableSearch" placeholder="搜索数据表" allow-clear class="picker-search"/>
     <div class="picker-list"><button v-for="item in searchedTables" :key="item.value" type="button" class="picker-table-item" :class="{active:preapplyForm.tableName===item.value}" @click="changeTable(item.value)"><span class="picker-checkbox">{{preapplyForm.tableName===item.value?'✓':''}}</span>{{item.label}}</button><a-empty v-if="!searchedTables.length" :image="undefined" description="暂无可选数据表"/></div>
    </div>
    <div class="picker-columns"><div class="picker-pane-head"><span>字段 <span>{{preapplyForm.columns.length}} / {{columnOptions.length}}</span></span><a-button type="link" size="small" :disabled="!columnOptions.length" @click="toggleColumns">{{columnToggleLabel}}</a-button></div>
     <a-checkbox-group v-if="columnOptions.length" v-model:value="preapplyForm.columns" :options="columnOptions.map(value=>({label:value,value}))" class="preapply-column-grid"/><a-empty v-else :image="undefined" description="请先选择左侧数据表"/>
    </div>
   </div>
   <a-form-item label="最大读取行数" required><a-input-number v-model:value="preapplyForm.requestedRows" :min="1" :max="200"/></a-form-item>
  </a-form>
  <p class="subtle">仅显示当前 Agent 已授权的表和字段。选择数据表后默认勾选全部可读字段；取消勾选可缩小申请范围。实际请求需要字段顺序、行数和查询参数完全匹配。</p>
 </a-modal>
</template>
