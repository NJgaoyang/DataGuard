<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
import {api,stamp,match,page,fail,stateName,stateColor,type DataRow,type User} from '../api';
const props=defineProps<{user:User;initialScope?:string;initialDetailId?:string}>();
const maxRowsPerQuery=ref(200);
async function loadQueryLimit(){try{const limits=await api<{maxRowsPerQuery:number}>('/api/security/limits');maxRowsPerQuery.value=limits.maxRowsPerQuery}catch(e){fail(e)}}
const tab=ref(props.initialScope||(props.user.roleCode==='ADMIN'?'all':props.user.roleCode==='READER'?'mine':'pending')),rows=ref<DataRow[]>([]),search=ref(''),detail=ref<DataRow|null>(null),approvalMode=ref(false),reason=ref(''),busy=ref(false),requestHelpOpen=ref(false),preapplyBusy=ref(false),preapplyAgents=ref<DataRow[]>([]),preapplyOptions=ref<DataRow[]>([]),preapplyLoading=ref(false),tableSearch=ref(''),preapplyForm=ref({agentId:'',sourceId:'',tableName:'',columns:[] as string[],requestedRows:50,filterColumn:'',filterValue:'',sortColumn:'',sortDirection:'ASC',offset:0});
const filtered=computed(()=>rows.value.filter(r=>match(r,search.value,['id','requesterName','agentName','tableName','sourceName'])));
const canApprove=computed(()=>detail.value?.status==='PENDING'&&(detail.value.approverUsername===props.user.username||props.user.roleCode==='ADMIN')
 &&detail.value.ownerUsername!==props.user.username&&new Date(detail.value.expiresAt).getTime()>Date.now());
const preapplyEmptyReason=computed(()=>!preapplyForm.value.agentId?'请先选择 Agent':preapplyLoading.value?'正在读取授权信息':!preapplyOptions.value.length?'该 Agent 暂无有效的数据源、表和字段授权；请先在数据源和 Agent 管理中授权':'没有匹配的数据源');
const sourceOptions=computed(()=>[...new Map(preapplyOptions.value.map(x=>[x.sourceId,{value:x.sourceId,label:x.sourceName}])).values()]);
const tableOptions=computed(()=>[...new Set(preapplyOptions.value.filter(x=>x.sourceId===preapplyForm.value.sourceId).map(x=>String(x.tableName)))].map(value=>({value,label:value})));
const searchedTables=computed(()=>tableOptions.value.filter(x=>x.label.toLowerCase().includes(tableSearch.value.toLowerCase())));
const allColumnsSelected=computed(()=>columnOptions.value.length>0&&preapplyForm.value.columns.length===columnOptions.value.length);
const columnToggleLabel=computed(()=>allColumnsSelected.value?'取消全选':'全选');
function toggleColumns(){preapplyForm.value.columns=allColumnsSelected.value?[]:[...columnOptions.value];}
const columnOptions=computed(()=>preapplyOptions.value.filter(x=>x.sourceId===preapplyForm.value.sourceId&&x.tableName===preapplyForm.value.tableName).map(x=>String(x.columnName)));
function resetTable(){preapplyForm.value.tableName='';preapplyForm.value.columns=[];
 preapplyForm.value.filterColumn='';preapplyForm.value.filterValue='';
 preapplyForm.value.sortColumn='';preapplyForm.value.sortDirection='ASC';preapplyForm.value.offset=0;}
function changeTable(value:string){preapplyForm.value.tableName=value;preapplyForm.value.columns=[...columnOptions.value];
 preapplyForm.value.filterColumn='';preapplyForm.value.filterValue='';
 preapplyForm.value.sortColumn='';preapplyForm.value.sortDirection='ASC';preapplyForm.value.offset=0;}
async function changeAgent(value:string){
 preapplyForm.value.agentId=value;preapplyForm.value.sourceId='';tableSearch.value='';resetTable();preapplyOptions.value=[];
 if(!value)return;
 try{preapplyLoading.value=true;preapplyOptions.value=await api<DataRow[]>('/api/requests/preapply/options?agentId='+encodeURIComponent(value));}
 catch(e){fail(e)}finally{preapplyLoading.value=false}
}
async function openPreapply(){try{
 preapplyAgents.value=await api<DataRow[]>('/api/requests/preapply/agents');
 preapplyForm.value={agentId:'',sourceId:'',tableName:'',columns:[],requestedRows:50,filterColumn:'',filterValue:'',sortColumn:'',sortDirection:'ASC',offset:0};preapplyOptions.value=[];
 requestHelpOpen.value=true;
}catch(e){fail(e)}}
async function submitPreapply(){try{
 preapplyBusy.value=true;
 const parts=[...preapplyForm.value.columns];
 if(!preapplyForm.value.agentId||!preapplyForm.value.sourceId||!preapplyForm.value.tableName||!parts.length)throw Error('请选择 Agent、数据源、数据表和至少一个字段');
 if(preapplyForm.value.filterColumn&&!preapplyForm.value.filterValue)throw Error('筛选字段需要填写筛选值');
 const result=await api<{id:string}>('/api/requests/preapply','POST',{
  ...preapplyForm.value,columns:parts,
  filterColumn:preapplyForm.value.filterColumn||null,
  filterValue:preapplyForm.value.filterColumn?preapplyForm.value.filterValue:null,
  sortColumn:preapplyForm.value.sortColumn||null,
  sortDirection:preapplyForm.value.sortColumn?preapplyForm.value.sortDirection:null
 });
 requestHelpOpen.value=false;selectTab(props.user.roleCode==='ADMIN'?'all':'mine');
 const record=await api<DataRow>('/api/requests/'+result.id);detail.value=record;approvalMode.value=false;
}catch(e){fail(e)}finally{preapplyBusy.value=false}}
async function refresh(){try{rows.value=await api('/api/requests?scope='+tab.value)}catch(e){fail(e)}}
function selectTab(t:string){tab.value=t;detail.value=null;void refresh()}
async function decide(action:'approve'|'reject'){if(!detail.value)return;try{if(!reason.value.trim())throw Error('必须填写审批意见');busy.value=true;
 await api('/api/requests/'+detail.value.id+'/'+action,'POST',{reason:reason.value.trim()});
 detail.value=null;reason.value='';await refresh()}catch(e){fail(e)}finally{busy.value=false}}
