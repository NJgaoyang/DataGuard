<script setup lang="ts">
import {nextTick,onBeforeUnmount,onMounted,ref,watch} from 'vue';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
const props=withDefaults(defineProps<{modelValue:string;readOnly?:boolean;height?:number}>(),{readOnly:false,height:280});
const emit=defineEmits<{'update:modelValue':[value:string]}>();
const container=ref<HTMLDivElement|null>(null);
let instance:import('monaco-editor').editor.IStandaloneCodeEditor|null=null;
let observer:ResizeObserver|null=null;
let disposed=false;
onMounted(async()=>{
 await nextTick();
 if(!container.value)return;
 // Worker is local and bundled with Vite; no external CDN or third-party SQL upload.
 (globalThis as {MonacoEnvironment?:{getWorker:()=>Worker}}).MonacoEnvironment={getWorker:()=>new EditorWorker()};
 const monaco=await import('monaco-editor');
 await import('monaco-editor/esm/vs/basic-languages/sql/sql.contribution');
 if(disposed||!container.value)return;
 instance=monaco.editor.create(container.value,{
  value:props.modelValue,language:'sql',theme:'vs',readOnly:props.readOnly,
  minimap:{enabled:false},fontSize:13,scrollBeyondLastLine:false,wordWrap:'on',
  automaticLayout:false,overviewRulerLanes:0,lineNumbers:'on',padding:{top:12,bottom:12},
 });
 instance.onDidChangeModelContent(()=>{if(instance)emit('update:modelValue',instance.getValue())});
 observer=new ResizeObserver(()=>instance?.layout());
 observer.observe(container.value);
});
watch(()=>props.modelValue,value=>{if(instance&&value!==instance.getValue())instance.setValue(value)});
watch(()=>props.readOnly,readOnly=>instance?.updateOptions({readOnly}));
onBeforeUnmount(()=>{disposed=true;observer?.disconnect();instance?.dispose();instance=null});
</script>
<template>
 <div class="dg-monaco-wrap" :style="{height:height+'px'}">
  <div ref="container" class="dg-monaco-editor" aria-label="SQL 编辑器"/>
 </div>
</template>
