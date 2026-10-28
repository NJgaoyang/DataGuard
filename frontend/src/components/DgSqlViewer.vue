<script setup lang="ts">
import {computed} from 'vue';
import {message} from 'ant-design-vue';
const props=withDefaults(defineProps<{sql?:string|null;showCopy?:boolean}>(),{showCopy:true});
const known=new Set('SELECT FROM WHERE JOIN LEFT RIGHT INNER OUTER ON GROUP BY HAVING ORDER LIMIT OFFSET AS AND OR NOT IN IS NULL COUNT SUM AVG MIN MAX ROUND CASE WHEN THEN ELSE END DISTINCT DESC ASC'.split(' '));
const tokens=computed(()=>{
 const sql=props.sql||'';
 const pieces=sql.match(/'(?:''|[^'])*'|"(?:""|[^"])*"|\b[A-Za-z_][A-Za-z_0-9]*\b|\d+(?:\.\d+)?|\s+|./g)||[];
 return pieces.map((text)=>({text,kind:/^['"]/.test(text)?'literal':known.has(text.toUpperCase())?'keyword':/^\d/.test(text)?'number':''}));
});
async function copy(){
 if(!props.sql)return;
 try{await navigator.clipboard.writeText(props.sql);message.success('SQL 模板已复制');}
 catch{message.error('复制失败，请手动选择文本');}
}
</script>
<template>
 <div class="dg-sql-viewer">
  <div class="dg-sql-head">
   <span>SQL（已脱敏）</span><a-tag>仅审计模板 · 不含绑定参数</a-tag>
   <a-button v-if="showCopy&&sql" size="small" @click="copy">复制</a-button>
  </div>
  <pre class="dg-sql-code record-sql-full"><code v-if="sql"><span v-for="(token,i) in tokens" :key="i" :class="token.kind">{{token.text}}</span></code><span v-else class="subtle">历史记录未保存 SQL</span></pre>
 </div>
</template>