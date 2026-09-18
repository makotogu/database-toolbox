#!/usr/bin/env node
// Run only against a disposable workbench storage root. All created profiles are removed.
const assert = require('node:assert/strict');
const {chromium} = require(process.env.TOOLBOX_PLAYWRIGHT_PATH || 'playwright');
const base = process.env.TOOLBOX_BASE_URL || 'http://127.0.0.1:18096';
const shot = process.env.TOOLBOX_UI_SCREENSHOT || '/tmp/toolbox-onboarding.png';
let token, browser; const sessions = new Set(), created = new Set(), errors = [], submitted = [];
async function api(path, method='GET', body) {
  const r = await fetch(base+'/api'+path,{method,headers:{'Content-Type':'application/json','X-Toolbox-Token':token||''},body:body === undefined?undefined:JSON.stringify(body)});
  const j=await r.json();assert.ok(r.ok&&j.success,j.message);return j.data;
}
(async()=>{try {
  token=(await api('/bootstrap')).token;
  const drivers=await api('/drivers');
  browser=await chromium.launch({headless:true,executablePath:process.env.TOOLBOX_CHROME_PATH});
  const page=await browser.newPage({viewport:{width:1366,height:960}});
  page.on('pageerror',e=>errors.push(e.message));
  page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
  page.on('response',async r=>{if(r.request().method()==='POST'&&r.url().endsWith('/api/sessions'))try{sessions.add((await r.json()).data.id);}catch{}});
  page.on('dialog',d=>d.accept());
  page.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/api/executions'))submitted.push(r.postDataJSON());});
  await page.goto(base);await page.locator('#sql-editor').waitFor();
  await page.locator('#sql-editor').fill('SELECT 987654321; -- untouched draft');
  await page.getByRole('button',{name:'试用 H2',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.result-state')?.textContent.includes('执行完成'));
  const demo=(await api('/connections')).find(c=>c.name==='H2 演示');assert.ok(demo);created.add(demo.id);
  assert.equal(submitted.length,1);assert.equal(submitted[0].sql,'SELECT 1;');
  assert.ok(submitted[0].confirmationToken);
  await page.getByRole('button',{name:'试用 H2',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.result-state')?.textContent.includes('执行完成'));
  assert.equal((await api('/connections')).filter(c=>c.name==='H2 演示').length,1);
  await page.locator('.work-tab').first().click();assert.match(await page.locator('#sql-editor').inputValue(),/987654321/);
  console.log('PASS demo: SELECT 1 through prepare/submit, repeated reuse, existing editor preserved');
  for(const cls of ['com.mysql.cj.jdbc.Driver','org.postgresql.Driver']) {
    const d=drivers.find(d=>d.driverClass===cls);assert.ok(d);
    await page.locator('[data-action=new-connection]').first().click();
    await page.locator('#f-driverId').selectOption(d.id);
    assert.equal(await page.locator('#connection-mode').inputValue(),'basic');
    await page.locator('#basic-host').fill('::1');await page.locator('#basic-port').fill('15432');
    await page.locator('#basic-database').fill('中文 库');
    assert.match(await page.locator('#f-jdbcUrl').inputValue(),/\[::1\]:15432\/%E4/);
    await page.locator('#basic-port').fill('0');
    await page.locator('#f-name').fill('invalid');
    await page.getByRole('button',{name:'保存连接',exact:true}).click();
    assert.match(await page.locator('#connection-feedback').textContent(),/端口/);
    await page.locator('#basic-port').fill('15432');
    await page.locator('#connection-mode').selectOption('url');
    await page.locator('#f-jdbcUrl').fill(d.urlTemplate+'?sslmode=require');
    assert.equal(await page.locator('#connection-mode option[value=basic]').isDisabled(),true);
    if(cls.includes('postgresql'))await page.screenshot({path:shot.replace('.png','-advanced.png')});
    await page.locator('#dialog').getByRole('button',{name:'取消',exact:true}).click();
  }
  console.log('PASS fields: MySQL/PostgreSQL, IPv6, encoded names, validation, complex URL retained');
  const h2=drivers.find(d=>d.driverClass==='org.h2.Driver');
  const custom=await api('/connections','POST',{name:'Onboarding complex',driverId:h2.id,jdbcUrl:'jdbc:h2:mem:onboarding;DB_CLOSE_DELAY=-1;MODE=PostgreSQL',username:'sa',properties:{customOption:'private-fixture'}});created.add(custom.id);
  await page.reload();await page.locator('[data-action=edit-connection][data-id="'+custom.id+'"]').click();
  assert.equal(await page.locator('#connection-mode').inputValue(),'url');
  assert.match(await page.locator('#f-jdbcUrl').inputValue(),/<saved>/);
  await page.locator('#f-name').fill('Onboarding renamed');
  await page.getByRole('button',{name:'保存连接',exact:true}).click();
  await page.locator('#connection-form').waitFor({state:'hidden'});
  const saved=(await api('/connections')).find(c=>c.id===custom.id);assert.equal(saved.jdbcUrl,custom.jdbcUrl);assert.deepEqual(saved.properties,custom.properties);
  await page.locator('[data-action=new-connection]').first().click();await page.locator('#f-driverId').selectOption(drivers.find(d=>d.driverClass==='org.postgresql.Driver').id);
  await page.screenshot({path:shot});
  await page.setViewportSize({width:820,height:900});await page.screenshot({path:shot.replace('.png','-narrow.png')});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  assert.deepEqual(errors,[]);console.log('PASS saved hidden values preserved, desktop/narrow rendering and browser errors');
} finally {
  if(browser)await browser.close();
  for(const id of sessions)await api('/sessions/'+id,'DELETE');
  for(const id of created)await api('/connections/'+id,'DELETE');
}})().catch(e=>{console.error(e);process.exitCode=1;});
