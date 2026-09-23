#!/usr/bin/env node
// Optional developer check: Node 18+, externally installed Playwright and Chromium.
// Run against a workbench using disposable storage; only this script's H2 fixture is changed.
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');

if (process.argv.includes('--help')) {
  console.log(`Usage: node scripts/smoke-layout-ui.cjs

Run only against a local workbench with a temporary storage root.
Creates and removes a unique in-memory H2 connection and its sessions.
Requires Node 18+, Playwright and Chromium; installs no dependencies.

Environment:
  TOOLBOX_BASE_URL          Workbench URL (default http://127.0.0.1:18098)
  TOOLBOX_PLAYWRIGHT_PATH   Existing Playwright module path (default playwright)
  TOOLBOX_CHROME_PATH       Existing Chromium/Chrome executable (optional)
  TOOLBOX_UI_SCREENSHOT     Desktop PNG path (default /tmp/toolbox-layout-ui.png)
                           Also writes -narrow.png and, on failure, -failure.png.

Checks the unified header at desktop/narrow widths; pointer and keyboard resizing,
double-click reset and persistence; real tab dragging, session/editor preservation,
context-menu movement, close-right order, page identity and browser errors.`);
  process.exit(0);
}

const {chromium} = require(process.env.TOOLBOX_PLAYWRIGHT_PATH || 'playwright');
const base = process.env.TOOLBOX_BASE_URL || 'http://127.0.0.1:18098';
const screenshot = process.env.TOOLBOX_UI_SCREENSHOT || '/tmp/toolbox-layout-ui.png';
const shot = (suffix = '') => screenshot.replace(/(?:\.png)?$/i, `${suffix}.png`);
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELED', 'TIMED_OUT', 'OUTCOME_UNKNOWN']);
const sessions = new Set(), submissions = [], errors = [];
let token, fixture, connection, browser, page;

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
    await new Promise((resolve) => setTimeout(resolve, 50));
    result = await api('/executions/' + result.id);
  }
  assert.equal(result.state, 'SUCCEEDED', result.message);
}
const tabs = () => page.locator('[data-action="select-tab"][data-id]');
const tabIds = () => tabs().evaluateAll((els) => els.map((el) => el.dataset.id));
const activeTabId = () => page.locator('[data-action="select-tab"][aria-selected="true"]').getAttribute('data-id');
const tabButton = (id) => page.locator(`[data-action="select-tab"][data-id="${id}"]`);
const sidebar = () => page.locator('.workspace > .sidebar');
const editor = () => page.locator('.editor-results > .editor-pane');
const divider = (kind) => page.locator(`[data-layout-resize="${kind}"]`);
const width = async (locator) => (await locator.boundingBox()).width;
const height = async (locator) => (await locator.boundingBox()).height;

async function menuCommand(kind, name) {
  await page.locator(`[data-menu-trigger="${kind}"]`).first().click();
  const popup = page.locator('#workbench-popup-menu');
  await popup.waitFor({state: 'visible'});
  await popup.getByRole('menuitem', {name}).click();
}
async function tabCommand(id, name) {
  await tabButton(id).click({button: 'right'});
  const popup = page.locator('#workbench-popup-menu');
  await popup.waitFor({state: 'visible'});
  await popup.getByRole('menuitem', {name}).click();
}
async function dragDivider(kind, dx, dy) {
  const box = await divider(kind).boundingBox();
  assert.ok(box, `${kind} divider must be rendered`);
  const x = box.x + box.width / 2, y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + dx, y + dy, {steps: 8});
  await page.mouse.up();
}
async function assertHeader(viewportWidth) {
  await page.setViewportSize({width: viewportWidth, height: 900});
  const header = page.locator('.app-header');
  const bar = page.locator('.app-menubar');
  assert.equal(await bar.evaluate((el) => el.parentElement?.classList.contains('app-header')), true,
    'menubar must be inside the unified header');
  const bounds = await header.boundingBox();
  assert.ok(bounds && bounds.height < 65, `single header must stay below 65px at ${viewportWidth}px`);
  assert.equal(await page.locator('.app-menubar').count(), 1);
  for (const kind of ['file', 'execution', 'view', 'help']) {
    assert.ok(await page.locator(`.app-header [data-menu-trigger="${kind}"]`).isVisible(), `${kind} menu must be visible`);
  }
  assert.ok(await page.locator('.app-header .brand').isVisible());
  if (viewportWidth > 850) assert.ok(await page.locator('#global-connection').isVisible());
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false,
    `${viewportWidth}px page must not overflow horizontally`);
}
function assertLayoutStorage(raw) {
  assert.ok(raw, 'layout preference must be stored');
  const value = JSON.parse(raw);
  assert.deepEqual(Object.keys(value).sort(), ['editorRatio', 'sidebarWidth']);
  assert.ok(Number.isFinite(value.editorRatio) && Number.isFinite(value.sidebarWidth));
  assert.ok(!/SELECT|session|tab|connection|[0-9a-f]{8}-[0-9a-f]{4}-/i.test(raw),
    'layout storage must contain only numeric dimensions');
  return value;
}

