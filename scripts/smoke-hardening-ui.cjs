#!/usr/bin/env node
// Disposable browser regression: requires Node 18+, Playwright and a temporary Toolbox instance.
const assert=require('node:assert/strict');
const {chromium}=require(process.env.TOOLBOX_PLAYWRIGHT_PATH||'playwright');
const base=process.env.TOOLBOX_BASE_URL||'http://127.0.0.1:18090';
const terminal=new Set(['SUCCEEDED','FAILED','CANCELED','TIMED_OUT','OUTCOME_UNKNOWN']);
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let token,browser,page;const connections=[],sessions=new Set(),errors=[];
async function api(path,method='GET',body){
 const response=await fetch(base+'/api'+path,{method,headers:{'Content-Type':'application/json','X-Toolbox-Token':token||''},body:body===undefined?undefined:JSON.stringify(body)});
 const result=await response.json();assert.ok(response.ok&&result.success,result.message);return result.data;
}
async function execute(sessionId,sql){
 const q={sessionId,sql,mode:'SCRIPT',requestId:crypto.randomUUID()};q.confirmationToken=(await api('/executions/prepare','POST',q)).confirmationToken;
 let r=await api('/executions','POST',q);for(let i=0;!terminal.has(r.state)&&i<160;i++){await sleep(50);r=await api('/executions/'+r.id);}assert.equal(r.state,'SUCCEEDED',r.message);return r;
}
async function uiRun(sql){await page.locator('#sql-editor').fill(sql);await page.getByRole('button',{name:/执行当前/}).click();}
async function completed(){await page.locator('#results .result-state').filter({hasText:'执行完成'}).waitFor({timeout:15000});}
(async()=>{
 try{
  token=(await api('/bootstrap')).token;
  const driver=(await api('/drivers')).find(d=>d.driverClass==='org.h2.Driver'&&d.bundled);
  for(const name of ['A','B']){
   const c=await api('/connections','POST',{name:'Hardening '+name+' '+Date.now(),driverId:driver.id,jdbcUrl:'jdbc:h2:mem:hardening_'+name+'_'+Date.now()+';DB_CLOSE_DELAY=-1',username:'sa',password:''});connections.push(c);
   const session=await api('/sessions','POST',{connectionId:c.id});sessions.add(session.id);await execute(session.id,'CREATE SCHEMA '+name+'_SCOPE; CREATE TABLE '+name+'_SCOPE.TREE_FIXTURE(ID INT)');await api('/sessions/'+session.id,'DELETE');sessions.delete(session.id);
  }
  browser=await chromium.launch({headless:true,...(process.env.TOOLBOX_CHROME_PATH?{executablePath:process.env.TOOLBOX_CHROME_PATH}:{})});
  page=await browser.newPage({viewport:{width:1440,height:980}});
  page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&!m.text().includes('net::ERR_ABORTED'))errors.push(m.text());});
  page.on('dialog',d=>d.accept());
  const uiSessionIds=[];let writes=0;
  page.on('response',async response=>{
   if(response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/sessions')try{const id=(await response.json()).data.id;sessions.add(id);uiSessionIds.push(id);}catch{}
  });
  page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/api/executions')writes++;});
  await page.goto(base);assert.match(await page.title(),/数据库/);await page.locator('#sql-editor').waitFor();
  assert.ok((await page.locator('#app').innerText()).includes('数据库工作台'));
  await page.locator('#tab-connection').selectOption('');
  let releaseContext,contextCaptured;
  const contextGate=new Promise(r=>releaseContext=r),captured=new Promise(r=>contextCaptured=r);
  await page.route('**/api/connections/'+connections[0].id+'/objects?kind=schemas',async route=>{
   const response=await route.fetch();contextCaptured();await contextGate;try{await route.fulfill({response});}catch{}
  });
  await page.locator('#tab-connection').selectOption(connections[0].id);await captured;
  await page.locator('#tab-connection').selectOption(connections[1].id);
  await page.waitForFunction(()=>document.querySelector('#tab-schema')?.textContent.includes('B_SCOPE'));
  releaseContext();await sleep(200);
  assert.ok(!(await page.locator('#tab-schema').textContent()).includes('A_SCOPE'));
  console.log('PASS browser: rapid A/B connection switch keeps B context');
  await page.unrouteAll({behavior:'wait'});

  await uiRun('SELECT 1');
  await page.locator('#sql-editor').evaluate(el=>{window.fixtureEditor=el;el.focus();el.setSelectionRange(1,4);});
  await completed();
  assert.deepEqual(await page.locator('#sql-editor').evaluate(el=>({same:el===window.fixtureEditor,focused:document.activeElement===el,start:el.selectionStart,end:el.selectionEnd})),{same:true,focused:true,start:1,end:4});
  console.log('PASS browser: completion keeps editor node, focus and selection');

  let releaseObjects,heldObjects=0;const objectGate=new Promise(resolve=>releaseObjects=resolve);
  await page.route('**/api/connections/'+connections[1].id+'/objects?*',async route=>{
   const query=new URL(route.request().url()).searchParams;
   if(!['tables','routines'].includes(query.get('kind'))||query.get('schema')!=='B_SCOPE')return route.continue();
   heldObjects++;await objectGate;await route.abort('aborted').catch(()=>{});
  });
  await page.locator('[data-action="toggle-connection"][data-id="'+connections[1].id+'"]').click();
  await page.locator('[data-action="toggle-schema"][data-id="'+connections[1].id+'"][data-schema="B_SCOPE"]').click();
  const treeError=page.locator('#tree .tree-error').filter({hasText:'读取超时，请重试'});
  await treeError.waitFor({timeout:20000});assert.equal(heldObjects,2);
  assert.equal(await treeError.locator('.tree-empty').count(),0);
  const retry=treeError.getByRole('button',{name:'重试',exact:true});assert.ok(await retry.isVisible());
  await page.screenshot({path:process.env.TOOLBOX_UI_SCREENSHOT?.replace(/\.png$/,'-tree-timeout.png')||'/tmp/toolbox-tree-timeout.png',fullPage:false});
  releaseObjects();await page.unrouteAll({behavior:'wait'});
  await retry.click();
  await page.locator('[data-action="open-object"][data-id="'+connections[1].id+'"]').filter({hasText:'TREE_FIXTURE'}).waitFor();
  assert.equal(await treeError.count(),0);
  console.log('PASS browser: object read deadline shows timeout, retry restores the real table');

  let releasePoll,pollCaptured;const pollGate=new Promise(r=>releasePoll=r),pollReady=new Promise(r=>pollCaptured=r);let held=false;
  await page.route('**/api/executions/*',async route=>{
   const request=route.request(),path=new URL(request.url()).pathname;
   if(request.method()!=='GET'||!/^\/api\/executions\/[^/]+$/.test(path)||held)return route.continue();
   held=true;const response=await route.fetch();const body=await response.json();assert.equal(body.data.state,'RUNNING');pollCaptured();await pollGate;try{await route.fulfill({response});}catch{}
  });
  await uiRun('SELECT SUM(X) FROM SYSTEM_RANGE(1,1000000000)');await pollReady;
  await page.getByRole('button',{name:'取消',exact:true}).click();releasePoll();
  await page.locator('#results .result-state').filter({hasText:/已取消|结果未知|执行超时/}).waitFor({timeout:15000});
  const ended=await page.locator('#results .result-state').textContent();await sleep(600);assert.equal((await page.locator('#results .result-state').textContent()).split(' · ')[0],ended.split(' · ')[0]);
  console.log('PASS browser: stale running poll cannot overwrite cancellation');
  await page.unrouteAll({behavior:'wait'});

  await uiRun('SHUTDOWN');await page.getByRole('button',{name:'恢复会话',exact:true}).waitFor({timeout:15000});
  const oldId=uiSessionIds[uiSessionIds.length-1],before=writes;
  await page.getByRole('button',{name:'恢复会话',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.session-badge')?.textContent.includes('会话就绪'));
  await sleep(150);assert.notEqual(uiSessionIds[uiSessionIds.length-1],oldId);assert.equal(writes,before);
  await uiRun('SELECT 2');await completed();
  console.log('PASS browser: broken session recovery creates a new session without replaying SQL');
  await page.screenshot({path:process.env.TOOLBOX_UI_SCREENSHOT||'/tmp/toolbox-hardening-ui.png',fullPage:false});
  await page.setViewportSize({width:1100,height:800});await page.locator('#sql-editor').waitFor();assert.ok(await page.getByRole('button',{name:/执行当前/}).isVisible());
  assert.deepEqual(errors,[]);console.log('PASS browser: page identity, meaningful content, controls, 2 viewports, no JS/console errors');
 }catch(error){if(page)await page.screenshot({path:'/tmp/toolbox-hardening-ui-failure.png'}).catch(()=>{});throw error;}
 finally{
  if(page)await page.unrouteAll({behavior:'ignoreErrors'}).catch(()=>{});
  if(browser)await browser.close();
  for(const id of sessions)await api('/sessions/'+id,'DELETE').catch(()=>{});
  for(const c of connections)await api('/connections/'+c.id,'DELETE').catch(()=>{});
 }
})().catch(error=>{console.error(error);process.exitCode=1;});
