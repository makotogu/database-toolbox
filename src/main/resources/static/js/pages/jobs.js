import { apiGet } from '../api/client.js';
import { table } from '../components/table.js';
import { toast } from '../components/toast.js';
import { $, escapeHtml } from '../utils/dom.js';

export async function renderJobs(root) {
    root.innerHTML = `
        <section class="panel">
            <div class="panel-header">
                <h2 class="panel-title">任务列表</h2>
                <button class="btn" id="refresh-jobs">刷新</button>
            </div>
            <div class="panel-body" id="jobs-body"></div>
        </section>
    `;
    await refresh(root);
    $('#refresh-jobs', root).addEventListener('click', () => refresh(root));
    window.__jobsTimer = setInterval(() => refresh(root), 3000);
}

async function refresh(root) {
    try {
        const jobs = await apiGet('/api/jobs');
        $('#jobs-body', root).innerHTML = table([
            { key: 'type', label: '类型' },
            { key: 'name', label: '名称' },
            { key: 'status', label: '状态', html: true, render: row => badge(row.status) },
            { key: 'processedRows', label: '成功行' },
            { key: 'failedRows', label: '失败行' },
            { key: 'message', label: '消息' },
            { key: 'artifact', label: '产物', render: row => row.artifact || '' },
            { key: 'failureFile', label: '失败文件', render: row => row.failureFile || '' }
        ], jobs, { empty: '暂无执行任务' });
    } catch (error) {
        toast(error.message, 'error');
    }
}

function badge(status) {
    const cls = status === 'SUCCESS' ? 'success' : (status === 'FAILED' ? 'danger' : 'warn');
    return '<span class="badge ' + cls + '">' + escapeHtml(status) + '</span>';
}
