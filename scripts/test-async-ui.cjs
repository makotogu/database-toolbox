#!/usr/bin/env node
// Deterministic regression tests of the actual app functions, with network responses reordered.
const fs = require("node:fs"), vm = require("node:vm"), assert = require("node:assert/strict");
const source = fs.readFileSync(require("node:path").join(__dirname, "../src/main/resources/static/workbench/app.js"), "utf8");
const between = (a, b) => source.slice(source.indexOf(a), source.indexOf(b, source.indexOf(a)));
function fixture() {
  const requests = [], timers = new Map(); let sequence = 0;
  const t = { id: "tab", connectionId: "A", execution: { id: "old", state: "RUNNING" }, session: { id: "session", state: "RUNNING" } };
  const ctx = {
    pollTimer: null, polling: false, readRequests: new Map(), state: { tabs: [t], tree: new Map(), expanded: new Set() },
    activeStates: new Set(["RUNNING", "QUEUED", "CANCEL_REQUESTED"]), AbortController,
    setTimeout: (fn, ms) => { const id = ++sequence; timers.set(id, { fn, ms }); return id; }, clearTimeout: (id) => timers.delete(id),
    api: (path, options = {}) => new Promise((resolve, reject) => requests.push({ path, options, resolve, reject })),
    tab: () => null, connection: () => ({}), list: (x) => x, syncSessionContext: () => {}, allResults: () => [],
    rememberEditor: () => {}, renderTab: () => {}, renderTree: () => {}, renderStatus: () => {}, renderResults: () => {},
    toast: () => {}, report: () => {}, $: () => null, $$: () => [], document: { activeElement: null },
  };
  vm.createContext(ctx);
  vm.runInContext(source.match(/^const busy =.*$/m)[0], ctx);
  vm.runInContext(between("function beginRead(", "let cellEditor;"), ctx);
  vm.runInContext(between("function startPolling()", "async function transaction("), ctx);
  vm.runInContext(between("async function loadTree(", "function renderTab("), ctx);
  vm.runInContext("globalThis.cancelAction = " + between("  cancel: async () => {", '  "recover-session":').replace(/^  cancel: /, "").trim().replace(/,$/, ""), ctx);
  function runPoll() {
    const entry = [...timers].find(([, item]) => item.ms === 350);
    assert.ok(entry, "poll should be scheduled"); timers.delete(entry[0]); return entry[1].fn();
  }
  return { ctx, t, requests, timers, runPoll };
}
async function tick() { await new Promise((resolve) => setImmediate(resolve)); }
(async () => {
  {
    const { ctx, requests, timers, runPoll } = fixture();
    ctx.startPolling(); const p = runPoll(); ctx.startPolling(); ctx.startPolling();
    assert.equal([...timers.values()].filter((x) => x.ms === 350).length, 0);
    assert.equal(requests.length, 1);
    requests[0].resolve({ id: "old", state: "RUNNING" }); await tick(); requests[1].resolve({ id: "session", state: "RUNNING" }); await p;
    assert.equal([...timers.values()].filter((x) => x.ms === 350).length, 1);
    console.log("PASS one poll scheduler while a read is in flight");
  }
  {
    const { ctx, t, requests, runPoll } = fixture(); ctx.tab = () => t;
    ctx.startPolling(); const p = runPoll(); const cancel = ctx.cancelAction();
    assert.ok(requests[0].options.signal.aborted);
    requests[1].resolve({ id: "old", state: "CANCELED" }); await cancel;
    requests[0].resolve({ id: "old", state: "RUNNING" }); await p;
    assert.equal(t.execution.state, "CANCELED"); assert.equal(t.cancelInFlight, false);
    console.log("PASS old RUNNING response cannot overwrite cancellation");
  }
  {
    const { ctx, t, requests, runPoll } = fixture(); ctx.startPolling(); const p = runPoll();
    t.execution = { id: "replacement", state: "RUNNING" };
    requests[0].resolve({ id: "old", state: "SUCCEEDED" }); await p;
    assert.equal(t.execution.id, "replacement");
    console.log("PASS obsolete execution response cannot replace current execution");
  }
  {
    const { ctx, t, requests } = fixture();
    const a = ctx.loadContext(t); t.connectionId = "B"; const b = ctx.loadContext(t);
    assert.ok(requests[0].options.signal.aborted);
    requests.slice(2).forEach((r) => r.resolve([{ name: "B" }])); await b;
    requests.slice(0, 2).forEach((r) => r.resolve([{ name: "A" }])); await a;
    assert.equal(t.schemas[0].name, "B"); assert.equal(t.catalogs[0].name, "B");
    const removed = ctx.loadContext(t); ctx.state.tabs = [];
    requests.slice(4).forEach((r) => r.resolve([{ name: "removed" }])); await removed;
    assert.equal(t.schemas[0].name, "B");
    console.log("PASS connection changes and removed tabs ignore old context responses");
  }
  {
    const { ctx, requests } = fixture(); const first = ctx.loadTree("A"); const next = ctx.loadTree("A", true);
    requests[2].resolve([]); requests[3].resolve([{ name: "new1" }, { name: "new2" }]); await next;
    requests[0].resolve([]); requests[1].resolve([{ name: "old1" }, { name: "old2" }]); await first;
    assert.equal(ctx.state.tree.get("A").scopes[0].schema, "new1");
    console.log("PASS refreshed tree cannot be overwritten by an older response");
  }
  {
    const { ctx, t, requests, runPoll } = fixture(); ctx.startPolling(); const p = runPoll();
    requests[0].reject(new Error("synthetic connection loss")); await p;
    assert.equal(t.execution.state, "RUNNING"); assert.ok(t.pollError);
    assert.equal(requests.length, 1); // No SQL or cancellation writes on a failed read.
    console.log("PASS poll read errors retain active state and never resubmit SQL");
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
