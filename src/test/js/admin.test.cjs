const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {JSDOM, VirtualConsole} = require('jsdom');
const html=readFileSync('src/main/resources/static/admin/index.html','utf8');
const script=readFileSync('src/main/resources/static/admin/admin.js','utf8');
const owner='11111111-1111-4111-8111-111111111111',id='22222222-2222-4222-8222-222222222222';
const payload={name:'Присед',muscleGroup:'LEGS',type:'STRENGTH',isCustom:true,updatedAt:1,needsMuscleMapReview:false,equipmentRequirementState:'KNOWN',muscles:[{muscle:'ABS',contribution:0},{muscle:'QUADS',contribution:100}],equipmentIds:['barbell']};
const record={user_id:owner,email:'athlete@example.test',kind:'exercise',id,revision:7,payload};
const summary={users:1,verifiedUsers:1,activeSessions:1,records:[{kind:'exercise',count:1}],recentActions:[]};
const tick=()=>new Promise(resolve=>setTimeout(resolve,10));
async function until(predicate) {for(let i=0;i<100;i++){if(predicate())return;await tick();}assert.fail('Expected UI state was not reached');}
async function setup(overrides={}) {
  const errors=[],requests=[];
  const virtualConsole=new VirtualConsole();virtualConsole.on('jsdomError',e=>errors.push(e));
  const dom=new JSDOM(html,{url:'https://admin.test/admin/',runScripts:'outside-only',virtualConsole,pretendToBeVisual:true});
  const w=dom.window;
  w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
  w.HTMLDialogElement.prototype.close=function(){this.open=false;};
  w.confirm=()=>true;
  w.fetch=async(path,options={})=>{
    const url=new URL(path,'https://admin.test'),method=options.method||'GET',body=options.body?JSON.parse(options.body):null;
    requests.push({path:url.pathname,query:url.searchParams,method,body,headers:options.headers});
    let status=200,data;
    const custom=overrides[method+' '+url.pathname];
    if(custom){const result=await custom(body,requests.at(-1));status=result.status||200;data=result.data;}
    else if(url.pathname.endsWith('/session'))data={email:'admin@example.test',userId:owner,csrfToken:'test-csrf'};
    else if(url.pathname.endsWith('/summary'))data=summary;
    else if(url.pathname.endsWith('/catalog'))data={equipment:['barbell','dumbbells'],muscles:['ABS','QUADS']};
    else if(url.pathname.endsWith('/exercise-options'))data=[{id,name:'Присед'}];
    else if(url.pathname==='/admin/api/records')data={items:[{...record,name:payload.name,muscle_group:'LEGS',exercise_type:'STRENGTH'}],hasMore:false,offset:0};
    else if(url.pathname==='/admin/api/users')data={items:[{id:owner,email:'athlete@example.test',email_verified:true,record_count:1}],hasMore:false,offset:0};
    else if(url.pathname.endsWith('/records/exercise/'+id) && method==='GET')data=record;
    else if(url.pathname.endsWith('/records/exercise/'+id) && method==='PUT')data={revision:8};
    else throw new Error('Unexpected API request: '+method+' '+url.pathname);
    return {ok:status>=200&&status<300,status,text:async()=>JSON.stringify(data)};
  };
  w.eval(script);
  await until(()=>!w.document.getElementById('shell').hidden);
  await until(()=>w.document.querySelectorAll('.stat').length===4);
  const click=label=>{const found=[...w.document.querySelectorAll('button')].find(x=>x.textContent.trim()===label&&!x.hidden);assert.ok(found,'Button '+label);found.click();};
  const field=label=>{const found=[...w.document.querySelectorAll('#dialog-content label')].find(x=>x.firstChild?.textContent===label);assert.ok(found,'Field '+label);return found.querySelector('input,select,textarea');};
  async function editor() {w.document.querySelector('[data-view=exercise]').click();await until(()=>w.document.querySelector('.table-link'));click('Изменить →');await until(()=>w.document.querySelector('#dialog-content form'));}
  function submit() {const form=w.document.querySelector('#dialog-content form');form.dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));}
  return {dom,w,requests,errors,click,field,editor,submit};
}

