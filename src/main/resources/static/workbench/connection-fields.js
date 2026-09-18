// Basic fields are intentionally limited to losslessly understood, single-host JDBC URLs.
export function connectionKind(driverClass) {
  if (["com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"].includes(driverClass)) return "mysql";
  return driverClass === "org.postgresql.Driver" ? "postgresql" : null;
}
export function parseConnectionUrl(kind, url) {
  if (!kind) return null;
  const match = url.match(new RegExp(`^jdbc:${kind}:\\/\\/(\\[[0-9a-fA-F:]+\\]|[a-zA-Z0-9_.-]+)(?::([0-9]+))?\\/([^/?#;&<>]+)$`));
  if (!match) return null;
  try {
    const fields = {host: match[1], port: match[2] || (kind === "mysql" ? "3306" : "5432"), database: decodeURIComponent(match[3])};
    buildConnectionUrl(kind, fields);
    return fields;
  } catch (_) { return null; }
}
export function buildConnectionUrl(kind, {host, port, database}) {
  if (!['mysql', 'postgresql'].includes(kind)) throw new Error("此驱动请使用完整 JDBC URL。");
  host = host.trim(); port = String(port).trim();
  if (/^[0-9a-fA-F:]+$/.test(host) && host.includes(':')) host = `[${host}]`;
  if (!/^(?:[a-zA-Z0-9_.-]+|\[[0-9a-fA-F:]+\])$/.test(host)) throw new Error("请填写单个主机名、IPv4 或 IPv6 地址。");
  if (!/^\d+$/.test(port) || +port < 1 || +port > 65535) throw new Error("端口必须为 1–65535 的整数。");
  if (!database || /[\x00-\x1f\x7f]/.test(database)) throw new Error("请填写数据库名称，不能包含控制字符。");
  const encoded = encodeURIComponent(database).replace(/[!'()*]/g, c => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
  return `jdbc:${kind}://${host}:${port}/${encoded}`;
}
export function installConnectionFields(form, getDriver) {
  const url = form.querySelector('#f-jdbcUrl'), mode = form.querySelector('#connection-mode');
  const basic = form.querySelector('#connection-basic'), hint = form.querySelector('#connection-mode-hint');
  const fields = ['host', 'port', 'database'];
  const values = () => Object.fromEntries(fields.map(key => [key, form.querySelector(`#basic-${key}`).value]));
  let originalFields = '', kind;
  function paint() {
    const enabled = mode.value === 'basic';
    basic.hidden = !enabled;
    url.readOnly = enabled;
    mode.querySelector('[value=basic]').disabled = !kind || (!parseConnectionUrl(kind, url.value) && !!url.value);
    hint.textContent = enabled ? '地址与库名生成下方 URL；特殊参数可切换到完整 URL。' : '保留完整 URL；含参数、多主机或隐藏值时请在此编辑。';
  }
  form.configureConnectionFields = () => {
    kind = connectionKind(getDriver()?.driverClass);
    const parsed = parseConnectionUrl(kind, url.value);
    mode.value = parsed ? 'basic' : 'url';
    if (parsed) fields.forEach(key => { form.querySelector(`#basic-${key}`).value = parsed[key]; });
    originalFields = JSON.stringify(values());
    paint();
  };
  form.syncConnectionUrl = () => {
    if (mode.value === 'basic') {
      const current = values();
      const built = buildConnectionUrl(kind, current);
      if (JSON.stringify(current) !== originalFields || !url.value) url.value = built;
    }
  };
  mode.addEventListener('change', () => {
    if (mode.value === 'basic') {
      const parsed = parseConnectionUrl(kind, url.value) || {host:'localhost',port:kind === 'mysql'?'3306':'5432',database:''};
      fields.forEach(key => { form.querySelector(`#basic-${key}`).value = parsed[key]; });
      originalFields = JSON.stringify(values());
    }
    paint();
  });
  basic.addEventListener('input', () => {
    try { form.syncConnectionUrl(); paint(); }
    catch (error) { hint.textContent = error.message; }
  });
  url.addEventListener('input', paint);
  form.configureConnectionFields();
}
