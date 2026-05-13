import { apiDelete, apiGet, apiPost } from '../api/client.js';
import { table } from '../components/table.js';
import { toast } from '../components/toast.js';
import { $, datasourceOptions, escapeHtml, formData } from '../utils/dom.js';
import { loadDatasources } from '../state/store.js';

let editingTask = null;
let mappingRows = [];
let mappingRowSeq = 0;
let currentTargetColumns = [];

const EXAMPLE_MAPPING = [
    'id=id',
    'tenant_id=tenant_id',
    'user_name=user_name',
    'email=email',
    'status=status',
    'balance=balance'
].join('\n');

const MYSQL_RANGE_PARTITION_TEMPLATE = "ALTER TABLE {targetTableQuoted} ADD PARTITION (PARTITION {partitionName} VALUES LESS THAN ({lessThanValue}));";
const MYSQL_LIST_PARTITION_TEMPLATE = "ALTER TABLE {targetTableQuoted} ADD PARTITION (PARTITION {partitionName} VALUES IN ({lessThanValue}));";
const GAUSS_RANGE_PARTITION_TEMPLATE = "ALTER TABLE {targetTableQuoted} ADD PARTITION {partitionNameQuoted} VALUES LESS THAN ({lessThanValue});";
const GAUSS_LIST_PARTITION_TEMPLATE = "ALTER TABLE {targetTableQuoted} ADD PARTITION {partitionNameQuoted} VALUES ({lessThanValue});";
const PARTITION_TEMPLATES = {
    MYSQL: {
        RANGE: MYSQL_RANGE_PARTITION_TEMPLATE,
        LIST: MYSQL_LIST_PARTITION_TEMPLATE
    },
    GAUSSDB: {
        RANGE: GAUSS_RANGE_PARTITION_TEMPLATE,
        LIST: GAUSS_LIST_PARTITION_TEMPLATE
    }
};
// Range/List 是分区策略，不是数据源类型；定义格式统一使用 partitionName|fromValue|toValue|lessThanValue。
const EXAMPLE_PARTITIONS = [
    'user_profile_target_p202606|||2026-06-01',
    'user_profile_target_p202607|||2026-07-01'
].join('\n');

export async function renderSync(root) {
    const datasources = await loadDatasources();
    const tasks = await apiGet('/api/sync-tasks');
    resetMappingRows(editingTask);
    root.innerHTML = `
        <div class="sync-stack">
            <section class="panel">
                <div class="panel-header"><h2 class="panel-title">${editingTask ? '编辑同步模板' : '新增同步模板'}</h2></div>
                <div class="panel-body">${formHtml(datasources, editingTask)}</div>
            </section>
            <section class="panel">
                <div class="panel-header">
                    <h2 class="panel-title">测试库示例</h2>
                    <button class="btn" type="button" id="fill-sync-example">填入示例</button>
                </div>
                <div class="panel-body">${exampleHtml(datasources)}</div>
            </section>
            <section class="panel">
                <div class="panel-header"><h2 class="panel-title">同步模板</h2></div>
                <div class="panel-body">${listHtml(tasks)}</div>
            </section>
        </div>
    `;
    bind(root, tasks, datasources);
}

