import {computed,ref} from 'vue';
/** Draft changes never silently mutate the last applied search. */
export function useListSearch(){
 const draftSearch=ref('');
 const appliedSearch=ref('');
 const isDirty=computed(()=>draftSearch.value.trim()!==appliedSearch.value);
 function querySearch(){appliedSearch.value=draftSearch.value.trim();}
 function resetSearch(){draftSearch.value='';}
 return {draftSearch,appliedSearch,isDirty,querySearch,resetSearch};
}