const emit=defineEmits<{'detail-change':[value:{open:boolean;name:string;id:string}]}>();
watch(detail,value=>emit('detail-change',{open:Boolean(value),name:value?'申请 #'+String(value.id||''):'',id:String(value?.id||'')}));
onMounted(async()=>{await loadQueryLimit();await refresh();if(props.initialDetailId){try{detail.value=await api('/api/requests/'+props.initialDetailId)}catch(e){fail(e)}}});defineExpose({refresh});
</script>
<template>
<section v-if="!detail" class="panel">
 <div class="toolbar stack">
 <a-button type="primary" @click="openPreapply">＋ 发起访问申请</a-button>
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
 <a-modal v-model:open="requestHelpOpen" title="提前申请访问审批" :width="960" ok-text="提交申请" :confirm-loading="preapplyBusy" @ok="submitPreapply">
  <a-form layout="vertical" class="preapply-form">
   <div class="preapply-form-grid">
    <a-form-item label="Agent" required><a-select :value="preapplyForm.agentId||undefined" placeholder="选择 Agent（管理员可选择全部）" :options="preapplyAgents.map(x=>({value:x.id,label:x.name}))" @change="changeAgent"/></a-form-item>
    <a-form-item label="数据源" required><a-select v-model:value="preapplyForm.sourceId" placeholder="选择已授权数据源" :not-found-content="preapplyEmptyReason" :loading="preapplyLoading" :disabled="!preapplyForm.agentId" :options="sourceOptions" @change="resetTable"/></a-form-item>
   </div>
   <a-alert v-if="preapplyForm.agentId&&!preapplyLoading&&!preapplyOptions.length" type="warning" show-icon message="该 Agent 暂无可用于申请的有效授权" description="请先到数据源管理开放表和字段，再到 Agent 管理为这个 Agent 单独授予相同表和字段；管理员可以操作所有 Agent。" style="margin-bottom:14px"/>
   <div class="picker-two-pane">
    <div class="picker-tables"><div class="picker-pane-head">数据表 <span>{{tableOptions.length}}</span></div><a-input v-model:value="tableSearch" placeholder="搜索数据表" allow-clear class="picker-search"/>
     <div class="picker-list"><button v-for="item in searchedTables" :key="item.value" type="button" class="picker-table-item" :class="{active:preapplyForm.tableName===item.value}" @click="changeTable(item.value)"><span class="picker-checkbox">{{preapplyForm.tableName===item.value?'✓':''}}</span>{{item.label}}</button><a-empty v-if="!searchedTables.length" :image="undefined" description="暂无可选数据表"/></div>
    </div>
    <div class="picker-columns"><div class="picker-pane-head"><span>字段 <span>{{preapplyForm.columns.length}} / {{columnOptions.length}}</span></span><a-button type="link" size="small" :disabled="!columnOptions.length" @click="toggleColumns">{{columnToggleLabel}}</a-button></div>
     <a-checkbox-group v-if="columnOptions.length" v-model:value="preapplyForm.columns" :options="columnOptions.map(value=>({label:value,value}))" class="preapply-column-grid"/><a-empty v-else :image="undefined" description="请先选择左侧数据表"/>
    </div>
   </div>
   <a-form-item label="最大读取行数" required><a-input-number v-model:value="preapplyForm.requestedRows" :min="1" :max="maxRowsPerQuery"/></a-form-item>
   <a-form-item label="起始偏移量（可选，默认 0）"><a-input-number v-model:value="preapplyForm.offset" :min="0" :max="1000000"/></a-form-item>
   <a-form-item label="筛选字段（可选）"><a-select v-model:value="preapplyForm.filterColumn" allow-clear placeholder="不筛选" :options="columnOptions.map(value=>({value,label:value}))"/></a-form-item>
   <a-form-item v-if="preapplyForm.filterColumn" label="筛选值"><a-input v-model:value="preapplyForm.filterValue" :maxlength="256"/></a-form-item>
   <a-form-item label="排序字段（可选）"><a-select v-model:value="preapplyForm.sortColumn" allow-clear placeholder="不排序" :options="columnOptions.map(value=>({value,label:value}))"/></a-form-item>
   <a-form-item v-if="preapplyForm.sortColumn" label="排序方向"><a-radio-group v-model:value="preapplyForm.sortDirection"><a-radio-button value="ASC">升序</a-radio-button><a-radio-button value="DESC">降序</a-radio-button></a-radio-group></a-form-item>
  </a-form>
  <p class="subtle">仅显示当前 Agent 已授权的表和字段。选择数据表后默认勾选全部可读字段；取消勾选可缩小申请范围。实际请求需要字段顺序、行数和查询参数完全匹配。</p>
 </a-modal>
</template>