function formHtml(datasources, task) {
    task = task || {};
    return `
        <form id="sync-form" class="form">
            <input type="hidden" name="id" value="${escapeHtml(task.id)}">
            <div class="field"><label>模板名称</label><input class="input" name="name" value="${escapeHtml(task.name)}" required></div>
            <div class="grid-3">
                <div class="field"><label>源数据源</label><select class="select" name="sourceDatasourceId">${datasourceOptions(datasources, task.sourceDatasourceId)}</select></div>
                <div class="field"><label>目标数据源</label><select class="select" name="targetDatasourceId">${datasourceOptions(datasources, task.targetDatasourceId)}</select></div>
                <div class="field"><label>写前备份</label><select class="select" name="backupBeforeWrite"><option value="true">开启</option><option value="false"${task.backupBeforeWrite === false ? ' selected' : ''}>关闭</option></select></div>
            </div>
            <div class="grid-3">
                <div class="field"><label>源表</label><input class="input" name="sourceTable" value="${escapeHtml(task.sourceTable)}"></div>
                <div class="field"><label>目标表</label><input class="input" name="targetTable" value="${escapeHtml(task.targetTable)}"></div>
                <div class="field"><label>匹配键（目标字段，逗号分隔）</label><input class="input" name="matchKeys" value="${escapeHtml((task.matchKeys || []).join(','))}"></div>
            </div>
            <div class="field"><label>源表 WHERE 条件</label><input class="input" name="whereClause" value="${escapeHtml(task.whereClause)}"></div>
            <div id="mapping-editor">${mappingEditorHtml()}</div>
            ${partitionRuleHtml(task.partitionRule, datasources, task.targetDatasourceId)}
            <div class="grid-3">
                <div class="field"><label>Fetch Size</label><input class="input" name="fetchSize" value="${escapeHtml(task.fetchSize || 1000)}"></div>
                <div class="field"><label>Batch Size</label><input class="input" name="batchSize" value="${escapeHtml(task.batchSize || 1000)}"></div>
                <div class="field"><label>&nbsp;</label><div class="mapping-help">按匹配键 upsert，目标其他数据保留。</div></div>
            </div>
            <div class="button-row">
                <button class="btn primary" type="submit">保存模板</button>
                <button class="btn ghost" type="button" id="new-sync">清空</button>
            </div>
        </form>
    `;
}

function mappingEditorHtml() {
    return `
        <div class="field mapping-editor">
            <div class="label-row">
                <label>字段映射</label>
                <div class="mapping-toolbar">
                    <button class="btn compact" type="button" id="auto-fill-mappings">自动填入字段</button>
                    <button class="btn compact" type="button" id="add-mapping-row">新增映射行</button>
                </div>
            </div>
            ${mappingTableHtml()}
            <div class="mapping-help">表格只负责编辑字段映射；保存时仍提交后端原有的 sourceColumn/targetColumn 结构。未匹配行默认不启用。</div>
            <details class="mapping-advanced">
                <summary>高级文本模式</summary>
                <div class="mapping-advanced-body">
                    <textarea class="textarea mapping-textarea" id="field-mapping-text" placeholder="id=id&#10;name=user_name">${escapeHtml(rowsToMappingText(mappingRows))}</textarea>
                    <div class="button-row">
                        <button class="btn compact" type="button" id="import-mapping-text">从文本导入表格</button>
                    </div>
                </div>
            </details>
        </div>
    `;
}

function mappingTableHtml() {
    if (!mappingRows.length) {
        return '<div class="result-empty mapping-empty">还没有字段映射。可点击“自动填入字段”读取元数据，或点击“新增映射行”。</div>';
    }
    return `
        <div class="mapping-table-wrap">
            <table class="mapping-table">
                <thead>
                    <tr>
                        <th>启用</th>
                        <th>源字段</th>
                        <th>源类型</th>
                        <th>目标字段</th>
                        <th>目标类型</th>
                        <th>匹配状态</th>
                        <th>主键</th>
                        <th>操作</th>
                    </tr>
                </thead>
                <tbody>${mappingRows.map(mappingRowHtml).join('')}</tbody>
            </table>
        </div>
    `;
}

function mappingRowHtml(row) {
    return `
        <tr data-mapping-row="${escapeHtml(row.id)}">
            <td class="mapping-check"><input type="checkbox" data-map-enabled${row.enabled ? ' checked' : ''}></td>
            <td><input class="input mapping-input" data-map-source value="${escapeHtml(row.sourceColumn)}" placeholder="source_column"></td>
            <td><span class="mapping-type">${escapeHtml(row.sourceType || '-')}</span></td>
            <td><select class="select mapping-select" data-map-target>${targetColumnOptions(row.targetColumn)}</select></td>
            <td><span class="mapping-type">${escapeHtml(row.targetType || '-')}</span></td>
            <td>${mappingStatusHtml(row)}</td>
            <td>${row.primaryKey ? '<span class="badge success">PK</span>' : '<span class="mapping-muted">-</span>'}</td>
            <td class="mapping-actions"><button class="btn compact danger" type="button" data-map-delete>删除</button></td>
        </tr>
    `;
}

