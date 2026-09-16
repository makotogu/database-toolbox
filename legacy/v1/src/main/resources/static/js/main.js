import { startRouter } from './router.js';

const THEME_STORAGE_KEY = 'database-toolbox-theme';
const themes = [
    ['graphite', '石墨蓝灰'],
    ['dark', '黑夜模式'],
    ['forest', '森林绿'],
    ['indigo', '浅灰靛蓝'],
    ['amber', '暖灰琥珀']
];
const nav = [
    ['dashboard', '工作台', 'HOME'],
    ['datasources', '数据源', 'DS'],
    ['sql', 'SQL执行', 'SQL'],
    ['backup-export', '数据导出', 'EXP'],
    ['backup-restore', '备份恢复', 'IMP'],
    ['sync-create', '新增同步', 'NEW'],
    ['sync-run', '执行同步', 'RUN'],
    ['jobs', '执行历史', 'JOB']
];

function readTheme() {
    const saved = localStorage.getItem(THEME_STORAGE_KEY);
    return themes.some(item => item[0] === saved) ? saved : 'graphite';
}

function applyTheme(theme) {
    const nextTheme = themes.some(item => item[0] === theme) ? theme : 'graphite';
    document.body.dataset.theme = nextTheme;
    localStorage.setItem(THEME_STORAGE_KEY, nextTheme);
}

applyTheme(readTheme());

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
                <div class="topbar-left">
                    <div id="topbar-title" class="topbar-title"></div>
                    <div id="topbar-desc" class="topbar-meta"></div>
                </div>
                <div class="topbar-right">
                    <label class="theme-switcher">主题
                        <select class="select theme-select" id="theme-select">
                            ${themes.map(item => `
                                <option value="${item[0]}"${document.body.dataset.theme === item[0] ? ' selected' : ''}>${item[1]}</option>
                            `).join('')}
                        </select>
                    </label>
                    <div class="topbar-meta">Spring Boot 2.7.18 · Java 1.8</div>
                </div>
            </header>
            <section id="workspace" class="workspace"></section>
        </main>
    </div>
`;

document.getElementById('theme-select').addEventListener('change', event => {
    applyTheme(event.target.value);
});

startRouter();
