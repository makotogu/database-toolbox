import { apiGet } from '../api/client.js';
import { table } from '../components/table.js';
import { toast } from '../components/toast.js';
import { escapeHtml } from '../utils/dom.js';

export async function renderDashboard(root) {
    root.innerHTML = '<div class="result-empty">正在读取工作台数据</div>';
    try {
        const overview = await apiGet('/api/dashboard');
        root.innerHTML = `
            <div class="dashboard-stack">
                <section class="dashboard-hero">
                    <div>
                        <h1>工作台</h1>
                        <p>查看本机测试数据库配置、同步模板和最近任务状态。</p>
                    </div>
                    <div class="dashboard-health ${overview.failedJobCount > 0 ? 'danger' : 'success'}">
                        <span>${overview.failedJobCount > 0 ? '需要关注' : '运行正常'}</span>
                        <strong>${escapeHtml(overview.failedJobCount)}</strong>
                        <small>失败任务</small>
                    </div>
                </section>
                ${statsHtml(overview)}
                <section class="dashboard-actions">
                    ${actionHtml('配置数据源', '维护 MySQL/GaussDB 连接', 'datasources')}
                    ${actionHtml('执行 SQL', '查询和显式确认写入', 'sql')}
                    ${actionHtml('数据导出', '生成 ZIP 数据包', 'backup-export')}
                    ${actionHtml('字段同步', '保存并运行映射模板', 'sync')}
                </section>
                <section class="panel">
                    <div class="panel-header">
                        <h2 class="panel-title">最近任务</h2>
                        <button class="btn" type="button" onclick="location.hash='jobs'">查看全部</button>
                    </div>
                    <div class="panel-body">${recentJobsHtml(overview.recentJobs || [])}</div>
                </section>
            </div>
        `;
    } catch (error) {
        toast(error.message, 'error');
        root.innerHTML = '<div class="result-empty">工作台数据读取失败</div>';
    }
}

function statsHtml(overview) {
    const items = [
        ['数据源', overview.datasourceCount, '已配置连接'],
        ['同步模板', overview.syncTaskCount, '可复用任务'],
        ['备份文件', overview.backupFileCount, 'ZIP 数据包'],
        ['任务总数', overview.jobCount, '当前进程记录'],
        ['运行中', overview.runningJobCount, '排队或执行中'],
        ['成功', overview.successJobCount, '已完成任务'],
        ['失败', overview.failedJobCount, '需要处理']
    ];
    return '<section class="dashboard-stats">' + items.map(item => `
        <div class="dashboard-stat">
            <div class="dashboard-stat-value">${escapeHtml(item[1])}</div>
            <div class="dashboard-stat-label">${escapeHtml(item[0])}</div>
            <div class="dashboard-stat-note">${escapeHtml(item[2])}</div>
        </div>
    `).join('') + '</section>';
}

function actionHtml(title, desc, route) {
    return `
        <button class="dashboard-action" type="button" onclick="location.hash='${route}'">
            <span>${escapeHtml(title)}</span>
            <small>${escapeHtml(desc)}</small>
        </button>
    `;
}

function recentJobsHtml(jobs) {
    return table([
        { key: 'type', label: '类型' },
        { key: 'name', label: '名称' },
        { key: 'status', label: '状态', html: true, render: row => badge(row.status) },
        { key: 'processedRows', label: '成功行' },
        { key: 'failedRows', label: '失败行' },
        { key: 'message', label: '消息' }
    ], jobs, { empty: '还没有任务，先执行一次导出或同步。' });
}

function badge(status) {
    const cls = status === 'SUCCESS' ? 'success' : (status === 'FAILED' ? 'danger' : 'warn');
    return '<span class="badge ' + cls + '">' + escapeHtml(status) + '</span>';
}