function targetColumnOptions(selected) {
    const options = ['<option value="">不匹配</option>'];
    const hasSelected = currentTargetColumns.some(column => column.columnName === selected);
    currentTargetColumns.forEach(column => {
        const name = column.columnName || '';
        options.push('<option value="' + escapeHtml(name) + '"' + (name === selected ? ' selected' : '') + '>' + escapeHtml(name) + '</option>');
    });
    if (selected && !hasSelected) {
        options.push('<option value="' + escapeHtml(selected) + '" selected>' + escapeHtml(selected) + '</option>');
    }
    return options.join('');
}

function mappingStatusHtml(row) {
    if (row.targetColumn && row.status === 'LOOSE') {
        return '<span class="mapping-status warn">宽松匹配</span>';
    }
    if (row.targetColumn) {
        return '<span class="mapping-status success">已匹配</span>';
    }
    return '<span class="mapping-status muted">未匹配</span>';
}

function partitionRuleHtml(rule, datasources, targetDatasourceId) {
    rule = rule || {};
    const targetType = datasourceType(datasources, targetDatasourceId);
    const definitions = (rule.definitions || []).map(item => [
        item.partitionName || '',
        item.fromValue || '',
        item.toValue || '',
        item.lessThanValue || ''
    ].join('|').replace(/\|+$/, '')).join('\n');
    return `
        <div class="partition-rule">
            <div class="grid-3">
                <div class="field"><label>自动创建分区</label><select class="select" name="partitionEnabled">
                    <option value="false">关闭</option>
                    <option value="true"${rule.enabled ? ' selected' : ''}>开启</option>
                </select></div>
                <div class="field"><label>分区策略</label><select class="select" name="partitionStrategy">
                    <option value="RANGE"${(rule.partitionStrategy || 'RANGE') === 'RANGE' ? ' selected' : ''}>Range</option>
                    <option value="LIST"${rule.partitionStrategy === 'LIST' ? ' selected' : ''}>List</option>
                </select></div>
                <div class="field"><label>分区字段</label><input class="input" name="partitionColumn" value="${escapeHtml(rule.partitionColumn)}" placeholder="created_at"></div>
            </div>
            <div class="grid-3">
                <div class="field"><label>创建失败处理</label><select class="select" name="ignorePartitionErrors">
                    <option value="true">忽略已存在/重复创建错误</option>
                    <option value="false"${rule.ignoreCreateErrors === false ? ' selected' : ''}>失败即停止同步</option>
                </select></div>
            </div>
            <div class="field">
                <div class="label-row">
                    <label>分区定义（partitionName|fromValue|toValue|lessThanValue）</label>
                    <button class="btn compact" type="button" id="probe-source-partitions">探查源表分区</button>
                </div>
                <textarea class="textarea partition-textarea" name="partitionDefinitions" placeholder="user_order_p202606|||2026-06-01">${escapeHtml(definitions)}</textarea>
            </div>
            <div class="field"><label>建分区 SQL 模板</label><textarea class="textarea partition-template" name="partitionSqlTemplate" spellcheck="false">${escapeHtml(rule.sqlTemplate || templateForPartitionStrategy(rule.partitionStrategy, targetType))}</textarea></div>
            <div class="mapping-help">可用变量：{targetTableQuoted}、{partitionNameQuoted}、{partitionColumnQuoted}、{fromValue}、{toValue}、{lessThanValue}。Range 用 VALUES LESS THAN，List 用 VALUES IN。</div>
        </div>
    `;
}

function exampleHtml(datasources) {
    const datasourceName = exampleDatasource(datasources)
            ? exampleDatasource(datasources).name
            : '先在数据源页面保存 local-mysql-toolbox';
    return `
        <div class="sync-example">
            <div>
                <div class="sync-example-label">数据源</div>
                <div class="sync-example-value">${escapeHtml(datasourceName)}</div>
            </div>
            <div>
                <div class="sync-example-label">源表</div>
                <div class="sync-example-value">user_profile_source</div>
            </div>
            <div>
                <div class="sync-example-label">目标表</div>
                <div class="sync-example-value">user_profile_target</div>
            </div>
            <div>
                <div class="sync-example-label">匹配键</div>
                <div class="sync-example-value">id</div>
            </div>
            <div class="sync-example-map">
                <div class="sync-example-label">字段映射</div>
                <pre>${escapeHtml(EXAMPLE_MAPPING)}</pre>
            </div>
            <div class="sync-example-map">
                <div class="sync-example-label">分区规则</div>
                <pre>${escapeHtml('分区字段: updated_at\n' + EXAMPLE_PARTITIONS)}</pre>
            </div>
        </div>
    `;
}

