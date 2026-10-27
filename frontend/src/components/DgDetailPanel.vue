<script setup lang="ts">
defineOptions({inheritAttrs:false});
defineProps<{backLabel:string}>();
defineEmits<{back:[]}>();
</script>
<template>
 <section v-bind="$attrs" class="panel detail-page dg-detail-panel">
  <teleport to="#workspace-actions"><a-button class="dg-detail-back" @click="$emit('back')">{{backLabel}}</a-button></teleport>
  <slot/>
 </section>
</template>