import { apiPost } from '../api/client.js';
import { table } from '../components/table.js';
import { toast } from '../components/toast.js';
import { $, datasourceOptions, escapeHtml, formData } from '../utils/dom.js';
import { loadDatasources } from '../state/store.js';

let lastSqlPayload = null;
let lastResult = null;

export async function renderSql(root) {
    const datasources = await loadDatasources();
    root.innerHTML = `
        <div class="sql-stack">
            <section class="panel">
                <div class="panel-header"><h2 class="panel-title">SQL 编辑器</h2></div>
                <div class="panel-body">
                    <form id="sql-form" class="form">
                        <div class="grid-3">
                            <div class="field"><label>数据源</label><select class="select" name="datasourceId">${datasourceOptions(datasources)}</select></div>
                            <div class="field"><label>最大行数</label><input class="input" name="maxRows" value="500"></div>
                            <div class="field"><label>写入确认</label><select class="select" name="confirmedWrite"><option value="false">未确认</option><option value="true">确认写入</option></select></div>
                        </div>
                        <div class="field"><label>SQL</label><textarea class="textarea sql-editor" name="sql" spellcheck="false">select * from your_table limit 20</textarea></div>
                        <div class="button-row">
                            <button class="btn primary" type="submit">执行</button>
                            <button class="btn danger" type="button" id="confirm-write" style="display:none">确认并执行写操作</button>
                        </div>
                    </form>
                </div>
            </section>
            <section class="panel">
                <div class="panel-header"><h2 class="panel-title">执行结果</h2></div>
                <div class="panel-body" id="sql-result">${renderResult(lastResult)}</div>
            </section>
        </div>
    `;
    bind(root);
}

function bind(root) {
    $('#sql-form', root).addEventListener('submit', async event => {
        event.preventDefault();
        await execute(root, false);
    });
    $('#confirm-write', root).addEventListener('click', async () => execute(root, true));
}

async function execute(root, confirmed) {
    try {
        const data = formData($('#sql-form', root));
        const payload = {
            datasourceId: data.datasourceId,
            sql: data.sql,
            maxRows: Number(data.maxRows || 500),
            confirmedWrite: confirmed || data.confirmedWrite === 'true'
        };
        lastSqlPayload = payload;
        const result = await apiPost('/api/sql/execute', payload);
        lastResult = result;
        $('#confirm-write', root).style.display = result.confirmationRequired ? '' : 'none';
        $('#sql-result', root).innerHTML = renderResult(result);
        toast(result.confirmationRequired ? '写操作需要确认' : 'SQL执行完成');
    } catch (error) {
        toast(error.message, 'error');
    }
}

function renderResult(result) {
    if (!result) {
        return '<div class="result-empty">执行后结果会显示在这里</div>';
    }
    if (result.confirmationRequired) {
        return '<div class="result-empty">检测到写操作，请确认后再执行。<pre>' + escapeHtml(lastSqlPayload ? lastSqlPayload.sql : '') + '</pre></div>';
    }
    if (result.sqlType === 'WRITE') {
        return '<div class="stat-row"><div class="stat"><div class="stat-value">' + escapeHtml(result.updateCount) + '</div><div class="stat-label">影响行数</div></div><div class="stat"><div class="stat-value">' + escapeHtml(result.elapsedMs) + 'ms</div><div class="stat-label">执行耗时</div></div></div>';
    }
    const columns = (result.columns || []).map(column => ({ key: column, label: column }));
    return table(columns, result.rows || [], { empty: '查询无结果' });
}