function listHtml(tasks) {
    return table([
        { key: 'name', label: '名称' },
        { key: 'sourceTable', label: '源表' },
        { key: 'targetTable', label: '目标表' },
        { key: 'matchKeys', label: '匹配键', render: row => (row.matchKeys || []).join(', ') },
        { key: 'ops', label: '操作', html: true, render: row => `
            <div class="button-row">
                <button class="btn" data-edit="${escapeHtml(row.id)}">编辑</button>
                <button class="btn primary" data-run="${escapeHtml(row.id)}">执行</button>
                <button class="btn danger" data-delete="${escapeHtml(row.id)}">删除</button>
            </div>` }
    ], tasks, { empty: '还没有同步模板' });
}

function bind(root, tasks, datasources) {
    $('#sync-form', root).addEventListener('submit', async event => {
        event.preventDefault();
        try {
            syncMappingRowsFromDom(event.target);
            await apiPost('/api/sync-tasks', payload(event.target));
            editingTask = null;
            toast('同步模板已保存');
            await renderSync(root);
        } catch (error) {
            toast(error.message, 'error');
        }
    });
    $('#new-sync', root).addEventListener('click', async () => {
        editingTask = null;
        await renderSync(root);
    });
    $('#fill-sync-example', root).addEventListener('click', () => {
        fillExample(root, datasources);
    });
    bindMappingEditor(root);
    $('[name="targetDatasourceId"]', root).addEventListener('change', () => {
        updatePartitionTemplate(root, $('[name="partitionStrategy"]', root).value, datasources);
    });
    $('[name="partitionStrategy"]', root).addEventListener('change', event => {
        updatePartitionTemplate(root, event.target.value, datasources);
    });
    $('#probe-source-partitions', root).addEventListener('click', async () => {
        await probeSourcePartitions(root, datasources);
    });
    root.querySelectorAll('[data-edit]').forEach(button => {
        button.addEventListener('click', async () => {
            editingTask = tasks.find(item => item.id === button.dataset.edit);
            await renderSync(root);
        });
    });
    root.querySelectorAll('[data-run]').forEach(button => {
        button.addEventListener('click', async () => {
            try {
                const job = await apiPost('/api/sync-tasks/run', { taskId: button.dataset.run });
                toast('同步任务已创建: ' + job.id);
                location.hash = 'jobs';
            } catch (error) {
                toast(error.message, 'error');
            }
        });
    });
    root.querySelectorAll('[data-delete]').forEach(button => {
        button.addEventListener('click', async () => {
            if (!confirm('确认删除这个同步模板？')) {
                return;
            }
            await apiDelete('/api/sync-tasks/' + button.dataset.delete);
            toast('同步模板已删除');
            await renderSync(root);
        });
    });
}

function bindMappingEditor(root) {
    $('#auto-fill-mappings', root).addEventListener('click', async () => {
        await autoFillMappings(root);
    });
    $('#add-mapping-row', root).addEventListener('click', () => {
        syncMappingRowsFromDom($('#sync-form', root));
        mappingRows.push(createMappingRow({
            enabled: true,
            status: 'CUSTOM'
        }));
        renderMappingEditor(root);
    });
    const importButton = $('#import-mapping-text', root);
    if (importButton) {
        importButton.addEventListener('click', () => {
            const text = $('#field-mapping-text', root).value;
            mappingRows = rowsFromMappingText(text);
            setMatchKeysFromRows(root);
            renderMappingEditor(root);
            toast('已从文本导入 ' + mappingRows.length + ' 个字段映射');
        });
    }
    bindMappingRowEvents(root);
}

