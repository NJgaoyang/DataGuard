import {createRouter,createWebHistory} from 'vue-router';
const names=['dashboard','sources','agents','rules','approvals','records','settings'];
const View={render:()=>null};
export const router=createRouter({history:createWebHistory(),routes:[
 {path:'/',redirect:'/dashboard'},
 ...names.map(name=>({path:'/'+name,name,component:View})),
 ...['sources','agents','rules','approvals','records'].map(name=>({path:'/'+name+'/:id',name:name+'-detail',component:View})),
 {path:'/:pathMatch(.*)*',redirect:'/dashboard'}
]});
