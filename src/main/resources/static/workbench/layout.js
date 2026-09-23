const STORAGE_KEY = "toolbox.layout.v1";
const DEFAULT_SIDEBAR = 248;
const DEFAULT_EDITOR_RATIO = 0.45;
const TAB_SELECTOR = '.tabs [data-action="select-tab"][data-id]';

function bounded(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

function readPreferences() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
    if (!saved || typeof saved !== "object" || Array.isArray(saved)) return {};
    return {
      sidebarWidth: Number.isFinite(saved.sidebarWidth) && saved.sidebarWidth >= 180 && saved.sidebarWidth <= 420
        ? saved.sidebarWidth : DEFAULT_SIDEBAR,
      editorRatio: Number.isFinite(saved.editorRatio) && saved.editorRatio > 0 && saved.editorRatio < 1
        ? saved.editorRatio : DEFAULT_EDITOR_RATIO,
    };
  } catch (_) {
    return {};
  }
}

export function createLayoutController({ onTabMove, beforeGesture } = {}) {
  let preferences = { sidebarWidth: DEFAULT_SIDEBAR, editorRatio: DEFAULT_EDITOR_RATIO, ...readPreferences() };
  let workspace = null;
  let editorResults = null;
  let observer = null;
  let gesture = null;
  let draggedTab = null;
  let dropTab = null;

  function save() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        sidebarWidth: preferences.sidebarWidth,
        editorRatio: preferences.editorRatio,
      }));
    } catch (_) { /* Layout remains usable when storage is unavailable. */ }
  }

  function clearDrop() {
    dropTab?.classList.remove("tab-drop-before", "tab-drop-after");
    draggedTab?.classList.remove("tab-dragging");
    dropTab = null;
    draggedTab = null;
  }

  function stopGesture(saveChange = false) {
    if (!gesture) return;
    window.removeEventListener("pointermove", onPointerMove);
    window.removeEventListener("pointerup", onPointerUp);
    window.removeEventListener("pointercancel", onPointerCancel);
    window.removeEventListener("blur", onPointerCancel);
    document.documentElement.classList.remove("layout-resizing-sidebar", "layout-resizing-editor");
    gesture.handle.classList.remove("is-resizing");
    try { gesture.handle.releasePointerCapture(gesture.pointerId); } catch (_) { /* Capture may already be gone. */ }
    gesture = null;
    if (saveChange) save();
  }

  function sidebarLimit() {
    // Leave enough width for the main pane, even in a narrow desktop window.
    return Math.max(0, Math.min(420, (workspace?.clientWidth || 0) - 320 - 6));
  }

  function editorBounds() {
    const handle = editorResults?.querySelector('[data-layout-resize="editor"]');
    const available = Math.max(0, (editorResults?.clientHeight || 0) - (handle?.offsetHeight || 6));
    if (available < 196) {
      return { available, min: available * 0.25, max: available * 0.75 };
    }
    return { available, min: 96, max: available - 100 };
  }

  function apply() {
    const nextWorkspace = document.querySelector(".workspace");
    const nextEditorResults = document.querySelector(".editor-results");
    if (draggedTab && !draggedTab.isConnected) clearDrop();
    if (gesture && (!gesture.handle.isConnected ||
      (gesture.kind === "sidebar" && gesture.handle !== nextWorkspace?.querySelector('[data-layout-resize="sidebar"]')) ||
      (gesture.kind === "editor" && gesture.handle !== nextEditorResults?.querySelector('[data-layout-resize="editor"]')))) {
      stopGesture(true);
    }
    if (workspace !== nextWorkspace || editorResults !== nextEditorResults) {
      observer?.disconnect();
      workspace = nextWorkspace;
      editorResults = nextEditorResults;
      if (!observer && typeof ResizeObserver !== "undefined") observer = new ResizeObserver(apply);
      if (workspace) observer?.observe(workspace);
      if (editorResults) observer?.observe(editorResults);
    }

    const sidebar = workspace?.querySelector(".sidebar");
    const sidebarHandle = workspace?.querySelector('[data-layout-resize="sidebar"]');
    if (sidebar) {
      const enabled = window.innerWidth > 620 && workspace.classList.contains("sidebar-open");
      sidebar.style.width = enabled ? `${bounded(preferences.sidebarWidth, Math.min(180, sidebarLimit()), sidebarLimit())}px` : "";
      if (sidebarHandle) {
        sidebarHandle.hidden = !enabled;
        sidebarHandle.setAttribute("aria-valuenow", String(Math.round(enabled ? sidebar.getBoundingClientRect().width : preferences.sidebarWidth)));
        sidebarHandle.setAttribute("aria-valuemin", "180");
        sidebarHandle.setAttribute("aria-valuemax", String(Math.max(180, Math.round(sidebarLimit()))));
        sidebarHandle.setAttribute("aria-label", "调整导航宽度");
      }
    }
    if (editorResults) {
      const editorHandle = editorResults.querySelector('[data-layout-resize="editor"]');
      const bounds = editorBounds();
      const height = bounded(bounds.available * preferences.editorRatio, bounds.min, bounds.max);
      editorResults.style.setProperty("--editor-height", `${height}px`);
      if (editorHandle) {
        editorHandle.setAttribute("aria-valuenow", String(Math.round(height)));
        editorHandle.setAttribute("aria-valuemin", String(Math.round(bounds.min)));
        editorHandle.setAttribute("aria-valuemax", String(Math.round(bounds.max)));
        editorHandle.setAttribute("aria-label", "调整编辑器高度");
      }
    }
  }

  function reset() {
    stopGesture();
    preferences = { sidebarWidth: DEFAULT_SIDEBAR, editorRatio: DEFAULT_EDITOR_RATIO };
    try { localStorage.removeItem(STORAGE_KEY); } catch (_) { /* Optional preference. */ }
    apply();
  }

  function onPointerMove(event) {
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    if (!gesture.handle.isConnected) { stopGesture(true); return; }
    if (gesture.kind === "sidebar") {
      preferences.sidebarWidth = bounded(event.clientX - gesture.origin, Math.min(180, sidebarLimit()), sidebarLimit());
    } else {
      const bounds = editorBounds();
      if (bounds.available > 0) {
        const height = bounded(event.clientY - gesture.origin, bounds.min, bounds.max);
        preferences.editorRatio = height / bounds.available;
      }
    }
    apply();
    event.preventDefault();
  }

  function onPointerUp(event) {
    if (gesture && event.pointerId === gesture.pointerId) stopGesture(true);
  }

  function onPointerCancel(event) {
    if (gesture && (event.pointerId === undefined || event.pointerId === gesture.pointerId)) stopGesture(true);
  }

  document.addEventListener("pointerdown", (event) => {
    const handle = event.target.closest?.("[data-layout-resize]");
    if (!handle || event.button !== 0 || !event.isPrimary || handle.hidden || getComputedStyle(handle).display === "none") return;
    const kind = handle.dataset.layoutResize;
    if (kind !== "sidebar" && kind !== "editor") return;
    apply();
    beforeGesture?.();
    if (!handle.isConnected) return;
    const rect = kind === "sidebar" ? workspace?.getBoundingClientRect() : editorResults?.getBoundingClientRect();
    if (!rect) return;
    gesture = { kind, handle, pointerId: event.pointerId, origin: kind === "sidebar" ? rect.left : rect.top };
    handle.classList.add("is-resizing");
    document.documentElement.classList.add(`layout-resizing-${kind}`);
    try { handle.setPointerCapture(event.pointerId); } catch (_) { /* Window listeners still receive the gesture. */ }
    window.addEventListener("pointermove", onPointerMove);
    window.addEventListener("pointerup", onPointerUp);
    window.addEventListener("pointercancel", onPointerCancel);
    window.addEventListener("blur", onPointerCancel);
    event.preventDefault();
  });

  document.addEventListener("keydown", (event) => {
    const handle = event.target.closest?.("[data-layout-resize]");
    if (!handle || handle.hidden || getComputedStyle(handle).display === "none") return;
    const kind = handle.dataset.layoutResize;
    const axisKeys = kind === "sidebar" ? ["ArrowLeft", "ArrowRight"] : ["ArrowUp", "ArrowDown"];
    if (![...axisKeys, "Home", "End"].includes(event.key)) return;
    apply();
    beforeGesture?.();
    const direction = event.key === axisKeys[0] ? -1 : 1;
    if (kind === "sidebar") {
      const max = sidebarLimit();
      preferences.sidebarWidth = bounded(event.key === "Home" ? 180 : event.key === "End" ? max : preferences.sidebarWidth + direction * (event.shiftKey ? 32 : 10), Math.min(180, max), max);
    } else if (kind === "editor") {
      const bounds = editorBounds();
      if (bounds.available > 0) {
        const current = bounded(bounds.available * preferences.editorRatio, bounds.min, bounds.max);
        const next = bounded(event.key === "Home" ? bounds.min : event.key === "End" ? bounds.max : current + direction * (event.shiftKey ? 32 : 10), bounds.min, bounds.max);
        preferences.editorRatio = next / bounds.available;
      }
    }
    apply();
    save();
    event.preventDefault();
  });

  document.addEventListener("dblclick", (event) => {
    const handle = event.target.closest?.("[data-layout-resize]");
    if (!handle || handle.hidden) return;
    beforeGesture?.();
    if (handle.dataset.layoutResize === "sidebar") preferences.sidebarWidth = DEFAULT_SIDEBAR;
    else if (handle.dataset.layoutResize === "editor") preferences.editorRatio = DEFAULT_EDITOR_RATIO;
    else return;
    apply();
    save();
    event.preventDefault();
  });

  let closePressed = false;
  document.addEventListener("pointerdown", (event) => {
    closePressed = !!event.target.closest?.('.close-tab[data-action="close-tab"]');
  }, true);
  document.addEventListener("pointerup", () => { closePressed = false; }, true);
  document.addEventListener("pointercancel", () => { closePressed = false; }, true);
  document.addEventListener("dragstart", (event) => {
    const item = event.target.closest?.(TAB_SELECTOR);
    if (!item || closePressed || event.target.closest?.('.close-tab[data-action="close-tab"]')) {
      if (item) event.preventDefault();
      return;
    }
    beforeGesture?.();
    if (!item.isConnected) { event.preventDefault(); return; }
    draggedTab = item;
    item.classList.add("tab-dragging");
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = "move";
      event.dataTransfer.setData("application/x-toolbox-tab", "tab");
    }
  });
  document.addEventListener("dragover", (event) => {
    if (!draggedTab?.isConnected) { clearDrop(); return; }
    const target = event.target.closest?.(TAB_SELECTOR);
    if (!target || target === draggedTab || !target.isConnected ||
      !draggedTab.parentElement || target.parentElement !== draggedTab.parentElement ||
      !draggedTab.dataset.id || !target.dataset.id) {
      dropTab?.classList.remove("tab-drop-before", "tab-drop-after");
      dropTab = null;
      return;
    }
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
    dropTab?.classList.remove("tab-drop-before", "tab-drop-after");
    dropTab = target;
    const after = event.clientX >= target.getBoundingClientRect().left + target.getBoundingClientRect().width / 2;
    target.classList.add(after ? "tab-drop-after" : "tab-drop-before");
  });
  document.addEventListener("drop", (event) => {
    // An internal tab gesture must never insert text into an editor or navigate.
    if (draggedTab) event.preventDefault();
    if (!draggedTab || !dropTab || !draggedTab.isConnected || !dropTab.isConnected ||
      event.target.closest?.(TAB_SELECTOR) !== dropTab) { clearDrop(); return; }
    event.preventDefault();
    const sourceId = draggedTab.dataset.id;
    const targetId = dropTab.dataset.id;
    const after = dropTab.classList.contains("tab-drop-after");
    const valid = sourceId && targetId && sourceId !== targetId && draggedTab.parentElement === dropTab.parentElement;
    clearDrop();
    if (valid) onTabMove?.(sourceId, targetId, after);
  });
  document.addEventListener("dragend", clearDrop);

  window.addEventListener("resize", apply);
  return { apply, reset };
}
