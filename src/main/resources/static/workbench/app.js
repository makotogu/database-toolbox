import { installConnectionFields } from "./connection-fields.js";
import { highlightSql } from "./sql-highlight.js";
import { createDraftManager } from "./sql-drafts.js";

const icons = {
  database:
    '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  play: '<path d="m8 5 11 7-11 7Z"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="1"/>',
  driver: '<path d="M8 3v5M16 3v5M6 8h12v3a6 6 0 0 1-12 0ZM12 17v4"/>',
  folder: '<path d="M3 6h7l2 3h9v11H3Z"/>',
  table:
    '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 10h18M9 10v10M15 10v10"/>',
  code: '<path d="m8 6-6 6 6 6m8-12 6 6-6 6M14 3l-4 18"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
  refresh:
    '<path d="M20 7v5h-5M4 17v-5h5M19 11a7 7 0 0 0-12-5L4 9m16 6-3 3a7 7 0 0 1-12-5"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  settings: '<path d="M4 7h16M4 17h16M8 4v6M16 14v6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  alert: '<path d="m12 3 10 18H2Z M12 9v5M12 17v.2"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7v.2"/>',
  file: '<path d="M14 2H5v20h14V7ZM14 2v6h5M8 12h8M8 16h6"/>',
  save: '<path d="M4 3h13l4 4v14H3V3ZM7 3v6h10V3M7 21v-8h10v8"/>',
  open: '<path d="M3 19V5h7l2 3h9v3M3 19l4-8h16l-4 8Z"/>',
  copy: '<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M16 8V3H3v13h5"/>',
  branch:
    '<path d="M7 4v14M7 8h11M7 16h11"/><circle cx="7" cy="4" r="2"/><circle cx="18" cy="8" r="2"/><circle cx="18" cy="16" r="2"/>',
  undo: '<path d="M3 10h11a6 6 0 0 1 0 12M3 10l6-6M3 10l6 6"/>',
  keyboard:
    '<rect x="2" y="5" width="20" height="14" rx="2"/><path d="M6 9h.1M10 9h.1M14 9h.1M18 9h.1M6 12h.1M10 12h.1M14 12h.1M18 12h.1M7 15h10"/>',
  link: '<path d="m10 13 4-4M8 16l-1 1a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0m0 12a4 4 0 0 0 6 0l5-5a4 4 0 0 0-6-6l-1 1"/>',
  layout:
    '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6l4 2"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"/>',
};
const icon = (name) =>
  `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.file}</svg>`;
const esc = (value) =>
  String(value == null ? "" : value).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const $ = (q, root = document) => root.querySelector(q);
const $$ = (q, root = document) => [...root.querySelectorAll(q)];
const list = (value) =>
  Array.isArray(value) ? value : value?.items || value?.data || [];
const uid = () =>
  globalThis.crypto?.randomUUID?.() ||
  `${Date.now()}-${Math.random().toString(36).slice(2)}`;
const activeStates = new Set(["QUEUED", "RUNNING", "CANCEL_REQUESTED"]);
const stateLabels = {
  QUEUED: "排队中",
  RUNNING: "执行中",
  CANCEL_REQUESTED: "正在请求取消",
  SUCCEEDED: "执行完成",
  FAILED: "执行失败",
  CANCELED: "已取消",
  CANCELLED: "已取消",
  TIMED_OUT: "执行超时",
  OUTCOME_UNKNOWN: "结果未知",
  OPEN: "会话就绪",
  TX_PENDING: "事务待提交",
  BROKEN: "会话已失效",
  CLOSED: "会话已关闭",
};
const state = {
  token: "",
  drivers: [],
  connections: [],
  selectedConnection: "",
  expanded: new Set(),
  tree: new Map(),
  tabs: [],
  activeTab: "",
  sequence: 0,
  search: "",
  sidebarOpen: false,
};
let pollTimer, polling = false;
const readRequests = new Map();
function beginRead(key) {
  abortRead(key);
  const controller = new AbortController();
  const scope = { controller, current: () => readRequests.get(key) === scope };
  scope.timer = setTimeout(() => controller.abort(), 15000);
  readRequests.set(key, scope);
  return scope;
}
function finishRead(key, scope) {
  clearTimeout(scope.timer);
  if (scope.current()) readRequests.delete(key);
}
function abortRead(key) {
  const old = readRequests.get(key);
  if (old) { clearTimeout(old.timer); old.controller.abort(); readRequests.delete(key); }
}
function invalidateTree(id) {
  for (const key of readRequests.keys())
    if (key === `tree:${id}` || key.startsWith(`objects:${id}:`)) abortRead(key);
  for (const key of state.tree.keys())
    if (key === id || key.startsWith(id + ":")) state.tree.delete(key);
}
let cellEditor;
const tab = () => state.tabs.find((t) => t.id === state.activeTab);
const connection = (id) => state.connections.find((c) => c.id === id);
const busy = (t) => !!t?.submitting || !!t?.transactionPending || !!t?.cancelInFlight || !!t?.recovering || activeStates.has(t?.execution?.state);

