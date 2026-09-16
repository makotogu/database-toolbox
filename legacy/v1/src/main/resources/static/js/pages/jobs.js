import { apiDelete, apiGet, apiPost } from '../api/client.js';
import { table } from '../components/table.js';
import { toast } from '../components/toast.js';
import { $, escapeHtml } from '../utils/dom.js';

export async function renderJobs(root) {
    root.innerHTML = `
        <section class="panel">
            <div class="panel-header">
                <h2 class="panel-title">任务列表</h2>
                <div class="button-row">
                    <button class="btn" id="refresh-jobs">刷新</button>
                    <button class="btn danger" id="cleanup-failures">清理失败文件</button>
                </div>
            </div>
            <div class="panel-body">
                <div class="notice">运行中的任务可以发送中断请求；已提交到数据库的批次不会自动回滚。</div>
                <div id="jobs-body"></div>
            </div>
        </section>
    `;
    await refresh(root);
    $('#refresh-jobs', root).addEventListener('click', () => refresh(root));
    $('#cleanup-failures', root).addEventListener('click', async () => {
        if (!confirm('确认清理已结束任务的失败文件？运行中的任务文件会保留。')) {
            return;
        }
        try {
            const result = await apiDelete('/api/jobs/failure-files');
            toast('已清理 ' + result.deletedFiles + ' 个文件，释放 ' + formatBytes(result.deletedBytes));
            await refresh(root);
        } catch (error) {
            toast(error.message, 'error');
        }
    });
    $('#jobs-body', root).addEventListener('click', async event => {
        const button = event.target.closest('button');
        if (!button) {
            return;
        }
        if (button.dataset.cancel) {
            if (!confirm('确认中断这个任务？已提交到数据库的批次不会自动回滚。')) {
                return;
            }
            await apiPost('/api/jobs/' + button.dataset.cancel + '/cancel');
            toast('已发送中断请求');
            await refresh(root);
        }
        if (button.dataset.cleanFailure) {
            const result = await apiDelete('/api/jobs/' + button.dataset.cleanFailure + '/failure-file');
            toast('已清理 ' + result.deletedFiles + ' 个文件，释放 ' + formatBytes(result.deletedBytes));
            await refresh(root);
        }
    });
    window.__jobsTimer = setInterval(() => refresh(root), 3000);
}

async function refresh(root) {
    try {
        const jobs = await apiGet('/api/jobs');
        $('#jobs-body', root).innerHTML = jobsSummaryHtml(jobs) + table([
            { key: 'type', label: '类型' },
            { key: 'name', label: '名称' },
            { key: 'status', label: '状态', html: true, render: row => badge(row.status) },
            { key: 'processedRows', label: '处理行' },
            { key: 'failedRows', label: '失败行' },
            { key: 'message', label: '消息' },
            { key: 'artifact', label: '产物', render: row => row.artifact || '' },
            { key: 'failureFile', label: '失败文件', render: row => shortPath(row.failureFile) },
            { key: 'ops', label: '操作', html: true, render: row => actions(row) }
        ], jobs, { empty: '暂无执行任务' });
    } catch (error) {
        toast(error.message, 'error');
    }
}

function jobsSummaryHtml(jobs) {
    const rows = jobs || [];
    const running = rows.filter(row => row.status === 'RUNNING' || row.status === 'PENDING').length;
    const failed = rows.filter(row => row.status === 'FAILED').length;
    const failureFiles = rows.filter(row => row.failureFile).length;
    return `
        <div class="template-stats job-summary">
            <span>任务 ${rows.length}</span>
            <span>运行中 ${running}</span>
            <span>失败 ${failed}</span>
            <span>失败文件 ${failureFiles}</span>
        </div>
    `;
}

function badge(status) {
    const cls = status === 'SUCCESS' ? 'success' : (status === 'FAILED' ? 'danger' : 'warn');
    return '<span class="badge ' + cls + '">' + escapeHtml(status) + '</span>';
}

function actions(row) {
    const buttons = [];
    if (row.status === 'RUNNING' || row.status === 'PENDING') {
        buttons.push('<button class="btn compact danger" data-cancel="' + escapeHtml(row.id) + '">中断</button>');
    }
    if (row.failureFile && row.finishedAt) {
        buttons.push('<button class="btn compact" data-clean-failure="' + escapeHtml(row.id) + '">清理失败文件</button>');
    }
    return buttons.length ? '<div class="button-row">' + buttons.join('') + '</div>' : '<span class="muted">-</span>';
}

function shortPath(value) {
    if (!value) {
        return '';
    }
    const normalized = String(value).replace(/\\/g, '/');
    return normalized.slice(normalized.lastIndexOf('/') + 1);
}

function formatBytes(value) {
    const bytes = Number(value || 0);
    if (bytes < 1024) {
        return bytes + ' B';
    }
    if (bytes < 1024 * 1024) {
        return (bytes / 1024).toFixed(1) + ' KB';
    }
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}