function bindMappingRowEvents(root) {
    root.querySelectorAll('[data-mapping-row]').forEach(tr => {
        const row = findMappingRow(tr.dataset.mappingRow);
        if (!row) {
            return;
        }
        tr.querySelector('[data-map-enabled]').addEventListener('change', event => {
            row.enabled = event.target.checked;
        });
        tr.querySelector('[data-map-source]').addEventListener('input', event => {
            row.sourceColumn = event.target.value;
        });
        tr.querySelector('[data-map-target]').addEventListener('change', event => {
            row.targetColumn = event.target.value;
            row.enabled = !!row.sourceColumn && !!row.targetColumn;
            applyTargetMetadata(row);
            renderMappingEditor(root);
        });
        tr.querySelector('[data-map-delete]').addEventListener('click', () => {
            mappingRows = mappingRows.filter(item => item.id !== row.id);
            renderMappingEditor(root);
        });
    });
}

function renderMappingEditor(root) {
    const container = $('#mapping-editor', root);
    container.innerHTML = mappingEditorHtml();
    bindMappingEditor(root);
}

async function autoFillMappings(root) {
    const form = $('#sync-form', root);
    const data = formData(form);
    if (!data.sourceDatasourceId || !data.targetDatasourceId || !data.sourceTable || !data.targetTable) {
        toast('请先选择源/目标数据源，并填写源表和目标表', 'error');
        return;
    }
    syncMappingRowsFromDom(form);
    if (hasActiveMappingRows() && !confirm('字段映射已有内容，是否用自动匹配结果覆盖？')) {
        return;
    }
    try {
        /*
         * 字段映射只在页面回填，不会立即保存。
         * 匹配策略保持可解释：同名优先，其次忽略大小写和下划线做一次宽松匹配。
         */
        const sourceColumns = await loadColumns(data.sourceDatasourceId, data.sourceTable);
        const targetColumns = await loadColumns(data.targetDatasourceId, data.targetTable);
        currentTargetColumns = targetColumns || [];
        const result = buildAutoMappingRows(sourceColumns, currentTargetColumns);
        if (!result.rows.length) {
            toast('没有读取到源表字段', 'error');
            return;
        }
        mappingRows = result.rows;
        const matchKeys = result.primaryKeys.length ? result.primaryKeys : guessMatchKeysFromRows(mappingRows);
        if (matchKeys.length) {
            form.querySelector('[name="matchKeys"]').value = matchKeys.join(',');
        }
        renderMappingEditor(root);
        toast('已生成 ' + mappingRows.length + ' 行映射，匹配 ' + result.matchedCount + ' 行');
    } catch (error) {
        toast(error.message, 'error');
    }
}

async function loadColumns(datasourceId, rawTableName) {
    const tableName = parseQualifiedTable(rawTableName);
    const params = new URLSearchParams();
    params.set('table', tableName.table);
    if (tableName.schema) {
        params.set('schema', tableName.schema);
    }
    return apiGet('/api/datasources/' + encodeURIComponent(datasourceId) + '/columns?' + params.toString());
}

function parseQualifiedTable(rawTableName) {
    const value = String(rawTableName || '').trim();
    const dot = value.lastIndexOf('.');
    if (dot > 0 && dot < value.length - 1) {
        return {
            schema: stripIdentifierQuote(value.slice(0, dot)),
            table: stripIdentifierQuote(value.slice(dot + 1))
        };
    }
    return { schema: '', table: stripIdentifierQuote(value) };
}

function stripIdentifierQuote(value) {
    const trimmed = String(value || '').trim();
    if (trimmed.length >= 2) {
        const first = trimmed.charAt(0);
        const last = trimmed.charAt(trimmed.length - 1);
        if ((first === '"' && last === '"') || (first === '`' && last === '`')) {
            return trimmed.slice(1, -1);
        }
    }
    return trimmed;
}

