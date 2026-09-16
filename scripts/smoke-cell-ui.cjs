#!/usr/bin/env node
// Optional developer check: Node 18+, Playwright, and a Chromium browser.
// Creates and modifies only its unique in-memory H2 fixture through a running JAR.
const assert = require("node:assert/strict");
const { chromium } = require(
  process.env.TOOLBOX_PLAYWRIGHT_PATH || "playwright",
);
const base = process.env.TOOLBOX_BASE_URL || "http://127.0.0.1:18090";
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const terminal = new Set([
  "SUCCEEDED",
  "FAILED",
  "CANCELED",
  "TIMED_OUT",
  "OUTCOME_UNKNOWN",
]);
let token, connection, fixture, browser, page;
const uiSessions = new Set();
async function api(path, method = "GET", body) {
  const response = await fetch(base + "/api" + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      "X-Toolbox-Token": token || "",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json();
  assert.ok(response.ok && result.success, result.message);
  return result.data;
}
async function execute(sql) {
  const request = {
    sessionId: fixture.id,
    sql,
    mode: "SCRIPT",
    requestId: crypto.randomUUID(),
  };
  request.confirmationToken = (
    await api("/executions/prepare", "POST", request)
  ).confirmationToken;
  let record = await api("/executions", "POST", request);
  for (let i = 0; !terminal.has(record.state) && i < 200; i++) {
    await sleep(50);
    record = await api("/executions/" + record.id);
  }
  assert.equal(record.state, "SUCCEEDED", record.message);
  await sleep(40);
  return record;
}
(async () => {
  try {
    token = (await api("/bootstrap")).token;
    const driver = (await api("/drivers")).find(
      (d) => d.bundled && d.driverClass === "org.h2.Driver",
    );
    const name = "Cell UI " + Date.now();
    connection = await api("/connections", "POST", {
      name,
      driverId: driver.id,
      jdbcUrl: "jdbc:h2:mem:cell_ui_" + Date.now(),
      username: "sa",
      password: "",
    });
    fixture = await api("/sessions", "POST", { connectionId: connection.id });
    await execute(
      "CREATE TABLE CELL_UI(ID INT PRIMARY KEY, TITLE VARCHAR(80), AMOUNT DECIMAL(30,19)); INSERT INTO CELL_UI VALUES(1,'原始值',1.1234567890123456789),(2,'第二行',2); CREATE VIEW CELL_VIEW AS SELECT * FROM CELL_UI;",
    );
    browser = await chromium.launch({
      headless: true,
      ...(process.env.TOOLBOX_CHROME_PATH
        ? { executablePath: process.env.TOOLBOX_CHROME_PATH }
        : {}),
    });
    page = await browser.newPage({ viewport: { width: 1440, height: 980 } });
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("dialog", (dialog) => dialog.accept());
    page.on("response", async (response) => {
      if (
        response.url().endsWith("/api/sessions") &&
        response.request().method() === "POST"
      ) {
        const result = await response.json().catch(() => null);
        if (result?.data?.id) uiSessions.add(result.data.id);
      }
    });
    await page.goto(base);
    await page.waitForLoadState("networkidle");
    const connectionButton = page.locator(
      `[data-action="toggle-connection"][data-id="${connection.id}"]`,
    );
    if (
      !(await connectionButton.evaluate((el) =>
        el
          .closest(".tree-row")
          .nextElementSibling?.classList.contains("tree-children"),
      ))
    )
      await connectionButton.click();
    const scope = page.locator(
      `[data-action="toggle-schema"][data-id="${connection.id}"][data-schema="PUBLIC"]`,
    );
    await scope.waitFor();
    if (
      !(await page
        .locator('[data-action="open-object"]')
        .filter({ hasText: /^CELL_UI$/ })
        .count())
    )
      await scope.click();
    await page
      .locator('[data-action="open-object"]')
      .filter({ hasText: /^CELL_UI$/ })
      .click();
    const cell = (row, col) =>
      page.locator(
        `#results [data-cell][data-row="${row}"][data-column="${col}"]`,
      );
    const waitCell = async (text) => {
      await page.waitForFunction(
        (expected) =>
          document.querySelector(
            '#results [data-cell][data-row="0"][data-column="1"]',
          )?.textContent === expected && !document.querySelector('[data-action="table-read"]')?.disabled,
        text,
      );
    };
    await waitCell("原始值");
    await cell(0, 1).click();
    assert.match(await cell(0, 1).getAttribute("class"), /selected-cell/);
    await page.locator('[data-action="edit-selected-cell"]').click();
    await page.locator("#cell-input").fill("修改 ' ; <script> 精确保存");
    await page.screenshot({
      path: process.env.TOOLBOX_UI_SCREENSHOT || "/tmp/toolbox-cell-editor.png",
      fullPage: true,
    });
    await page.locator('[data-action="save-cell"]').click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    await waitCell("修改 ' ; <script> 精确保存");
    assert.equal(await cell(1, 1).textContent(), "第二行");
    console.log(
      "PASS selection, editor, parameterized save, escaped display, refresh",
    );
    await cell(0, 0).dblclick();
    assert.match(await page.locator("#dialog").textContent(), /主键列只读/);
    assert.equal(await page.locator('[data-action="save-cell"]').count(), 0);
    await page
      .locator('#dialog .dialog-foot [data-action="dialog-close"]')
      .click();
    await cell(0, 1).focus();
    await page.keyboard.press("Enter");
    await page.locator("#cell-null").check();
    assert.ok(await page.locator("#cell-input").isDisabled());
    await page.locator('[data-action="save-cell"]').click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    await waitCell("NULL");
    await cell(0, 1).dblclick();
    await page.locator("#cell-null").uncheck();
    await page.locator("#cell-input").fill("");
    await page.locator('[data-action="save-cell"]').click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    await waitCell("(空字符串)");
    console.log("PASS keyboard, read-only primary key, NULL versus empty");
    // Real concurrent change while the editing dialog is open.
    await cell(0, 1).dblclick();
    await page.locator("#cell-input").fill("我的草稿");
    await execute("UPDATE CELL_UI SET TITLE='并发修改' WHERE ID=1");
    await page.locator('[data-action="save-cell"]').click();
    await page.locator('[data-action="cell-refresh"]').waitFor();
    assert.match(await page.locator("#cell-feedback").textContent(), /冲突/);
    assert.equal(await page.locator("#cell-input").inputValue(), "我的草稿");
    assert.ok(await page.locator('[data-action="save-cell"]').isDisabled());
    await page.locator('[data-action="cell-refresh"]').click();
    await waitCell("并发修改");
    console.log(
      "PASS concurrent conflict retains draft, prevents resubmission, refreshes",
    );
    await page.locator("#auto-commit").uncheck();
    await waitCell("并发修改");
    await cell(0, 1).dblclick();
    await page.locator("#cell-input").fill("待回滚");
    await page.locator('[data-action="save-cell"]').click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    await waitCell("待回滚");
    assert.equal(
      (await execute("SELECT TITLE FROM CELL_UI WHERE ID=1")).statements[0]
        .results[0].rows[0][0],
      "并发修改",
    );
    await page.locator('[data-action="rollback"]').click();
    await waitCell("并发修改");
    await cell(0, 1).dblclick();
    await page.locator("#cell-input").fill("已提交");
    await page.locator('[data-action="save-cell"]').click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    await waitCell("已提交");
    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().endsWith("/transaction") &&
          r.request().postDataJSON()?.action === "COMMIT",
      ),
      page.locator('[data-action="commit"]').click(),
    ]);
    assert.equal(
      (await execute("SELECT TITLE FROM CELL_UI WHERE ID=1")).statements[0]
        .results[0].rows[0][0],
      "已提交",
    );
    console.log(
      "PASS manual save is uncommitted, rollback refresh, explicit commit",
    );
    await waitCell("已提交");
    await cell(0, 1).dblclick();
    await page.locator("#cell-input").fill("不应写入");
    await api(`/sessions/${fixture.id}/transaction`, "POST", {action: "AUTO_COMMIT", autoCommit: false});
    await execute("UPDATE CELL_UI SET TITLE='锁定中' WHERE ID=1");
    await page.locator('[data-action="save-cell"]').click();
    await page.locator('[data-action="cancel-cell"]').click();
    await api(`/sessions/${fixture.id}/transaction`, "POST", {action: "ROLLBACK"});
    await page.locator('[data-action="cell-refresh"]').waitFor();
    assert.match(await page.locator("#cell-feedback").textContent(), /中断|取消/);
    await page.locator('[data-action="cell-refresh"]').click();
    await waitCell("已提交");
    await api(`/sessions/${fixture.id}/transaction`, "POST", {action: "AUTO_COMMIT", autoCommit: true});
    console.log("PASS cancel while waiting for row lock prevents write");
    await page
      .locator('[data-action="open-object"]')
      .filter({ hasText: /^CELL_VIEW$/ })
      .click();
    await page.waitForFunction(() =>
      document
        .querySelector("#results")
        ?.textContent.includes("视图或非普通表不可编辑"),
    );
    await cell(0, 1).dblclick();
    assert.equal(await page.locator('[data-action="save-cell"]').count(), 0);
    assert.deepEqual(errors, []);
    console.log("PASS view read-only, no browser script errors");
  } catch (error) {
    if (page) {
      await page.screenshot({
        path: "/tmp/toolbox-cell-ui-failure.png",
        fullPage: true,
      });
      console.error((await page.locator("body").innerText()).slice(-4000));
    }
    throw error;
  } finally {
    if (browser) await browser.close();
    for (const id of uiSessions)
      await api("/sessions/" + id, "DELETE").catch(() => {});
    if (fixture) await api("/sessions/" + fixture.id, "DELETE");
    if (connection) await api("/connections/" + connection.id, "DELETE");
  }
})().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