test('exercise editor preserves stabilizers and sends revision with CSRF',async t=>{
  const ui=await setup();t.after(()=>ui.dom.window.close());await ui.editor();
  assert.equal(ui.field('Пресс').value,'0');
  ui.field('Название').value='Присед со штангой';
  ui.field('Причина изменения').value='Уточнено название';
  ui.submit();await until(()=>ui.requests.some(r=>r.method==='PUT'));
  const request=ui.requests.find(r=>r.method==='PUT');
  assert.equal(request.headers['X-CSRF-Token'],'test-csrf');
  assert.equal(request.body.baseRevision,7);
  assert.equal(request.body.payload.name,'Присед со штангой');
  assert.deepEqual(request.body.payload.muscles,[{muscle:'ABS',contribution:0},{muscle:'QUADS',contribution:100}]);
  await until(()=>!ui.w.document.getElementById('dialog').open);
  assert.equal(ui.errors.length,0);
  assert.equal(ui.w.localStorage.length,0);
  await until(()=>!ui.w.document.getElementById('refresh').disabled);
});

test('revision conflict preserves unsaved form and offers explicit reload',async t=>{
  const ui=await setup({['PUT /admin/api/users/'+owner+'/records/exercise/'+id]:()=>({status:409,data:{message:'Запись изменилась'}})});
  t.after(()=>ui.dom.window.close());await ui.editor();ui.field('Название').value='Моя правка';ui.field('Причина изменения').value='Исправление';
  ui.submit();await until(()=>ui.w.document.querySelector('#dialog-content .error').textContent);
  assert.equal(ui.field('Название').value,'Моя правка');
  assert.equal(ui.w.document.getElementById('dialog').open,true);
  assert.ok([...ui.w.document.querySelectorAll('button')].some(x=>x.textContent==='Открыть актуальную версию'&&!x.hidden));
});

test('retry after lost response reuses exact operation and payload',async t=>{
  let calls=0;
  const ui=await setup({['PUT /admin/api/users/'+owner+'/records/exercise/'+id]:()=>{if(++calls===1)throw new Error('connection lost');return {data:{revision:8}};}});
  t.after(()=>ui.dom.window.close());await ui.editor();ui.field('Причина изменения').value='Проверка повторного сохранения';
  ui.submit();await until(()=>ui.w.document.querySelector('#dialog-content .error').textContent);
  ui.submit();await until(()=>ui.requests.filter(r=>r.method==='PUT').length===2);
  const writes=ui.requests.filter(r=>r.method==='PUT');assert.deepEqual(writes[0].body,writes[1].body);
  await until(()=>!ui.w.document.getElementById('dialog').open&&!ui.w.document.getElementById('refresh').disabled);
});

test('user supplied markup is displayed as text and never becomes an element',async t=>{
  const attack='<img src=x onerror="window.compromised=true">';
  const ui=await setup({'GET /admin/api/records':()=>({data:{items:[{...record,name:attack}],hasMore:false,offset:0}})});
  t.after(()=>ui.dom.window.close());ui.w.document.querySelector('[data-view=exercise]').click();
  await until(()=>ui.w.document.getElementById('table-wrap').textContent.includes(attack));
  assert.equal(ui.w.document.querySelector('#table-wrap img'),null);
  assert.equal(ui.w.compromised,undefined);assert.equal(ui.errors.length,0);
});

test('gym editor keeps selected exercises when editing inventory mode',async t=>{
  const gym={...record,kind:'gym',payload:{name:'Зал',updatedAt:1,inventoryConfigured:false,exerciseIds:[id],equipmentIds:['barbell']}};
  const path='/admin/api/users/'+owner+'/records/gym/'+id;
  const ui=await setup({'GET /admin/api/records':()=>({data:{items:[{...gym,name:'Зал'}],hasMore:false,offset:0}}),['GET '+path]:()=>({data:gym}),['PUT '+path]:()=>({data:{revision:8}})});
  t.after(()=>ui.dom.window.close());ui.w.document.querySelector('[data-view=gym]').click();
  await until(()=>ui.w.document.querySelector('.table-link'));ui.click('Изменить →');await until(()=>ui.w.document.querySelector('#dialog-content form'));
  ui.field('Название').value='Обновлённый зал';ui.field('Причина изменения').value='Обновлено название';
  ui.submit();await until(()=>ui.requests.some(r=>r.method==='PUT'));
  const write=ui.requests.find(r=>r.method==='PUT');
  assert.deepEqual(write.body.payload.exerciseIds,[id]);assert.deepEqual(write.body.payload.equipmentIds,['barbell']);
  assert.equal(write.body.payload.inventoryConfigured,false);
  await until(()=>!ui.w.document.getElementById('dialog').open&&!ui.w.document.getElementById('refresh').disabled);
});