function buildAutoMappingRows(sourceColumns, targetColumns) {
    const targetExact = new Map();
    const targetLoose = new Map();
    (targetColumns || []).forEach(column => {
        const name = column.columnName || '';
        targetExact.set(normalizeColumn(name), column);
        const looseKey = looseColumnKey(name);
        if (!targetLoose.has(looseKey)) {
            targetLoose.set(looseKey, column);
        }
    });
    const rows = [];
    const primaryKeys = [];
    let matchedCount = 0;
    (sourceColumns || []).forEach(source => {
        const sourceName = source.columnName || '';
        const exactTarget = targetExact.get(normalizeColumn(sourceName));
        const looseTarget = exactTarget ? null : targetLoose.get(looseColumnKey(sourceName));
        const target = exactTarget || looseTarget;
        const row = createMappingRow({
            enabled: !!target,
            sourceColumn: sourceName,
            sourceType: columnTypeLabel(source),
            targetColumn: target ? target.columnName : '',
            targetType: target ? columnTypeLabel(target) : '-',
            primaryKey: !!(target && target.primaryKey),
            status: target ? (exactTarget ? 'MATCHED' : 'LOOSE') : 'UNMATCHED'
        });
        if (target) {
            matchedCount += 1;
        }
        if (row.primaryKey) {
            primaryKeys.push(row.targetColumn);
        }
        rows.push(row);
    });
    return { rows: rows, primaryKeys: primaryKeys, matchedCount: matchedCount };
}

function rowsFromMappingText(text) {
    return String(text || '').split(/\r?\n/).map(line => {
        const index = line.indexOf('=');
        if (index <= 0) {
            return null;
        }
        const row = createMappingRow({
            enabled: true,
            sourceColumn: line.slice(0, index).trim(),
            targetColumn: line.slice(index + 1).trim(),
            status: 'CUSTOM'
        });
        applyTargetMetadata(row);
        return row;
    }).filter(Boolean);
}

function rowsToMappingText(rows) {
    return (rows || []).filter(row => row.sourceColumn && row.targetColumn).map(row => row.sourceColumn + '=' + row.targetColumn).join('\n');
}

function resetMappingRows(task) {
    currentTargetColumns = [];
    mappingRows = (task && task.fieldMappings ? task.fieldMappings : []).map(item => createMappingRow({
        enabled: true,
        sourceColumn: item.sourceColumn,
        targetColumn: item.targetColumn,
        status: 'CUSTOM'
    }));
}

function createMappingRow(values) {
    const row = values || {};
    mappingRowSeq += 1;
    return {
        id: 'mapping-row-' + mappingRowSeq,
        enabled: row.enabled === undefined ? true : !!row.enabled,
        sourceColumn: row.sourceColumn || '',
        sourceType: row.sourceType || '-',
        targetColumn: row.targetColumn || '',
        targetType: row.targetType || '-',
        primaryKey: !!row.primaryKey,
        status: row.status || (row.targetColumn ? 'MATCHED' : 'UNMATCHED')
    };
}

function syncMappingRowsFromDom(form) {
    form.querySelectorAll('[data-mapping-row]').forEach(tr => {
        const row = findMappingRow(tr.dataset.mappingRow);
        if (!row) {
            return;
        }
        row.enabled = tr.querySelector('[data-map-enabled]').checked;
        row.sourceColumn = tr.querySelector('[data-map-source]').value.trim();
        row.targetColumn = tr.querySelector('[data-map-target]').value.trim();
        applyTargetMetadata(row);
    });
}

function findMappingRow(id) {
    return mappingRows.find(row => row.id === id);
}

function applyTargetMetadata(row) {
    const target = currentTargetColumns.find(column => column.columnName === row.targetColumn);
    row.targetType = target ? columnTypeLabel(target) : (row.targetColumn ? row.targetType || '-' : '-');
    row.primaryKey = !!(target && target.primaryKey);
    row.status = row.targetColumn ? (row.status === 'LOOSE' ? 'LOOSE' : 'MATCHED') : 'UNMATCHED';
}

function hasActiveMappingRows() {
    return mappingRows.some(row => row.enabled && row.sourceColumn && row.targetColumn);
}

function setMatchKeysFromRows(root) {
    const keys = guessMatchKeysFromRows(mappingRows);
    if (keys.length) {
        $('#sync-form', root).querySelector('[name="matchKeys"]').value = keys.join(',');
    }
}

function guessMatchKeysFromRows(rows) {
    const targetColumns = rows.filter(row => row.enabled && row.targetColumn).map(row => row.targetColumn);
    if (targetColumns.indexOf('id') >= 0) {
        return ['id'];
    }
    const lowerId = targetColumns.find(column => column.toLowerCase() === 'id');
    return lowerId ? [lowerId] : [];
}

