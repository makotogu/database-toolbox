#!/usr/bin/env node
// Deterministic regressions against the real app functions; no browser or database required.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/workbench/app.js'), 'utf8');

function between(start, end) {
  const from = source.indexOf(start), to = source.indexOf(end, from);
  assert.ok(from >= 0 && to > from, `Application function boundary missing: ${start}`);
  return source.slice(from, to);
}
function sqlTab(id, extra = {}) {
  return {id, name: id, type: 'sql', sql: 'SELECT 1;', connectionId: 'fixture', ...extra};
}
function fixture(tabs, answers = []) {
  const requests = [], prompts = [], messages = [], actions = [];
  const editor = {readOnly: false};
  const ctx = {
    state: {tabs, activeTab: tabs[0]?.id}, closingTabs: false,
    activeStates: new Set(['QUEUED', 'RUNNING', 'CANCEL_REQUESTED']),
    connection: () => ({saveSqlDrafts: false}),
    confirm: message => {prompts.push(message); return answers.shift() === true;},
    api: (url, options) => new Promise((resolve, reject) => requests.push({url, options, resolve, reject})),
    rememberEditor: () => {}, captureTableFilters: () => {}, abortRead: () => {}, loadContext: () => {},
    render: () => {}, renderTab: () => {}, renderStatus: () => {}, renderExecutionChange: () => {},
    drafts: {schedule: () => {}}, menus: {close: () => {}},
    toast: message => messages.push(message), report: error => {throw error;},
    $: selector => selector === '#sql-editor' ? editor : null, $$: () => [],
    newTab: () => {const t = sqlTab('replacement'); ctx.state.tabs.push(t); ctx.state.activeTab = t.id;},
    selectCell: () => {}, cellReadOnly: () => '', formatValue: String,
    cellDetail: () => actions.push('view'), editCellDialog: () => actions.push('edit'),
    copyText: value => actions.push(['copy', value]),
  };
  ctx.tab = () => ctx.state.tabs.find(t => t.id === ctx.state.activeTab);
  vm.createContext(ctx);
  const busy = source.match(/^const busy =.*$/m);
  assert.ok(busy, 'Application busy predicate missing');
  vm.runInContext(busy[0] + '\nglobalThis.isBusy = busy;\n' +
    between('async function closeTab(', 'function saveSql(') +
    between('async function closeTabs(', 'function mainMenu(') +
    between('function settleTablePreview(', 'async function readTable(') +
    between('function allResults(', 'function renderResults(') +
    between('function menuCommand(', 'const menuSeparator') +
    between('function cellMenu(', 'function openMenu('), ctx);
  return {ctx, requests, prompts, messages, actions, editor};
}
function tableExecution(id, value, state = 'SUCCEEDED') {
  return {id, state, mode: 'TABLE_PREVIEW', statements: [{results: [{
    kind: 'RESULT_SET', columns: [{label: 'TITLE', editable: true}], rows: [[value]],
  }]}]};
}
function cell() {return {dataset: {result: '0', row: '0', column: '0'}, isConnected: true};}

