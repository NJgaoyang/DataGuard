import {createRouter,createWebHistory} from 'vue-router';
const names=['dashboard','sources','agents','rules','approvals','records','settings'];
const View={render:()=>null};
export const router=createRouter({history:createWebHistory(),routes:[
 {path:'/',redirect:'/dashboard'},
 ...names.map(name=>({path:'/'+name,name,component:View})),
 {path:'/records/:id',name:'record-detail',component:View},
 {path:'/:pathMatch(.*)*',redirect:'/dashboard'}
]});