function columnTypeLabel(column) {
    if (!column) {
        return '-';
    }
    const base = column.typeName || '-';
    if (column.columnSize && column.decimalDigits !== null && column.decimalDigits !== undefined && Number(column.decimalDigits) > 0) {
        return base + '(' + column.columnSize + ',' + column.decimalDigits + ')';
    }
    if (column.columnSize && isSizedType(base)) {
        return base + '(' + column.columnSize + ')';
    }
    return base;
}

function isSizedType(typeName) {
    const value = String(typeName || '').toUpperCase();
    return value.indexOf('CHAR') >= 0 || value.indexOf('DECIMAL') >= 0 || value.indexOf('NUMERIC') >= 0;
}

function normalizeColumn(name) {
    return String(name || '').trim().toLowerCase();
}

function looseColumnKey(name) {
    return normalizeColumn(name).replace(/_/g, '');
}

async function probeSourcePartitions(root, datasources) {
    const form = $('#sync-form', root);
    const data = formData(form);
    if (!data.sourceDatasourceId || !data.sourceTable) {
        toast('请先选择源数据源并填写源表', 'error');
        return;
    }
    try {
        /*
         * 探查只负责从源库读取现有分区并回填页面，不会保存模板，也不会创建目标分区。
         * 用户确认分区名/边界/SQL 模板后，再点击“保存模板”。
         */
        const result = await apiPost('/api/sync-tasks/probe-partitions', {
            sourceDatasourceId: data.sourceDatasourceId,
            sourceTable: data.sourceTable,
            targetTable: data.targetTable
        });
        form.querySelector('[name="partitionEnabled"]').value = 'true';
        if (result.partitionStrategy) {
            form.querySelector('[name="partitionStrategy"]').value = result.partitionStrategy;
        }
        form.querySelector('[name="partitionDefinitions"]').value = formatPartitionDefinitions(result.definitions || []);
        form.querySelector('[name="partitionSqlTemplate"]').value = templateForPartitionStrategy(
                result.partitionStrategy || data.partitionStrategy,
                datasourceType(datasources, data.targetDatasourceId)
        );
        toast(result.message || '源表分区已填入');
    } catch (error) {
        toast(error.message, 'error');
    }
}

function formatPartitionDefinitions(definitions) {
    // 去掉行尾空字段；GaussDB/MySQL RANGE/LIST 分区通常显示 name|||boundaryOrValues。
    return definitions.map(item => [
        item.partitionName || '',
        item.fromValue || '',
        item.toValue || '',
        item.lessThanValue || ''
    ].join('|').replace(/\|+$/, '')).join('\n');
}

function updatePartitionTemplate(root, strategy, datasources) {
    const templateInput = $('[name="partitionSqlTemplate"]', root);
    const current = templateInput.value.trim();
    const knownTemplates = allKnownPartitionTemplates();
    if (!current || knownTemplates.indexOf(current) >= 0) {
        templateInput.value = templateForPartitionStrategy(strategy, datasourceType(datasources, $('[name="targetDatasourceId"]', root).value));
    }
}

function templateForPartitionStrategy(strategy, datasourceTypeValue) {
    const type = normalizeDatasourceType(datasourceTypeValue);
    const strategyKey = String(strategy || 'RANGE').toUpperCase();
    const group = PARTITION_TEMPLATES[type] || PARTITION_TEMPLATES.GAUSSDB;
    return group[strategyKey] || group.RANGE;
}

function allKnownPartitionTemplates() {
    return Object.keys(PARTITION_TEMPLATES).reduce((items, type) => {
        Object.keys(PARTITION_TEMPLATES[type]).forEach(strategy => items.push(PARTITION_TEMPLATES[type][strategy]));
        return items;
    }, []);
}

function datasourceType(datasources, datasourceId) {
    const item = (datasources || []).find(ds => ds.id === datasourceId) || (datasources || [])[0] || {};
    return normalizeDatasourceType(item.type);
}

function normalizeDatasourceType(type) {
    const value = String(type || 'GAUSSDB').toUpperCase();
    return value === 'MYSQL' ? 'MYSQL' : 'GAUSSDB';
}

