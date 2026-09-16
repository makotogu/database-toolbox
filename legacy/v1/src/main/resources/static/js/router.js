import { renderDashboard } from './pages/dashboard.js';
import { renderDatasources } from './pages/datasources.js';
import { renderSql } from './pages/sql.js';
import { renderBackupExport, renderBackupRestore } from './pages/backup.js';
import { renderSyncCreate, renderSyncRun } from './pages/sync.js';
import { renderJobs } from './pages/jobs.js';

const routes = {
    dashboard: { title: '工作台', desc: '查看配置、模板、备份和任务状态。', render: renderDashboard },
    datasources: { title: '数据源管理', desc: '维护 MySQL 和 GaussDB 测试环境连接。', render: renderDatasources },
    sql: { title: 'SQL 执行', desc: '读取查询直接执行，写操作需要显式确认。', render: renderSql },
    backup: { title: '数据导出', desc: '导出 ZIP 数据包，默认使用 Char(27) 分隔符。', render: renderBackupExport },
    'backup-export': { title: '数据导出', desc: '导出 ZIP 数据包，默认使用 Char(27) 分隔符。', render: renderBackupExport },
    'backup-restore': { title: '备份恢复', desc: '从 ZIP 数据包恢复，优先读取包内格式信息。', render: renderBackupRestore },
    sync: { title: '新增同步', desc: '按步骤创建字段映射同步模板。', render: renderSyncCreate },
    'sync-create': { title: '新增同步', desc: '按步骤创建字段映射同步模板。', render: renderSyncCreate },
    'sync-run': { title: '执行同步', desc: '选择已保存模板并创建同步任务。', render: renderSyncRun },
    jobs: { title: '执行历史', desc: '查看备份、恢复和同步任务进度。', render: renderJobs }
};

export function startRouter() {
    window.addEventListener('hashchange', renderRoute);
    renderRoute();
}

export async function renderRoute() {
    if (window.__jobsTimer) {
        clearInterval(window.__jobsTimer);
        window.__jobsTimer = null;
    }
    const rawKey = (location.hash || '#dashboard').replace('#', '');
    const key = rawKey === 'sync' ? 'sync-create' : rawKey;
    const route = routes[key] || routes.datasources;
    document.querySelectorAll('.nav-button').forEach(button => {
        button.classList.toggle('active', button.dataset.route === key);
    });
    document.getElementById('topbar-title').textContent = route.title;
    document.getElementById('topbar-desc').textContent = route.desc;
    const workspace = document.getElementById('workspace');
    workspace.innerHTML = '';
    await route.render(workspace);
}
