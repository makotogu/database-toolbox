import { startRouter } from './router.js';

const nav = [
    ['dashboard', '工作台', 'HOME'],
    ['datasources', '数据源', 'DS'],
    ['sql', 'SQL执行', 'SQL'],
    ['backup-export', '数据导出', 'EXP'],
    ['backup-restore', '备份恢复', 'IMP'],
    ['sync', '字段同步', 'MAP'],
    ['jobs', '执行历史', 'JOB']
];

document.getElementById('app').innerHTML = `
    <div class="app-shell">
        <aside class="sidebar">
            <div class="brand">
                <div class="brand-mark">DB</div>
                <div>
                    <div class="brand-title">数据库测试工具箱</div>
                    <div class="brand-subtitle">Local JDBC Data Workbench</div>
                </div>
            </div>
            <nav class="nav">
                ${nav.map(item => `
                    <button class="nav-button" data-route="${item[0]}" onclick="location.hash='${item[0]}'">
                        <span>${item[1]}</span><span>${item[2]}</span>
                    </button>
                `).join('')}
            </nav>
            <div class="sidebar-note">默认仅监听 127.0.0.1。写 SQL、恢复和同步都需要显式操作，不做后台自动调度。</div>
        </aside>
        <main class="main">
            <header class="topbar">
                <div>
                    <div id="topbar-title" class="topbar-title"></div>
                    <div id="topbar-desc" class="topbar-meta"></div>
                </div>
                <div class="topbar-meta">Spring Boot 2.7.18 · Java 1.8</div>
            </header>
            <section id="workspace" class="workspace"></section>
        </main>
    </div>
`;

startRouter();