(async () => {
  try {
    token = (await api('/bootstrap')).token;
    const driver = (await api('/drivers')).find((d) => d.bundled && d.driverClass === 'org.h2.Driver');
    assert.ok(driver, 'bundled H2 driver is required');
    const suffix = randomUUID().replaceAll('-', '');
    connection = await api('/connections', 'POST', {
      name: 'Layout UI ' + suffix.slice(0, 8), driverId: driver.id,
      jdbcUrl: 'jdbc:h2:mem:layout_' + suffix, username: 'sa', password: '',
    });
    fixture = await api('/sessions', 'POST', {connectionId: connection.id});
    await fixtureSql('CREATE TABLE LAYOUT_UI(ID INT PRIMARY KEY, TITLE VARCHAR(40)); INSERT INTO LAYOUT_UI VALUES(1,\'fixture\');');

    browser = await chromium.launch({headless: true,
      ...(process.env.TOOLBOX_CHROME_PATH ? {executablePath: process.env.TOOLBOX_CHROME_PATH} : {})});
    const context = await browser.newContext({viewport: {width: 1440, height: 900}});
    page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
    page.on('response', async (response) => {
      if (response.url().endsWith('/api/sessions') && response.request().method() === 'POST') {
        const result = await response.json().catch(() => null);
        if (result?.data?.id) sessions.add(result.data.id);
      }
    });
    page.on('request', (request) => {
      if (request.url().endsWith('/api/executions') && request.method() === 'POST') submissions.push(request.postDataJSON());
    });
    page.on('dialog', (dialog) => dialog.accept());
    await page.goto(base);
    await page.locator('#sql-editor').waitFor();
    assert.ok((await page.title()).trim(), 'page title must identify the app');
    assert.match(await page.locator('body').innerText(), /数据库工作台/);
    assert.equal(new URL(page.url()).origin, new URL(base).origin);
    await assertHeader(1440);
    await page.locator('#global-connection').selectOption(connection.id);
    await page.locator('#tab-connection').selectOption(connection.id);

    const sourceId = await activeTabId();
    const sql = 'SELECT TITLE FROM LAYOUT_UI WHERE ID = 1;';
    await page.locator('#sql-editor').fill(sql);
    const executed = page.waitForResponse((r) => r.url().endsWith('/api/executions') && r.request().method() === 'POST');
    await page.locator('[data-action="execute-current"]').click();
    const executionRequest = (await executed).request().postDataJSON();
    assert.ok(executionRequest.confirmationToken, 'SQL execution must use prepare/submit confirmation');
    await page.waitForFunction(() => document.querySelector('.result-state')?.textContent.includes('执行完成'));
    assert.equal(submissions.length, 1);
    const sessionId = executionRequest.sessionId;

    const originalSidebar = await width(sidebar());
    await dragDivider('sidebar', 80, 0);
    assert.ok((await width(sidebar())) > originalSidebar + 55, 'sidebar pointer drag must change width');
    const resizedSidebar = await width(sidebar());
    await divider('sidebar').focus();
    await page.keyboard.press('ArrowLeft');
    assert.ok((await width(sidebar())) < resizedSidebar - 5, 'sidebar arrow key must adjust width');
    await divider('sidebar').dblclick();
    assert.ok(Math.abs((await width(sidebar())) - originalSidebar) < 3, 'sidebar double-click must reset width');
    const originalEditor = await height(editor());
    await dragDivider('editor', 0, 70);
    assert.ok((await height(editor())) > originalEditor + 45, 'editor pointer drag must change height');
    const resizedEditor = await height(editor());
    await divider('editor').focus();
    await page.keyboard.press('ArrowUp');
    assert.ok((await height(editor())) < resizedEditor - 5, 'editor arrow key must adjust height');
    const prefs = assertLayoutStorage(await page.evaluate(() => localStorage.getItem('toolbox.layout.v1')));
    assert.equal(submissions.length, 1, 'layout gestures must submit no SQL');
    console.log('PASS sidebar/editor pointer resize, keyboard adjustment, reset and numeric-only storage');

    await page.locator('#sql-editor').evaluate((el) => { el.focus(); el.setSelectionRange(7, 12); window.__layoutEditorRef = el; });
    const selection = await page.locator('#sql-editor').evaluate((el) => [el.selectionStart, el.selectionEnd]);
    await page.locator('[data-action="new-tab"]').first().click();
    const secondId = await activeTabId();
    await page.locator('[data-action="new-tab"]').first().click();
    const thirdId = await activeTabId();
    assert.deepEqual(await tabIds(), [sourceId, secondId, thirdId]);
    await tabButton(sourceId).click();
    assert.ok(Math.abs((await height(editor())) - (await page.locator('.editor-results').evaluate((el, ratio) =>
      (el.clientHeight - el.querySelector('[data-layout-resize="editor"]').offsetHeight) * ratio, prefs.editorRatio))) < 3,
    'editor ratio must survive tab creation and switching');
    // Save a fresh DOM reference after the deliberate tab switch, then drag another tab.
    await page.locator('#sql-editor').evaluate((el) => { el.focus(); el.setSelectionRange(7, 12); window.__layoutEditorRef = el; });
    const beforeDrag = submissions.length;
    await tabButton(thirdId).dragTo(page.locator('#sql-editor'));
    assert.deepEqual(await tabIds(), [sourceId, secondId, thirdId], 'dropping on editor must not reorder tabs');
    assert.equal(await page.locator('#sql-editor').inputValue(), sql, 'dropping a tab on editor must not insert text');
    assert.equal(submissions.length, beforeDrag, 'invalid drop must submit no SQL');
    await tabButton(thirdId).dragTo(tabButton(sourceId), {targetPosition: {x: 8, y: 12}});
    assert.deepEqual(await tabIds(), [thirdId, sourceId, secondId], 'drag must move the target tab before the source tab');
    assert.equal(await activeTabId(), sourceId, 'drag must retain the active tab');
    assert.equal(await page.locator('#sql-editor').evaluate((el) => el === window.__layoutEditorRef), true,
      'tab reordering must preserve the live editor DOM node');
    assert.equal(await page.locator('#sql-editor').inputValue(), sql);
    assert.deepEqual(await page.locator('#sql-editor').evaluate((el) => [el.selectionStart, el.selectionEnd]), selection);
    assert.equal((await api('/sessions/' + sessionId)).id, sessionId, 'drag must retain the active JDBC session');
    assert.equal(submissions.length, beforeDrag, 'tab drag must submit no SQL');
    await tabCommand(thirdId, '向右移动标签');
    assert.deepEqual(await tabIds(), [sourceId, thirdId, secondId], 'context menu must move tab right');
    await tabCommand(thirdId, '向左移动标签');
    assert.deepEqual(await tabIds(), [thirdId, sourceId, secondId], 'context menu must move tab left');
    await tabCommand(sourceId, '关闭右侧标签');
    await tabButton(secondId).waitFor({state: 'detached'});
    assert.deepEqual(await tabIds(), [thirdId, sourceId], 'close-right must follow reordered tab order');
    assert.equal(await activeTabId(), sourceId);
    console.log('PASS real tab drag, context movement, editor/session identity and close-right order');

    await page.screenshot({path: shot()});
    await page.reload();
    await page.locator('#sql-editor').waitFor();
    assert.deepEqual(assertLayoutStorage(await page.evaluate(() => localStorage.getItem('toolbox.layout.v1'))), prefs,
      'layout values must persist across reload');
    assert.ok(Math.abs((await width(sidebar())) - prefs.sidebarWidth) < 3, 'sidebar preference must survive reload');
    await assertHeader(360);
    assert.equal(await page.locator('.workspace').evaluate((el) => el.classList.contains('sidebar-open')), false,
      'navigation must collapse when the window crosses the narrow breakpoint');
    assert.equal(await sidebar().isVisible(), false, 'narrow navigation must not cover the editor');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false,
      'narrow main workspace must fit with navigation collapsed');
    await page.screenshot({path: shot('-narrow')});
    await assertHeader(1440);
    assert.equal(await page.locator('.workspace').evaluate((el) => el.classList.contains('sidebar-open')), true,
      'returning to desktop must restore the previously visible navigation');
    assert.ok(Math.abs((await width(sidebar())) - prefs.sidebarWidth) < 3,
      'restored navigation must retain its saved width');
    const resetSql = 'SELECT 99 AS LAYOUT_RESET;';
    await page.locator('#sql-editor').fill(resetSql);
    const beforeReset = submissions.length;
    await menuCommand('view', '恢复默认布局');
    assert.equal(await page.evaluate(() => localStorage.getItem('toolbox.layout.v1')), null,
      'View-menu reset must remove the saved layout key');
    assert.equal(await page.locator('#sql-editor').inputValue(), resetSql, 'layout reset must retain SQL text');
    assert.equal(submissions.length, beforeReset, 'layout reset must submit no SQL');
    assert.deepEqual(errors, [], 'browser console and page must have no errors');
    console.log('PASS reload persistence, responsive navigation, View reset, screenshots and browser health');
  } catch (error) {
    if (page) await page.screenshot({path: shot('-failure'), fullPage: true}).catch(() => {});
    if (errors.length) console.error('Browser errors:', JSON.stringify(errors));
    throw error;
  } finally {
    if (browser) await browser.close();
    for (const id of sessions) await api('/sessions/' + id, 'DELETE').catch(() => {});
    if (fixture) await api('/sessions/' + fixture.id, 'DELETE').catch(() => {});
    if (connection) await api('/connections/' + connection.id, 'DELETE').catch(() => {});
  }
})().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