// Keep unresolved deferred requests from making Node exit with an accidental success.
const deadline = setTimeout(() => {
  console.error('FAIL menu state regression did not settle its deferred requests');
  process.exitCode = 1;
}, 5000);
(async () => {
  {
    const target = sqlTab('manual', {savedSql: 'SELECT 1;', session: {id: 'manual-session', autoCommit: false}});
    const {ctx, requests, prompts} = fixture([sqlTab('keep'), target], [false]);
    const closing = ctx.closeTab(target.id);
    assert.equal(prompts.length, 1, 'Saved non-empty SQL must still ask before rolling back its transaction');
    assert.match(prompts[0], /回滚/);
    assert.equal(requests.length, 0, 'Declining rollback must not delete the session');
    assert.equal(await closing, false);
    assert.ok(ctx.state.tabs.includes(target));
    console.log('PASS saved SQL still confirms manual-transaction rollback; cancellation preserves the tab');
  }
  {
    const target = sqlTab('manual', {savedSql: 'SELECT 1;', session: {id: 'manual-session', autoCommit: false}});
    const {ctx, requests, prompts} = fixture([sqlTab('keep'), target], [true]);
    const closing = ctx.closeTab(target.id);
    assert.equal(prompts.length, 1, 'One confirmation should cover the manual transaction');
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/sessions/manual-session');
    assert.equal(requests[0].options.method, 'DELETE');
    requests[0].resolve();
    assert.equal(await closing, true);
    assert.equal(ctx.state.tabs.includes(target), false);
    console.log('PASS accepting rollback closes exactly the requested session and tab');
  }
  {
    const keep = sqlTab('keep'), first = sqlTab('first', {session: {id: 'first-session', autoCommit: true}});
    const canceled = sqlTab('canceled', {session: {id: 'canceled-session', autoCommit: true}});
    const untouched = sqlTab('untouched', {session: {id: 'untouched-session', autoCommit: true}});
    const {ctx, requests, prompts} = fixture([keep, first, canceled, untouched], [true, false, true]);
    const closing = ctx.closeTabs(keep, 'right');
    assert.equal(requests.length, 1);
    requests[0].resolve();
    await closing;
    assert.equal(prompts.length, 2, 'Batch closure must stop at the first canceled confirmation');
    assert.equal(requests.length, 1, 'Later sessions must remain untouched after cancellation');
    assert.deepEqual(Array.from(ctx.state.tabs, t => t.id), ['keep', 'canceled', 'untouched']);
    assert.equal(ctx.closingTabs, false);
    console.log('PASS batch close stops after cancellation and leaves later sessions intact');
  }
  {
    const target = sqlTab('pending-close', {session: {id: 'slow-session', autoCommit: true}});
    const {ctx, requests, editor} = fixture([sqlTab('keep'), target], [true, true]);
    ctx.state.activeTab = target.id;
    const closing = ctx.closeTab(target.id);
    assert.equal(requests.length, 1);
    assert.equal(ctx.isBusy(target), true, 'Closing must block new execution while DELETE is pending');
    assert.equal(editor.readOnly, true, 'The closing active tab must stop accepting new SQL text');
    assert.equal(await ctx.closeTab(target.id), false, 'A second close must not issue a duplicate DELETE');
    assert.equal(requests.length, 1);
    requests[0].reject(new Error('fixture session deletion failed'));
    await assert.rejects(closing, /fixture session deletion failed/);
    assert.ok(ctx.state.tabs.includes(target), 'Failed close must preserve the tab and its SQL');
    assert.equal(target.sql, 'SELECT 1;');
    assert.equal(ctx.isBusy(target), false, 'Failed close must clear the temporary busy state');
    assert.equal(editor.readOnly, false, 'A failed close must restore editor input');
    const retry = ctx.closeTab(target.id);
    assert.equal(requests.length, 2, 'The retained tab can be closed again after a failure');
    requests[1].resolve();
    assert.equal(await retry, true);
    console.log('PASS closing blocks duplicate work and failure restores a usable, retryable tab');
  }
  {
    const t = {id: 'table', type: 'table', execution: tableExecution('same-query', 'new value', 'RUNNING'),
      pendingPreview: {executionId: 'same-query', filters: {}, offset: 0},
      previewResult: tableExecution('old-query', 'old visible value')};
    const {ctx, actions, messages} = fixture([t]);
    const selected = cell(), menu = ctx.cellMenu(selected);
    assert.ok(menu);
    // The query ID is unchanged, but the displayed old snapshot is replaced on completion.
    t.execution = tableExecution('same-query', 'new different row');
    ctx.allResults(t);
    for (const command of menu.items) await command.run();
    assert.equal(actions.length, 0, 'A replaced result must not be viewed, edited, or copied through an old menu');
    assert.equal(messages.length, 3);
    assert.ok(messages.every(message => /结果已变化|重新选择/.test(message)));
    console.log('PASS same execution ID cannot validate a menu from replaced preview rows');
  }
  {
    const t = {id: 'table', type: 'table', execution: tableExecution('query', 'visible value')};
    const {ctx, actions, messages} = fixture([t]);
    const selected = cell(), menu = ctx.cellMenu(selected);
    selected.isConnected = false; // A render replaces the table even if its rows have not changed.
    for (const command of menu.items) await command.run();
    assert.equal(actions.length, 0, 'Detached cell menus must not act on a newly rendered grid');
    assert.equal(messages.length, 3);
    console.log('PASS detached menu anchor cannot operate on a replaced grid');
  }
  {
    const t = {id: 'table', type: 'table', execution: tableExecution('query', 'visible value')};
    const {ctx, actions, messages} = fixture([t]);
    const menu = ctx.cellMenu(cell());
    // allResults returns new wrapper objects; the underlying unchanged rows must remain usable.
    ctx.allResults(t);
    for (const command of menu.items) await command.run();
    assert.deepEqual(actions, ['view', 'edit', ['copy', 'visible value']]);
    assert.equal(messages.length, 0);
    console.log('PASS unchanged rows remain usable despite fresh result wrapper objects');
  }
})().catch(error => {console.error(error.stack || error.message); process.exitCode = 1;})
  .finally(() => clearTimeout(deadline));
