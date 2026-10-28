<script setup lang="ts">
defineEmits<{query:[];reset:[]}>();
</script>
<template>
 <div class="toolbar dg-filter-bar">
  <slot/>
  <div class="dg-filter-actions">
   <a-button class="dg-filter-reset" @click="$emit('reset')">重置</a-button>
   <a-button class="dg-filter-query" type="primary" @click="$emit('query')">查询</a-button>
  </div>
  <slot name="actions"/>
 </div>
</template>