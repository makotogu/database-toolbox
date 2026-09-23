#!/usr/bin/env node
// Optional developer check: Node 18+, externally installed Playwright and Chromium.
// Run against a workbench using disposable storage; only this script's H2 fixture is changed.
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
if (process.argv.includes('--help')) {
  console.log(`Usage: node scripts/smoke-menus-ui.cjs

Run only against a local workbench with a temporary storage root.
Creates and removes a unique in-memory H2 connection and its sessions.
Requires Node 18+, Playwright and Chromium; installs no dependencies.

Environment:
  TOOLBOX_BASE_URL          Workbench URL (default http://127.0.0.1:18098)
  TOOLBOX_PLAYWRIGHT_PATH   Existing Playwright module path (default playwright)
  TOOLBOX_CHROME_PATH       Existing Chromium/Chrome executable (optional)
  TOOLBOX_UI_SCREENSHOT     Desktop PNG path (default /tmp/toolbox-menus.png)
                           Also writes -narrow.png and, on failure, -failure.png.

Checks menu keyboard/focus behavior, disabled actions, SQL copy session isolation,
inactive-tab actions, manual-transaction close confirmation and pending-close locks,
canceled batch closure, metadata-only structure entry,
desktop/narrow layout and browser errors. Browser plugin not available; uses Playwright.`);
  process.exit(0);
}
const { chromium } = require(process.env.TOOLBOX_PLAYWRIGHT_PATH || 'playwright');
const base = process.env.TOOLBOX_BASE_URL || 'http://127.0.0.1:18098';
const screenshot = process.env.TOOLBOX_UI_SCREENSHOT || '/tmp/toolbox-menus.png';
const shot = (suffix = '') => screenshot.replace(/(?:\.png)?$/i, `${suffix}.png`);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELED', 'TIMED_OUT', 'OUTCOME_UNKNOWN']);
const sessions = new Set(), submitted = [], errors = [], dialogs = [];
let token, fixture, connection, browser, page;
let dialogAnswers = [];

