<script setup lang="ts">
import {computed,onMounted,ref} from 'vue';
import {api,stamp,stateName,stateColor,type DataRow} from '../api';
const emit=defineEmits<{navigate:[page:string,condition?:{status?:string;scope?:string}];openRecord:[id:string]}>();
const data=ref<DataRow>({stats:{},recent:[]}),loading=ref(false);
const cards=computed(()=>{const s=data.value.stats||{},requests=Number(s.requests||0),success=Number(s.success||0);
 return [
 {name:'访问次数',value:requests,to:'records',condition:{},icon:'bars'}, {name:'待审批',value:s.pending||0,to:'approvals',condition:{scope:'pending'},icon:'clock'},
 {name:'已拒绝',value:s.denied||0,to:'records',condition:{status:'DENIED_OR_REJECTED'},icon:'stop'},{name:'成功率',value:requests?Math.round(success*100/requests)+'%':'—',to:'records',condition:{status:'SUCCESS'},icon:'check'}]});
async function refresh(){loading.value=true;try{data.value=await api('/api/dashboard')}finally{loading.value=false}}
onMounted(()=>void refresh());defineExpose({refresh});
</script>
<template><a-spin :spinning="loading">
 <div class="metric-grid"><button v-for="card in cards" :key="card.name" class="metric" @click="emit('navigate',card.to,card.condition)">
 <span>{{card.name}}</span><strong>{{card.value}}</strong><span class="metric-decoration" :class="card.icon">●</span>
 </button></div>
 <section class="panel"><div class="panel-head"><h2>最近访问</h2><a-button type="link" @click="emit('navigate','records')">查看全部</a-button></div>
 <a-table :data-source="data.recent||[]" row-key="id" :pagination="false" :scroll="{x:720}">
  <a-table-column title="序号" :width="72"><template #default="{index}">{{index+1}}</template></a-table-column>
  <a-table-column title="时间"><template #default="{record}">{{stamp(record.createdAt)}}</template></a-table-column>
  <a-table-column title="Agent" data-index="agentName"/>
  <a-table-column title="访问对象" data-index="tableName"/>
  <a-table-column title="请求行数" data-index="requestedRows"/>
  <a-table-column title="状态"><template #default="{record}"><a-tag :color="stateColor(record.status)">{{stateName[record.status]||record.status}}</a-tag></template></a-table-column>
  <a-table-column title="操作" :width="110"><template #default="{record}"><a-space class="table-row-actions"><a-button class="row-view-btn" @click="emit('openRecord',record.id)">查看</a-button></a-space></template></a-table-column>
 </a-table></section></a-spin>
</template>