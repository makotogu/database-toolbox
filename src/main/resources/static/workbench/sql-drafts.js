// One serial save queue per connection. Failed requests retain their identity for safe retry.
export function createDraftManager({request, snapshot, changed, uuid, delay = 900}) {
  const records = new Map();
  const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  function forget(id) {
    const r = records.get(id);
    if (r) clearTimeout(r.timer);
    records.delete(id);
  }
  async function load(id) {
    forget(id);
    const r = {saved: [], loading: true}; records.set(id, r);
    try {
      const result = await request(id);
      if (records.get(id) !== r) return [];
      r.revision = result.revision; r.saved = result.tabs; r.loading = false;
      changed(); return result.tabs;
    } catch (e) {
      if (records.get(id) !== r) return [];
      r.loading = false; r.blocked = true;
      r.error = '读取草稿失败。请先下载当前 SQL，再刷新页面。' + e.message;
      changed(); return [];
    }
  }
  function schedule() {
    for (const [id, r] of records) {
      if (r.loading || r.blocked || r.error || r.saving) continue;
      clearTimeout(r.timer);
      if (!equal(snapshot(id), r.saved)) r.timer = setTimeout(() => save(id), delay);
    }
    changed();
  }
  async function save(id) {
    const r = records.get(id);
    if (!r || r.loading || r.blocked || r.saving) return;
    clearTimeout(r.timer);
    const tabs = snapshot(id);
    if (!r.pending && equal(tabs, r.saved)) { r.error = ''; changed(); return; }
    r.pending ||= {revision: r.revision, requestId: uuid(), tabs};
    r.saving = true; r.error = ''; changed();
    try {
      const result = await request(id, r.pending);
      if (records.get(id) !== r) return;
      r.revision = result.revision; r.saved = result.tabs; r.pending = null;
    } catch (e) {
      if (records.get(id) !== r) return;
      r.error = e.message;
      r.blocked = e.status === 409;
      // A definitive validation rejection has not stored anything; a changed draft may retry.
      if (e.status === 400) r.pending = null;
    } finally {
      if (records.get(id) === r) { r.saving = false; schedule(); }
    }
  }
  function saved(tab) {
    const r = records.get(tab.connectionId);
    return !!r && !r.blocked && r.saved.some(x => x.id === (tab.draftId || tab.id) && x.sql === tab.sql && x.name === tab.name);
  }
  function dirty() {
    return [...records].some(([id, r]) => !!r.pending || (!r.loading && !r.blocked && !equal(snapshot(id), r.saved)));
  }
  function status(id) {
    const r = records.get(id);
    if (!r) return {label: '草稿保存未开启', message: '可在编辑连接中开启本机加密草稿保存。SQL 文件仍可用 ⌘/Ctrl + S 手动下载。'};
    if (r.loading) return {label: '正在读取草稿…'};
    if (r.error) return {label: '草稿未保存', message: r.error, retry: !r.blocked, error: true};
    if (r.saving) return {label: '正在保存草稿…'};
    if (!equal(snapshot(id), r.saved)) return {label: '草稿待保存…'};
    return {label: '草稿已加密保存', message: '已保存在本机。关闭 SQL 标签会删除对应草稿；关闭连接的保存选项会清除该连接的全部草稿。不会恢复会话或执行 SQL。'};
  }
  return {load, forget, schedule, save, saved, dirty, status};
}
