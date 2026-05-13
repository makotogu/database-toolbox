import { apiPost } from '../api/client.js';
import { toast } from '../components/toast.js';
import { $, datasourceOptions, formData } from '../utils/dom.js';
import { loadDatasources } from '../state/store.js';

export async function renderBackupExport(root) {
    const datasources = await loadDatasources();
    root.innerHTML = `
        <section class="panel">
            <div class="panel-header"><h2 class="panel-title">导出 ZIP 数据包</h2></div>
            <div class="panel-body">
                <form id="backup-form" class="form">
                    <div class="grid-3">
                        <div class="field"><label>数据源</label><select class="select" name="datasourceId">${datasourceOptions(datasources)}</select></div>
                        <div class="field"><label>表名</label><input class="input" name="tableName" placeholder="schema.table 或 table"></div>
                        ${delimiterField('delimiter')}
                    </div>
                    <div class="field delimiter-custom" data-for="delimiter" style="display:none"><label>自定义分隔符</label><input class="input" name="delimiterCustom" placeholder="可填单字符、CHAR(27)、\\u001B、0x1B"></div>
                    <div class="field"><label>WHERE 条件（不含 where）</label><input class="input" name="whereClause" placeholder="tenant_id = 1001"></div>
                    <div class="grid-3">
                        <div class="field"><label>字符集</label><input class="input" name="charset" value="UTF-8"></div>
                        <div class="field"><label>Fetch Size</label><input class="input" name="fetchSize" value="1000"></div>
                        <div class="field"><label>&nbsp;</label><div class="mapping-help">Quote/Escape 使用系统默认双引号，已写入备份包 manifest。</div></div>
                    </div>
                    <div class="button-row"><button class="btn primary" type="submit">开始导出</button></div>
                </form>
            </div>
        </section>
    `;
    bindExport(root);
}

export async function renderBackupRestore(root) {
    const datasources = await loadDatasources();
    root.innerHTML = `
        <section class="panel">
            <div class="panel-header"><h2 class="panel-title">从备份恢复</h2></div>
            <div class="panel-body">
                <form id="restore-form" class="form">
                    <div class="grid-3">
                        <div class="field"><label>目标数据源</label><select class="select" name="datasourceId">${datasourceOptions(datasources)}</select></div>
                        <div class="field"><label>备份文件名或路径</label><input class="input" name="backupFile" placeholder="backup-jobid.zip"></div>
                        <div class="field"><label>Batch Size</label><input class="input" name="batchSize" value="1000"></div>
                    </div>
                    <div class="field"><label>目标表（可选，默认使用备份元数据表名）</label><input class="input" name="targetTableName"></div>
                    <p class="mapping-help">恢复会优先读取 ZIP 内 manifest 的分隔符、字符集、Quote 和 Escape 配置；旧备份包缺少 manifest 字段时使用系统默认。</p>
                    <div class="button-row"><button class="btn danger" type="submit">创建恢复任务</button></div>
                </form>
            </div>
        </section>
    `;
    bindRestore(root);
}

function bindExport(root) {
    bindDelimiterSelects(root);
    $('#backup-form', root).addEventListener('submit', async event => {
        event.preventDefault();
        try {
            const data = formData(event.target);
            const job = await apiPost('/api/backups/export', {
                datasourceId: data.datasourceId,
                tableName: data.tableName,
                whereClause: data.whereClause,
                delimiter: selectedDelimiter(data),
                charset: data.charset || 'UTF-8',
                fetchSize: Number(data.fetchSize || 1000)
            });
            toast('导出任务已创建: ' + job.id);
            location.hash = 'jobs';
        } catch (error) {
            toast(error.message, 'error');
        }
    });
}

function bindRestore(root) {
    $('#restore-form', root).addEventListener('submit', async event => {
        event.preventDefault();
        try {
            const data = formData(event.target);
            const job = await apiPost('/api/backups/restore', {
                datasourceId: data.datasourceId,
                backupFile: data.backupFile,
                targetTableName: data.targetTableName,
                batchSize: Number(data.batchSize || 1000)
            });
            toast('恢复任务已创建: ' + job.id);
            location.hash = 'jobs';
        } catch (error) {
            toast(error.message, 'error');
        }
    });
}

function delimiterField(name) {
    return `
        <div class="field">
            <label>分隔符</label>
            <select class="select delimiter-select" name="${name}">
                <option value=",">逗号 (,)</option>
                <option value="|">竖线 (|)</option>
                <option value="\\t">Tab · CHAR(9)</option>
                <option value="CHAR(27)" selected>Char(27) · ESC</option>
                <option value="CHAR(1)">Char(1) · Ctrl-A</option>
                <option value="CUSTOM">自定义</option>
            </select>
        </div>
    `;
}

function bindDelimiterSelects(root) {
    root.querySelectorAll('.delimiter-select').forEach(select => {
        const form = select.closest('form');
        const custom = form.querySelector('.delimiter-custom[data-for="' + select.name + '"]');
        const sync = () => {
            if (custom) {
                custom.style.display = select.value === 'CUSTOM' ? '' : 'none';
            }
        };
        select.addEventListener('change', sync);
        sync();
    });
}

function selectedDelimiter(data) {
    if (data.delimiter === 'CUSTOM') {
        return data.delimiterCustom || ',';
    }
    return data.delimiter || ',';
}
