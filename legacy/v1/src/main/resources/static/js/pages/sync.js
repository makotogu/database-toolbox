import { apiDelete, apiGet, apiPost } from '../api/client.js';
import { table } from '../components/table.js';
import { toast } from '../components/toast.js';
import { $, datasourceOptions, escapeHtml, formData } from '../utils/dom.js';
import { loadDatasources } from '../state/store.js';

let editingTask = null;
let mappingRows = [];
let mappingRowSeq = 0;
let currentTargetColumns = [];
let currentSyncStep = 0;
let templateFilter = {
    keyword: '',
    writeMode: 'ALL'
};

const SYNC_STEPS = [
    '基础信息',
    '字段映射',
    '写入策略',
    '分区规则',
    '确认保存'
];

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

export async function renderSyncCreate(root) {
    const datasources = await loadDatasources();
    const pendingEditId = sessionStorage.getItem('sync-edit-task-id');
    if (pendingEditId) {
        const tasks = await apiGet('/api/sync-tasks');
        editingTask = tasks.find(item => item.id === pendingEditId) || null;
        sessionStorage.removeItem('sync-edit-task-id');
    } else {
        editingTask = null;
    }
    currentSyncStep = 0;
    resetMappingRows(editingTask);
    root.innerHTML = `
        <div class="sync-stack">
            ${routeFlowHtml('create')}
            <section class="panel">
                <div class="panel-header"><h2 class="panel-title">${editingTask ? '编辑同步模板' : '新增同步模板'}</h2></div>
                <div class="panel-body">${formHtml(datasources, editingTask)}</div>
            </section>
            ${exampleDrawerHtml(datasources)}
        </div>
    `;
    bindCreate(root, datasources);
}

export async function renderSyncRun(root) {
    const tasks = await apiGet('/api/sync-tasks');
    root.innerHTML = `
        <div class="sync-stack">
            ${routeFlowHtml('run')}
            <section class="panel" id="sync-template-panel">
                <div class="panel-header">
                    <h2 class="panel-title">执行同步模板</h2>
                    <button class="btn primary" type="button" id="create-sync-template">新增模板</button>
                </div>
                <div class="panel-body">
                    <div class="notice">选择模板后会创建执行任务；进度、失败文件和中断操作在执行历史里查看。</div>
                    ${listHtml(tasks)}
                </div>
            </section>
        </div>
    `;
    bindRun(root, tasks);
}

export async function renderSync(root) {
    await renderSyncCreate(root);
}

function routeFlowHtml(active) {
    return `
        <div class="sync-route-flow">
            <button class="${active === 'create' ? 'active' : ''}" type="button" onclick="location.hash='sync-create'">
                <strong>1</strong><span>新增/编辑模板</span>
            </button>
            <button class="${active === 'run' ? 'active' : ''}" type="button" onclick="location.hash='sync-run'">
                <strong>2</strong><span>执行同步模板</span>
            </button>
            <button type="button" onclick="location.hash='jobs'">
                <strong>3</strong><span>查看执行历史</span>
            </button>
        </div>
    `;
}