async function api(path, options = {}) {
  const opts = { ...options, headers: { ...options.headers } };
  if (opts.body && !(opts.body instanceof FormData)) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(opts.body);
  }
  if (opts.method && opts.method !== "GET")
    opts.headers["X-Toolbox-Token"] = state.token;
  const response = await fetch(`/api${path}`, opts);
  let payload;
  try {
    payload = await response.json();
  } catch (e) {
    throw new Error(
      response.ok
        ? "服务返回了无法读取的响应。"
        : `请求失败（HTTP ${response.status}）`,
    );
  }
  if (!response.ok || payload.success === false)
    throw Object.assign(new Error(payload.message || `请求失败（HTTP ${response.status}）`), {status: response.status});
  return payload.data === undefined ? payload : payload.data;
}
const drafts = createDraftManager({
  uuid: () => crypto.randomUUID(),
  snapshot: id => state.tabs.filter(t => t.type === "sql" && t.connectionId === id && t.sql.length)
    .map(t => ({id: t.draftId || t.id, name: t.name, sql: t.sql})),
  changed: updateDraftStatus,
  request: async (id, body) => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
      return await api(`/connections/${id}/sql-drafts`, {signal: controller.signal, ...(body ? {method: "PUT", body} : {})});
    } catch (e) {
      if (e.name === "AbortError") throw new Error("草稿保存或读取超时，状态未确认。可重试保存；刷新前请先下载 SQL。");
      throw e;
    } finally { clearTimeout(timer); }
  },
});
function updateDraftStatus() {
  const el = $("#draft-status"), t = tab();
  if (!el || !t) return;
  const status = t.type === "sql" ? drafts.status(t.connectionId) : {label: "调用标签不保存草稿", message: "可用保存 SQL 文件保留文本；调用参数不自动保存。"};
  el.textContent = status.label; el.title = status.message || status.label;
  el.classList.toggle("error-text", !!status.error);
}
function draftStatusDialog() {
  const t = tab(), status = drafts.status(t.connectionId);
  showDialog("SQL 草稿", `<p>${esc(t.type === "sql" ? status.message || status.label : "调用标签及其参数不自动保存，请下载 SQL 文件。")}</p>`,
    `${t.type === "sql" && status.retry ? button("重试保存", "retry-drafts", null, "primary") : ""}${button("关闭", "dialog-close", null)}`);
}
function syncHighlightScroll() {
  const ed = $("#sql-editor"), layer = $("#sql-highlight");
  if (!ed || !layer) return;
  layer.style.width = `${ed.clientWidth}px`; layer.style.height = `${ed.clientHeight}px`;
  layer.scrollTop = ed.scrollTop; layer.scrollLeft = ed.scrollLeft;
  $("#line-numbers").scrollTop = ed.scrollTop;
}
function updateHighlight() {
  const ed = $("#sql-editor"), layer = $("#sql-highlight"), label = $("#highlight-status");
  if (!ed || !layer) return;
  const html = ed.dataset.composing ? null : highlightSql(ed.value);
  ed.classList.toggle("highlighted", html !== null);
  layer.hidden = html === null;
  layer.innerHTML = html === null ? "" : html + "\n ";
  if (label) label.textContent = html === null ? (ed.dataset.composing ? "中文输入中" : "大文本 · 高亮暂停") : "SQL 高亮";
  syncHighlightScroll();
}
const editorResize = new ResizeObserver(syncHighlightScroll);
function toast(message, error = false) {
  const el = document.createElement("div");
  el.className = `toast${error ? " error" : ""}`;
  el.innerHTML = `${icon(error ? "alert" : "check")}<span>${esc(message)}</span>`;
  $("#toast-root").append(el);
  setTimeout(() => el.remove(), error ? 8000 : 4200);
}
function report(error) {
  toast(error.message || String(error), true);
}
function button(label, action, ico, cls = "", extra = "") {
  return `<button class="${cls}" data-action="${action}" ${extra}>${ico ? icon(ico) : ""}${label}</button>`;
}
function options(items, value, empty) {
  return (
    (empty !== undefined ? `<option value="">${esc(empty)}</option>` : "") +
    items
      .map(
        (item) =>
          `<option value="${esc(item.id ?? item.name ?? item)}" ${(item.id ?? item.name ?? item) === value ? "selected" : ""}>${esc(item.name ?? item)}</option>`,
      )
      .join("")
  );
}
function showDialog(title, content, footer = "", wide = false) {
  const d = $("#dialog");
  if (d.open) d.close();
  d.className = wide ? "wide" : "";
  d.innerHTML = `<div class="dialog-head"><h2 id="dialog-title">${esc(title)}</h2><span class="spacer"></span>${button("", "dialog-close", "close", "ghost icon-btn", 'aria-label="关闭对话框"')}</div><div class="dialog-content">${content}</div><div class="dialog-foot">${footer || button("关闭", "dialog-close", null)}</div>`;
  d.showModal();
  return d;
}
function field(
  label,
  name,
  value = "",
  type = "text",
  help = "",
  wide = false,
) {
  return `<div class="form-field ${wide ? "wide" : ""}"><label for="f-${name}">${label}</label><input id="f-${name}" name="${name}" type="${type}" value="${esc(value)}" ${type === "password" ? 'autocomplete="new-password"' : 'autocomplete="off"'}>${help ? `<small>${help}</small>` : ""}</div>`;
}
function rememberEditor() {
  const t = tab(),
    editor = $("#sql-editor");
  if (t && editor) {
    const changed = t.sql !== editor.value;
    t.sql = editor.value;
    if (changed) drafts.schedule();
    t.selection = [editor.selectionStart, editor.selectionEnd];
    t.scrollTop = editor.scrollTop;
    t.scrollLeft = editor.scrollLeft;
  }
  captureParameters(t);
}
function captureParameters(t) {
  if (!t?.parameters) return;
  $$(".param-row").forEach((row, i) => {
    const p = t.parameters[i];
    if (!p) return;
    p.position = Number($("[name=position]", row).value);
    p.mode = $("[name=mode]", row).value;
    p.jdbcType = Number($("[name=jdbcType]", row).value);
    p.name = $("[name=name]", row).value;
    p.value = $("[name=value]", row).value;
    p.isNull = $("[name=isNull]", row).checked;
  });
}
function newTab(spec = {}, restoring = false) {
  rememberEditor();
  const t = {
    id: uid(),
    name: `SQL ${++state.sequence}`,
    type: "sql",
    connectionId: state.selectedConnection || state.connections[0]?.id || "",
    sql: "",
    selection: [0, 0],
    resultIndex: 0,
    session: null,
    execution: null,
    schemas: [],
    catalogs: [],
    schema: "",
    catalog: "",
    ...spec,
  };
  state.tabs.push(t);
  state.activeTab = t.id;
  render();
  if (!restoring) {
    if (t.connectionId) loadContext(t);
    drafts.schedule();
  }
  return t;
}
function render() {
  const t = tab();
  $("#app").innerHTML =
    `<header class="app-header">${button("", "sidebar-toggle", "layout", "ghost icon-btn split-toggle", 'aria-label="显示或隐藏对象导航"')}<div class="brand"><span class="brand-mark">${icon("database")}</span>数据库工作台 <span class="version">V2</span></div><div class="header-context"><span class="context-label">连接</span><select id="global-connection" aria-label="当前连接">${options(state.connections, state.selectedConnection, "选择数据库连接")}</select></div><span class="spacer"></span><span class="local-label"><i class="dot online"></i>本机工作区</span>${button('<span class="button-label">新建 SQL</span>', "new-tab", "plus")}${button('<span class="button-label">驱动管理</span>', "drivers", "driver", "ghost")}${button("", "execution-settings", "settings", "ghost icon-btn", 'title="当前标签执行设置" aria-label="当前标签执行设置"')}${button("", "shortcuts", "keyboard", "ghost icon-btn", 'title="键盘快捷键" aria-label="键盘快捷键"')}</header><div class="workspace ${state.sidebarOpen ? "sidebar-open" : ""}"><aside class="sidebar"><div class="sidebar-head"><strong>数据库导航</strong><span class="spacer"></span>${button("", "new-connection", "plus", "ghost icon-btn", 'title="新建连接" aria-label="新建连接"')}${button("", "refresh-tree", "refresh", "ghost icon-btn", 'title="刷新对象" aria-label="刷新对象"')}</div><div class="search-box">${icon("search")}<input id="object-search" value="${esc(state.search)}" placeholder="筛选对象名称" aria-label="筛选对象名称"></div><div id="tree" class="tree"></div><div class="sidebar-footer"><div class="demo-entry">${button("试用 H2", "demo-connection", "play", "ghost small")}</div><strong>${icon("link")} 每个标签独立连接</strong>关闭标签会回滚未提交事务</div></aside><main class="main"><div class="tab-strip"><div class="tabs" role="tablist" aria-label="工作区标签">${state.tabs.map((x) => `<button class="work-tab ${x.id === state.activeTab ? "active" : ""}" role="tab" aria-selected="${x.id === state.activeTab}" data-action="select-tab" data-id="${x.id}" title="${esc(x.name)}">${icon(x.type === "table" ? "table" : x.type === "routine" ? "code" : "file")}<span class="tab-label">${esc(x.name)}</span>${busy(x) ? '<i class="loading-dot"></i>' : ""}<span class="close-tab" data-action="close-tab" data-id="${x.id}" role="button" tabindex="0" aria-label="关闭 ${esc(x.name)}">${icon("close")}</span></button>`).join("")}</div>${button("", "new-tab", "plus", "ghost icon-btn", 'title="新建 SQL 标签" aria-label="新建 SQL 标签"')}</div><div id="tab-body" class="tab-body"></div></main></div><footer id="status-bar" class="status-bar"></footer>`;
  renderTree();
  renderTab(t);
  renderStatus();
}
function renderTree() {
  const root = $("#tree");
  if (!root) return;
  if (!state.connections.length) {
    root.innerHTML = `<div class="sidebar-placeholder">${icon("database")}<strong>连接你的数据库</strong>选择内置 MySQL、PostgreSQL 或 H2 驱动创建连接，也可以导入自己的 JDBC 驱动及依赖包。${button(state.drivers.length ? "新建连接" : "导入 JDBC 驱动", state.drivers.length ? "new-connection" : "drivers", state.drivers.length ? "plus" : "driver", "primary")}</div>`;
    return;
  }
  root.innerHTML = state.connections
    .map((c) => {
      const open = state.expanded.has(c.id);
      return `<div class="tree-row ${c.id === state.selectedConnection ? "active" : ""}"><button class="row-main" data-action="toggle-connection" data-id="${c.id}">${icon(open ? "down" : "chevron")}${icon("database")}<span>${esc(c.name)}</span></button>${button("", "edit-connection", "more", "ghost icon-btn", `data-id="${c.id}" title="连接设置" aria-label="编辑 ${esc(c.name)}"`)}</div>${open ? renderConnectionTree(c) : ""}`;
    })
    .join("");
}
function renderConnectionTree(c) {
  const data = state.tree.get(c.id);
  if (!data) return '<div class="skeleton">正在读取数据库对象…</div>';
  if (data.error)
    return `<div class="tree-error">${esc(data.error)}<br>${button("重试", "refresh-connection", null, "small", `data-id="${c.id}"`)}</div>`;
  const scopes = data.scopes || [];
  return `<div class="tree-children">${scopes
    .map((scope) => {
      const key = `${c.id}:${scope.catalog || ""}:${scope.schema || ""}`,
        open = state.expanded.has(key);
      return `<div class="tree-row"><button class="row-main" data-action="toggle-schema" data-id="${c.id}" data-key="${esc(key)}" data-catalog="${esc(scope.catalog)}" data-schema="${esc(scope.schema)}">${icon(open ? "down" : "chevron")}${icon("folder")}<span>${esc(scope.label || scope.schema || scope.catalog || "默认命名空间")}</span></button></div>${open ? renderObjects(c, scope, key) : ""}`;
    })
    .join(
      "",
    )}${!scopes.length ? '<div class="tree-empty">未返回可浏览的命名空间</div>' : ""}</div>`;
}
function renderObjects(c, scope, key) {
  const data = state.tree.get(key);
  if (!data) return '<div class="skeleton">正在读取表与存过…</div>';
  if (data.error) return `<div class="tree-error">${esc(data.error)}<br>${button("重试", "refresh-objects", null, "small", `data-id="${c.id}" data-key="${esc(key)}" data-catalog="${esc(scope.catalog || "")}" data-schema="${esc(scope.schema || "")}"`)}</div>`;
  let query = state.search.toLowerCase();
  const groups = [
    ["表与视图", "table", data.tables || [], data.tableError],
    ["存储过程与函数", "code", data.routines || [], data.routineError],
  ];
  return `<div class="tree-children">${groups
    .map(([title, ico, objects, error]) => {
      const shown = objects.filter((o) => o.name.toLowerCase().includes(query));
      return `<div class="tree-section">${icon(ico)}<span>${title}</span>${shown.length}</div>${error ? `<div class="tree-error">${esc(error)}</div>` : ""}${shown.map((o) => `<div class="tree-row"><button class="row-main" data-action="open-object" data-id="${c.id}" data-object="${esc(JSON.stringify({ ...o, catalog: o.catalog ?? scope.catalog, schema: o.schema ?? scope.schema, kind: ico === "table" ? "table" : "routine" }))}" title="${esc(o.type || "")} · ${esc(o.name)}">${icon(ico)}<span>${esc(o.name)}</span></button></div>`).join("")}${!shown.length && !error ? '<div class="tree-empty">' + (query ? "没有匹配的对象" : "无对象") + "</div>" : ""}`;
    })
    .join("")}</div>`;
}
async function loadTree(id, refresh = false) {
  if (refresh) invalidateTree(id);
  if (!refresh && state.tree.has(id)) return;
  state.tree.delete(id);
  renderTree();
  const key = `tree:${id}`, scope = beginRead(key);
  const options = { signal: scope.controller.signal };
  try {
    const [catalogs, schemas] = await Promise.all([
      api(`/connections/${id}/objects?kind=catalogs`, options).catch((e) => { if (e.name === "AbortError") throw e; return []; }),
      api(`/connections/${id}/objects?kind=schemas`, options),
    ]);
    if (!scope.current() || !connection(id)) return;
    const scopes = list(schemas).map((s) => ({
      catalog: s.catalog || "",
      schema: s.name || s.schema || "",
      label: s.name || s.schema,
    }));
    if (!scopes.length)
      list(catalogs).forEach((c) =>
        scopes.push({
          catalog: c.name || c.catalog || "",
          schema: "",
          label: c.name || c.catalog,
        }),
      );
    if (!scopes.length)
      scopes.push({ catalog: "", schema: "", label: "默认命名空间" });
    state.tree.set(id, { scopes });
    if (scopes.length === 1) {
      const scope = scopes[0],
        key = `${id}:${scope.catalog}:${scope.schema}`;
      state.expanded.add(key);
      loadObjects(id, scope, key);
    }
  } catch (e) {
    if (!scope.current()) return;
    state.tree.set(id, { error: e.name === "AbortError" ? "读取超时，请重试" : e.message });
  } finally { finishRead(key, scope); }
  renderTree();
}
async function loadObjects(id, scope, key, refresh = false) {
  if (!refresh && state.tree.has(key)) return;
  state.tree.delete(key);
  renderTree();
  const q = new URLSearchParams({
    catalog: scope.catalog || "",
    schema: scope.schema || "",
  });
  const requestKey = `objects:${key}`, request = beginRead(requestKey);
  const options = { signal: request.controller.signal };
  const results = await Promise.allSettled([
    api(`/connections/${id}/objects?kind=tables&${q}`, options),
    api(`/connections/${id}/objects?kind=routines&${q}`, options),
  ]);
  const current = request.current() && !!connection(id);
  finishRead(requestKey, request);
  if (!current) return;
  const aborted = request.controller.signal.aborted || results.some(
    (result) => result.status === "rejected" && result.reason?.name === "AbortError",
  );
  if (aborted) {
    // A superseded request returned above; a current request was interrupted or timed out.
    // Keep this distinct from a successful read with no objects, and allow an explicit retry.
    state.tree.set(key, { error: "读取超时，请重试" });
    renderTree();
    return;
  }
  const obj = {
    tables: results[0].status === "fulfilled" ? list(results[0].value) : [],
    routines: results[1].status === "fulfilled" ? list(results[1].value) : [],
  };
  if (results[0].status === "rejected")
    obj.tableError = results[0].reason.message;
  if (results[1].status === "rejected")
    obj.routineError = results[1].reason.message;
  state.tree.set(key, obj);
  renderTree();
}
async function loadContext(t) {
  t.restoreContext = false;
  const connectionId = t.connectionId, key = `context:${t.id}`, scope = beginRead(key);
  const fetchOptions = { signal: scope.controller.signal };
  try {
    const [schemas, catalogs] = await Promise.all([
      api(`/connections/${connectionId}/objects?kind=schemas`, fetchOptions).catch((e) => { if (e.name === "AbortError") throw e; return []; }),
      api(`/connections/${connectionId}/objects?kind=catalogs`, fetchOptions).catch((e) => { if (e.name === "AbortError") throw e; return []; }),
    ]);
    if (!scope.current() || !state.tabs.includes(t) || t.connectionId !== connectionId) return;
    t.schemas = list(schemas); t.catalogs = list(catalogs);
    if (tab() === t) {
      const s = $("#tab-schema"), c = $("#tab-catalog");
      if (s) s.innerHTML = options(t.schemas, t.schema, "默认 schema");
      if (c) c.innerHTML = options(t.catalogs, t.catalog, "默认 catalog");
    }
  } catch (e) {
    if (scope.current() && e.name !== "AbortError") report(e);
  } finally { finishRead(key, scope); }
}
function renderTab(t) {
  if (!t) return;
  const body = $("#tab-body");
  body.classList.toggle("routine-tab", t.type === "routine");
  const connected = t.session;
  body.innerHTML = `<div class="context-bar"><span class="context-label">连接</span><select id="tab-connection" aria-label="标签数据库连接" ${busy(t) || t.session ? "disabled" : ""}>${options(state.connections, t.connectionId, "选择连接")}</select><span class="context-label">catalog</span><select id="tab-catalog" aria-label="数据库 catalog" ${busy(t) || t.session ? "disabled" : ""}>${options(t.catalogs, t.catalog, "默认 catalog")}</select><span class="context-label">schema</span><select id="tab-schema" class="context-schema" aria-label="数据库 schema" ${busy(t) || t.session ? "disabled" : ""}>${options(t.schemas, t.schema, "默认 schema")}</select><span class="spacer"></span><span class="session-badge"><i class="dot ${connected ? "online" : ""}"></i>${connected ? esc(stateLabels[connected.state] || connected.state) : "执行时建立会话"}</span>${connected ? button("断开", "disconnect", null, "ghost small", busy(t) ? "disabled" : "") : ""}${connected?.state === "BROKEN" || connected?.resourceReleased ? button(connected.recoveryPending ? "正在回收…" : "恢复会话", "recover-session", null, "small", t.recovering || connected.recoveryPending ? "disabled" : "") : ""}</div>${t.type === "table" ? tableToolbar(t) : ""}${t.type === "routine" ? routinePanel(t) : ""}${t.type !== "table" ? editorToolbar(t) + editorMarkup(t) : `<div class="preview-sql"><span>实际 SQL</span><code id="preview-sql">${esc(t.previewSql || "点击「读取数据」生成查询。")}${t.previewBindings?.length ? `<br><span class="muted">输入参数（按占位符顺序）：${esc(JSON.stringify(t.previewBindings))}</span>` : ""}</code></div>`}<section id="results" class="results-pane" aria-label="执行结果"></section>`;
  if (t.type !== "table") {
    const ed = $("#sql-editor");
    ed.value = t.sql;
    ed.setSelectionRange(...t.selection);
    ed.scrollTop = t.scrollTop || 0;
    ed.scrollLeft = t.scrollLeft || 0;
    editorResize.disconnect();
    editorResize.observe(ed);
    updateHighlight();
    updateDraftStatus();
  }
  renderResults(t);
}
function editorToolbar(t) {
  const disabled = busy(t) ? "disabled" : "";
  return `<div class="toolbar">${button('执行当前 <kbd class="shortcut-key">⌘ ↵</kbd>', "execute-current", "play", "primary", disabled)}${button("选区", "execute-selection", null, "", disabled)}${button("整块", "execute-block", null, "", disabled)}${button("脚本", "execute-script", null, "", disabled)}<span class="divider"></span>${button("EXPLAIN", "explain", "branch", "", disabled)}${button("实际分析", "analyze", null, "", disabled + ' title="EXPLAIN ANALYZE 会真正执行语句"')}${button("取消", "cancel", "stop", "ghost", activeStates.has(t.execution?.state) ? "" : "disabled")}<span class="spacer"></span>${transactionControls(t)}<span class="divider"></span>${button("", "open-sql", "open", "ghost icon-btn", 'title="打开 SQL 文件" aria-label="打开 SQL 文件"')}${button("", "save-sql", "save", "ghost icon-btn", 'title="保存 SQL 文件" aria-label="保存 SQL 文件"')}</div>`;
}
function transactionControls(t) {
  const unavailable = busy(t) || t.session?.state === "BROKEN" || t.session?.state === "CLOSED" || t.session?.resourceReleased;
  return `<label class="check-label transaction-label"><input id="auto-commit" type="checkbox" ${t.session?.autoCommit !== false ? "checked" : ""} ${unavailable ? "disabled" : ""}>自动提交</label>${button("提交", "commit", "check", "ghost", !t.session || t.session.autoCommit || unavailable ? "disabled" : "")}${button("回滚", "rollback", "undo", "ghost", !t.session || t.session.autoCommit || unavailable ? "disabled" : "")}`;
}
function editorMarkup(t) {
  return `<section class="editor-pane" aria-label="SQL 编辑器"><div class="editor"><div id="line-numbers" class="line-numbers" aria-hidden="true">${lineNumbers(t.sql)}</div><div class="sql-editor-stack"><pre id="sql-highlight" class="sql-highlight" aria-hidden="true"></pre><textarea id="sql-editor" class="sql-editor" wrap="off" spellcheck="false" autocapitalize="off" autocomplete="off" aria-label="SQL 编辑区" placeholder="-- 在这里编写 SQL\n-- ⌘ / Ctrl + Enter 执行当前语句\n-- 选择一段 SQL 执行选区，或完整执行数据库代码块"></textarea></div></div><div class="editor-footer"><span id="cursor-position">行 1，列 1</span><button id="draft-status" class="draft-status" data-action="draft-status" aria-live="polite"></button><span class="spacer"></span><span id="highlight-status">SQL 高亮</span><span>UTF-8</span><span>拖动右下角调整高度</span></div></section>`;
}
function lineNumbers(sql) {
  return Array.from(
    { length: Math.max(8, sql.split("\n").length) },
    (_, i) => i + 1,
  ).join("\n");
}
function tableToolbar(t) {
  const cols = (t.structure?.columns || []).map((c) => ({
    name: c.name || c.columnName || c.COLUMN_NAME,
  }));
  settleTablePreview(t);
  const rows = t.filterDraft || [{column:"", operator:"=", value:""}];
  const operators = [["=","等于"],["<>","不等于"],[">","大于"],[">=","大于等于"],["<","小于"],["<=","小于等于"],["LIKE","LIKE"],["NOT LIKE","NOT LIKE"],["IS NULL","IS NULL"],["IS NOT NULL","IS NOT NULL"]];
  return `<div class="object-toolbar"><h2>${icon("table")}${esc(t.name)}<span class="object-type">${esc(t.object.type || "TABLE")}</span></h2>${button("数据", "table-data", null, t.tableView !== "structure" ? "selected" : "")}${button("结构", "table-structure", null, t.tableView === "structure" ? "selected" : "")}<span class="spacer"></span>${transactionControls(t)}<span class="divider"></span>${button("SQL 模板", "table-to-sql", "code", "small")}</div><div class="table-filters"><div class="filter-heading"><label>条件组合 <select id="filter-match" aria-label="条件组合" ${busy(t)?"disabled":""}><option value="ALL" ${t.filterMatch !== "ANY"?"selected":""}>全部满足（AND）</option><option value="ANY" ${t.filterMatch === "ANY"?"selected":""}>任一满足（OR）</option></select></label>${button("添加条件", "filter-add", "plus", "small", rows.length >= 30 || busy(t) ? "disabled" : "")}${button("清空条件", "filter-clear", null, "ghost small", busy(t)?"disabled":"")}<span id="filter-status" role="status">${esc(filterStatus(t))}</span></div><div class="filter-rows">${rows.map((f,i)=>`<div class="filter-row" data-filter-row="${i}"><select data-filter="column" aria-label="过滤列 ${i+1}" ${busy(t)?"disabled":""}>${options(cols,f.column,"选择过滤列")}</select><select data-filter="operator" aria-label="过滤操作 ${i+1}" ${busy(t)?"disabled":""}>${operators.map(([op,label])=>`<option value="${esc(op)}" ${f.operator===op?"selected":""}>${label}</option>`).join("")}</select><input data-filter="value" value="${esc(f.value ?? "")}" placeholder="过滤值（参数绑定）" aria-label="过滤值 ${i+1}" ${busy(t)||f.operator.includes("NULL")?"disabled":""}>${button("", "filter-remove", "close", "ghost icon-btn", `data-index="${i}" aria-label="移除条件 ${i+1}" ${busy(t)?"disabled":""}`)}</div>`).join("")}</div><div class="filter-heading"><select id="order-column" aria-label="排序列" ${busy(t)?"disabled":""}>${options(cols,t.orderBy||"","默认排序")}</select><label class="check-label"><input id="order-desc" type="checkbox" ${t.descending?"checked":""} ${busy(t)?"disabled":""}>降序</label>${button("读取数据", "table-read", "refresh", "primary small", busy(t)?"disabled":"")}${button("取消", "cancel", "stop", "ghost small", activeStates.has(t.execution?.state)?"":"disabled")}</div></div>`;
}
function routinePanel(t) {
  if (t.loading)
    return '<div class="routine-panel muted">正在读取存过定义与参数…</div>';
  return `<div class="object-toolbar"><h2>${icon("code")}${esc(t.name)}<span class="object-type">${esc(t.object.type || "ROUTINE")}</span></h2>${button("查看定义", "routine-definition", "file", "small")}${button("调用模板", "routine-template", "code", "small")}<span class="spacer"></span>${button("调用存过", "routine-call", "play", "primary", busy(t) ? "disabled" : "")}</div><div class="routine-panel"><details open><summary>调用参数 · ${(t.parameters || []).length} 个参数</summary><table class="parameter-table"><thead><tr><th>位置</th><th>名称</th><th>模式</th><th>JDBC 类型</th><th>输入值</th><th>NULL</th><th></th></tr></thead><tbody>${(t.parameters || []).map((p, i) => `<tr class="param-row"><td><input class="param-position" name="position" type="number" min="1" value="${p.position ?? i + 1}" aria-label="参数位置"></td><td><input name="name" value="${esc(p.name)}" aria-label="参数名称"></td><td><select class="param-mode" name="mode" aria-label="参数模式">${["IN", "OUT", "INOUT", "RETURN", ...(p.mode === "UNKNOWN" ? ["UNKNOWN"] : [])].map((m) => `<option ${p.mode === m ? "selected" : ""}>${m}</option>`).join("")}</select></td><td><input class="param-type" name="jdbcType" type="number" value="${Number(p.jdbcType ?? 12)}" title="${esc(p.typeName || "JDBC Types 数值")}" aria-label="JDBC 类型编号"></td><td><input name="value" value="${esc(p.value ?? "")}" ${p.mode === "OUT" || p.mode === "RETURN" ? "disabled" : ""} aria-label="参数输入值"></td><td><input name="isNull" type="checkbox" ${p.isNull ? "checked" : ""} aria-label="输入 NULL"></td><td>${button("", "remove-parameter", "close", "ghost icon-btn", `data-index="${i}" aria-label="移除此参数"`)}</td></tr>`).join("")}</tbody></table>${button("添加参数", "add-parameter", "plus", "ghost small")}<span class="muted" style="font-size:11px;margin-left:10px">按驱动返回的参数位置调用；JDBC 类型可手动调整。</span></details>${(t.routine?.warnings || []).map((w) => `<p class="muted" style="font-size:11px">${esc(w)}</p>`).join("")}</div>`;
}
function allResults(t) {
  const entries = [];
  settleTablePreview(t);
  const displayed = t.type === "table" && t.previewResult && (t.pendingPreview || t.previewFailure) ? t.previewResult : t.execution;
  (displayed?.statements || []).forEach((s, si) =>
    (s.results || []).forEach((r, ri) =>
      entries.push({ ...r, statement: s, statementIndex: si, resultIndex: ri }),
    ),
  );
  return entries;
}
function renderResults(t) {
  const el = $("#results");
  if (!el || tab() !== t) return;
  if (t.type === "table" && t.tableView === "structure") {
    el.innerHTML = `<div class="result-tabs"><span class="result-heading">表结构</span><span class="result-state">${esc(t.object.schema || t.object.catalog || "")}</span></div><div class="result-body">${t.structure ? renderStructure(t.structure) : '<div class="empty-state">正在读取字段、索引与外键…</div>'}</div>`;
    return;
  }
  const results = allResults(t),
    ex = t.execution;
  const labels = {
    RESULT_SET: "结果",
    UPDATE_COUNT: "更新计数",
    OUT_PARAMETERS: "输出参数",
    PLAN: "执行计划",
    MESSAGE: "消息",
  };
  if (typeof t.resultIndex === "number" && t.resultIndex >= results.length)
    t.resultIndex = 0;
  el.innerHTML = `<div class="result-tabs" role="tablist" aria-label="查询结果标签"><span class="result-heading">执行结果</span>${results.map((r, i) => `<button role="tab" aria-selected="${t.resultIndex === i}" class="${t.resultIndex === i ? "active" : ""}" data-action="result-tab" data-index="${i}">${esc(labels[r.kind] || r.kind)} ${i + 1}${r.kind === "UPDATE_COUNT" ? ` <span class="muted">(${r.updateCount})</span>` : r.kind === "OUT_PARAMETERS" ? ` <span class="muted">(${r.parameters?.length || 0})</span>` : r.rows ? ` <span class="muted">(${r.rows.length})</span>` : ""}</button>`).join("")}${ex ? `<button role="tab" aria-selected="${t.resultIndex === "messages"}" data-action="result-tab" data-index="messages" class="${t.resultIndex === "messages" ? "active" : ""}">消息${ex.state === "FAILED" ? " · 错误" : ""}</button>` : ""}<span class="result-state">${busy(t) ? '<i class="loading-dot"></i>' : ""}${t.type === "table" && t.previewFailure ? "本次读取失败" : t.pendingPreview && !t.pendingPreview.executionId ? "正在准备读取" : ex ? esc(stateLabels[ex.state] || ex.state) + " · " + duration(ex.elapsedMs) : "尚未执行"}</span></div>${t.type === "table" && t.previewFailure ? `<div class="filter-failure" role="alert">${esc(t.previewFailure)}${t.previewResult ? " · 当前显示上次成功结果，仅供查看；请重新读取后编辑。" : ""}</div>` : ""}<div id="result-body" class="result-body"></div>`;
  const body = $("#result-body");
  if (t.localError && !busy(t) && !(t.type === "table" && t.previewResult)) {
    body.innerHTML = `<div class="result-message"><div class="message-row error">${icon("alert")}<div><strong>本次执行未能提交</strong><p>${esc(t.localError)}</p><p class="muted">请修正问题后重新执行。</p>${button(ex ? "查看上一次执行结果" : "返回工作区", "dismiss-execution-error", null, "small")}</div></div></div>`;
    return;
  }
  if (t.resultIndex === "messages") {
    body.innerHTML = renderMessages(t);
    return;
  }
  if (results.length) {
    const r = results[t.resultIndex];
    if (r.kind === "UPDATE_COUNT") {
      body.innerHTML = `<div class="empty-state">${icon("check")}<h2>${Number(r.updateCount ?? 0)} 行受影响</h2><p>语句 ${r.statementIndex + 1} 执行完成。${t.session?.autoCommit === false ? "当前为手动提交模式，请按需提交或回滚。" : "请以数据库返回的事务状态为准。"}</p></div>`;
    } else if (r.kind === "OUT_PARAMETERS") {
      const params = r.parameters || [];
      body.innerHTML =
        dataTable(
          [
            { label: "位置" },
            { label: "参数" },
            { label: "模式" },
            { label: "值" },
          ],
          params.map((p) => [p.position, p.name, p.mode, p.value]),
          "out",
        ) +
        `<div class="table-footer">${params.length} 个输出参数 · 双击单元格查看完整值</div>`;
    } else if (r.kind === "PLAN") {
      body.innerHTML = renderPlan(r, t);
    } else {
      body.innerHTML =
        dataTable(r.columns || [], r.rows || [], String(t.resultIndex)) +
        tableFooter(t, r);
    }
  } else if (ex) {
    body.innerHTML = busy(t)
      ? `<div class="empty-state"><i class="loading-dot"></i><h2 style="margin-top:13px">${esc(stateLabels[ex.state] || "准备执行")}</h2><p>同一标签中的语句按顺序执行，结果将在完成后显示。</p>${button("请求取消", "cancel", "stop", "small", !ex.id ? "disabled" : "")}</div>`
      : renderMessages(t);
  } else {
    body.innerHTML = `<div class="empty-state">${icon("table")}<h2>${state.connections.length ? "准备好开始查询" : "从一个数据库连接开始"}</h2><p>${state.connections.length ? "执行 SQL 后，结果、更新计数与输出参数会分别显示在这里。每个编辑标签拥有独立会话。" : "选择内置驱动连接数据库，或用 H2 内存库即开即试。在一个工作区浏览表、执行 SQL、调用存过和查看执行计划。"}</p>${!state.connections.length ? button(state.drivers.length ? "新建数据库连接" : "导入 JDBC 驱动", state.drivers.length ? "new-connection" : "drivers", state.drivers.length ? "plus" : "driver", "primary") : ""}<div class="initial-guide"><div class="guide-step">${icon("driver")}<strong>自主选择驱动</strong><span>内置常用驱动，也可导入 JAR</span></div><div class="guide-step">${icon("code")}<strong>完整执行代码块</strong><span>选区、匿名块和多语句脚本</span></div><div class="guide-step">${icon("branch")}<strong>查看真实执行计划</strong><span>估算计划与实际分析分开执行</span></div></div></div>`;
  }
}
function dataTable(columns, rows, key = "result") {
  return `<div class="data-scroll"><table class="data-table"><thead><tr><th class="row-number">#</th>${columns.map((c, i) => `<th title="列 ${i + 1} · ${esc(c.typeName || "")}">${esc(c.label ?? c.name ?? `列 ${i + 1}`)}<small>${esc(c.typeName || "")}</small><span class="column-resize" role="separator" aria-orientation="vertical" tabindex="0" aria-label="调整第 ${i + 1} 列宽度"></span></th>`).join("")}</tr></thead><tbody>${rows
    .map(
      (row, ri) =>
        `<tr><td class="row-number">${ri + 1}</td>${columns
          .map((c, ci) => {
            const v = row[ci];
            return `<td tabindex="0" data-cell="true" data-result="${key}" data-row="${ri}" data-column="${ci}" class="${v === null || v === undefined ? "null" : v === "" ? "empty-string" : ""}" title="双击或按 Enter 查看 / 编辑单元格">${v == null ? "NULL" : v === "" ? "(空字符串)" : esc(formatValue(v))}</td>`;
          })
          .join("")}</tr>`,
    )
    .join(
      "",
    )}${!rows.length ? `<tr><td colspan="${columns.length + 1}" class="muted" style="text-align:center;padding:24px">查询成功，返回 0 行</td></tr>` : ""}</tbody></table></div>`;
}
function tableFooter(t, r) {
  return `<div class="table-footer"><span>${r.rows?.length || 0} 行 · ${r.columns?.length || 0} 列</span>${r.truncated ? '<span class="warning">结果已截断，达到资源上限</span>' : ""}<span class="muted">单击选中 · 双击 / Enter 查看或编辑</span>${t.type === "table" ? button("编辑选中格", "edit-selected-cell", "save", "small", "disabled") : ""}<span class="spacer"></span>${t.type === "table" ? `${button("上一页", "table-prev", null, "small", (t.offset || 0) === 0 || busy(t) || filtersDirty(t) || !!t.previewFailure ? "disabled" : "")}<span>第 ${Math.floor((t.offset || 0) / 200) + 1} 页</span>${button("下一页", "table-next", null, "small", (r.rows?.length || 0) < 200 || busy(t) || filtersDirty(t) || !!t.previewFailure || r.paginationSupported === false || !["MYSQL", "POSTGRESQL", "GAUSSDB", "H2"].includes(t.session?.dialect) ? "disabled" : "")}` : button("复制表格", "copy-results", "copy", "ghost small")}</div>${t.type === "table" ? `<div class="table-footer">${esc(r.readOnlyReason || "支持编辑非主键普通字段；保存前核对原值。分页会重新查询，并发修改时可能出现重复或遗漏。")}</div>` : ""}`;
}
function formatValue(value) {
  if (typeof value === "object") return JSON.stringify(value, null, 2);
  return String(value);
}
function renderMessages(t) {
  const ex = t.execution;
  if (!ex) return "";
  return `<div class="result-message"><div class="message-row ${["FAILED", "OUTCOME_UNKNOWN", "TIMED_OUT"].includes(ex.state) ? "error" : ""}">${icon(ex.state === "SUCCEEDED" ? "check" : busy(t) ? "clock" : "info")}<div><strong>${esc(stateLabels[ex.state] || ex.state)}</strong> · ${duration(ex.elapsedMs)}${ex.message ? `<div>${esc(ex.message)}</div>` : ""}${ex.state === "OUTCOME_UNKNOWN" ? "<div>数据库最终执行结果无法确认。请先核对数据库状态，再决定后续操作。</div>" : ""}</div></div>${(
    ex.statements || []
  )
    .map(
      (s, i) =>
        `<div class="message-row ${s.error ? "error" : ""}">${icon(s.error ? "alert" : "file")}<div><strong>语句 ${i + 1}</strong> · 第 ${(s.startLine || 1) + (t.sourceLineOffset || 0)}–${(s.endLine || s.startLine || 1) + (t.sourceLineOffset || 0)} 行 · ${esc(stateLabels[s.state] || s.state || "")}<pre class="statement-sql">${esc(s.sql)}</pre>${s.error ? `<div>${esc(s.error.message)}</div><small>SQLState: ${esc(s.error.sqlState || "—")} · 数据库错误码: ${esc(s.error.vendorCode ?? "—")}</small>${button("定位语句", "locate-statement", null, "small", `data-index="${i}"`)}` : ""}${(s.warnings || []).map((w) => `<div class="warn">${esc(typeof w === "string" ? w : w.message || JSON.stringify(w))}</div>`).join("")}${(
          s.results || []
        )
          .flatMap((r) => r.warnings || [])
          .map((w) => `<div class="warn">${esc(w)}</div>`)
          .join("")}</div></div>`,
    )
    .join("")}</div>`;
}
function duration(ms) {
  return ms >= 1000
    ? `${(ms / 1000).toFixed(2)} 秒`
    : `${Math.round(ms || 0)} ms`;
}
function renderStructure(s) {
  return `<div class="structure-section">${(s.warnings || []).map((w) => `<p class="muted">${esc(w)}</p>`).join("")}<h3>字段 · ${(s.columns || []).length}</h3>${objectTable(s.columns || [], "columns")}<h3>索引</h3>${objectTable(s.indexes || [], "indexes")}<h3>外键</h3>${objectTable(s.foreignKeys || [], "foreignKeys")}</div>`;
}
const columnNames = {
  type: "类型",
  scale: "小数位",
  direction: "排序",
  referencedCatalog: "引用 Catalog",
  referencedSchema: "引用 Schema",
  name: "名称",
  columnName: "字段",
  typeName: "类型",
  jdbcType: "JDBC 类型",
  size: "长度",
  columnSize: "长度",
  decimalDigits: "小数位",
  nullable: "可空",
  defaultValue: "默认值",
  remarks: "备注",
  primaryKey: "主键",
  position: "位置",
  ordinalPosition: "位置",
  indexName: "索引",
  nonUnique: "非唯一",
  unique: "唯一",
  column: "字段",
  columns: "字段",
  ascOrDesc: "排序",
  fkName: "外键",
  pkTableName: "引用表",
  pkColumnName: "引用字段",
  fkColumnName: "字段",
  keySeq: "序号",
  updateRule: "更新规则",
  deleteRule: "删除规则",
  table: "表",
  schema: "Schema",
  catalog: "Catalog",
  referencedTable: "引用表",
  referencedColumn: "引用字段",
};
function objectTable(items, key) {
  if (!items.length)
    return '<p class="muted" style="font-size:12px">驱动未返回该类元数据。</p>';
  const keys = [...new Set(items.flatMap((x) => Object.keys(x)))];
  return dataTable(
    keys.map((k) => ({ label: columnNames[k] || k })),
    items.map((x) =>
      keys.map((k) =>
        x[k] === undefined
          ? null
          : typeof x[k] === "boolean"
            ? x[k]
              ? "是"
              : "否"
            : typeof x[k] === "object" && x[k] !== null
              ? JSON.stringify(x[k])
              : x[k],
      ),
    ),
    `structure-${key}`,
  );
}
function parsePlan(r) {
  try {
    const raw = r.rows?.[0]?.[0];
    if (typeof raw === "object") return raw;
    return JSON.parse(raw);
  } catch (e) {
    return null;
  }
}
function renderPlan(r, t) {
  const parsed = parsePlan(r),
    tree = Array.isArray(parsed)
      ? parsed[0]?.Plan || parsed[0]
      : parsed?.Plan || parsed;
  const canTree =
    tree &&
    typeof tree === "object" &&
    ("Node Type" in tree || "Plans" in tree);
  return `${canTree ? `<div class="plan-toggle">${button("计划树", "plan-tree", null, t.planMode !== "raw" ? "selected small" : "small")}${button("原始输出", "plan-raw", null, t.planMode === "raw" ? "selected small" : "small")}<span class="muted" style="padding:4px 8px;font-size:11px">cost 为数据库估算成本；实际耗时仅在数据库返回时显示。</span></div>` : ""}${canTree && t.planMode !== "raw" ? `<div class="plan-view">${planNode(tree)}</div>` : dataTable(r.columns || [], r.rows || [], String(t.resultIndex))}${!canTree ? '<div class="table-footer">展示数据库原始计划输出；此格式尚未转换为计划树。</div>' : ""}`;
}
function planNode(node) {
  return `<div class="plan-node"><strong>${esc(node["Node Type"] || "Plan")}${node["Relation Name"] ? " · " + esc(node["Relation Name"]) : ""}</strong><div class="plan-metrics">${node["Total Cost"] !== undefined ? `<span>估算 cost ${esc(node["Total Cost"])}</span>` : ""}${node["Plan Rows"] !== undefined ? `<span>估算行数 ${esc(node["Plan Rows"])}</span>` : ""}${node["Actual Total Time"] !== undefined ? `<span>实际 ${esc(node["Actual Total Time"])} ms</span>` : ""}${node["Actual Rows"] !== undefined ? `<span>实际行数 ${esc(node["Actual Rows"])}</span>` : ""}</div>${node.Filter ? `<code>Filter: ${esc(node.Filter)}</code>` : ""}${(node.Plans || []).map(planNode).join("")}</div>`;
}
function renderStatus() {
  const t = tab(),
    el = $("#status-bar");
  if (!el) return;
  const s = t?.session;
  el.innerHTML = `<span>${icon("database")}${esc(connection(t?.connectionId)?.name || "未选择连接")}</span><span class="session-status">${s ? esc(stateLabels[s.state] || s.state) : "会话未建立"}</span><span class="${s?.autoCommit === false ? "warning" : ""}">${s?.autoCommit === false ? "手动提交" : "自动提交"}</span><span class="spacer"></span>${t?.pollError ? `<span class="warning">${esc(t.pollError)}</span>` : ""}${t?.execution ? `<span class="status-message">${esc(stateLabels[t.execution.state] || t.execution.state)}</span><span>${icon("clock")}${duration(t.execution.elapsedMs)}</span>` : ""}<span class="optional">结果不落盘 · SQL 草稿按连接设置保存</span>`;
}
async function ensureSession(t) {
  if (t.session && ["BROKEN", "CLOSED"].includes(t.session.state))
    throw new Error("当前会话已失效，请先断开，再重新执行。");
  if (t.session) return t.session;
  if (!t.connectionId) throw new Error("请先选择数据库连接。");
  t.session = await api("/sessions", {
    method: "POST",
    body: {
      connectionId: t.connectionId,
      schema: t.schema || null,
      catalog: t.catalog || null,
    },
  });
  syncSessionContext(t);
  return t.session;
}
function syncSessionContext(t) {
  if (!t.session) return;
  t.schema = t.session.schema || "";
  t.catalog = t.session.catalog || "";
}
async function disconnect(t) {
  if (!t.session) return;
  if (busy(t)) throw new Error("请先取消正在执行的语句。");
  if (
    t.session.autoCommit === false &&
    !confirm("断开会话将回滚未提交事务。继续断开？")
  )
    return;
  await api(`/sessions/${t.session.id}`, { method: "DELETE" });
  abortRead(`poll:${t.id}`);
  t.pollVersion = (t.pollVersion || 0) + 1;
  t.session = null;
  if (t === tab()) {
    rememberEditor();
    render();
  }
}
async function execute(mode, extra = {}, t = tab()) {
  if (!t || busy(t)) return;
  if (t === tab()) rememberEditor();
  const sourceSql = t.sql;
  const request = {
    mode,
    sql: t.sql,
    maxRows: t.maxRows || 500,
    timeoutSeconds: t.timeoutSeconds || 60,
    ...extra,
  };
  const sourceLineOffset =
    ["SQL", "BLOCK", "EXPLAIN"].includes(mode) &&
    t.selection[0] !== t.selection[1]
      ? t.sql.slice(0, t.selection[0]).split("\n").length - 1
      : 0;
  if (mode === "SQL") {
    const [start, end] = t.selection;
    if (start === end) throw new Error("请先在编辑器中选择要执行的 SQL。");
    request.sql = t.sql.slice(start, end);
  }
  if (mode === "CURRENT" || mode === "EXPLAIN")
    request.cursorOffset = t.selection[0];
  if (mode === "EXPLAIN" && t.selection[0] !== t.selection[1]) {
    request.sql = t.sql.slice(...t.selection);
    request.cursorOffset = 0;
  }
  if (mode === "BLOCK" && t.selection[0] !== t.selection[1])
    request.sql = t.sql.slice(...t.selection);
  if (mode === "CALL") {
    request.parameters = t.parameters || [];
    if (
      request.parameters.some(
        (p) => !["IN", "OUT", "INOUT", "RETURN"].includes(p.mode),
      )
    )
      throw new Error("请为所有参数明确选择 IN、OUT、INOUT 或 RETURN 模式。");
  }
  if (mode !== "TABLE_PREVIEW" && !request.sql.trim())
    throw new Error("请先输入 SQL。");
  t.selectedCell = null;
  t.localError = null;
  t.submitting = true;
  if (t === tab()) render();
  try {
    const session = await ensureSession(t);
    request.sessionId = session.id;
    const prepared = await api("/executions/prepare", {
      method: "POST",
      body: request,
    });
    request.confirmationToken = prepared.confirmationToken;
    if (prepared.units?.length) t.preparedUnits = prepared.units;
    if (mode === "TABLE_PREVIEW" && prepared.units?.length) {
      t.pendingPreview.sql = prepared.units.map((x) => x.sql).join("\n");
      t.pendingPreview.bindings = (request.filters || [])
        .filter((f) => !["IS NULL", "IS NOT NULL"].includes(f.operator))
        .map((f) => f.value);
      if (["MYSQL", "POSTGRESQL", "GAUSSDB", "H2"].includes(session.dialect))
        t.pendingPreview.bindings.push(request.limit, request.offset);
    }
    if (prepared.confirmationRequired) {
      const ranges = (prepared.units || [])
        .map(
          (u, i) =>
            `${i + 1}. 第 ${(u.startLine || 1) + sourceLineOffset}–${(u.endLine || u.startLine || 1) + sourceLineOffset} 行\n${u.sql.length > 350 ? u.sql.slice(0, 350) + "…" : u.sql}`,
        )
        .join("\n\n");
      if (
        !confirm(
          `${prepared.message || "以下语句可能修改数据、调用过程或实际执行分析。请确认执行范围。"}\n\n连接：${connection(t.connectionId)?.name || ""}\n模式：${mode}\n${ranges}\n\n确认执行？`,
        )
      )
        return;
      request.confirmationToken = prepared.confirmationToken;
    }
    request.requestId = uid();
    t.resultIndex = 0;
    const previousExecution = t.execution;
    t.execution = await api("/executions", { method: "POST", body: request });
    t.sourceLineOffset = sourceLineOffset;
    t.executionSourceSql = sourceSql;
    if (previousExecution?.id && !activeStates.has(previousExecution.state))
      api(`/executions/${previousExecution.id}`, { method: "DELETE" }).catch(
        () => {},
      );
    if (!t.execution.id && t.execution.executionId)
      t.execution.id = t.execution.executionId;
    if (mode === "TABLE_PREVIEW") t.pendingPreview.executionId = t.execution.id;
    if (!activeStates.has(t.execution.state)) {
      try {
        t.session = await api(`/sessions/${session.id}`);
        syncSessionContext(t);
      } catch (e) {
        t.session.state = "BROKEN";
      }
    }
    if (mode === "TABLE_PREVIEW") {
      t.tableView = "data";
      settleTablePreview(t);
    }
    if (t === tab()) renderExecutionChange(t);
    startPolling();
  } catch (e) {
    t.localError = e.message;
    throw e;
  } finally {
    t.submitting = false;
    if (t === tab()) renderExecutionChange(t);
  }
}
function startPolling() {
  if (pollTimer || polling) return;
  pollTimer = setTimeout(pollExecutions, 350);
}
async function pollExecutions() {
  pollTimer = null;
  if (polling) return;
  polling = true;
  try {
    const pending = state.tabs.filter((t) => !t.cellSaving && !t.cancelInFlight && t.execution?.id && (activeStates.has(t.execution.state) || t.recoveryWaiting));
    await Promise.all(pending.map(async (t) => {
      const id = t.execution.id, sessionId = t.session?.id, version = t.pollVersion || 0;
      const key = `poll:${t.id}`, scope = beginRead(key);
      const current = () => scope.current() && state.tabs.includes(t) && t.execution?.id === id && t.session?.id === sessionId && (t.pollVersion || 0) === version;
      try {
        const execution = await api(`/executions/${id}`, { signal: scope.controller.signal });
        if (!current()) return;
        const wasBroken = t.session?.state === "BROKEN";
        t.execution = execution; t.pollError = null;
        if (sessionId) {
          try {
            const session = await api(`/sessions/${sessionId}`, { signal: scope.controller.signal });
            if (!current()) return;
            t.session = session; t.recoveryWaiting = session.recoveryPending && !session.resourceReleased; syncSessionContext(t);
          } catch (e) {
            if (!current()) return;
            if (e.name === "AbortError") throw e;
            if (t.session) t.session.state = "BROKEN";
          }
        }
        if (t.execution.state === "FAILED" && !allResults(t).length) t.resultIndex = "messages";
        if (t === tab()) {
          if (!activeStates.has(t.execution.state) || wasBroken !== (t.session?.state === "BROKEN")) renderExecutionChange(t);
          else { renderResults(t); renderStatus(); }
        }
      } catch (e) {
        if (!current()) return;
        // A failed read does not stop JDBC or authorize a new write; retain the active state.
        t.pollError = "暂时无法读取状态，正在重试；数据库操作可能仍在执行";
        if (t === tab()) renderStatus();
      } finally { finishRead(key, scope); }
    }));
  } finally {
    polling = false;
    if (state.tabs.some((t) => activeStates.has(t.execution?.state) || t.recoveryWaiting)) startPolling();
  }
}
function renderExecutionChange(t) {
  if (t !== tab()) return;
  const editor = $("#sql-editor");
  const focused = document.activeElement === editor;
  rememberEditor();
  // Update only this tab, preserving the navigation tree and the editor node/focus/selection.
  renderTab(t);
  if (editor && $("#sql-editor")) {
    $("#sql-editor").replaceWith(editor);
    editorResize.disconnect();
    editorResize.observe(editor);
    if (focused) editor.focus({ preventScroll: true });
    updateHighlight();
  }
  renderStatus();
  const tabButton = $$('[data-action="select-tab"]').find((b) => b.dataset.id === t.id);
  if (!busy(t)) tabButton?.querySelector(".loading-dot")?.remove();
}
async function recoverSession(t) {
  if (!t?.session || t.recovering) return;
  const oldId = t.session.id;
  t.recovering = true;
  try {
    const session = await api(`/sessions/${oldId}/recover`, { method: "POST" });
    if (!state.tabs.includes(t) || t.session?.id !== oldId) return;
    t.session = session; t.recoveryWaiting = !session.resourceReleased;
    if (!session.resourceReleased || activeStates.has(t.execution?.state)) {
      toast("会话资源尚未释放，请等待后重试。数据库结果仍需核实。", true);
      startPolling(); return;
    }
    if (!confirm("旧会话已关闭。重新连接不会恢复事务或重跑 SQL；请自行核实旧操作结果。继续？")) return;
    abortRead(`poll:${t.id}`); t.pollVersion = (t.pollVersion || 0) + 1;
    t.session = null;
    await ensureSession(t);
    toast("新会话已建立；没有重新执行旧 SQL。");
  } finally { t.recovering = false; if (t === tab()) renderExecutionChange(t); }
}
async function transaction(action, autoCommit) {
  const t = tab();
  if (!t || busy(t)) return;
  rememberEditor();
  t.transactionPending = true;
  renderExecutionChange(t);
  try {
    const session = await ensureSession(t);
    if (action === "AUTO_COMMIT" && autoCommit && session.autoCommit === false &&
        !confirm("切换为自动提交会先回滚当前未提交事务。确认切换？")) return;
    if (t.type === "table") t.previewStale = true;
    t.session = await api(`/sessions/${session.id}/transaction`, {method:"POST", body:{action, autoCommit}});
    syncSessionContext(t);
    toast(action === "COMMIT" ? "事务已提交" : action === "ROLLBACK" ? "事务已回滚" : autoCommit ? "已启用自动提交" : "已切换为手动提交");
    // Transfer the busy state directly to the refresh, without exposing an editable stale grid.
    t.transactionPending = false;
    if (t.type === "table" && t.tableView === "data") await readTable(t, t.offset);
  } finally {
    t.transactionPending = false;
    if (t === tab()) renderExecutionChange(t);
  }
}
async function driversDialog() {
  state.drivers = list(await api("/drivers"));
  const d = showDialog(
    "JDBC 驱动管理",
    `<p class="form-note">已内置 MySQL、PostgreSQL 和 H2 驱动。需要其他数据库或驱动版本时，可自主导入兼容的纯 Java JDBC 驱动；主驱动与依赖包支持多选上传。</p><div class="driver-list">${state.drivers.map((p) => `<div class="driver-item">${icon("driver")}<div class="driver-details"><strong>${esc(p.name)}</strong>${p.bundled ? '<span class="driver-badge">内置</span>' : ""}${p.version ? `<span class="driver-version">${esc(p.version)}</span>` : ""}<code>${esc(p.driverClass || "尚未选择驱动类")}</code><div class="driver-files">${esc((p.files || p.jarFiles || []).map((x) => (typeof x === "string" ? x : x.name || x.fileName || "")).join(" · "))}</div>${p.sha256 ? `<details><summary class="muted" style="font-size:10px;cursor:pointer">SHA-256 校验摘要</summary><code>${esc(typeof p.sha256 === "string" ? p.sha256 : JSON.stringify(p.sha256))}</code></details>` : ""}</div>${p.bundled ? "" : `${button("驱动类", "driver-class", null, "small", `data-id="${p.id}"`)}${button("", "delete-driver", "trash", "ghost icon-btn danger", `data-id="${p.id}" aria-label="删除驱动 ${esc(p.name)}"`)}`}</div>`).join("") || '<p class="muted" style="font-size:12px">尚未导入驱动。</p>'}</div><h3 class="section-title">导入新的驱动</h3><form id="driver-form"><div class="form-grid">${field("驱动名称", "driver-name", "", "text", "例如 PostgreSQL 42.x")}${field("驱动类（可选）", "driver-class", "", "text", "留空自动读取 JDBC Service 声明")}<div class="form-field wide"><label for="driver-files">驱动与依赖 JAR</label><input id="driver-files" name="files" type="file" multiple accept=".jar" required><small>驱动会在本机执行；请使用可信来源提供的 JAR 文件。</small></div></div><div id="driver-feedback" class="form-feedback" role="status"></div></form>`,
    `${button("关闭", "dialog-close", null)}${button("导入驱动", "import-driver", "plus", "primary")}`,
    true,
  );
  return d;
}
async function importDriver() {
  const form = $("#driver-form"),
    files = $("#driver-files").files;
  if (!files.length) throw new Error("请选择至少一个 JAR 文件。");
  const data = new FormData();
  for (const file of files) data.append("files", file);
  data.append(
    "name",
    $("[name=driver-name]", form).value.trim() ||
      files[0].name.replace(/\.jar$/i, ""),
  );
  const cls = $("[name=driver-class]", form).value.trim();
  if (cls) data.append("driverClass", cls);
  const btn = $("[data-action=import-driver]");
  btn.disabled = true;
  $("#driver-feedback").textContent = "正在复制驱动文件并检测 JDBC 驱动类…";
  try {
    const p = await api("/drivers/import", { method: "POST", body: data });
    state.drivers = list(await api("/drivers"));
    if (!p.driverClass) {
      await chooseDriverClass(p);
    } else {
      await driversDialog();
      toast("驱动已导入，可用于新建数据库连接。");
      renderTree();
    }
  } catch (e) {
    $("#driver-feedback").innerHTML =
      `<span class="error-text">${esc(e.message)}</span>`;
    throw e;
  } finally {
    if (btn.isConnected) btn.disabled = false;
  }
}
async function chooseDriverClass(profile) {
  const candidates = profile.candidates || [];
  showDialog(
    "选择 JDBC 驱动类",
    `<p class="form-note">${esc(profile.name)} · ${candidates.length ? "检测到以下候选类，也可以手动指定。" : "未发现 JDBC Service 声明，请填写数据库驱动文档提供的完整类名。"}</p><form id="driver-class-form"><div class="form-field"><label for="driver-class-value">驱动类全名</label><input id="driver-class-value" list="driver-candidates" value="${esc(profile.driverClass || candidates[0] || "")}" placeholder="例如 org.postgresql.Driver"><datalist id="driver-candidates">${candidates.map((c) => `<option value="${esc(c)}"></option>`).join("")}</datalist><input name="id" type="hidden" value="${profile.id}"></div><div class="form-feedback" id="class-feedback" role="status"></div></form>`,
    `${button("返回", "drivers", null)}${button("保存驱动类", "save-driver-class", "check", "primary")}`,
  );
}
let demoOpening = false;
async function openDemo() {
  if (demoOpening) return;
  demoOpening = true;
  try {
    const demo = await api("/connections/demo", {method: "POST"});
    state.connections = list(await api("/connections"));
    state.selectedConnection = demo.id;
    state.expanded.add(demo.id);
    // A new tab protects any existing text, draft, selection and transaction.
    newTab({name:"H2 演示 · SELECT 1", connectionId:demo.id, sql:"SELECT 1;"});
    await execute("SCRIPT", {sql:"SELECT 1;"});
    loadTree(demo.id, true);
    toast("已打开 H2 内存演示。应用退出后数据清空；连接配置会保留。这里只执行固定 SELECT 1。");
  } finally { demoOpening = false; }
}
async function connectionDialog(id) {
  const c = id ? connection(id) : {};
  if (!state.drivers.length) {
    await driversDialog();
    toast("当前没有可用驱动，请导入 JDBC 驱动后创建连接。");
    return;
  }
  showDialog(
    id ? "编辑数据库连接" : "新建数据库连接",
    `<p class="form-note">连接信息仅保存在本机。MySQL / PostgreSQL 可填写地址与库名；复杂连接使用完整 URL。H2 演示数据在应用退出后清空。</p><form id="connection-form"><input name="id" type="hidden" value="${esc(c.id || "")}"><div class="form-grid">${field("连接名称", "name", c.name || "")}<div class="form-field"><label for="f-driverId">JDBC 驱动</label><select id="f-driverId" name="driverId" required>${options(
      state.drivers.map((p) => ({
        ...p,
        name: `${p.name}${p.version ? ` ${p.version}` : ""}${p.bundled ? " · 内置" : ""}`,
      })),
      c.driverId || "",
      "选择 JDBC 驱动",
    )}</select></div><div class="form-field wide"><label for="connection-mode">连接方式</label><select id="connection-mode"><option value="basic">地址与库名</option><option value="url">完整 JDBC URL</option></select><small id="connection-mode-hint"></small></div><div id="connection-basic" class="form-grid wide" hidden><div class="form-field"><label for="basic-host">主机地址</label><input id="basic-host" autocomplete="off"></div><div class="form-field"><label for="basic-port">端口</label><input id="basic-port" inputmode="numeric"></div><div class="form-field wide"><label for="basic-database">数据库名称</label><input id="basic-database" autocomplete="off"></div></div>${field("完整 JDBC URL", "jdbcUrl", c.jdbcUrl || "", "text", "切换驱动会保留已填写的 URL，请确认它与所选驱动一致。", true)}${field("用户名", "username", c.username || "")}${field("密码", "password", "", "password", id ? "留空保留已保存密码" : "密码加密保存在本机")}<details class="wide connection-advanced"><summary>高级设置：方言、命名空间与 JDBC 属性</summary><div class="form-grid"><div class="form-field"><label for="f-dialectHint">SQL 方言</label><select id="f-dialectHint" name="dialectHint">${[
      { id: "AUTO", name: "自动识别" },
      { id: "GENERIC", name: "通用 JDBC" },
      { id: "MYSQL", name: "MySQL" },
      { id: "POSTGRESQL", name: "PostgreSQL" },
      { id: "GAUSSDB", name: "GaussDB" },
      { id: "ORACLE", name: "Oracle" },
    ]
      .map(
        (x) =>
          `<option value="${x.id}" ${(c.dialectHint || "AUTO").toUpperCase() === x.id ? "selected" : ""}>${x.name}</option>`,
      )
      .join(
        "",
      )}</select><small>方言决定代码块、分页和 EXPLAIN 的生成规则。</small></div><div class="form-field"><label>已保存凭据</label><label class="check-label"><input name="clearPassword" type="checkbox">清除已保存密码</label><small>修改密码时，在密码框中输入新密码。</small></div>${field("默认 catalog（可选）", "catalog", c.catalog || "")}${field("默认 schema（可选）", "schema", c.schema || "")}<div class="form-field wide"><label for="f-properties">JDBC 扩展属性（JSON 对象）</label><textarea id="f-properties" name="properties" class="mono" spellcheck="false">${esc(JSON.stringify(c.properties || {}, null, 2))}</textarea><small>例如 { "connectTimeout": "10" }；用户名与密码使用上方专用字段；&lt;saved&gt; 表示保留已有值。</small></div></div></details><div class="form-field wide draft-setting"><label class="check-label"><input name="saveSqlDrafts" type="checkbox" ${c.saveSqlDrafts ? "checked" : ""} aria-describedby="draft-privacy">保存此连接的 SQL 草稿（可选）</label><small id="draft-privacy">默认关闭。开启后，SQL 标签的文本和名称会自动加密保存到本机，刷新后可恢复。SQL 可能含口令、个人信息或业务数据；拥有本机账户与密钥的人仍可读取。结果、调用参数和事务状态不保存，也不会自动执行。关闭此选项会清除该连接的已存草稿；下载文件和备份需自行管理。</small></div></div><div id="connection-feedback" class="form-feedback" role="status"></div></form>`,
    `${id ? button("删除连接", "delete-connection", "trash", "danger", `data-id="${id}"`) : ""}<span class="spacer"></span>${button("测试连接", "test-connection", "link")}${button("取消", "dialog-close", null)}${button("保存连接", "save-connection", "check", "primary")}`,
    true,
  );
  const form = $("#connection-form");
  installConnectionFields(form, () => state.drivers.find(p => p.id === $("#f-driverId", form).value));
}
function fillDriverDefaults() {
  const form = $("#connection-form");
  if (!form) return;
  const profile = state.drivers.find(
    (p) => p.id === $("#f-driverId", form).value,
  );
  if (!profile) return;
  const url = $("#f-jdbcUrl", form),
    username = $("#f-username", form);
  if (!$("[name=id]", form).value) {
    if (!url.value.trim() && profile.urlTemplate) url.value = profile.urlTemplate;
    if (!username.value.trim() && profile.driverClass === "org.h2.Driver") username.value = "sa";
  }
  form.configureConnectionFields();
}
function connectionPayload() {
  const form = $("#connection-form");
  form.syncConnectionUrl();
  const data = Object.fromEntries(new FormData(form));
  data.clearPassword = $("[name=clearPassword]", form).checked;
  data.saveSqlDrafts = $("[name=saveSqlDrafts]", form).checked;
  try {
    data.properties = JSON.parse(data.properties || "{}");
  } catch (e) {
    throw new Error("JDBC 扩展属性必须是有效的 JSON 对象。");
  }
  if (
    !data.properties ||
    Array.isArray(data.properties) ||
    typeof data.properties !== "object"
  )
    throw new Error("JDBC 扩展属性必须是 JSON 对象。");
  if (!data.name.trim()) throw new Error("请填写连接名称。");
  if (!data.driverId) throw new Error("请选择 JDBC 驱动。");
  if (!data.jdbcUrl.trim().startsWith("jdbc:"))
    throw new Error("请填写以 jdbc: 开头的完整连接 URL。");
  if (!data.id) delete data.id;
  if (!data.password) delete data.password;
  return data;
}
async function saveConnection(test = false) {
  const feedback = $("#connection-feedback");
  let payload;
  try {
    payload = connectionPayload();
  } catch (e) {
    feedback.innerHTML = `<span class="error-text">${esc(e.message)}</span>`;
    return;
  }
  const previouslyEnabled = !!connection(payload.id)?.saveSqlDrafts;
  if (!test && payload.saveSqlDrafts !== previouslyEnabled && !confirm(payload.saveSqlDrafts
    ? "开启此连接的 SQL 草稿保存？SQL 可能包含口令、个人信息和业务数据。内容会加密保存在本机；拥有本机账户及密钥的人仍可读取。当前属于此连接的 SQL 标签也将保存。"
    : "关闭此连接的 SQL 草稿保存？已保存草稿将被清除，当前页面文本保留；下载文件和备份不受影响。")) return;
  rememberEditor();
  const btn = $(
    `[data-action=${test ? "test-connection" : "save-connection"}]`,
  );
  btn.disabled = true;
  feedback.textContent = test ? "正在测试连接…" : "正在保存连接…";
  try {
    const result = await api(test ? "/connections/test" : "/connections", {
      method: "POST",
      body: payload,
    });
    if (test) {
      if (result.success === false)
        throw new Error(result.message || "连接测试失败。");
      feedback.innerHTML = `<span class="success-text">连接成功</span> ${esc(result.productName || result.databaseProductName || result.product || "")} ${esc(result.productVersion || result.databaseProductVersion || result.version || "")}<br><span class="muted">${esc(result.message || "驱动已成功建立 JDBC 连接。")}</span>`;
    } else {
      state.connections = list(await api("/connections"));
      state.selectedConnection =
        result.id ||
        payload.id ||
        state.connections[state.connections.length - 1]?.id ||
        "";
      state.expanded.add(state.selectedConnection);
      if (!tab().connectionId) {
        tab().connectionId = state.selectedConnection;
        tab().schema = payload.schema || "";
        tab().catalog = payload.catalog || "";
        loadContext(tab());
      }
      if (payload.saveSqlDrafts !== previouslyEnabled) {
        if (payload.saveSqlDrafts) await drafts.load(state.selectedConnection);
        else drafts.forget(state.selectedConnection);
      }
      drafts.schedule();
      $("#dialog").close();
      render();
      loadTree(state.selectedConnection, true);
      toast("连接已保存。");
    }
  } catch (e) {
    feedback.innerHTML = `<span class="error-text">${esc(e.message)}</span>`;
  } finally {
    if (btn.isConnected) btn.disabled = false;
  }
}
async function openObject(connectionId, obj) {
  const existing = state.tabs.find(
    (t) =>
      t.type === obj.kind &&
      t.connectionId === connectionId &&
      t.object?.name === obj.name &&
      t.object?.schema === obj.schema &&
      t.object?.catalog === obj.catalog &&
      t.object?.specificName === obj.specificName,
  );
  if (existing) {
    rememberEditor();
    state.activeTab = existing.id;
    render();
    return;
  }
  state.selectedConnection = connectionId;
  const t = newTab({
    type: obj.kind,
    name: obj.name,
    connectionId,
    object: obj,
    schema: obj.schema || "",
    catalog: obj.catalog || "",
    loading: true,
    tableView: "data",
    offset: 0,
    parameters: [],
  });
  state.sidebarOpen = false;
  try {
    const q = new URLSearchParams({
      catalog: obj.catalog || "",
      schema: obj.schema || "",
    });
    if (obj.kind === "table") {
      q.set("table", obj.name);
      t.structure = await api(
        `/connections/${connectionId}/table-structure?${q}`,
      );
      t.loading = false;
      if (tab() === t) {
        render();
        await readTable(t);
      }
    } else {
      q.set("name", obj.name);
      q.set("type", obj.type || "");
      q.set("specificName", obj.specificName || "");
      t.routine = await api(`/connections/${connectionId}/routine-detail?${q}`);
      t.parameters = (t.routine.parameters || []).map((p) => ({
        ...p,
        mode: normalizeMode(p.mode),
        value: "",
        isNull: false,
      }));
      t.sql =
        t.routine.callSql || `-- 驱动未返回调用模板，请依据数据库语法填写\n`;
      t.loading = false;
      if (tab() === t) render();
    }
  } catch (e) {
    t.loading = false;
    t.localError = e.message;
    if (t === tab()) render();
    report(e);
  }
}
function normalizeMode(mode) {
  return typeof mode === "number"
    ? { 1: "IN", 2: "INOUT", 4: "OUT", 5: "RETURN" }[mode] || "IN"
    : String(mode || "IN").toUpperCase();
}
function draftFilters(t) {
  return {filters: (t.filterDraft || []).filter(f => f.column).map(f => ({...f, value:f.operator.includes("NULL") ? null : f.value})),
    filterMatch:t.filterMatch || "ALL", orderBy:t.orderBy || null, descending:!!t.descending};
}
function filtersDirty(t) {
  return JSON.stringify(draftFilters(t)) !== JSON.stringify(t.appliedFilters || {filters:[],filterMatch:"ALL",orderBy:null,descending:false});
}
function filterStatus(t) {
  const applied = t.appliedFilters;
  const summary = applied ? `已应用 ${applied.filters.length} 条 · ${applied.filterMatch === "ANY" ? "OR" : "AND"}` : "尚未读取";
  return (filtersDirty(t) ? "条件待应用 · " : "") + summary;
}
function captureTableFilters(t) {
  if (t !== tab() || !$("#filter-match")) return;
  t.filterDraft = $$("[data-filter-row]").map(row => ({column:$("[data-filter=column]",row).value,
    operator:$("[data-filter=operator]",row).value,value:$("[data-filter=value]",row).value}));
  t.filterMatch = $("#filter-match").value;
  t.orderBy = $("#order-column").value;
  t.descending = $("#order-desc").checked;
}
function filterChanged() {
  const t = tab(); captureTableFilters(t);
  $$("[data-filter-row]").forEach(row => { $("[data-filter=value]",row).disabled = $("[data-filter=operator]",row).value.includes("NULL"); });
  t.selectedCell = null;
  $("#filter-status").textContent = filterStatus(t);
  renderResults(t);
}
function settleTablePreview(t) {
  const pending = t.pendingPreview, ex = t.execution;
  if (!pending?.executionId || !ex || pending.executionId !== ex.id || activeStates.has(ex.state)) return;
  if (ex.state === "SUCCEEDED") {
    t.appliedFilters = pending.filters;
    t.offset = pending.offset;
    t.previewSql = pending.sql;
    t.previewBindings = pending.bindings;
    t.previewResult = ex;
    t.previewFailure = null;
    t.previewStale = false;
  } else {
    t.previewFailure = ex.message || stateLabels[ex.state] || "本次读取未完成";
  }
  t.pendingPreview = null;
}
async function readTable(t, offset, apply = false) {
  if (busy(t)) return;
  captureTableFilters(t);
  const filters = apply || !t.appliedFilters ? draftFilters(t) : t.appliedFilters;
  if (apply && (t.filterDraft || []).some(f => !f.column && f.value !== "")) throw new Error("请为填写了值的条件选择过滤列。");
  t.pendingPreview = {filters, offset:offset ?? 0};
  t.previewStale = true;
  t.previewFailure = null;
  t.tableView = "data";
  try {
    await execute("TABLE_PREVIEW", {sql:"", table:t.object.name, catalog:t.object.catalog || "", schema:t.object.schema || "",
      offset:offset ?? 0, limit:200, ...filters}, t);
  } catch (error) {
    t.pendingPreview = null;
    t.previewFailure = error.message;
    if (t === tab()) renderExecutionChange(t);
    throw error;
  }
}
async function closeTab(id) {
  const t = state.tabs.find((x) => x.id === id);
  if (!t) return;
  if (busy(t)) {
    toast("请先取消当前标签中正在运行的执行。", true);
    return;
  }
  rememberEditor();
  if (
    t.sql.trim() &&
    (t.savedSql !== t.sql || (t.type === "sql" && connection(t.connectionId)?.saveSqlDrafts)) &&
    !confirm(
      `关闭「${t.name}」？${t.type === "sql" && connection(t.connectionId)?.saveSqlDrafts ? "对应草稿也会删除；需要保留请先下载 SQL 文件。" : "SQL 文本不会自动保存。"}${t.session?.autoCommit === false ? "未提交事务将回滚。" : ""}`,
    )
  )
    return;
  if (
    t.session?.autoCommit === false &&
    !t.sql.trim() &&
    !confirm("关闭标签将回滚未提交事务。继续关闭？")
  )
    return;
  if (t.session) await api(`/sessions/${t.session.id}`, { method: "DELETE" });
  if (t.execution?.id && !activeStates.has(t.execution.state))
    await api(`/executions/${t.execution.id}`, { method: "DELETE" }).catch(
      () => {},
    );
  abortRead(`context:${t.id}`); abortRead(`poll:${t.id}`);
  state.tabs = state.tabs.filter((x) => x !== t);
  drafts.schedule();
  if (!state.tabs.length) newTab();
  else {
    if (state.activeTab === id)
      state.activeTab = state.tabs[state.tabs.length - 1].id;
    render();
  }
  if (tab()?.restoreContext) loadContext(tab());
}
function saveSql() {
  rememberEditor();
  const t = tab(),
    blob = new Blob([t.sql], { type: "text/plain;charset=utf-8" }),
    url = URL.createObjectURL(blob),
    a = document.createElement("a");
  a.href = url;
  a.download = `${t.name.replace(/[<>:"/\\|?*]/g, "_")}.sql`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  t.savedSql = t.sql;
  toast("已导出 SQL 文件。");
}
async function copyText(value) {
  if (navigator.clipboard?.writeText)
    await navigator.clipboard.writeText(value);
  else {
    const input = document.createElement("textarea");
    input.value = value;
    input.style.position = "fixed";
    input.style.left = "-9999px";
    document.body.append(input);
    input.select();
    if (!document.execCommand("copy")) {
      input.remove();
      throw new Error("浏览器未允许复制，请手动选择文本复制。");
    }
    input.remove();
  }
  toast("已复制到剪贴板。");
}
function cellDetail(cell) {
  const t = tab(),
    key = cell.dataset.result,
    ri = Number(cell.dataset.row),
    ci = Number(cell.dataset.column);
  let value, label;
  if (/^\d+$/.test(key)) {
    selectCell(cell);
    if (t.type === "table") return editCellDialog(t, Number(key), ri, ci);
  }
  if (key.startsWith("structure-")) {
    const items = t.structure[key.slice(10)] || [],
      keys = [...new Set(items.flatMap((x) => Object.keys(x)))];
    value = items[ri]?.[keys[ci]];
    label = columnNames[keys[ci]] || keys[ci];
  } else if (key === "out") {
    const p = allResults(t)[t.resultIndex]?.parameters?.[ri];
    value = [p?.position, p?.name, p?.mode, p?.value][ci];
    label = ["位置", "参数", "模式", "值"][ci];
  } else {
    const result = allResults(t)[Number(key)];
    value = result?.rows?.[ri]?.[ci];
    label = result?.columns?.[ci]?.label || `列 ${ci + 1}`;
  }
  const text = value == null ? "NULL" : formatValue(value);
  showDialog(
    `${label} · 第 ${ri + 1} 行，第 ${ci + 1} 列`,
    `${value == null ? '<p class="form-note">此单元格为数据库 NULL。</p>' : value === "" ? '<p class="form-note">此单元格为空字符串，长度为 0。</p>' : ""}<pre class="cell-value" id="cell-value">${esc(text)}</pre>`,
    `${button("关闭", "dialog-close", null)}${button("复制内容", "copy-cell", "copy", "primary")}`,
  );
}
function cellReadOnly(t, result, row, column) {
  const c = result?.columns?.[column];
  if (busy(t)) return "请等待当前执行结束";
  if (t.previewStale || t.previewFailure) return "上次预览仅供查看，请重新读取后编辑";
  if (filtersDirty(t)) return "过滤条件待应用，请先读取数据";
  if (t.execution?.mode !== "TABLE_PREVIEW")
    return "普通 SQL 查询结果只读；请打开表内容预览";
  if (result?.truncatedCells?.some(([r, c]) => r === row && c === column))
    return "单元格内容已截断，不能编辑";
  return (
    result?.readOnlyReason ||
    c?.readOnlyReason ||
    (!c?.editable ? "此单元格只读" : "")
  );
}
function selectCell(cell) {
  const t = tab();
  if (!t) return;
  $$("[data-cell].selected-cell").forEach((el) => {
    el.classList.remove("selected-cell");
    el.removeAttribute("aria-selected");
  });
  cell.classList.add("selected-cell");
  cell.setAttribute("aria-selected", "true");
  t.selectedCell = {
    result: cell.dataset.result,
    row: Number(cell.dataset.row),
    column: Number(cell.dataset.column),
    executionId: t.execution?.id,
  };
  const btn = $('[data-action="edit-selected-cell"]');
  if (btn) {
    const selected = t.selectedCell;
    const reason = cellReadOnly(
      t,
      allResults(t)[Number(selected.result)],
      selected.row,
      selected.column,
    );
    btn.disabled = !!reason;
    btn.title = reason || "编辑选中的单元格";
  }
}
function editCellDialog(t, resultIndex, row, column) {
  const result = allResults(t)[resultIndex],
    c = result?.columns?.[column];
  if (!c || !result.rows?.[row]) return;
  const value = result.rows[row][column],
    reason = cellReadOnly(t, result, row, column);
  cellEditor = {
    tabId: t.id,
    executionId: t.execution.id,
    result: resultIndex,
    row,
    column,
    submitted: false,
  };
  showDialog(
    `${c.label} · 第 ${row + 1} 行`,
    `<p class="form-note">${esc(connection(t.connectionId)?.name)} / ${esc(t.object.name)} · ${esc(c.typeName)}</p><label class="cell-label">原值</label><pre class="cell-value cell-original" id="cell-value">${esc(value == null ? "NULL" : value === "" ? "" : formatValue(value))}</pre>${reason ? `<p class="form-note">${esc(reason)}</p>` : `<div class="form-field"><label for="cell-input">新值</label><textarea id="cell-input" rows="5" maxlength="32768" spellcheck="false" ${value == null ? "disabled" : ""}>${esc(value == null ? "" : formatValue(value))}</textarea><label class="cell-null"><input type="checkbox" id="cell-null" ${value == null ? "checked" : ""} ${c.nullable ? "" : "disabled"}>设为 NULL${c.nullable ? "" : "（字段不允许）"}</label><small>留空表示空字符串；数字、日期与布尔值会校验类型。保存前会检查该格原值。</small></div><p class="form-note">${t.session?.autoCommit === false ? "手动事务：保存后仍需点击提交；回滚可撤销。" : "自动提交：保存成功后立即提交。"}</p>`}<div id="cell-feedback" class="form-feedback" role="status"></div>`,
    `${button("关闭", "dialog-close")}${button("复制原值", "copy-cell", "copy")}${!reason ? button("保存修改", "save-cell", "save", "primary") : ""}`,
  );
  if (!reason && value != null) $("#cell-input").focus();
}
async function saveCell() {
  const editor = cellEditor,
    t = tab();
  if (!editor || editor.tabId !== t?.id || busy(t) || editor.submitted) return;
  const value = $("#cell-input").value,
    nullValue = $("#cell-null").checked;
  const feedback = $("#cell-feedback"),
    save = $('[data-action="save-cell"]');
  const request = {
    mode: "CELL_UPDATE",
    sessionId: t.session.id,
    requestId: uid(),
    timeoutSeconds: t.timeoutSeconds || 60,
    cellChange: {
      executionId: editor.executionId,
      result: editor.result,
      row: editor.row,
      column: editor.column,
      value,
      nullValue,
    },
  };
  t.submitting = true;
  t.cellSaving = true;
  save.disabled = true;
  $$('#dialog [data-action="dialog-close"]').forEach(
    (b) => (b.disabled = true),
  );
  try {
    feedback.textContent = "正在核对修改…";
    const plan = await api("/executions/prepare", {
      method: "POST",
      body: request,
    });
    request.confirmationToken = plan.confirmationToken;
    if (
      !confirm(
        `连接：${connection(t.connectionId)?.name}\n表：${t.object.name}\n${t.session.autoCommit === false ? "保存到当前事务，之后仍需提交或回滚。" : "本次保存会立即提交。"}\n\n${plan.units.map((u) => u.sql).join("\n")}\n\n确认保存所选单元格？`,
      )
    ) {
      feedback.textContent = "已取消保存。";
      return;
    }
    // Once POST is attempted, do not generate another write request after an uncertain response.
    editor.submitted = true;
    feedback.textContent = "正在保存，请等待…";
    t.execution = await api("/executions", { method: "POST", body: request });
    t.resultIndex = 0;
    render();
    feedback.insertAdjacentHTML(
      "beforeend",
      button("取消保存", "cancel-cell", "stop"),
    );
    const until = Date.now() + (request.timeoutSeconds + 15) * 1000;
    while (activeStates.has(t.execution.state)) {
      if (Date.now() > until)
        throw new Error(
          "未能确认保存结果，请核对数据库状态后刷新。不要重复提交。",
        );
      await new Promise((resolve) => setTimeout(resolve, 350));
      t.execution = await api(`/executions/${t.execution.id}`);
    }
    t.session = await api(`/sessions/${t.session.id}`);
    syncSessionContext(t);
    if (t.execution.state !== "SUCCEEDED")
      throw new Error(t.execution.message || "保存失败，请查看执行消息");
    $("#dialog").close();
    t.submitting = false;
    t.cellSaving = false;
    toast(
      t.session.autoCommit
        ? "单元格已保存并提交。"
        : "单元格已保存；事务待提交，可继续编辑或回滚。",
    );
    api(`/executions/${editor.executionId}`, { method: "DELETE" }).catch(
      () => {},
    );
    await readTable(t, t.offset);
  } catch (e) {
    feedback.textContent = e.message;
    if (editor.submitted) {
      if (activeStates.has(t.execution?.state)) {
        t.execution.state = "OUTCOME_UNKNOWN";
        t.execution.message = e.message;
      }
      t.resultIndex = "messages";
      feedback.insertAdjacentHTML(
        "beforeend",
        `<p>请刷新表内容后重新选择；上方输入保留供复制。</p>${button("关闭并刷新表内容", "cell-refresh", "refresh")}`,
      );
    }
  } finally {
    t.submitting = false;
    t.cellSaving = false;
    save.disabled = editor.submitted;
    $$('#dialog [data-action="dialog-close"]').forEach(
      (b) => (b.disabled = false),
    );
    if (t === tab()) render();
  }
}
const actions = {
  "edit-selected-cell": () => {
    const t = tab(),
      selected = t?.selectedCell;
    if (selected && selected.executionId === t.execution?.id)
      editCellDialog(t, Number(selected.result), selected.row, selected.column);
  },
  "save-cell": saveCell,
  "cancel-cell": async (button) => {
    const t = tab();
    if (t?.cellSaving && t.execution?.mode === "CELL_UPDATE") {
      button.disabled = true;
      try {
        await api(`/executions/${t.execution.id}/cancel`, { method: "POST" });
        button.textContent = "已请求取消";
      } catch (e) {
        button.disabled = false;
        throw e;
      }
    }
  },
  "cell-refresh": () => {
    $("#dialog").close();
    return readTable(tab(), tab().offset);
  },
  "dismiss-execution-error": () => {
    tab().localError = null;
    renderResults(tab());
  },
  "execution-settings": () => {
    const t = tab();
    showDialog(
      "当前标签 · 执行设置",
      `<p class="form-note">设置只应用于当前标签的后续执行。表数据预览每页仍为 200 行。</p><div class="form-grid">${field("每个结果最多返回行数", "maxRows", t.maxRows || 500, "number", "1–5000 行；达到上限会显示截断提示。")}${field("执行超时（秒）", "timeoutSeconds", t.timeoutSeconds || 60, "number", "1–3600 秒；超时后请求数据库取消执行。")}</div>`,
      `${button("取消", "dialog-close", null)}${button("应用设置", "save-execution-settings", "check", "primary")}`,
    );
  },
  "save-execution-settings": () => {
    const maxRows = Number($("#f-maxRows").value),
      timeoutSeconds = Number($("#f-timeoutSeconds").value);
    if (!Number.isInteger(maxRows) || maxRows < 1 || maxRows > 5000)
      throw new Error("结果行数必须为 1–5000 的整数。");
    if (
      !Number.isInteger(timeoutSeconds) ||
      timeoutSeconds < 1 ||
      timeoutSeconds > 3600
    )
      throw new Error("超时必须为 1–3600 秒的整数。");
    tab().maxRows = maxRows;
    tab().timeoutSeconds = timeoutSeconds;
    $("#dialog").close();
    toast("当前标签的执行设置已更新。");
  },
  "dialog-close": () => $("#dialog").close(),
  "sidebar-toggle": () => {
    state.sidebarOpen = !state.sidebarOpen;
    $(".workspace").classList.toggle("sidebar-open", state.sidebarOpen);
  },
  "new-tab": () => newTab(),
  drivers: driversDialog,
  "new-connection": () => connectionDialog(),
  "demo-connection": () => openDemo(),
  "edit-connection": (el) => connectionDialog(el.dataset.id),
  "import-driver": importDriver,
  "driver-class": (el) =>
    chooseDriverClass(state.drivers.find((p) => p.id === el.dataset.id)),
  "save-driver-class": async () => {
    const driverClass = $("#driver-class-value").value.trim(),
      id = $("[name=id]", $("#driver-class-form")).value;
    if (!driverClass) throw new Error("请输入驱动类全名。");
    try {
      await api(`/drivers/${id}/class`, {
        method: "POST",
        body: { driverClass },
      });
      await driversDialog();
      toast("驱动类已保存。");
    } catch (e) {
      $("#class-feedback").innerHTML =
        `<span class="error-text">${esc(e.message)}</span>`;
    }
  },
  "delete-driver": async (el) => {
    const p = state.drivers.find((x) => x.id === el.dataset.id);
    if (confirm(`删除驱动「${p.name}」？已有连接引用的驱动无法删除。`)) {
      await api(`/drivers/${p.id}`, { method: "DELETE" });
      await driversDialog();
      toast("驱动已删除。");
    }
  },
  "test-connection": () => saveConnection(true),
  "save-connection": () => saveConnection(false),
  "delete-connection": async (el) => {
    const c = connection(el.dataset.id);
    if (!confirm(`删除连接「${c.name}」？${c.saveSqlDrafts ? "该连接的已存 SQL 草稿也将清除。" : ""}数据库本身不会被删除。`)) return;
    await api(`/connections/${c.id}`, { method: "DELETE" });
    state.connections = list(await api("/connections"));
    invalidateTree(c.id);
    state.expanded.delete(c.id);
    if (state.selectedConnection === c.id)
      state.selectedConnection = state.connections[0]?.id || "";
    state.tabs
      .filter((t) => t.connectionId === c.id && !t.session)
      .forEach((t) => { abortRead(`context:${t.id}`); t.connectionId = ""; });
    $("#dialog").close();
    render();
    drafts.forget(c.id);
    updateDraftStatus();
    toast("连接配置已删除。");
  },
  "toggle-connection": async (el) => {
    const id = el.dataset.id;
    state.selectedConnection = id;
    if (state.expanded.has(id)) state.expanded.delete(id);
    else {
      state.expanded.add(id);
      loadTree(id);
    }
    const select = $("#global-connection");
    if (select) select.value = id;
    renderTree();
  },
  "toggle-schema": async (el) => {
    const key = el.dataset.key;
    if (state.expanded.has(key)) state.expanded.delete(key);
    else {
      state.expanded.add(key);
      loadObjects(
        el.dataset.id,
        { catalog: el.dataset.catalog, schema: el.dataset.schema },
        key,
      );
    }
    renderTree();
  },
  "refresh-tree": async () => {
    const id = state.selectedConnection;
    if (!id) return;
    invalidateTree(id);
    state.expanded.add(id);
    await loadTree(id, true);
  },
  "refresh-connection": (el) => loadTree(el.dataset.id, true),
  "refresh-objects": (el) => loadObjects(
    el.dataset.id,
    { catalog: el.dataset.catalog, schema: el.dataset.schema },
    el.dataset.key,
    true,
  ),
  "open-object": (el) =>
    openObject(el.dataset.id, JSON.parse(el.dataset.object)),
  "select-tab": (el) => {
    rememberEditor();
    state.activeTab = el.dataset.id;
    render();
    if (tab()?.restoreContext) loadContext(tab());
  },
  "close-tab": (el) => closeTab(el.dataset.id),
  "execute-current": () => execute("CURRENT"),
  "execute-selection": () => execute("SQL"),
  "execute-block": () => execute("BLOCK"),
  "execute-script": () => execute("SCRIPT"),
  explain: () => execute("EXPLAIN", { analyze: false }),
  analyze: () => execute("EXPLAIN", { analyze: true }),
  cancel: async () => {
    const t = tab(), id = t?.execution?.id;
    if (!id || t.cancelInFlight) return;
    t.cancelInFlight = true; t.pollVersion = (t.pollVersion || 0) + 1;
    abortRead(`poll:${t.id}`);
    try {
      const result = await api(`/executions/${id}/cancel`, { method: "POST" });
      if (!state.tabs.includes(t) || t.execution?.id !== id) return;
      if (result?.state) t.execution = result;
      toast("已请求取消，正在等待数据库确认。");
    } finally {
      t.cancelInFlight = false;
      if (t === tab()) renderExecutionChange(t);
      startPolling();
    }
  },
  "recover-session": () => recoverSession(tab()),
  commit: () => transaction("COMMIT"),
  rollback: () => transaction("ROLLBACK"),
  disconnect: () => disconnect(tab()),
  "open-sql": () => $("#sql-file").click(),
  "save-sql": saveSql,
  "draft-status": draftStatusDialog,
  "retry-drafts": () => { $("#dialog").close(); return drafts.save(tab().connectionId); },
  "result-tab": (el) => {
    tab().resultIndex =
      el.dataset.index === "messages" ? "messages" : Number(el.dataset.index);
    renderResults(tab());
  },
  "copy-results": () => {
    const r = allResults(tab())[tab().resultIndex];
    return copyText(
      [
        r.columns.map((c) => c.label).join("\t"),
        ...r.rows.map((row) =>
          row
            .map((v) =>
              v == null ? "NULL" : formatValue(v).replace(/\t/g, " "),
            )
            .join("\t"),
        ),
      ].join("\n"),
    );
  },
  "copy-cell": () => copyText($("#cell-value").textContent),
  "plan-tree": () => {
    tab().planMode = "tree";
    renderResults(tab());
  },
  "plan-raw": () => {
    tab().planMode = "raw";
    renderResults(tab());
  },
  "table-structure": () => {
    captureTableFilters(tab());
    tab().tableView = "structure";
    renderTab(tab());
  },
  "table-data": () => {
    captureTableFilters(tab());
    tab().tableView = "data";
    renderTab(tab());
  },
  "filter-add": () => {
    const t = tab(); if (busy(t)) return; captureTableFilters(t);
    if (t.filterDraft.length < 30) t.filterDraft.push({column:"", operator:"=", value:""});
    renderTab(t);
  },
  "filter-remove": el => {
    const t = tab(); if (busy(t)) return; captureTableFilters(t);
    t.filterDraft.splice(Number(el.dataset.index),1); renderTab(t);
  },
  "filter-clear": () => {
    const t = tab(); if (busy(t)) return; t.filterDraft = []; t.filterMatch = "ALL"; renderTab(t);
  },
  "table-read": () => readTable(tab(), 0, true),
  "table-prev": () => readTable(tab(), Math.max(0, (tab().offset || 0) - 200)),
  "table-next": () => readTable(tab(), (tab().offset || 0) + 200),
  "table-to-sql": () => {
    const t = tab();
    newTab({
      connectionId: t.connectionId,
      schema: t.schema,
      catalog: t.catalog,
      sql: t.previewSql
        ? `-- SQL 模板：? 为参数占位符，执行前请按顺序替换为数据库字面量。\n-- 输入参数（按占位符顺序）：${JSON.stringify(t.previewBindings || [])}\n${t.previewSql}`
        : "-- 请先读取表数据，再生成 SQL 模板。",
    });
  },
  "routine-definition": () => {
    const t = tab();
    showDialog(
      `${t.name} · 定义`,
      `<pre class="cell-value" id="cell-value">${esc(t.routine?.definition || "数据库或驱动未提供可读取的定义。你仍可在编辑器中编写原生 SQL。")}</pre>${(t.routine?.warnings || []).map((w) => `<p class="form-note">${esc(w)}</p>`).join("")}`,
      `${button("关闭", "dialog-close", null)}${button("复制定义", "copy-cell", "copy")}`,
      true,
    );
  },
  "routine-template": () => {
    const t = tab();
    rememberEditor();
    if (t.routine?.callSql) {
      t.sql = t.routine.callSql;
      renderTab(t);
    } else toast("驱动未返回调用模板，请手动输入原生调用语句。", true);
  },
  "routine-call": () => execute("CALL"),
  "add-parameter": () => {
    rememberEditor();
    const t = tab();
    t.parameters.push({
      position: t.parameters.length + 1,
      name: "",
      mode: "IN",
      jdbcType: 12,
      typeName: "VARCHAR",
      value: "",
      isNull: false,
    });
    renderTab(t);
  },
  "remove-parameter": (el) => {
    rememberEditor();
    tab().parameters.splice(Number(el.dataset.index), 1);
    renderTab(tab());
  },
  "locate-statement": (el) => {
    const t = tab(),
      s = t.execution.statements[Number(el.dataset.index)],
      ed = $("#sql-editor");
    if (!ed) return;
    if (t.executionSourceSql !== ed.value) {
      toast("编辑器内容已改变，请根据执行消息中的原始 SQL 和行号定位。", true);
      return;
    }
    const from = (s.startLine || 1) + (t.sourceLineOffset || 0),
      to = (s.endLine || s.startLine || 1) + (t.sourceLineOffset || 0);
    const lines = t.sql.split("\n"),
      start = lines.slice(0, from - 1).join("\n").length + (from > 1 ? 1 : 0),
      end = lines.slice(0, to).join("\n").length;
    ed.focus();
    ed.setSelectionRange(start, end);
    ed.scrollTop = (from - 1) * 23;
    $("#line-numbers").scrollTop = ed.scrollTop;
  },
  shortcuts: () =>
    showDialog(
      "键盘快捷键",
      `<div class="shortcut-list"><span>执行当前语句</span><kbd>⌘ / Ctrl + Enter</kbd><span>执行选区</span><kbd>⌘ / Ctrl + Shift + Enter</kbd><span>新建 SQL 标签</span><kbd>⌘ / Ctrl + Alt + N</kbd><span>保存 SQL 文件</span><kbd>⌘ / Ctrl + S</kbd><span>缩进 / 取消缩进</span><kbd>Tab / Shift + Tab</kbd><span>查看单元格</span><kbd>Enter / 双击</kbd></div><p class="form-note" style="margin-top:21px">「整块」完整发送选区或全部文本，保留过程体内部分号；「脚本」由后端识别边界，再顺序执行。SQL 可手动下载为文件；连接开启“保存 SQL 草稿”后，文本还会自动加密保存在本机。草稿不会自动执行。</p>`,
    ),
};
// Never let a browser form navigation put connection credentials in the URL.
document.addEventListener("submit", (e) => {
  e.preventDefault();
  const action = {
    "connection-form": "save-connection",
    "driver-form": "import-driver",
    "driver-class-form": "save-driver-class",
  }[e.target.id];
  if (action)
    Promise.resolve()
      .then(() => actions[action]())
      .catch(report);
});
document.addEventListener("focusin", (e) => {
  const cell = e.target.closest("[data-cell]");
  if (cell) selectCell(cell);
});
document.addEventListener("click", (e) => {
  const cell = e.target.closest("[data-cell]");
  if (cell) selectCell(cell);
  const target = e.target.closest("[data-action]");
  if (!target || target.disabled) return;
  e.preventDefault();
  e.stopPropagation();
  const action = actions[target.dataset.action];
  if (action)
    Promise.resolve()
      .then(() => action(target))
      .catch(report);
});
document.addEventListener("pointerdown", (e) => {
  const handle = e.target.closest(".column-resize");
  if (!handle) return;
  e.preventDefault();
  const th = handle.closest("th"),
    table = th.closest("table"),
    start = e.clientX,
    width = th.getBoundingClientRect().width,
    tableWidth = table.getBoundingClientRect().width;
  const resize = (event) => {
    const next = Math.max(60, Math.min(700, width + event.clientX - start));
    th.style.width = next + "px";
    th.style.minWidth = next + "px";
    th.style.maxWidth = next + "px";
    table.style.width = tableWidth + next - width + "px";
  };
  const stop = () => {
    document.removeEventListener("pointermove", resize);
    document.removeEventListener("pointerup", stop);
  };
  document.addEventListener("pointermove", resize);
  document.addEventListener("pointerup", stop, { once: true });
});
document.addEventListener("dblclick", (e) => {
  const cell = e.target.closest("[data-cell]");
  if (cell) cellDetail(cell);
});
document.addEventListener("change", (e) => {
  const target = e.target,
    t = tab();
  if (target.matches("[data-filter], #filter-match, #order-column, #order-desc")) filterChanged();
  if (target.id === "cell-null") $("#cell-input").disabled = target.checked;
  if (target.id === "f-driverId") fillDriverDefaults();
  if (target.id === "global-connection") {
    state.selectedConnection = target.value;
    if (target.value) {
      state.expanded.add(target.value);
      loadTree(target.value);
      if (t && !t.connectionId) {
        t.connectionId = target.value;
        t.schema = connection(target.value)?.schema || "";
        t.catalog = connection(target.value)?.catalog || "";
        render();
        loadContext(t);
        drafts.schedule();
      }
    }
    renderTree();
  }
  if (target.id === "tab-connection") {
    rememberEditor();
    abortRead(`context:${t.id}`);
    t.connectionId = target.value;
    t.schema = connection(target.value)?.schema || "";
    t.catalog = connection(target.value)?.catalog || "";
    t.schemas = [];
    t.catalogs = [];
    render();
    if (target.value) loadContext(t);
    drafts.schedule();
  }
  if (target.id === "tab-schema") t.schema = target.value;
  if (target.id === "tab-catalog") t.catalog = target.value;
  if (target.id === "auto-commit")
    transaction("AUTO_COMMIT", target.checked).catch((e) => {
      report(e);
      render();
    });
  if (target.closest(".param-row")) {
    captureParameters(t);
    const row = target.closest(".param-row"),
      mode = $("[name=mode]", row).value;
    $("[name=value]", row).disabled =
      mode === "OUT" || mode === "RETURN" || $("[name=isNull]", row).checked;
  }
});
document.addEventListener("input", (e) => {
  if (e.target.matches("[data-filter=value]")) filterChanged();
  if (e.target.id === "sql-editor") {
    const t = tab();
    t.sql = e.target.value;
    $("#line-numbers").textContent = lineNumbers(t.sql);
    updateHighlight();
    drafts.schedule();
    updateCursor();
  }
  if (e.target.id === "object-search") {
    state.search = e.target.value;
    renderTree();
  }
});
document.addEventListener(
  "scroll",
  (e) => {
    if (e.target.id === "sql-editor") syncHighlightScroll();
  },
  true,
);
document.addEventListener("compositionstart", (e) => {
  if (e.target.id === "sql-editor") { e.target.dataset.composing = "true"; updateHighlight(); }
});
document.addEventListener("compositionend", (e) => {
  if (e.target.id === "sql-editor") { delete e.target.dataset.composing; updateHighlight(); }
});
document.addEventListener("keyup", (e) => {
  if (e.target.id === "sql-editor") updateCursor();
});
document.addEventListener("mouseup", (e) => {
  if (e.target.id === "sql-editor") updateCursor();
});
function updateCursor() {
  const ed = $("#sql-editor"),
    pos = $("#cursor-position");
  if (!ed || !pos) return;
  const lines = ed.value.slice(0, ed.selectionStart).split("\n");
  pos.textContent = `行 ${lines.length}，列 ${lines[lines.length - 1].length + 1}${ed.selectionStart !== ed.selectionEnd ? " · 已选择 " + (ed.selectionEnd - ed.selectionStart) + " 字符" : ""}`;
}
document.addEventListener("keydown", (e) => {
  const mod = e.metaKey || e.ctrlKey;
  if (
    e.target.matches?.(".column-resize") &&
    ["ArrowLeft", "ArrowRight"].includes(e.key)
  ) {
    e.preventDefault();
    const th = e.target.closest("th"),
      width = Math.max(
        60,
        Math.min(
          700,
          th.getBoundingClientRect().width +
            (e.key === "ArrowRight" ? 20 : -20),
        ),
      );
    th.style.width = th.style.minWidth = th.style.maxWidth = width + "px";
    return;
  }
  if (e.target.matches?.("[data-cell]") && e.key === "Enter") {
    e.preventDefault();
    cellDetail(e.target);
    return;
  }
  if (
    e.target.matches?.(".close-tab") &&
    (e.key === "Enter" || e.key === " ")
  ) {
    e.preventDefault();
    closeTab(e.target.dataset.id).catch(report);
    return;
  }
  if ($("#dialog").open) return;
  if (mod && e.key === "Enter") {
    e.preventDefault();
    execute(e.shiftKey ? "SQL" : "CURRENT").catch(report);
    return;
  }
  if (mod && e.key.toLowerCase() === "s") {
    e.preventDefault();
    saveSql();
    return;
  }
  if (mod && e.altKey && e.key.toLowerCase() === "n") {
    e.preventDefault();
    newTab();
    return;
  }
  if (e.target.id === "sql-editor" && e.key === "Tab") {
    e.preventDefault();
    const ed = e.target,
      start = ed.selectionStart,
      end = ed.selectionEnd;
    const lineStart = ed.value.lastIndexOf("\n", start - 1) + 1;
    if (start === end && !e.shiftKey) {
      ed.setRangeText("    ", start, end, "end");
    } else {
      const endLine = ed.value.indexOf("\n", end),
        blockEnd = endLine < 0 ? ed.value.length : endLine,
        block = ed.value.slice(lineStart, blockEnd),
        lines = block.split("\n"),
        changed = lines
          .map((line) =>
            e.shiftKey ? line.replace(/^( {1,4}|\t)/, "") : "    " + line,
          )
          .join("\n");
      ed.setRangeText(changed, lineStart, blockEnd, "select");
    }
    ed.dispatchEvent(new Event("input", { bubbles: true }));
  }
});
$("#sql-file").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  try {
    if (file.size > 4 * 1024 * 1024)
      throw new Error("SQL 文件超过 4 MiB，请分段打开。");
    newTab({ name: file.name, sql: await file.text() });
    toast("SQL 文件已打开。");
  } catch (err) {
    report(err);
  } finally {
    e.target.value = "";
  }
});
$("#dialog").addEventListener("cancel", (e) => {
  if (tab()?.cellSaving) e.preventDefault();
});
$("#dialog").addEventListener("click", (e) => {
  if (e.target === $("#dialog") && !tab()?.cellSaving) {
    const rect = e.target.getBoundingClientRect();
    if (
      e.clientX < rect.left ||
      e.clientX > rect.right ||
      e.clientY < rect.top ||
      e.clientY > rect.bottom
    )
      e.target.close();
  }
});
window.addEventListener("beforeunload", (e) => {
  rememberEditor();
  if (
    drafts.dirty() || state.tabs.some(
      (t) =>
        busy(t) ||
        t.session?.autoCommit === false ||
        (t.sql.trim() && t.savedSql !== t.sql && !drafts.saved(t)),
    )
  ) {
    e.preventDefault();
    e.returnValue = "";
  }
});
async function init() {
  try {
    const boot = await api("/bootstrap");
    state.token = boot.token;
    const [drivers, connections] = await Promise.all([
      api("/drivers"),
      api("/connections"),
    ]);
    state.drivers = list(drivers);
    state.connections = list(connections);
    state.selectedConnection = state.connections[0]?.id || "";
    const restored = await Promise.all(state.connections.filter(c => c.saveSqlDrafts).map(async c => ({connectionId: c.id, tabs: await drafts.load(c.id)})));
    for (const workspace of restored) for (const draft of workspace.tabs) {
      const c = connection(workspace.connectionId);
      newTab({...draft, id: uid(), draftId: draft.id, restoreContext: true,
        connectionId: c.id, schema: c.schema || "", catalog: c.catalog || ""}, true);
    }
    if (!state.tabs.length) newTab();
    else { state.selectedConnection = tab().connectionId; render(); loadContext(tab()); }
    if (restored.some(w => w.tabs.length)) toast("已恢复 SQL 草稿；数据库会话和事务未恢复，SQL 不会自动执行。");
    if (restored.some(w => drafts.status(w.connectionId).error))
      toast("有连接的 SQL 草稿读取失败，已有文件未覆盖。选择对应连接后，点击编辑器下方草稿状态查看说明。", true);
    if (state.selectedConnection) {
      state.expanded.add(state.selectedConnection);
      loadTree(state.selectedConnection);
    }
  } catch (e) {
    $("#app").innerHTML =
      `<div class="boot-state"><div><h2>暂时无法打开工作台</h2><p>${esc(e.message)}</p><button onclick="location.reload()">重新连接本机服务</button></div></div>`;
  }
}
init();
