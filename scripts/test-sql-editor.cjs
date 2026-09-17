#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const moduleAt = name => import('data:text/javascript;base64,' + fs.readFileSync(path.join(__dirname, '../src/main/resources/static/workbench/', name)).toString('base64'));
const tick = () => new Promise(r => setImmediate(r));
(async () => {
  const {highlightSql, HIGHLIGHT_LIMIT} = await moduleAt('sql-highlight.js');
  const decode = html => html.replace(/<[^>]*>/g, '').replace(/&(amp|lt|gt|quot|#39);/g, (_, e) => ({amp:'&',lt:'<',gt:'>',quot:'"','#39':"'"}[e]));
  for (const sql of [
    "SELECT 9007199254740993, 1.25e-3, NULL FROM \"quoted\";\n",
    "DO $body$ BEGIN SELECT 'a;b'; END; $body$; -- comment\nSELECT 2",
    "/* outer /* inner */ end */ SELECT q'[it\'s <html>]';",
    "SELECT '<img src=x onerror=alert(1)>', `中文列`, 'a''b', E'a\\\'b';",
    "-- unclosed\nSELECT 'unfinished\n", "\n\t\n", "$a$未关闭", "SELECT q'{未关闭",
  ]) assert.equal(decode(highlightSql(sql)), sql, 'highlighter must preserve every source character');
  assert.match(highlightSql('SELECT 1'), /sql-keyword/);
  assert.match(highlightSql('-- SELECT'), /^<span class="sql-comment">-- SELECT<\/span>$/);
  assert.match(highlightSql("SELECT '<script>'"), /&lt;script&gt;/);
  assert.equal(highlightSql('a'.repeat(HIGHLIGHT_LIMIT + 1)), null);
  console.log('PASS lossless SQL display, comments/quotes/blocks, HTML escaping and large-text fallback');
  const {createDraftManager} = await moduleAt('sql-drafts.js');
  function fixture() {
    const tabs = [], calls = [];
    const manager = createDraftManager({uuid: randomUUID, delay: 100000, changed: () => {},
      snapshot: id => tabs.filter(t => t.connectionId === id).map(({id,name,sql}) => ({id,name,sql})),
      request: (id, body) => new Promise((resolve,reject) => calls.push({id,body,resolve,reject})),
    });
    return {tabs,calls,manager,tab: {connectionId:'A', id:randomUUID(), name:'SQL 1', sql:'SELECT 1'}};
  }
  {
    const {tabs,calls,manager,tab} = fixture(); tabs.push(tab); manager.schedule(); await tick();
    assert.equal(calls.length, 0, 'disabled connections must never write');
    const loading = manager.load('A'); calls[0].resolve({revision:'r0',tabs:[]}); await loading;
    const save = manager.save('A'); assert.equal(calls[1].body.tabs[0].sql,'SELECT 1');
    tab.sql = 'SELECT 2'; manager.schedule(); assert.equal(calls.length,2, 'in-flight save is serialized');
    calls[1].resolve({revision:'r1',tabs:calls[1].body.tabs}); await save;
    assert.equal(manager.saved(tab),false,'new text must not be reported saved');
    const next = manager.save('A'); assert.equal(calls[2].body.revision,'r1');
    calls[2].resolve({revision:'r2',tabs:calls[2].body.tabs}); await next;
    assert.equal(manager.saved(tab),true);
    assert.equal(manager.saved({...tab,id:randomUUID(),draftId:tab.id}),true,'restored UI identity is separate from stored identity');
    manager.forget('A');
    console.log('PASS opt-in only, serialized saves, and accurate saved status during continued typing');
  }
  {
    const {tabs,calls,manager,tab} = fixture(); tabs.push(tab);
    const loading = manager.load('A'); calls[0].resolve({revision:'r0',tabs:[]}); await loading;
    const save = manager.save('A'); calls[1].reject(new Error('lost response')); await save;
    assert.equal(manager.dirty(),true); assert.equal(manager.status('A').retry,true);
    const retry = manager.save('A'); assert.deepEqual(calls[2].body,calls[1].body,'retry must preserve request identity and text');
    calls[2].resolve({revision:'r1',tabs:calls[2].body.tabs}); await retry;
    assert.equal(manager.saved(tab),true); manager.forget('A');
    console.log('PASS lost-response retry preserves exact payload and request ID');
  }
  {
    const {tabs,calls,manager,tab} = fixture(); tabs.push(tab);
    const loading = manager.load('A'); calls[0].resolve({revision:'r0',tabs:[]}); await loading;
    const save = manager.save('A'); calls[1].reject(Object.assign(new Error('conflict'),{status:409})); await save;
    assert.equal(manager.status('A').retry,false); assert.equal(manager.saved(tab),false);
    await manager.save('A'); assert.equal(calls.length,2); manager.forget('A');
    console.log('PASS conflicts block automatic overwrite and never claim current text is saved');
  }
  {
    const {tabs,calls,manager,tab} = fixture(); tabs.push(tab);
    const loading = manager.load('A'); calls[0].resolve({revision:'r0',tabs:[]}); await loading;
    const save = manager.save('A'); manager.forget('A');
    calls[1].resolve({revision:'r1',tabs:calls[1].body.tabs}); await save;
    assert.equal(manager.saved(tab),false); assert.equal(manager.status('A').label,'草稿保存未开启');
    console.log('PASS stale save response cannot restore a disabled connection');
  }
  {
    const {tabs,calls,manager,tab} = fixture(); tabs.push(tab);
    const loading = manager.load('A'); calls[0].reject(new Error('unavailable')); await loading;
    manager.schedule(); await manager.save('A'); assert.equal(calls.length,1); manager.forget('A');
    console.log('PASS failed restore never overwrites unknown stored drafts');
  }
})().catch(e => {console.error(e);process.exitCode=1;});
