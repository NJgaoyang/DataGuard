<script setup lang="ts">
export type PermissionField={name:string;checked:boolean;masked?:boolean};
withDefaults(defineProps<{fields:PermissionField[];disabled?:boolean;readOnly?:boolean;showMask?:boolean;showSelectAll?:boolean;allDisabled?:boolean;emptyText?:string}>(),{emptyText:'暂无字段'});
defineEmits<{toggle:[name:string,checked:boolean];toggleAll:[];toggleMask:[name:string]}>();
</script>
<template>
 <div class="dg-permission-fields">
  <div class="picker-pane-head"><span>字段 <span>{{fields.filter(c=>c.checked).length}} / {{fields.length}}</span></span>
   <a-button v-if="showSelectAll&&!readOnly" type="link" size="small" :disabled="disabled||allDisabled||!fields.length" @click="$emit('toggleAll')">{{fields.length&&fields.every(c=>c.checked)?'取消全选':'全选'}}</a-button>
  </div>
  <div v-if="fields.length" class="source-field-list">
   <div v-for="field in fields" :key="field.name" class="source-field-row">
    <a-checkbox :checked="field.checked" :disabled="readOnly||disabled" @change="$emit('toggle',field.name, $event.target.checked)">{{field.name}}</a-checkbox>
    <a-checkbox v-if="showMask" :checked="!!field.masked" :disabled="readOnly||!field.checked||disabled" @change="$emit('toggleMask',field.name)">脱敏</a-checkbox>
   </div>
  </div>
  <a-empty v-else :image="undefined" :description="emptyText"/>
 </div>
</template>