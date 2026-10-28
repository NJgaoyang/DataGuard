<script setup lang="ts">
import {computed,useAttrs} from 'vue';
// Forward native a-table props/events and scoped a-table-column slots.
defineOptions({inheritAttrs:false});
const attrs=useAttrs();
const mergedLocale=computed(()=>({emptyText:'暂无数据',...((attrs.locale as object)||{})}));
</script>
<template>
 <a-table v-bind="$attrs" class="dg-data-table" :locale="mergedLocale"><slot/></a-table>
</template>