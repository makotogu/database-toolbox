#!/usr/bin/env node
// Uses only disposable H2 profiles on a temporary Toolbox instance. Requires Playwright + Chrome.
const assert = require('node:assert/strict');
const {chromium} = require(process.env.TOOLBOX_PLAYWRIGHT_PATH || 'playwright');
const base = process.env.TOOLBOX_BASE_URL || 'http://127.0.0.1:18090';
const screenshot = process.env.TOOLBOX_UI_SCREENSHOT || '/tmp/toolbox-sql-editor.png';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let token, browser, page; const profiles = [], sessions = new Set(), errors = [], external = [], requests = [], dialogs = [];
let acceptDialogs = true;
async function api(path, method = 'GET', body) {
  const response = await fetch(base + '/api' + path, {method, headers:{'Content-Type':'application/json','X-Toolbox-Token':token || ''}, body:body === undefined ? undefined : JSON.stringify(body)});
  const result = await response.json(); assert.ok(response.ok && result.success, result.message); return result.data;
}
async function saved(p = page) { await p.waitForFunction(() => document.querySelector('#draft-status')?.textContent === '草稿已加密保存'); }
async function editProfile(id) {
  await page.locator('[data-action="edit-connection"][data-id="' + id + '"]').click();
  await page.locator('#connection-form').waitFor();
}
function monitor(p) {
  p.on('pageerror',e => errors.push(e.message));
  p.on('console',m => { if(m.type()==='error' && !/net::ERR_FAILED|status of 409/.test(m.text())) errors.push(m.text()); });
  p.on('dialog',d => { dialogs.push(d.message()); return acceptDialogs ? d.accept() : d.dismiss(); });
  p.on('request',r => {
    if (!r.url().startsWith(base) && /^https?:/.test(r.url())) external.push(r.url());
    if (r.method() !== 'GET') requests.push({url:new URL(r.url()).pathname, method:r.method(), body:r.postDataJSON()});
  });
  p.on('response',async r => {if(r.request().method()==='POST' && new URL(r.url()).pathname==='/api/sessions') try {sessions.add((await r.json()).data.id);} catch {}});
}
(async () => {
  try {
    token = (await api('/bootstrap')).token;
    const driver = (await api('/drivers')).find(d => d.bundled && d.driverClass === 'org.h2.Driver');
    for(const name of ['Editor A','Editor B']) profiles.push(await api('/connections','POST',{
      name:name+' '+Date.now(), driverId:driver.id, jdbcUrl:'jdbc:h2:mem:editor_'+Date.now()+';DB_CLOSE_DELAY=-1', username:'sa', password:''
    }));
    const denied = await fetch(base+'/api/connections/'+profiles[0].id+'/sql-drafts',{
      method:'PUT',headers:{'Content-Type':'application/json'},body:'{}'
    });
    assert.equal(denied.status,403,'draft mutations require the local request token');
    browser = await chromium.launch({headless:true,...(process.env.TOOLBOX_CHROME_PATH ? {executablePath:process.env.TOOLBOX_CHROME_PATH} : {})});
    page = await browser.newPage({viewport:{width:1366,height:900}}); monitor(page);
    await page.goto(base); await page.locator('#sql-editor').waitFor();
    assert.match(await page.title(),/数据库/);
    await page.locator('#tab-connection').selectOption(profiles[0].id);
    const sql = "-- 客户分析 · 只保存示例文本\nSELECT 9007199254740993 AS id, '中文 <img src=x>' AS label, NULL AS note;\n";
    await page.locator('#sql-editor').fill(sql);
    await sleep(1100); assert.equal(requests.filter(r => r.url.endsWith('/sql-drafts')).length,0);
    assert.equal(await page.locator('#sql-highlight').textContent(),sql+'\n ');
    assert.ok(await page.locator('#sql-highlight .sql-keyword').count() > 0);
    assert.equal(await page.locator('#sql-highlight img').count(),0);
    console.log('PASS browser: opt-out makes no draft writes; colored source preserves Chinese, large numbers and HTML-like text');

    await editProfile(profiles[0].id);
    assert.equal(await page.locator('[name=saveSqlDrafts]').isChecked(),false);
    assert.match(await page.locator('#draft-privacy').textContent(),/口令、个人信息或业务数据/);
    await page.screenshot({path:screenshot.replace(/\.png$/, '-privacy.png')});
    await page.locator('[name=saveSqlDrafts]').check();
    acceptDialogs = false; await page.getByRole('button',{name:'保存连接',exact:true}).click();
    assert.equal((await api('/connections')).find(c => c.id===profiles[0].id).saveSqlDrafts,false);
    acceptDialogs = true; await page.getByRole('button',{name:'保存连接',exact:true}).click();
    await page.locator('#connection-form').waitFor({state:'hidden'}); await saved();
    assert.match(dialogs.at(-1),/本机账户及密钥/);
    assert.equal((await api('/connections/'+profiles[0].id+'/sql-drafts')).tabs[0].sql,sql);
    assert.equal(await page.evaluate(() => localStorage.length),0);
    assert.equal(await page.evaluate(() => sessionStorage.length),0);
    console.log('PASS browser: per-connection opt-in requires privacy acknowledgement; confirmed drafts stay out of browser storage');

    const beforeRestore = requests.filter(r => /\/sessions$|\/executions$/.test(r.url)).length;
    await page.reload(); await page.locator('#sql-editor').waitFor();
    assert.equal(await page.locator('#sql-editor').inputValue(),sql);
    assert.equal(requests.filter(r => /\/sessions$|\/executions$/.test(r.url)).length,beforeRestore);
    assert.match(await page.locator('.session-badge').textContent(),/执行时建立会话/);
    await page.screenshot({path:screenshot});
    console.log('PASS browser: reload restores SQL with no database session, transaction or SQL replay');

    const source = "SELECT 'untouched' AS first_value;\nSELECT 42 AS picked;";
    await page.locator('#sql-editor').fill(source);
    await page.locator('#sql-editor').evaluate((el,start)=>{el.focus();el.setSelectionRange(start,el.value.length);},source.indexOf('SELECT 42'));
    await page.getByRole('button',{name:'选区',exact:true}).click();
    await page.locator('#results .result-state').filter({hasText:'执行完成'}).waitFor();
    assert.equal(requests.filter(r => r.url==='/api/executions').at(-1).body.sql,'SELECT 42 AS picked;');
    assert.equal(await page.locator('#sql-editor').inputValue(),source);
    await page.locator('#sql-editor').click(); await page.locator('#sql-editor').press('ControlOrMeta+End');
    await page.keyboard.type('abc');
    // Native browsers may group keystrokes differently. Verify the entire undo history.
    for (let i=0;i<3 && (await page.locator('#sql-editor').inputValue())!==source;i++)
      await page.keyboard.press('ControlOrMeta+z');
    assert.equal(await page.locator('#sql-editor').inputValue(),source);
    await page.locator('#sql-editor').evaluate(el=>el.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true})));
    assert.equal(await page.locator('#sql-editor').evaluate(el=>el.classList.contains('highlighted')),false);
    await page.keyboard.insertText('中文');
    await page.locator('#sql-editor').evaluate(el=>el.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true})));
    assert.ok((await page.locator('#sql-editor').inputValue()).includes('中文'));
    assert.equal(await page.locator('#sql-editor').evaluate(el=>el.classList.contains('highlighted')),true);
    console.log('PASS browser: selection execution is exact; native undo and composition event paths preserve editable text');

    const long = Array.from({length:180},(_,i)=>`SELECT ${i}, '${'中文示例'.repeat(40)}'; -- line ${i}`).join('\n');
    await page.locator('#sql-editor').fill(long);
    await page.locator('#sql-editor').evaluate(el=>{el.scrollTop=600;el.scrollLeft=220;}); await sleep(100);
    assert.deepEqual(await page.evaluate(()=>{
      const a=document.querySelector('#sql-editor'),b=document.querySelector('#sql-highlight');
      return [a.scrollTop===b.scrollTop,a.scrollLeft===b.scrollLeft,getComputedStyle(a).font===getComputedStyle(b).font,a.clientWidth===b.clientWidth];
    }),[true,true,true,true]);
    await page.screenshot({path:screenshot.replace(/\.png$/, '-scroll.png')});
    await page.locator('#sql-editor').fill('x'.repeat(200001));
    assert.equal(await page.locator('#highlight-status').textContent(),'大文本 · 高亮暂停');
    assert.equal(await page.locator('#sql-editor').evaluate(el=>el.classList.contains('highlighted')),false);
    console.log('PASS browser: horizontal/vertical scrolling stays aligned and oversized SQL remains readable');

    await page.locator('#sql-editor').fill('SELECT 7 AS saved_again'); await saved();
    let failOnce=true;
    await page.route('**/sql-drafts',async route=>{
      if(route.request().method()==='PUT' && failOnce){failOnce=false;await route.fetch();return route.abort('failed');}
      return route.continue();
    });
    await page.locator('#sql-editor').fill('SELECT 8 AS after_lost_response');
    await page.waitForFunction(()=>document.querySelector('#draft-status')?.textContent==='草稿未保存');
    await page.locator('#draft-status').click(); await page.getByRole('button',{name:'重试保存',exact:true}).click(); await saved();
    const writes=requests.filter(r=>r.url.endsWith('/sql-drafts') && r.method==='PUT');
    assert.deepEqual(writes.at(-1).body,writes.at(-2).body);
    await page.unrouteAll({behavior:'wait'});
    console.log('PASS browser: a lost save response is visible and retry uses identical text/version/request ID');

    const second = await browser.newPage({viewport:{width:1366,height:900}}); monitor(second);
    await second.goto(base); await second.locator('#sql-editor').waitFor();
    await page.locator('#sql-editor').fill('SELECT 9 AS latest_page'); await saved();
    await second.locator('#sql-editor').fill('SELECT 10 AS stale_page');
    await second.waitForFunction(()=>document.querySelector('#draft-status')?.textContent==='草稿未保存');
    await second.locator('#draft-status').click(); assert.match(await second.locator('#dialog').textContent(),/其他页面/);
    assert.equal((await api('/connections/'+profiles[0].id+'/sql-drafts')).tabs[0].sql,'SELECT 9 AS latest_page');
    await second.close();
    console.log('PASS browser: two pages produce an explicit conflict and keep the newest stored draft');

    await page.getByRole('button',{name:'新建 SQL',exact:true}).click();
    await page.locator('#tab-connection').selectOption(profiles[1].id); await page.locator('#sql-editor').fill('SELECT 11 AS opted_out');
    await page.reload(); await page.locator('#sql-editor').waitFor();
    assert.equal(await page.locator('.work-tab').count(),1);
    assert.equal(await page.locator('#sql-editor').inputValue(),'SELECT 9 AS latest_page');
    await page.setViewportSize({width:768,height:900});
    assert.ok(await page.locator('#sql-editor').isVisible());
    assert.ok(await page.locator('#draft-status').isVisible());
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({path:screenshot.replace(/\.png$/, '-narrow.png')});
    await page.setViewportSize({width:1366,height:900});
    console.log('PASS browser: only opted-in connection text restores; desktop and narrow layouts remain usable');

    await page.locator('#global-connection').selectOption(profiles[1].id);
    await page.locator('#tab-connection').selectOption('');
    await page.locator('#sql-editor').fill('SELECT 13 AS assigned_later');
    await page.locator('#global-connection').selectOption(profiles[0].id); await saved();
    assert.equal((await api('/connections/'+profiles[0].id+'/sql-drafts')).tabs[0].sql,'SELECT 13 AS assigned_later');
    console.log('PASS browser: assigning an unbound editor to an opted-in connection starts saving');

    await page.locator('.close-tab').first().click(); await saved();
    assert.equal((await api('/connections/'+profiles[0].id+'/sql-drafts')).tabs.length,0);
    await page.locator('#sql-editor').fill('SELECT 12 AS clear_on_disable'); await saved();
    await editProfile(profiles[0].id); await page.locator('[name=saveSqlDrafts]').uncheck();
    await page.getByRole('button',{name:'保存连接',exact:true}).click(); await page.locator('#connection-form').waitFor({state:'hidden'});
    assert.equal(await page.locator('#sql-editor').inputValue(),'SELECT 12 AS clear_on_disable');
    assert.equal(await page.locator('#draft-status').textContent(),'草稿保存未开启');
    await page.reload(); await page.locator('#sql-editor').waitFor(); assert.equal(await page.locator('#sql-editor').inputValue(),'');
    console.log('PASS browser: closing a tab deletes its draft; opt-out preserves current text and removes persisted drafts');

    // A connection move with an interrupted cleanup can leave the same stored ID in two connections.
    const sharedId=crypto.randomUUID();
    for (let i=0;i<profiles.length;i++) {
      const c=(await api('/connections')).find(c=>c.id===profiles[i].id);
      await api('/connections','POST',{...c,saveSqlDrafts:true});
      const url='/connections/'+c.id+'/sql-drafts';
      await api(url,'PUT',{revision:(await api(url)).revision,requestId:crypto.randomUUID(),
        tabs:[{id:sharedId,name:'Restore '+i,sql:'SELECT '+(20+i)+' AS restored'}]});
    }
    await page.reload(); await page.locator('#sql-editor').waitFor();
    const ids=await page.locator('.work-tab').evaluateAll(tabs=>tabs.map(t=>t.dataset.id));
    assert.equal(ids.length,2); assert.equal(new Set(ids).size,2);
    for (let i=0;i<profiles.length;i++) {
      await page.locator('.work-tab').filter({hasText:'Restore '+i}).click();
      assert.equal(await page.locator('#sql-editor').inputValue(),'SELECT '+(20+i)+' AS restored');
      assert.equal(await page.locator('#tab-connection').inputValue(),profiles[i].id);
      await page.waitForFunction(()=>document.querySelector('#tab-schema')?.textContent.includes('PUBLIC'));
      await saved();
    }
    console.log('PASS browser: restored tabs have independent UI IDs and load selectable schema metadata on activation');
    assert.deepEqual(errors,[]); assert.deepEqual(external,[]);
    console.log('PASS browser: no unexpected console/page errors, no external requests');
  } finally {
    if (browser) await browser.close();
    for (const id of sessions) await api('/sessions/'+id,'DELETE').catch(()=>{});
    for (const c of profiles) await api('/connections/'+c.id,'DELETE').catch(()=>{});
  }
})().catch(e=>{console.error(e);process.exitCode=1;});
