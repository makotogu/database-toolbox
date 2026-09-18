#!/usr/bin/env node
// Disposable instances only. Optional TOOLBOX_FILTER_FIXTURES points to JSON [{kind,host,port,database,username,password}].
if(process.argv.includes('--help')) {console.log('Run against a temporary Toolbox with TOOLBOX_BASE_URL, TOOLBOX_PLAYWRIGHT_PATH, TOOLBOX_CHROME_PATH. Creates/removes its own H2 fixtures; optional TOOLBOX_FILTER_FIXTURES enables explicitly supplied disposable MySQL/PostgreSQL databases. Never use production.');process.exit(0);}
const assert=require('node:assert/strict'), fs=require('node:fs');
const {chromium}=require(process.env.TOOLBOX_PLAYWRIGHT_PATH||'playwright');
const base=process.env.TOOLBOX_BASE_URL||'http://127.0.0.1:18096', shot=process.env.TOOLBOX_UI_SCREENSHOT||'/tmp/toolbox-filters.png';
const pause=ms=>new Promise(r=>setTimeout(r,ms));
let token,browser,page;const profiles=[],sessions=new Set(),errors=[];
async function api(path,method='GET',body){const r=await fetch(base+'/api'+path,{method,headers:{'Content-Type':'application/json','X-Toolbox-Token':token||''},body:body===undefined?undefined:JSON.stringify(body)});const j=await r.json();assert.ok(r.ok&&j.success,j.message);return j.data;}
async function execute(sessionId,sql,extra={}){const q={sessionId,sql,mode:'SCRIPT',requestId:crypto.randomUUID(),...extra};q.confirmationToken=(await api('/executions/prepare','POST',q)).confirmationToken;let r=await api('/executions','POST',q);for(let n=0;n<300&&['RUNNING','QUEUED','CANCEL_REQUESTED'].includes(r.state);n++){await pause(30);r=await api('/executions/'+r.id);}assert.equal(r.state,'SUCCEEDED',r.message);return r;}
const rows=r=>r.statements[0].results[0].rows;
async function completed(){await page.waitForFunction(()=>!document.querySelector('[data-action=table-read]')?.disabled && document.querySelector('#results .result-state')?.textContent.includes('执行完成'));}
async function read(){await page.locator('[data-action=table-read]').click();await completed();}
async function condition(index,column,operator,value=''){const row=page.locator('[data-filter-row]').nth(index);await row.locator('[data-filter=column]').selectOption(column);await row.locator('[data-filter=operator]').selectOption(operator);if(!operator.includes('NULL'))await row.locator('[data-filter=value]').fill(value);}
(async()=>{try{
 token=(await api('/bootstrap')).token;const drivers=await api('/drivers');
 browser=await chromium.launch({headless:true,executablePath:process.env.TOOLBOX_CHROME_PATH});page=await browser.newPage({viewport:{width:1440,height:980}});page.setDefaultTimeout(12000);page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&!/status of 400/.test(m.text()))errors.push(m.text());});page.on('dialog',d=>d.accept());page.on('response',async r=>{if(r.request().method()==='POST'&&r.url().endsWith('/api/sessions'))try{sessions.add((await r.json()).data.id);}catch{}});
 const profile=await api('/connections','POST',{name:'Filters '+Date.now(),driverId:drivers.find(d=>d.driverClass==='org.h2.Driver').id,jdbcUrl:'jdbc:h2:mem:filters_'+Date.now(),username:'sa'});profiles.push(profile.id);
 const fixture=(await api('/sessions','POST',{connectionId:profile.id})).id;sessions.add(fixture);
 const create="CREATE TABLE FILTER_UI(ID INT PRIMARY KEY, TITLE VARCHAR(80), AMOUNT DECIMAL(30,3)); INSERT INTO FILTER_UI SELECT X, CASE X WHEN 1 THEN NULL WHEN 2 THEN '' ELSE 'foo' END, 12345678901234567890.123 FROM SYSTEM_RANGE(1,405)";
 await execute(fixture,create);await page.goto(base);await page.locator('[data-action=toggle-schema][data-id="'+profile.id+'"][data-schema=PUBLIC]').click();await page.locator('[data-action=open-object]').filter({hasText:/^FILTER_UI$/}).click();await completed();
 const tableRows=page.locator('#result-body .data-table tbody tr');assert.equal(await tableRows.count(),200);
 await condition(0,'ID','>=','2');await page.locator('[data-action=filter-add]').click();await condition(1,'TITLE','=','foo');assert.match(await page.locator('#filter-status').textContent(),/待应用/);assert.ok(await page.locator('[data-action=table-next]').isDisabled());
 await read();assert.equal(await tableRows.first().locator('[data-column="0"]').textContent(),'3');
 await page.locator('[data-action=table-next]').click();await completed();assert.match(await page.locator('.table-footer').first().textContent(),/第 2 页/);assert.equal(await tableRows.first().locator('[data-column="0"]').textContent(),'203');
 await condition(0,'ID','=','1');await page.locator('#filter-match').selectOption('ANY');assert.ok(await page.locator('[data-action=table-prev]').isDisabled());await read();assert.match(await page.locator('.table-footer').first().textContent(),/第 1 页/);assert.equal(await tableRows.first().locator('[data-column="0"]').textContent(),'1');
 await page.screenshot({path:shot});await page.setViewportSize({width:820,height:900});await page.screenshot({path:shot.replace('.png','-narrow.png')});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.setViewportSize({width:1440,height:980});
 console.log('PASS H2 browser AND/OR, pending/apply labels, first-page reset and stable filter pagination');
 await page.locator('[data-action=filter-clear]').click();await page.locator('[data-action=filter-add]').click();await condition(0,'TITLE','IS NULL');assert.ok(await page.locator('[data-filter=value]').isDisabled());await read();assert.equal(await tableRows.count(),1);assert.equal(await tableRows.first().locator('[data-column="0"]').textContent(),'1');
 await condition(0,'TITLE','=','');await read();assert.equal(await tableRows.first().locator('[data-column="0"]').textContent(),'2');
 await condition(0,'AMOUNT','=','12345678901234567890.123');await read();assert.equal(await tableRows.count(),200);
 await condition(0,'TITLE','NOT LIKE','f%');await read();assert.equal(await tableRows.count(),1);assert.equal(await tableRows.first().locator('[data-column="0"]').textContent(),'2');
 await condition(0,'ID','=','bad-number');await page.locator('[data-action=table-read]').click();await page.locator('.filter-failure').waitFor();assert.equal(await tableRows.count(),1);assert.match(await page.locator('.filter-failure').textContent(),/上次成功结果/);assert.ok(await page.locator('[data-action=table-next]').isDisabled());
 console.log('PASS NULL versus empty string, precision, NOT LIKE and invalid type retains old result');
 await condition(0,'ID','>=','1');await read();
 await page.route('**/api/executions',async route=>{if(route.request().method()==='POST'){await execute(fixture,'DROP TABLE FILTER_UI');await route.continue();await page.unroute('**/api/executions');}else await route.continue();});
 await page.locator('[data-action=table-read]').click();await page.locator('.filter-failure').waitFor();assert.equal(await tableRows.count(),200);
 await tableRows.first().locator('[data-column="1"]').dblclick();assert.match(await page.locator('#dialog').textContent(),/仅供查看|重新读取/);assert.equal(await page.locator('#cell-input').count(),0);await page.locator('#dialog [data-action=dialog-close]').first().click();
 await execute(fixture,create);await read();
 for(let n=1;n<30;n++)await page.locator('[data-action=filter-add]').click();assert.equal(await page.locator('[data-filter-row]').count(),30);assert.ok(await page.locator('[data-action=filter-add]').isDisabled());await page.locator('[data-action=filter-clear]').click();await read();assert.equal(await tableRows.count(),200);
 console.log('PASS execution failure keeps read-only prior result, recovery, 30-row limit and clear');
 let releaseTransaction, reachedTransaction;
 const gate=new Promise(r=>{releaseTransaction=r;}), reached=new Promise(r=>{reachedTransaction=r;});
 await page.route('**/api/sessions/*/transaction',async route=>{reachedTransaction();await gate;await route.continue();});
 await page.locator('#auto-commit').click();await reached;
 assert.ok(await page.locator('[data-action=table-read]').isDisabled());
 await page.locator('header [data-action=new-tab]').click();await page.locator('#sql-editor').fill('SELECT 987654321; -- keep active tab');
 const refresh=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/api/executions'));
 releaseTransaction();const response=await refresh;
 assert.equal(response.request().postDataJSON().mode,'TABLE_PREVIEW');
 assert.equal(response.request().postDataJSON().table,'FILTER_UI');
 assert.equal(await page.locator('#sql-editor').inputValue(),'SELECT 987654321; -- keep active tab');
 assert.match(await page.locator('#results .result-state').textContent(),/尚未执行/);
 await page.unroute('**/api/sessions/*/transaction');
 await page.locator('.work-tab').filter({hasText:'FILTER_UI'}).click();await completed();assert.equal(await tableRows.count(),200);
 console.log('PASS transaction blocks old grid; delayed refresh stays on its original tab');

 const fixtures=process.env.TOOLBOX_FILTER_FIXTURES?JSON.parse(fs.readFileSync(process.env.TOOLBOX_FILTER_FIXTURES,'utf8')):[];
 for(const f of fixtures){
  await page.locator('[data-action=new-connection]').first().click();await page.locator('#f-driverId').selectOption(drivers.find(d=>d.driverClass===(f.kind==='mysql'?'com.mysql.cj.jdbc.Driver':'org.postgresql.Driver')).id);
  const name='Vendor filter '+f.kind+' '+Date.now();await page.locator('#f-name').fill(name);await page.locator('#basic-host').fill(f.host);await page.locator('#basic-port').fill(String(f.port));await page.locator('#basic-database').fill(f.database);await page.locator('#f-username').fill(f.username);await page.locator('#f-password').fill(f.password);
  const tested=page.waitForResponse(r=>r.url().endsWith('/connections/test'));await page.locator('[data-action=test-connection]').click();const report=(await (await tested).json()).data;assert.ok(report.success,report.message);console.log('PASS '+report.productName+' '+report.productVersion+' / '+report.driverName+' '+report.driverVersion+' basic connection form');
  await page.locator('[data-action=save-connection]').click();await page.locator('#connection-form').waitFor({state:'hidden'});const c=(await api('/connections')).find(c=>c.name===name);profiles.push(c.id);const session=(await api('/sessions','POST',{connectionId:c.id})).id;sessions.add(session);
  const table='filter_fixture_'+Date.now();await execute(session,`CREATE TABLE ${table}(id INT PRIMARY KEY, title VARCHAR(80), amount DECIMAL(30,3)); INSERT INTO ${table} VALUES(1,NULL,12345678901234567890.123),(2,'',2),(3,'foo',3)`);
  try{
   const q={mode:'TABLE_PREVIEW',table,limit:200,filters:[{column:'id',operator:'=',value:'1'},{column:'title',operator:'=',value:''}]};
   assert.equal(rows(await execute(session,'',{...q,filterMatch:'ALL'})).length,0);assert.equal(rows(await execute(session,'',{...q,filterMatch:'ANY'})).length,2);
   assert.equal(rows(await execute(session,'',{...q,filterMatch:'ANY',limit:1,offset:1}))[0][0],2);
   assert.equal(rows(await execute(session,'',{...q,filters:[{column:'title',operator:'IS NULL'}]}))[0][0],1);
   assert.equal(rows(await execute(session,'',{...q,filters:[{column:'amount',operator:'=',value:'12345678901234567890.123'}]}))[0][2],'12345678901234567890.123');
   assert.equal(rows(await execute(session,'',{...q,filters:[{column:'title',operator:'=',value:"' OR 1=1 --"}]})).length,0);
   console.log('PASS '+f.kind+' real AND/OR, paging, NULL/empty, precise decimal and injection-like bound value');
  }finally{await execute(session,'DROP TABLE '+table);}
 }
 assert.deepEqual(errors,[]);
}finally{if(browser)await browser.close();for(const id of sessions)await api('/sessions/'+id,'DELETE');for(const id of profiles)await api('/connections/'+id,'DELETE');}})().catch(e=>{console.error(e);process.exitCode=1;});
