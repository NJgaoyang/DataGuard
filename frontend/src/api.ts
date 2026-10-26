import {message,Modal} from 'ant-design-vue';
export type DataRow=Record<string,any>;
export type User={username:string;displayName:string;roleCode:string;mustChangePassword:boolean};
let csrf='';
export async function initCsrf(){
 const r=await fetch('/api/csrf',{credentials:'same-origin'});
 if(!r.ok)throw Error('无法获取安全会话令牌');
 csrf=(await r.json()).token;
}
export async function api<T=any>(path:string,method='GET',body?:unknown):Promise<T>{
 const r=await fetch(path,{method,credentials:'same-origin',
   headers:{'X-CSRF-TOKEN':csrf,...(body!==undefined?{'Content-Type':'application/json'}:{})},
   body:body===undefined?undefined:JSON.stringify(body)});
 if(r.status===401)throw Error('会话已失效，请重新登录');
 if(r.status===428)throw Error('请先修改初始密码');
 if(r.status===403&&path==='/api/password')throw Error('修改密码被拒绝：请确认当前密码正确，刷新页面后重新登录再试');
 if(!r.ok){let d:any;try{d=await r.json()}catch{}throw Error(d?.message||d?.detail||'请求失败 ('+r.status+')');}
 return r.status===204?undefined as T:await r.json();
}
export async function login(username:string,password:string){
 await initCsrf();
 const r=await fetch('/login',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded','X-CSRF-TOKEN':csrf},
   body:new URLSearchParams({username,password})});
 if(!r.ok)throw Error('用户名或密码错误');
 // Spring Security rotates the CSRF token upon successful authentication.
 // Re-fetch it in the authenticated session before any password/admin POST.
 await initCsrf();
 return api<User>('/api/session');
}
export async function logout(){
 try{await fetch('/logout',{method:'POST',credentials:'same-origin',headers:{'X-CSRF-TOKEN':csrf}})}finally{location.reload()}
}
export function fail(e:unknown){message.error(e instanceof Error?e.message:'操作失败')}
export function confirm(title:string,action:()=>Promise<void>){Modal.confirm({title,okText:'确认',cancelText:'取消',onOk:action})}
export function stamp(v:unknown){
 if(!v)return '—';const date=new Date(String(v));return Number.isNaN(date.getTime())?String(v):
 new Intl.DateTimeFormat('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(date).replaceAll('/','-');
}
export function match(row:DataRow,q:string,fields:string[]){return !q||fields.some(key=>String(row[key]??'').toLowerCase().includes(q.toLowerCase()))}
export const page={pageSize:10,showSizeChanger:false,showTotal:(total:number)=>`共 ${total} 条`};
export const stateName:Record<string,string>={SUCCESS:'成功',PENDING:'待审批',APPROVED:'审批通过',DENIED:'已拒绝',REJECTED:'已拒绝',FAILED:'失败',EXECUTING:'执行中',EXPIRED:'已过期',CANCELLED:'已取消',TIMEOUT:'超时',ERROR:'错误',ALLOW:'直接放行',APPROVAL:'需要审批',DENY:'直接拒绝',ACTIVE:'正常',DISABLED:'已停用',REVOKED:'已撤销'};
export function stateColor(s:string){return s==='SUCCESS'?'green':s==='PENDING'?'orange':s==='APPROVED'?'blue':['DENIED','REJECTED','FAILED'].includes(s)?'red':'default'}