async function api(path, method = 'GET', body) {
  const response = await fetch(base + '/api' + path, {
    method,
    headers: {'Content-Type': 'application/json', 'X-Toolbox-Token': token || ''},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json();
  assert.ok(response.ok && result.success, result.message);
  return result.data;
}
async function fixtureSql(sql) {
  const request = {sessionId: fixture.id, sql, mode: 'SCRIPT', requestId: randomUUID()};
  request.confirmationToken = (await api('/executions/prepare', 'POST', request)).confirmationToken;
  let result = await api('/executions', 'POST', request);
  for (let i = 0; !terminal.has(result.state) && i < 200; i++) {
    await sleep(50);
    result = await api('/executions/' + result.id);
  }
  assert.equal(result.state, 'SUCCEEDED', result.message);
  return result;
}
const menu = () => page.locator('#workbench-popup-menu');
const tabButton = (id) => page.locator(`[data-action="select-tab"][data-id="${id}"]`);
const tabIds = () => page.locator('[data-action="select-tab"][data-id]').evaluateAll((els) => els.map((el) => el.dataset.id));
const activeTabId = () => page.locator('[data-action="select-tab"][aria-selected="true"]').getAttribute('data-id');
async function openMenu(kind) {
  await page.locator(`[data-menu-trigger="${kind}"]`).first().click();
  await menu().waitFor({state: 'visible'});
  assert.equal(await menu().getAttribute('role'), 'menu');
}
async function choose(name) {
  // Shortcut hints are part of the accessible name; match the visible command prefix.
  const label = typeof name === 'string' ? new RegExp('^' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?:\\s|$)') : name;
  await menu().getByRole('menuitem', {name: label}).click();
}
async function tabMenu(id) {
  await tabButton(id).click({button: 'right'});
  await menu().waitFor({state: 'visible'});
}
async function executeCurrent() {
  await openMenu('execution');
  const response = page.waitForResponse((r) => r.url().endsWith('/api/executions') && r.request().method() === 'POST');
  await choose(/^执行当前语句/);
  const r = await response;
  const request = r.request().postDataJSON();
  assert.ok(request.confirmationToken, 'menu execution must use prepare/submit confirmation');
  await page.waitForFunction(() => document.querySelector('.result-state')?.textContent.includes('执行完成'));
  return request;
}
async function closeDialog() {
  await page.locator('#dialog [data-action="dialog-close"]').first().click();
  await page.locator('#dialog').waitFor({state: 'hidden'});
}
async function assertMenuFits() {
  const bounds = await menu().boundingBox();
  const viewport = page.viewportSize();
  assert.ok(bounds && bounds.x >= -1 && bounds.y >= -1, 'menu origin must remain onscreen');
  assert.ok(bounds.x + bounds.width <= viewport.width + 1, 'menu must fit viewport width');
  assert.ok(bounds.y + bounds.height <= viewport.height + 1, 'menu must fit viewport height');
}

(async () => {
  try {
    token = (await api('/bootstrap')).token;
    const driver = (await api('/drivers')).find((d) => d.bundled && d.driverClass === 'org.h2.Driver');
    assert.ok(driver, 'bundled H2 driver is required');
    const suffix = randomUUID().replaceAll('-', '');
    connection = await api('/connections', 'POST', {
      name: 'Menu UI ' + suffix.slice(0, 8), driverId: driver.id,
      jdbcUrl: 'jdbc:h2:mem:menus_' + suffix, username: 'sa', password: '',
    });
    fixture = await api('/sessions', 'POST', {connectionId: connection.id});
    await fixtureSql("CREATE TABLE MENU_UI(ID INT PRIMARY KEY, TITLE VARCHAR(80)); INSERT INTO MENU_UI VALUES(1,'菜单夹具');");
    browser = await chromium.launch({
      headless: true,
      ...(process.env.TOOLBOX_CHROME_PATH ? {executablePath: process.env.TOOLBOX_CHROME_PATH} : {}),
    });
    const context = await browser.newContext({viewport: {width: 1440, height: 980}, permissions: ['clipboard-read', 'clipboard-write']});
    page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
    page.on('response', async (response) => {
      if (response.url().endsWith('/api/sessions') && response.request().method() === 'POST') {
        const result = await response.json().catch(() => null);
        if (result?.data?.id) sessions.add(result.data.id);
      }
    });
    page.on('request', (request) => {
      if (request.url().endsWith('/api/executions') && request.method() === 'POST') submitted.push(request.postDataJSON());
    });
    page.on('dialog', async (dialog) => {
      dialogs.push(dialog.message());
      // Dismiss unplanned confirmation prompts so a regression cannot silently close more tabs.
      const answer = dialogAnswers.shift();
      if (answer === 'accept') await dialog.accept();
      else await dialog.dismiss();
    });
    await page.goto(base);
    await page.locator('#sql-editor').waitFor();
    await page.waitForLoadState('networkidle');
    assert.ok((await page.title()).trim(), 'page title must identify the app');
    assert.match(await page.locator('body').innerText(), /数据库工作台/);
    assert.equal(new URL(page.url()).origin, new URL(base).origin);
    await page.locator('#global-connection').selectOption(connection.id);
    await page.locator('#tab-connection').selectOption(connection.id);
    await page.waitForLoadState('networkidle');
    await page.locator('#sql-editor').fill('');

    const fileTrigger = page.locator('[data-menu-trigger="file"]');
    await fileTrigger.focus();
    await page.keyboard.press('Enter');
    await menu().waitFor({state: 'visible'});
    assert.ok(await menu().evaluate((el) => el.contains(document.activeElement)), 'opening by keyboard must focus a menu item');
    const focusedBefore = await page.evaluate(() => document.activeElement?.textContent);
    await page.keyboard.press('ArrowDown');
    assert.notEqual(await page.evaluate(() => document.activeElement?.textContent), focusedBefore, 'ArrowDown must move menu focus');
    await page.keyboard.press('Escape');
    await menu().waitFor({state: 'hidden'});
    assert.ok(await fileTrigger.evaluate((el) => el === document.activeElement), 'Escape must restore trigger focus');
    await openMenu('execution');
    assert.ok(await menu().getByRole('menuitem', {name: /^执行当前语句/}).isDisabled(), 'empty SQL must disable execution');
    await page.keyboard.press('Escape');
    console.log('PASS keyboard opening, arrow navigation, Escape focus restoration and empty SQL disabled');

    const sourceSql = 'SELECT 11 AS MENU_SOURCE;';
    const sourceId = await activeTabId();
    await page.locator('#sql-editor').fill(sourceSql);
    const firstExecution = await executeCurrent();
    const beforeCopy = await tabIds();
    await openMenu('tabs');
    await choose('复制 SQL 到新标签');
    await page.waitForFunction((count) => document.querySelectorAll('[data-action="select-tab"]').length === count + 1, beforeCopy.length);
    const copiedId = await activeTabId();
    assert.notEqual(copiedId, sourceId);
    assert.equal(await page.locator('#sql-editor').inputValue(), sourceSql);
    assert.match(await page.locator('.session-badge').innerText(), /执行时建立会话/);
    const secondExecution = await executeCurrent();
    assert.notEqual(secondExecution.sessionId, firstExecution.sessionId, 'copied SQL must own a new JDBC session');
    await page.locator('#sql-editor').fill('SELECT 22 AS COPIED_DRAFT;');
    await tabMenu(sourceId);
    await choose('重命名标签');
    await page.locator('#f-tabName').fill('源 SQL <菜单>');
    await page.locator('[data-action="rename-tab-save"]').click();
    await page.locator('#dialog').waitFor({state: 'hidden'});
    assert.match(await tabButton(sourceId).innerText(), /源 SQL <菜单>/);
    assert.doesNotMatch(await tabButton(copiedId).innerText(), /源 SQL <菜单>/);
    await tabButton(sourceId).click();
    assert.equal(await page.locator('#sql-editor').inputValue(), sourceSql, 'renaming another tab must not overwrite its editor');
    await tabButton(copiedId).click();
    assert.equal(await page.locator('#sql-editor').inputValue(), 'SELECT 22 AS COPIED_DRAFT;');
    console.log('PASS copied SQL uses an independent session; inactive-tab context rename targets the correct tab');

    const savedExecution = await executeCurrent();
    assert.equal(savedExecution.sessionId, secondExecution.sessionId);
    await openMenu('file');
    const downloading = page.waitForEvent('download');
    await choose('保存 SQL 文件');
    const download = await downloading;
    const downloaded = [];
    for await (const chunk of await download.createReadStream()) downloaded.push(chunk);
    assert.equal(Buffer.concat(downloaded).toString('utf8'), 'SELECT 22 AS COPIED_DRAFT;');
    const changedTransaction = page.waitForResponse((r) => r.url().endsWith(`/api/sessions/${savedExecution.sessionId}/transaction`) && r.request().method() === 'POST');
    await page.locator('#auto-commit').uncheck();
    assert.equal((await (await changedTransaction).json()).data.autoCommit, false);
    await page.waitForFunction(() => !document.querySelector('#auto-commit')?.disabled && !document.querySelector('#auto-commit')?.checked);
    const beforeDeclinedClose = dialogs.length;
    dialogAnswers = ['dismiss'];
    await tabMenu(copiedId);
    await choose('关闭当前标签');
    await page.waitForLoadState('networkidle');
    assert.equal(dialogs.length - beforeDeclinedClose, 1, 'saved SQL in a manual transaction still requires close confirmation');
    assert.match(dialogs.at(-1), /未提交事务将回滚/);
    assert.doesNotMatch(dialogs.at(-1), /SQL 文本不会自动保存/, 'downloaded SQL must already be marked saved');
    assert.ok(await tabButton(copiedId).isVisible(), 'declined close must retain the tab');
    assert.equal((await api('/sessions/' + savedExecution.sessionId)).autoCommit, false, 'declined close must retain the same manual session');
    assert.equal(await page.locator('#sql-editor').evaluate((el) => el.readOnly), false);

    let releaseDelete, deleteReached, rejectDelete, deleteTimer, finishDelete;
    let deleteHeld = false, deleteRouteError;
    const release = new Promise((resolve) => { releaseDelete = resolve; });
    const deleteFinished = new Promise((resolve) => { finishDelete = resolve; });
    const pendingDelete = new Promise((resolve, reject) => {
      deleteReached = () => { clearTimeout(deleteTimer); resolve(); };
      rejectDelete = reject;
    });
    const closeUrl = `${base}/api/sessions/${savedExecution.sessionId}`;
    const holdDelete = async (route) => {
      if (route.request().method() !== 'DELETE') return route.continue();
      deleteHeld = true;
      deleteReached();
      await release;
      try { await route.continue(); }
      catch (error) { deleteRouteError = error; }
      finally { finishDelete(); }
    };
    await page.route(closeUrl, holdDelete);
    try {
      deleteTimer = setTimeout(() => rejectDelete(new Error('accepted close did not request session DELETE')), 15000);
      dialogAnswers = ['accept'];
      await tabMenu(copiedId);
      await choose('关闭当前标签');
      await pendingDelete;
      assert.ok(await tabButton(copiedId).isVisible(), 'tab must remain until its session close is acknowledged');
      const closingEditor = page.locator('#sql-editor');
      assert.equal(await closingEditor.evaluate((el) => el.readOnly), true, 'pending close must make the editor read-only');
      assert.ok(await page.locator('[data-action="execute-current"]').isDisabled(), 'pending close must disable execution');
      await closingEditor.focus();
      await page.keyboard.press('End');
      await page.keyboard.type(' -- MUST_NOT_BE_ENTERED');
      assert.equal(await closingEditor.inputValue(), 'SELECT 22 AS COPIED_DRAFT;', 'typing during session closure must not alter SQL');
      await page.keyboard.press('Tab');
      assert.equal(await closingEditor.inputValue(), 'SELECT 22 AS COPIED_DRAFT;', 'custom Tab indentation must respect the read-only editor');
      await closingEditor.focus();
      await page.keyboard.press('Shift+Tab');
      assert.equal(await closingEditor.inputValue(), 'SELECT 22 AS COPIED_DRAFT;', 'custom Shift+Tab indentation must respect the read-only editor');
    } finally {
      clearTimeout(deleteTimer);
      releaseDelete();
      if (deleteHeld) await deleteFinished;
      await page.unroute(closeUrl, holdDelete);
    }
    if (deleteRouteError) throw deleteRouteError;
    await tabButton(copiedId).waitFor({state: 'detached'});
    console.log('PASS saved SQL still confirms manual rollback; declined close retains session; pending DELETE locks editing until closure');
    for (let i = 0; i < 3; i++) {
      await openMenu('file');
      await choose('新建 SQL');
      await page.locator('#sql-editor').fill(`SELECT ${30 + i} AS BATCH_DRAFT;`);
    }
    const batchBefore = await tabIds();
    const dialogCount = dialogs.length;
    dialogAnswers = ['accept', 'dismiss'];
    await tabMenu(sourceId);
    await choose('关闭右侧标签');
    await page.waitForFunction((count) => document.querySelectorAll('[data-action="select-tab"]').length === count - 1, batchBefore.length);
    await page.waitForLoadState('networkidle');
    assert.equal(dialogs.length - dialogCount, 2, 'batch close must stop after the canceled confirmation');
    const batchAfter = await tabIds();
    assert.equal(batchBefore.length - batchAfter.length, 1);
    assert.ok(batchAfter.includes(sourceId), 'context target must remain open');
    assert.equal(dialogAnswers.length, 0);
    console.log('PASS batch close accepts one tab, then stops immediately after cancellation');

    const connectionButton = page.locator(`[data-action="toggle-connection"][data-id="${connection.id}"]`);
    if (!(await connectionButton.evaluate((el) => el.closest('.tree-row').nextElementSibling?.classList.contains('tree-children')))) await connectionButton.click();
    const schema = page.locator(`[data-action="toggle-schema"][data-id="${connection.id}"][data-schema="PUBLIC"]`);
    await schema.waitFor();
    const object = page.locator('[data-action="open-object"]').filter({hasText: /^MENU_UI$/});
    if (!(await object.count())) await schema.click();
    await object.waitFor();
    const beforeStructure = submitted.length;
    await object.click({button: 'right'});
    await menu().waitFor({state: 'visible'});
    await choose('查看结构');
    await page.locator('.structure-section').waitFor();
    await page.waitForLoadState('networkidle');
    assert.match(await page.locator('.structure-section').innerText(), /TITLE/);
    assert.equal(submitted.length, beforeStructure, 'structure entry must read metadata without submitting TABLE_PREVIEW or other SQL');
    await object.click({button: 'right'});
    await choose('复制对象名称');
    assert.match(await page.evaluate(() => navigator.clipboard.readText()), /MENU_UI/);
    assert.equal(submitted.length, beforeStructure);
    await object.locator('..').locator('[data-menu-trigger="object"]').click();
    await choose('查看数据');
    await page.waitForFunction(() => document.querySelector('#results [data-cell][data-row="0"][data-column="1"]')?.textContent === '菜单夹具');
    assert.equal(submitted.at(-1).mode, 'TABLE_PREVIEW');
    console.log('PASS object context/More menus: structure reads metadata only, name copy, explicit data preview');

    await openMenu('help');
    await choose('键盘快捷键');
    await page.locator('#dialog').waitFor({state: 'visible'});
    assert.match(await page.locator('#dialog').innerText(), /快捷键/);
    await closeDialog();
    await openMenu('help');
    await choose('关于数据库工作台');
    await page.locator('#dialog').waitFor({state: 'visible'});
    assert.match(await page.locator('#dialog').innerText(), /数据库工作台/);
    await closeDialog();
    await openMenu('view');
    const checkbox = menu().getByRole('menuitemcheckbox', {name: '显示对象导航'});
    assert.ok(await checkbox.count(), 'View menu must expose a checked visibility control');
    const checked = await checkbox.getAttribute('aria-checked');
    await checkbox.click();
    await openMenu('view');
    const toggled = menu().getByRole('menuitemcheckbox', {name: '显示对象导航'});
    assert.notEqual(await toggled.getAttribute('aria-checked'), checked, 'visibility setting must change');
    await toggled.click();
    await openMenu('file');
    await assertMenuFits();
    await page.screenshot({path: shot()});
    await page.keyboard.press('Escape');
    await page.setViewportSize({width: 360, height: 800});
    for (const kind of ['file', 'execution', 'view', 'help']) {
      assert.ok(await page.locator(`[data-menu-trigger="${kind}"]`).isVisible(), `${kind} menu must remain visible on a narrow screen`);
    }
    await openMenu('file');
    await assertMenuFits();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'narrow screen must not overflow horizontally');
    await page.screenshot({path: shot('-narrow')});
    await page.keyboard.press('Escape');
    assert.deepEqual(errors, []);
    console.log('PASS Help/View actions, 1440px and 360px menu bounds, screenshots and browser console health');
  } catch (error) {
    if (page) await page.screenshot({path: shot('-failure'), fullPage: true}).catch(() => {});
    if (errors.length) console.error('Browser errors:', JSON.stringify(errors));
    throw error;
  } finally {
    if (browser) await browser.close();
    for (const id of sessions) await api('/sessions/' + id, 'DELETE').catch(() => {});
    if (fixture) await api('/sessions/' + fixture.id, 'DELETE');
    if (connection) await api('/connections/' + connection.id, 'DELETE');
  }
})().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
