<script setup lang="ts">defineProps<{open:boolean;title:string;width?:number}>();defineEmits<{close:[]}>();</script>
<template><a-drawer :open="open" :width="width||460" :title="title" @close="$emit('close')"><slot/></a-drawer></template>