function formHtml(datasources, task) {
    task = task || {};
    return `
        ${syncStepperHtml()}
        <form id="sync-form" class="form">
            <input type="hidden" name="id" value="${escapeHtml(task.id)}">
            <div class="sync-step-panel form-section" data-step-panel="0">
                <div class="section-title">基础信息</div>
                <div class="field"><label>模板名称</label><input class="input" name="name" value="${escapeHtml(task.name)}" required></div>
                <div class="grid-3">
                    <div class="field"><label>源数据源</label><select class="select" name="sourceDatasourceId">${datasourceOptions(datasources, task.sourceDatasourceId)}</select></div>
                    <div class="field"><label>目标数据源</label><select class="select" name="targetDatasourceId">${datasourceOptions(datasources, task.targetDatasourceId)}</select></div>
                    <div class="field"><label>源表 WHERE 条件</label><input class="input" name="whereClause" value="${escapeHtml(task.whereClause)}" placeholder="tenant_id = 1001"></div>
                </div>
                <div class="grid-3">
                    <div class="field"><label>源表</label><input class="input" name="sourceTable" value="${escapeHtml(task.sourceTable)}"></div>
                    <div class="field"><label>目标表</label><input class="input" name="targetTable" value="${escapeHtml(task.targetTable)}"></div>
                    <div class="field"><label>写前备份</label><select class="select" name="backupBeforeWrite"><option value="true">开启</option><option value="false"${task.backupBeforeWrite === false ? ' selected' : ''}>关闭</option></select></div>
                </div>
            </div>
            <div class="sync-step-panel form-section" data-step-panel="1">
                <div id="mapping-editor">${mappingEditorHtml()}</div>
            </div>
            <div class="sync-step-panel form-section" data-step-panel="2">
                <div class="section-title">写入策略</div>
                <div class="grid-3">
                    <div class="field"><label>写入模式</label><select class="select" name="writeMode">
                        <option value="UPSERT"${task.writeMode === 'OVERWRITE' ? '' : ' selected'}>按键覆盖</option>
                        <option value="OVERWRITE"${task.writeMode === 'OVERWRITE' ? ' selected' : ''}>目标表覆盖</option>
                    </select></div>
                    <div class="field"><label>匹配键（按键覆盖必填，目标字段，逗号分隔）</label><input class="input" name="matchKeys" value="${escapeHtml((task.matchKeys || []).join(','))}" placeholder="id,tenant_id"></div>
                    <div class="field"><label>写入并发</label><input class="input" name="writeConcurrency" value="${escapeHtml(task.writeConcurrency || 2)}" placeholder="1-8"></div>
                </div>
                <div class="sync-mode-guide" id="write-mode-guide">${writeModeGuideHtml(task.writeMode)}</div>
                <div class="grid-3">
                    <div class="field"><label>Fetch Size</label><input class="input" name="fetchSize" value="${escapeHtml(task.fetchSize || 1000)}"></div>
                    <div class="field"><label>Batch Size</label><input class="input" name="batchSize" value="${escapeHtml(task.batchSize || 1000)}"></div>
                    <div class="field"><label>COPY Batch Size</label><input class="input" name="copyBatchSize" value="${escapeHtml(task.copyBatchSize || 50000)}"></div>
                </div>
                <div class="mapping-help">COPY Batch Size 只影响 GaussDB COPY 路径。写入并发建议从 2 开始，目标库压力较小时再调到 4。</div>
            </div>
            <div class="sync-step-panel form-section" data-step-panel="3">${partitionRuleHtml(task.partitionRule, datasources, task.targetDatasourceId)}</div>
            <div class="sync-step-panel form-section" data-step-panel="4">
                <div class="section-title">确认保存</div>
                <div class="notice">保存前请确认关键配置。模板只会在点击“保存模板”后写入本地配置文件。</div>
                <div id="sync-confirm-summary">${confirmSummaryHtml(task)}</div>
            </div>
            <div class="sync-step-actions">
                <button class="btn" type="button" id="sync-prev-step">上一步</button>
                <button class="btn primary" type="button" id="sync-next-step">下一步</button>
                <button class="btn primary" type="submit" id="sync-save-template">保存模板</button>
                <button class="btn ghost" type="button" id="new-sync">清空</button>
            </div>
        </form>
    `;
}