function fillExample(root, datasources) {
    const form = $('#sync-form', root);
    const sourceSelect = form.querySelector('[name="sourceDatasourceId"]');
    const targetSelect = form.querySelector('[name="targetDatasourceId"]');
    const datasourceId = sourceSelect.value || targetSelect.value;
    form.querySelector('[name="id"]').value = '';
    form.querySelector('[name="name"]').value = 'Docker MySQL 用户资料同步';
    sourceSelect.value = datasourceId;
    targetSelect.value = datasourceId;
    form.querySelector('[name="backupBeforeWrite"]').value = 'true';
    form.querySelector('[name="sourceTable"]').value = 'user_profile_source';
    form.querySelector('[name="targetTable"]').value = 'user_profile_target';
    form.querySelector('[name="matchKeys"]').value = 'id';
    form.querySelector('[name="whereClause"]').value = 'tenant_id = 1001';
    currentTargetColumns = [];
    mappingRows = rowsFromMappingText(EXAMPLE_MAPPING);
    renderMappingEditor(root);
    form.querySelector('[name="partitionEnabled"]').value = 'false';
    form.querySelector('[name="partitionStrategy"]').value = 'RANGE';
    form.querySelector('[name="partitionColumn"]').value = 'updated_at';
    form.querySelector('[name="ignorePartitionErrors"]').value = 'true';
    form.querySelector('[name="partitionDefinitions"]').value = EXAMPLE_PARTITIONS;
    form.querySelector('[name="partitionSqlTemplate"]').value = templateForPartitionStrategy('RANGE', datasourceType(datasources, datasourceId));
    form.querySelector('[name="fetchSize"]').value = '1000';
    form.querySelector('[name="batchSize"]').value = '1000';
    toast('示例已填入表单，Range 分区策略默认关闭，可按需开启');
}

function exampleDatasource(datasources) {
    return datasources.find(item => item.name === 'local-mysql-toolbox')
            || datasources.find(item => item.type === 'MYSQL')
            || datasources[0];
}

function payload(form) {
    const data = formData(form);
    /*
     * 页面使用可视化表格编辑字段映射，提交前再还原成后端已有的结构。
     * 这样同步执行和旧模板存储格式都不需要跟着变化。
     */
    return {
        id: data.id || null,
        name: data.name,
        sourceDatasourceId: data.sourceDatasourceId,
        targetDatasourceId: data.targetDatasourceId,
        sourceTable: data.sourceTable,
        targetTable: data.targetTable,
        whereClause: data.whereClause,
        matchKeys: String(data.matchKeys || '').split(',').map(item => item.trim()).filter(Boolean),
        fieldMappings: mappingRows.filter(row => row.enabled && row.sourceColumn && row.targetColumn).map(row => ({
            sourceColumn: row.sourceColumn,
            targetColumn: row.targetColumn
        })),
        partitionRule: {
            enabled: data.partitionEnabled === 'true',
            partitionStrategy: data.partitionStrategy || 'RANGE',
            partitionColumn: data.partitionColumn,
            sqlTemplate: data.partitionSqlTemplate,
            ignoreCreateErrors: data.ignorePartitionErrors !== 'false',
            definitions: parsePartitionDefinitions(data.partitionDefinitions)
        },
        fetchSize: Number(data.fetchSize || 1000),
        batchSize: Number(data.batchSize || 1000),
        backupBeforeWrite: data.backupBeforeWrite === 'true'
    };
}

function parsePartitionDefinitions(text) {
    /*
     * 四段格式：
     * 1. partitionName: 目标分区名，必填。
     * 2. fromValue/toValue: 预留给其他分区 DDL 模板使用。
     * 3. lessThanValue: GaussDB/MySQL RANGE 的 VALUES LESS THAN、LIST 的 VALUES IN 共用。
     */
    return String(text || '').split(/\r?\n/).map(line => {
        const parts = line.split('|').map(item => item.trim());
        if (!parts[0]) {
            return null;
        }
        return {
            partitionName: parts[0],
            fromValue: parts[1] || '',
            toValue: parts[2] || '',
            lessThanValue: parts[3] || ''
        };
    }).filter(Boolean);
}
