<script setup lang="ts">
import {nextTick,onBeforeUnmount,onMounted,ref,watch} from 'vue';
import * as echarts from 'echarts/core';
import {PieChart,BarChart} from 'echarts/charts';
import {CanvasRenderer} from 'echarts/renderers';
import {TooltipComponent,GridComponent,LegendComponent} from 'echarts/components';
import type {EChartsType} from 'echarts/core';
echarts.use([PieChart,BarChart,CanvasRenderer,TooltipComponent,GridComponent,LegendComponent]);
type Recent={status?:string;agentName?:string};
const props=defineProps<{recent:Recent[];statusNames:Record<string,string>}>();
const statusEl=ref<HTMLDivElement|null>(null),agentEl=ref<HTMLDivElement|null>(null);
let statusChart:EChartsType|null=null,agentChart:EChartsType|null=null,resizeObserver:ResizeObserver|null=null;
function grouped(key:'status'|'agentName'){
 const sums=new Map<string,number>();
 for(const row of props.recent||[]){
  const name=key==='status'?(props.statusNames[row.status||'']||row.status||'未知'):row.agentName||'未知';
  sums.set(name,(sums.get(name)||0)+1);
 }
 return [...sums.entries()].sort((a,b)=>b[1]-a[1]).map(([name,value])=>({name,value}));
}
function render(){
 if(!statusEl.value||!agentEl.value)return;
 statusChart ||= echarts.init(statusEl.value,undefined,{renderer:'canvas'});
 agentChart ||= echarts.init(agentEl.value,undefined,{renderer:'canvas'});
 if(resizeObserver){resizeObserver.observe(statusEl.value);resizeObserver.observe(agentEl.value);}
 const statuses=grouped('status'),agents=grouped('agentName').slice(0,8);
 statusChart.setOption({
  animation:false,tooltip:{trigger:'item'},legend:{bottom:0,type:'scroll'},
  series:[{name:'访问状态',type:'pie',radius:['43%','68%'],center:['50%','42%'],
   label:{show:true,formatter:'{b} {c}'},data:statuses,
   itemStyle:{borderRadius:4,borderColor:'#fff',borderWidth:2}}]
 },true);
 agentChart.setOption({
  animation:false,tooltip:{trigger:'axis',axisPointer:{type:'shadow'}},
  grid:{left:8,right:22,top:18,bottom:8,containLabel:true},
  xAxis:{type:'value',minInterval:1,axisLabel:{color:'#75839a'}},
  yAxis:{type:'category',data:agents.map(a=>a.name).reverse(),axisLabel:{color:'#50627a',width:130,overflow:'truncate'}},
  series:[{name:'最近访问次数',type:'bar',data:agents.map(a=>a.value).reverse(),
   barWidth:16,itemStyle:{color:'#1766e8',borderRadius:[0,4,4,0]}}]
 },true);
}
onMounted(async()=>{await nextTick();render();resizeObserver=new ResizeObserver(()=>{statusChart?.resize();agentChart?.resize()});
 for(const el of [statusEl.value,agentEl.value])if(el)resizeObserver.observe(el);
});
watch(()=>props.recent,()=>void nextTick(render),{deep:true});
onBeforeUnmount(()=>{resizeObserver?.disconnect();statusChart?.dispose();agentChart?.dispose();statusChart=null;agentChart=null});
</script>
<template>
 <section class="dg-chart-grid" aria-label="最近访问图表">
  <div class="panel dg-chart-panel">
   <div class="panel-head"><h2>最近访问状态分布</h2><span class="subtle">最近 {{recent?.length||0}} 条 · 非历史全量</span></div>
   <div v-if="recent?.length" ref="statusEl" class="dg-chart-canvas" role="img" aria-label="最近访问状态分布图"/>
   <a-empty v-else description="暂无最近访问数据" class="dg-chart-empty"/>
  </div>
  <div class="panel dg-chart-panel">
   <div class="panel-head"><h2>最近访问 Agent 分布</h2><span class="subtle">最近 {{recent?.length||0}} 条 · 非历史全量</span></div>
   <div v-if="recent?.length" ref="agentEl" class="dg-chart-canvas" role="img" aria-label="最近访问 Agent 分布图"/>
   <a-empty v-else description="暂无最近访问数据" class="dg-chart-empty"/>
  </div>
 </section>
</template>