function syncStepperHtml() {
    return `
        <div class="sync-stepper">
            ${SYNC_STEPS.map((label, index) => `
                <button class="sync-step" type="button" data-sync-step="${index}">
                    <strong>${index + 1}</strong>
                    <span>${escapeHtml(label)}</span>
                </button>
            `).join('')}
        </div>
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
    /*
     * 旧模板里保存的列名大小写可能和当前从目标库读到的不一致（例如旧 "ID" 对应库里实际是 "id"）。
     * 大小写不敏感地比对 selected 是否已经在下拉中存在，避免重复追加一个 fallback 选项，
     * 同时优先把 selected 指向库里实际名字，让保存后端 lookup 不再落空。
     */
    const options = ['<option value="">不匹配</option>'];
    const lowerSelected = selected ? String(selected).toLowerCase() : '';
    const match = lowerSelected
            ? currentTargetColumns.find(column => String(column.columnName || '').toLowerCase() === lowerSelected)
            : null;
    const effectiveSelected = match ? match.columnName : selected;
    currentTargetColumns.forEach(column => {
        const name = column.columnName || '';
        options.push('<option value="' + escapeHtml(name) + '"' + (name === effectiveSelected ? ' selected' : '') + '>' + escapeHtml(name) + '</option>');
    });
    if (effectiveSelected && !match) {
        options.push('<option value="' + escapeHtml(effectiveSelected) + '" selected>' + escapeHtml(effectiveSelected) + '</option>');
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
            <div class="section-title">分区规则</div>
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

function exampleDrawerHtml(datasources) {
    return `
        <button class="sync-example-trigger" type="button" id="open-sync-example" title="查看字段同步示例">示例</button>
        <div class="sync-example-mask" id="sync-example-mask" hidden></div>
        <aside class="sync-example-drawer" id="sync-example-drawer" hidden>
            <div class="sync-example-drawer-head">
                <div>
                    <h2>字段同步示例</h2>
                    <p>需要参考时展开，不占用主页面。</p>
                </div>
                <button class="btn compact" type="button" id="close-sync-example">关闭</button>
            </div>
            <div class="sync-example-drawer-body">
                ${exampleHtml(datasources)}
                <div class="button-row">
                    <button class="btn primary" type="button" id="fill-sync-example">填入示例</button>
                </div>
            </div>
        </aside>
    `;
}

function confirmSummaryHtml(task) {
    task = task || {};
    const mappings = task.fieldMappings || [];
    const activeMappings = mappings.filter(item => item.enabled !== false).length;
    const partitionRule = task.partitionRule || {};
    const writeMode = task.writeMode === 'OVERWRITE' ? '目标表覆盖' : '按键覆盖';
    return `
        <div class="sync-confirm-grid">
            <div><span>模板名称</span><strong>${escapeHtml(task.name || '-')}</strong></div>
            <div><span>源表</span><strong>${escapeHtml(task.sourceTable || '-')}</strong></div>
            <div><span>目标表</span><strong>${escapeHtml(task.targetTable || '-')}</strong></div>
            <div><span>写入模式</span><strong>${escapeHtml(writeMode)}</strong></div>
            <div><span>匹配键</span><strong>${escapeHtml((task.matchKeys || []).join(', ') || '-')}</strong></div>
            <div><span>字段映射</span><strong>${activeMappings}/${mappings.length}</strong></div>
            <div><span>写前备份</span><strong>${task.backupBeforeWrite === false ? '关闭' : '开启'}</strong></div>
            <div><span>分区规则</span><strong>${partitionRule.enabled ? '开启' : '关闭'}</strong></div>
        </div>
    `;
}

function listHtml(tasks) {
    return `
        <div class="template-toolbar">
            <input class="input" id="sync-template-search" placeholder="搜索模板、源表、目标表、匹配键" value="${escapeHtml(templateFilter.keyword)}">
            <select class="select" id="sync-template-mode">
                <option value="ALL"${templateFilter.writeMode === 'ALL' ? ' selected' : ''}>全部模式</option>
                <option value="UPSERT"${templateFilter.writeMode === 'UPSERT' ? ' selected' : ''}>按键覆盖</option>
                <option value="OVERWRITE"${templateFilter.writeMode === 'OVERWRITE' ? ' selected' : ''}>目标表覆盖</option>
            </select>
        </div>
        ${templateStatsHtml(tasks)}
        <div id="sync-task-list">${taskTableHtml(filteredTasks(tasks))}</div>
    `;
}

function templateStatsHtml(tasks) {
    const total = (tasks || []).length;
    const overwrite = (tasks || []).filter(task => task.writeMode === 'OVERWRITE').length;
    const upsert = total - overwrite;
    const mappings = (tasks || []).reduce((sum, task) => sum + (task.fieldMappings || []).length, 0);
    return `
        <div class="template-stats">
            <span>模板 ${total}</span>
            <span>按键覆盖 ${upsert}</span>
            <span>目标表覆盖 ${overwrite}</span>
            <span>字段映射 ${mappings}</span>
        </div>
    `;
}

function taskTableHtml(tasks) {
    return table([
        { key: 'name', label: '名称' },
        { key: 'writeMode', label: '写入模式', html: true, render: row => writeModeBadge(row.writeMode) },
        { key: 'sourceTable', label: '源表' },
        { key: 'targetTable', label: '目标表' },
        { key: 'matchKeys', label: '匹配键', render: row => (row.matchKeys || []).join(', ') },
        { key: 'mappingCount', label: '映射', render: row => (row.fieldMappings || []).filter(item => item.enabled !== false).length + '/' + (row.fieldMappings || []).length },
        { key: 'ops', label: '操作', html: true, render: row => `
            <div class="button-row">
                <button class="btn" data-edit="${escapeHtml(row.id)}">编辑</button>
                <button class="btn primary" data-run="${escapeHtml(row.id)}">执行</button>
                <button class="btn danger" data-delete="${escapeHtml(row.id)}">删除</button>
            </div>` }
    ], tasks, { empty: '还没有同步模板' });
}

function filteredTasks(tasks) {
    const keyword = String(templateFilter.keyword || '').trim().toLowerCase();
    return (tasks || []).filter(task => {
        const mode = task.writeMode === 'OVERWRITE' ? 'OVERWRITE' : 'UPSERT';
        if (templateFilter.writeMode !== 'ALL' && mode !== templateFilter.writeMode) {
            return false;
        }
        if (!keyword) {
            return true;
        }
        const searchable = [
            task.name,
            task.sourceTable,
            task.targetTable,
            (task.matchKeys || []).join(','),
            mode,
            mode === 'OVERWRITE' ? '目标表覆盖' : '按键覆盖'
        ].join(' ').toLowerCase();
        return searchable.indexOf(keyword) >= 0;
    });
}

function writeModeBadge(mode) {
    if (mode === 'OVERWRITE') {
        return '<span class="badge danger">目标表覆盖</span>';
    }
    return '<span class="badge success">按键覆盖</span>';
}

function bindCreate(root, datasources) {
    $('#sync-form', root).addEventListener('submit', async event => {
        event.preventDefault();
        try {
            syncMappingRowsFromDom(event.target);
            if (!ensureMappingTextApplied(event.target)) {
                return;
            }
            await apiPost('/api/sync-tasks', payload(event.target));
            editingTask = null;
            currentSyncStep = 0;
            toast('同步模板已保存，可执行');
            location.hash = 'sync-run';
        } catch (error) {
            toast(error.message, 'error');
        }
    });
    $('#new-sync', root).addEventListener('click', async () => {
        editingTask = null;
        currentSyncStep = 0;
        await renderSyncCreate(root);
    });
    bindExampleDrawer(root, datasources);
    bindMappingEditor(root);
    bindWriteModeGuide(root);
    bindSyncStepper(root);
    $('[name="targetDatasourceId"]', root).addEventListener('change', () => {
        updatePartitionTemplate(root, $('[name="partitionStrategy"]', root).value, datasources);
    });
    $('[name="partitionStrategy"]', root).addEventListener('change', event => {
        updatePartitionTemplate(root, event.target.value, datasources);
    });
    $('#probe-source-partitions', root).addEventListener('click', async () => {
        await probeSourcePartitions(root, datasources);
    });
    showSyncStep(root, currentSyncStep);
}

function bindRun(root, tasks) {
    $('#create-sync-template', root).addEventListener('click', () => {
        editingTask = null;
        sessionStorage.removeItem('sync-edit-task-id');
        location.hash = 'sync-create';
    });
    bindTemplatePanel(root, tasks);
}

function bindSyncStepper(root) {
    root.querySelectorAll('[data-sync-step]').forEach(button => {
        button.addEventListener('click', () => {
            showSyncStep(root, Number(button.dataset.syncStep || 0));
        });
    });
    $('#sync-prev-step', root).addEventListener('click', () => {
        showSyncStep(root, currentSyncStep - 1);
    });
    $('#sync-next-step', root).addEventListener('click', () => {
        showSyncStep(root, currentSyncStep + 1);
    });
}

function showSyncStep(root, step) {
    currentSyncStep = Math.max(0, Math.min(SYNC_STEPS.length - 1, Number(step || 0)));
    syncMappingRowsFromDom($('#sync-form', root));
    root.querySelectorAll('[data-step-panel]').forEach(panel => {
        panel.hidden = Number(panel.dataset.stepPanel || 0) !== currentSyncStep;
    });
    root.querySelectorAll('[data-sync-step]').forEach(button => {
        const index = Number(button.dataset.syncStep || 0);
        button.classList.toggle('active', index === currentSyncStep);
        button.classList.toggle('done', index < currentSyncStep);
    });
    $('#sync-prev-step', root).disabled = currentSyncStep === 0;
    $('#sync-next-step', root).style.display = currentSyncStep === SYNC_STEPS.length - 1 ? 'none' : '';
    $('#sync-save-template', root).style.display = currentSyncStep === SYNC_STEPS.length - 1 ? '' : 'none';
    if (currentSyncStep === SYNC_STEPS.length - 1) {
        renderConfirmSummary(root);
    }
}

function renderConfirmSummary(root) {
    const form = $('#sync-form', root);
    const data = formData(form);
    const summary = {
        name: data.name,
        sourceTable: data.sourceTable,
        targetTable: data.targetTable,
        writeMode: data.writeMode,
        matchKeys: String(data.matchKeys || '').split(',').map(item => item.trim()).filter(Boolean),
        fieldMappings: mappingRows.filter(row => row.sourceColumn && row.targetColumn).map(row => ({
            enabled: !!row.enabled
        })),
        backupBeforeWrite: data.backupBeforeWrite === 'true',
        partitionRule: {
            enabled: data.partitionEnabled === 'true'
        }
    };
    $('#sync-confirm-summary', root).innerHTML = confirmSummaryHtml(summary);
}

function bindExampleDrawer(root, datasources) {
    const drawer = $('#sync-example-drawer', root);
    const mask = $('#sync-example-mask', root);
    const openButton = $('#open-sync-example', root);
    const closeButton = $('#close-sync-example', root);
    const fillButton = $('#fill-sync-example', root);
    const close = () => {
        drawer.hidden = true;
        mask.hidden = true;
    };
    openButton.addEventListener('click', () => {
        drawer.hidden = false;
        mask.hidden = false;
    });
    closeButton.addEventListener('click', close);
    mask.addEventListener('click', close);
    fillButton.addEventListener('click', () => {
        fillExample(root, datasources);
        close();
    });
}

function bindTemplatePanel(root, tasks) {
    const search = $('#sync-template-search', root);
    const mode = $('#sync-template-mode', root);
    const list = $('#sync-task-list', root);
    const renderList = () => {
        list.innerHTML = taskTableHtml(filteredTasks(tasks));
    };
    search.addEventListener('input', event => {
        templateFilter.keyword = event.target.value;
        renderList();
    });
    mode.addEventListener('change', event => {
        templateFilter.writeMode = event.target.value;
        renderList();
    });
    $('#sync-template-panel', root).addEventListener('click', async event => {
        const button = event.target.closest('button');
        if (!button) {
            return;
        }
        if (button.dataset.edit) {
            editingTask = tasks.find(item => item.id === button.dataset.edit);
            if (editingTask) {
                sessionStorage.setItem('sync-edit-task-id', editingTask.id);
            }
            location.hash = 'sync-create';
        }
        if (button.dataset.run) {
            try {
                const job = await apiPost('/api/sync-tasks/run', { taskId: button.dataset.run });
                toast('同步任务已创建: ' + job.id);
                location.hash = 'jobs';
            } catch (error) {
                toast(error.message, 'error');
            }
        }
        if (button.dataset.delete) {
            if (!confirm('确认删除这个同步模板？')) {
                return;
            }
            await apiDelete('/api/sync-tasks/' + button.dataset.delete);
            toast('同步模板已删除');
            await renderSyncRun(root);
        }
    });
}

function bindWriteModeGuide(root) {
    const select = $('[name="writeMode"]', root);
    const matchKeys = $('[name="matchKeys"]', root);
    const guide = $('#write-mode-guide', root);
    const update = () => {
        const overwrite = select.value === 'OVERWRITE';
        guide.innerHTML = writeModeGuideHtml(select.value);
        matchKeys.placeholder = overwrite ? '覆盖模式不使用匹配键' : 'id,tenant_id';
    };
    update();
    select.addEventListener('change', update);
}

function writeModeGuideHtml(mode) {
    if (mode === 'OVERWRITE') {
        return `
            <div class="mode-guide danger">
                <strong>目标表覆盖</strong>
                <span>执行时先清空目标表，再写入源查询结果；不读取匹配键，建议保持写前备份开启。</span>
            </div>
        `;
    }
    return `
        <div class="mode-guide success">
            <strong>按键覆盖</strong>
            <span>按匹配键判断目标行：存在则更新，不存在则插入；目标表其他数据会保留。</span>
        </div>
    `;
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
    /*
     * 高级文本框只反映"启用且字段都已填"的行：
     * 1. 文本模式语义只有 source=target，承担不了 enabled 信息；
     * 2. 把禁用行也展示出来会让"从文本导入表格"把所有行重新置为启用，造成回退。
     */
    return (rows || []).filter(row => row.enabled && row.sourceColumn && row.targetColumn).map(row => row.sourceColumn + '=' + row.targetColumn).join('\n');
}

function resetMappingRows(task) {
    currentTargetColumns = [];
    mappingRows = (task && task.fieldMappings ? task.fieldMappings : []).map(item => createMappingRow({
        // 旧模板里没有 enabled 字段，等价于全部启用；新模板尊重保存时的开关。
        enabled: item.enabled === undefined ? true : !!item.enabled,
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

function ensureMappingTextApplied(form) {
    /*
     * 用户经常在"高级文本模式"的 textarea 里改完直接点保存。表格里没点过"从文本导入表格"，
     * 这部分修改在保存时会被静默丢弃。这里在提交前比对两边内容，差异显著就让用户显式确认是
     * 否以文本框为准，避免"我明明改了"的误操作。
     */
    const textarea = form.querySelector('#field-mapping-text');
    if (!textarea) {
        return true;
    }
    const textValue = String(textarea.value || '').trim();
    const expected = rowsToMappingText(mappingRows).trim();
    if (textValue === expected) {
        return true;
    }
    if (!confirm('高级文本模式有未应用到表格的修改，是否以文本框内容覆盖当前表格后再保存？')) {
        return false;
    }
    mappingRows = rowsFromMappingText(textValue);
    return true;
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
    form.querySelector('[name="copyBatchSize"]').value = '50000';
    form.querySelector('[name="writeConcurrency"]').value = '2';
    form.querySelector('[name="writeMode"]').value = 'UPSERT';
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
        /*
         * 字段映射保存策略：只要 source/target 都填了就一起持久化，禁用行也保留，
         * 这样用户"先关掉再开"不会因为重新进页面发现配置丢了。后端执行时按 enabled 字段过滤。
         */
        fieldMappings: mappingRows.filter(row => row.sourceColumn && row.targetColumn).map(row => ({
            sourceColumn: row.sourceColumn,
            targetColumn: row.targetColumn,
            enabled: !!row.enabled
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
        copyBatchSize: Number(data.copyBatchSize || 50000),
        writeConcurrency: Number(data.writeConcurrency || 2),
        writeMode: data.writeMode || 'UPSERT',
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
