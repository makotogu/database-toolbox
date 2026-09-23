// One popup is shared by the workbench menus and context menus.
let controller;

export function createMenuController() {
  if (controller) return controller;
  const root = document.createElement("div");
  root.id = "workbench-popup-menu";
  root.className = "workbench-popup-menu";
  root.setAttribute("role", "menu");
  root.tabIndex = -1;
  root.hidden = true;
  document.body.append(root);

  let anchor = null;
  let returnFocus = null;
  let commands = new Map();
  let prefix = "";
  let prefixTime = 0;

  function close({ restoreFocus = true } = {}) {
    if (root.hidden) return;
    const target = returnFocus;
    root.hidden = true;
    anchor?.setAttribute("aria-expanded", "false");
    anchor = null;
    returnFocus = null;
    commands.clear();
    prefix = "";
    if (restoreFocus && target?.isConnected) target.focus({ preventScroll: true });
  }

  function enabledItems() {
    return [...commands.keys()].filter(button => !button.disabled);
  }

  function focusItem(button) {
    if (!button) return;
    button.focus({ preventScroll: true });
    button.scrollIntoView({ block: "nearest", inline: "nearest" });
  }

  function open({ anchor: trigger, x, y, title, items = [] }) {
    if (!root.hidden && trigger && trigger === anchor) {
      close();
      return;
    }
    close({ restoreFocus: false });
    anchor = trigger || null;
    returnFocus = trigger || document.activeElement;
    commands = new Map();
    prefix = "";
    prefixTime = 0;
    root.replaceChildren();
    root.setAttribute("aria-label", title || "操作菜单");
    if (title) {
      const heading = document.createElement("div");
      heading.className = "workbench-menu-title";
      heading.setAttribute("role", "presentation");
      heading.textContent = title;
      root.append(heading);
    }
    for (const item of items) {
      if (item.separator) {
        const separator = document.createElement("div");
        separator.className = "workbench-menu-separator";
        separator.setAttribute("role", "separator");
        root.append(separator);
        continue;
      }
      const button = document.createElement("button");
      button.type = "button";
      button.className = "workbench-menu-item";
      button.tabIndex = -1;
      button.disabled = !!item.disabled;
      button.setAttribute("role", item.checked === undefined ? "menuitem" : "menuitemcheckbox");
      if (item.checked !== undefined) button.setAttribute("aria-checked", String(!!item.checked));
      if (item.disabled) button.setAttribute("aria-disabled", "true");
      const check = document.createElement("span");
      check.className = "workbench-menu-check";
      check.setAttribute("aria-hidden", "true");
      check.textContent = item.checked ? "✓" : "";
      const text = document.createElement("span");
      text.className = "workbench-menu-text";
      const label = document.createElement("span");
      label.className = "workbench-menu-label";
      label.textContent = item.label;
      text.append(label);
      if (item.disabled && item.reason) {
        const reason = document.createElement("small");
        reason.className = "workbench-menu-reason";
        reason.textContent = item.reason;
        text.append(reason);
        button.title = item.reason;
      }
      button.append(check, text);
      if (item.shortcut) {
        const shortcut = document.createElement("span");
        shortcut.className = "workbench-menu-shortcut";
        shortcut.textContent = item.shortcut;
        button.append(shortcut);
      }
      commands.set(button, item);
      root.append(button);
    }
    if (anchor) {
      anchor.setAttribute("aria-haspopup", "menu");
      anchor.setAttribute("aria-controls", root.id);
      anchor.setAttribute("aria-expanded", "true");
    }
    const viewportWidth = document.documentElement.clientWidth;
    const viewportHeight = document.documentElement.clientHeight;
    root.style.maxHeight = Math.max(0, viewportHeight - 16) + "px";
    root.hidden = false;
    root.scrollTop = 0;
    const rect = anchor?.getBoundingClientRect();
    const pointerPosition = Number.isFinite(x) && Number.isFinite(y);
    const left = pointerPosition ? x : rect?.left || 8;
    let top = pointerPosition ? y : (rect?.bottom || 4) + 4;
    if (!pointerPosition && rect) {
      const below = Math.max(0, viewportHeight - rect.bottom - 12);
      const above = Math.max(0, rect.top - 12);
      const placeAbove = root.offsetHeight > below && above > below;
      // Long dropdowns scroll beside their trigger instead of covering it.
      root.style.maxHeight = (placeAbove ? above : below) + "px";
      if (placeAbove) top = rect.top - root.offsetHeight - 4;
    }
    const width = root.offsetWidth;
    const height = root.offsetHeight;
    root.style.left = Math.max(8, Math.min(left, viewportWidth - width - 8)) + "px";
    root.style.top = Math.max(8, Math.min(top, viewportHeight - height - 8)) + "px";
    const first = enabledItems()[0];
    if (first) focusItem(first);
    else root.focus({ preventScroll: true });
  }

  root.addEventListener("click", event => {
    const button = event.target.closest(".workbench-menu-item");
    const command = commands.get(button);
    if (!command || button.disabled) return;
    event.preventDefault();
    event.stopPropagation();
    close({ restoreFocus: false });
    // The caller owns action errors and asynchronous application state.
    command.run?.();
  });

  document.addEventListener("pointerdown", event => {
    if (root.hidden || root.contains(event.target) || anchor?.contains(event.target)) return;
    // Preserve the destination of the user's click instead of stealing its focus.
    close({ restoreFocus: false });
  }, true);

  document.addEventListener("keydown", event => {
    if (root.hidden || event.isComposing) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopImmediatePropagation();
      close();
      return;
    }
    if (event.key === "Tab") {
      close();
      return;
    }
    if (!root.contains(document.activeElement) || event.metaKey || event.ctrlKey || event.altKey) return;
    const buttons = enabledItems();
    const index = buttons.indexOf(document.activeElement);
    let next;
    if (event.key === "ArrowDown" || event.key === "ArrowRight") next = buttons[(index + 1) % buttons.length];
    else if (event.key === "ArrowUp" || event.key === "ArrowLeft") next = buttons[(index - 1 + buttons.length) % buttons.length];
    else if (event.key === "Home") next = buttons[0];
    else if (event.key === "End") next = buttons[buttons.length - 1];
    else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      event.stopImmediatePropagation();
      buttons[index]?.click();
      return;
    } else if (event.key.length === 1) {
      const now = Date.now();
      const key = event.key.toLocaleLowerCase();
      prefix = now - prefixTime < 750 ? prefix + key : key;
      prefixTime = now;
      const ordered = buttons.slice(index + 1).concat(buttons.slice(0, index + 1));
      const matching = value => ordered.find(button => String(commands.get(button).label).toLocaleLowerCase().startsWith(value));
      next = matching(prefix);
      if (!next) { prefix = key; next = matching(key); }
    } else return;
    event.preventDefault();
    event.stopImmediatePropagation();
    focusItem(next);
  }, true);

  document.addEventListener("scroll", event => {
    if (!root.hidden && !root.contains(event.target)) close();
  }, true);
  window.addEventListener("resize", () => close());

  controller = { open, close, isOpen: () => !root.hidden };
  return controller;
}
