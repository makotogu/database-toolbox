import { apiDelete, apiPost } from '../api/client.js';
import { table } from '../components/table.js';
import { toast } from '../components/toast.js';
import { $, escapeHtml, formData, keyValueLines } from '../utils/dom.js';
import { loadDatasources } from '../state/store.js';

let editing = null;

export async function renderDatasources(root) {
    const datasources = await loadDatasources();
    root.innerHTML = `
        <div class="page-head">
            <div>
                <h1 class="page-title">数据源管理</h1>
                <p class="page-desc">配置会保存到本地加密文件，页面不会回显密码。</p>
            </div>
        </div>
        <div class="grid-2">
            <section class="panel">
                <div class="panel-header"><h2 class="panel-title">${editing ? '编辑数据源' : '新增数据源'}</h2></div>
                <div class="panel-body">
                    <div class="notice">密码只用于本地连接测试和执行任务；保存后页面不会回显明文。</div>
                    ${formHtml(editing)}
                </div>
            </section>
            <section class="panel">
                <div class="panel-header">
                    <h2 class="panel-title">已配置数据源</h2>
                    <span class="badge">${datasources.length} 个连接</span>
                </div>
                <div class="panel-body">${listHtml(datasources)}</div>
            </section>
        </div>
    `;
    bind(root, datasources);
}

function formHtml(item) {
    item = item || {};
    const params = item.params ? Object.keys(item.params).map(k => k + '=' + item.params[k]).join('\n') : '';
    return `
        <form id="datasource-form" class="form">
            <input type="hidden" name="id" value="${escapeHtml(item.id)}">
            <div class="form-section">
                <div class="section-title">连接信息</div>
                <div class="grid-3">
                    <div class="field"><label>名称</label><input class="input" name="name" value="${escapeHtml(item.name)}" required></div>
                    <div class="field"><label>类型</label><select class="select" name="type">
                        <option value="MYSQL"${item.type === 'MYSQL' ? ' selected' : ''}>MySQL</option>
                        <option value="GAUSSDB"${isGaussDbType(item.type) ? ' selected' : ''}>GaussDB</option>
                    </select></div>
                    <div class="field"><label>端口</label><input class="input" name="port" value="${escapeHtml(item.port)}" placeholder="默认端口"></div>
                </div>
                <div class="field"><label>JDBC URL（可选，填写后优先使用）</label><input class="input" name="jdbcUrl" value="${escapeHtml(item.jdbcUrl)}" placeholder="jdbc:gaussdb://..."></div>
                <div class="grid-3">
                    <div class="field"><label>主机</label><input class="input" name="host" value="${escapeHtml(item.host || '127.0.0.1')}"></div>
                    <div class="field"><label>数据库</label><input class="input" name="databaseName" value="${escapeHtml(item.databaseName)}"></div>
                    <div class="field"><label>用户名</label><input class="input" name="username" value="${escapeHtml(item.username)}"></div>
                </div>
                <div class="field"><label>密码${item.hasPassword ? '（留空保持不变）' : ''}</label><input class="input" name="password" type="password"></div>
            </div>
            <details class="form-section compact">
                <summary>连接参数</summary>
                <div class="field"><label>key=value，每行一个</label><textarea class="textarea" name="params" placeholder="connectTimeout=5000&#10;driverClassName=com.huawei.gaussdb.jdbc.Driver&#10;urlPrefix=jdbc:gaussdb://">${escapeHtml(params)}</textarea></div>
            </details>
            <div class="button-row">
                <button class="btn primary" type="submit">保存</button>
                <button class="btn" type="button" id="test-current">测试当前配置</button>
                <button class="btn ghost" type="button" id="new-datasource">清空</button>
            </div>
        </form>
    `;
}

function listHtml(datasources) {
    return table([
        { key: 'name', label: '名称' },
        { key: 'type', label: '类型', render: row => '<span class="badge">' + escapeHtml(row.type) + '</span>', html: true },
        { key: 'host', label: '主机', render: row => row.jdbcUrl || ((row.host || '') + ':' + (row.port || '')) },
        { key: 'databaseName', label: '数据库' },
        { key: 'ops', label: '操作', html: true, render: row => `
            <div class="button-row">
                <button class="btn" data-edit="${escapeHtml(row.id)}">编辑</button>
                <button class="btn" data-test="${escapeHtml(row.id)}">测试</button>
                <button class="btn danger" data-delete="${escapeHtml(row.id)}">删除</button>
            </div>` }
    ], datasources, { empty: '还没有数据源' });
}

function bind(root, datasources) {
    $('#datasource-form', root).addEventListener('submit', async event => {
        event.preventDefault();
        try {
            await apiPost('/api/datasources', payload(event.target));
            editing = null;
            toast('数据源已保存');
            await renderDatasources(root);
        } catch (error) {
            toast(error.message, 'error');
        }
    });
    $('#new-datasource', root).addEventListener('click', async () => {
        editing = null;
        await renderDatasources(root);
    });
    $('#test-current', root).addEventListener('click', async () => {
        try {
            const result = await apiPost('/api/datasources/test', payload($('#datasource-form', root)));
            toast(result.success ? '连接成功，耗时 ' + result.elapsedMs + 'ms' : result.message, result.success ? '' : 'error');
        } catch (error) {
            toast(error.message, 'error');
        }
    });
    root.querySelectorAll('[data-edit]').forEach(button => {
        button.addEventListener('click', async () => {
            editing = datasources.find(item => item.id === button.dataset.edit);
            await renderDatasources(root);
        });
    });
    root.querySelectorAll('[data-test]').forEach(button => {
        button.addEventListener('click', async () => {
            const result = await apiPost('/api/datasources/' + button.dataset.test + '/test');
            toast(result.success ? '连接成功，耗时 ' + result.elapsedMs + 'ms' : result.message, result.success ? '' : 'error');
        });
    });
    root.querySelectorAll('[data-delete]').forEach(button => {
        button.addEventListener('click', async () => {
            if (!confirm('确认删除这个数据源？')) {
                return;
            }
            await apiDelete('/api/datasources/' + button.dataset.delete);
            toast('数据源已删除');
            await renderDatasources(root);
        });
    });
}

function isGaussDbType(type) {
    // 兼容旧版本保存过的连接类型，页面上统一展示为 GaussDB。
    return type === 'GAUSSDB' || type === 'POSTGRESQL' || type === 'RANGE';
}

function payload(form) {
    const data = formData(form);
    return {
        id: data.id || null,
        name: data.name,
        type: data.type,
        host: data.host,
        port: data.port ? Number(data.port) : null,
        databaseName: data.databaseName,
        username: data.username,
        password: data.password,
        jdbcUrl: data.jdbcUrl,
        params: keyValueLines(data.params)
    };
}
