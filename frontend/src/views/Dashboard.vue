<script setup lang="ts">
import {computed,onMounted,ref} from 'vue';
import {api,stamp,stateName,stateColor,type DataRow} from '../api';
const emit=defineEmits<{navigate:[page:string];openRecord:[row:DataRow]}>();
const data=ref<DataRow>({stats:{},recent:[]}),loading=ref(false);
const cards=computed(()=>{const s=data.value.stats||{},requests=Number(s.requests||0),success=Number(s.success||0);
 return [
 {name:'访问次数',value:requests,to:'records',icon:'bars'}, {name:'待审批',value:s.pending||0,to:'approvals',icon:'clock'},
 {name:'已拒绝',value:s.denied||0,to:'records',icon:'stop'},{name:'成功率',value:requests?Math.round(success*100/requests)+'%':'—',to:'records',icon:'check'}]});
async function refresh(){loading.value=true;try{data.value=await api('/api/dashboard')}finally{loading.value=false}}
onMounted(()=>void refresh());defineExpose({refresh});
</script>
<template><a-spin :spinning="loading">
 <div class="metric-grid"><button v-for="card in cards" :key="card.name" class="metric" @click="emit('navigate',card.to)">
 <span>{{card.name}}</span><strong>{{card.value}}</strong><span class="metric-decoration" :class="card.icon">●</span>
 </button></div>
 <section class="panel"><div class="panel-head"><h2>最近访问</h2><a-button type="link" @click="emit('navigate','records')">查看全部</a-button></div>
 <a-table :data-source="data.recent||[]" row-key="id" :pagination="false" :scroll="{x:720}">
  <a-table-column title="时间"><template #default="{record}">{{stamp(record.createdAt)}}</template></a-table-column>
  <a-table-column title="Agent" data-index="agentName"/>
  <a-table-column title="访问对象" data-index="tableName"/>
  <a-table-column title="请求行数" data-index="requestedRows"/>
  <a-table-column title="状态"><template #default="{record}"><a-tag :color="stateColor(record.status)">{{stateName[record.status]||record.status}}</a-tag></template></a-table-column>
  <a-table-column title="操作"><template #default="{record}"><a-button type="link" @click="emit('navigate','records')">查看</a-button></template></a-table-column>
 </a-table></section></a-spin>
</template>