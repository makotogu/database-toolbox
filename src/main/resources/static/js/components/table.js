import { escapeHtml } from '../utils/dom.js';

export function table(columns, rows, options) {
    const opts = options || {};
    if (!rows || rows.length === 0) {
        return '<div class="result-empty">' + escapeHtml(opts.empty || '暂无数据') + '</div>';
    }
    const head = columns.map(col => '<th>' + escapeHtml(col.label || col.key) + '</th>').join('');
    const body = rows.map(row => {
        const cells = columns.map(col => {
            const value = typeof col.render === 'function' ? col.render(row) : row[col.key];
            return '<td>' + (col.html ? value : escapeHtml(value)) + '</td>';
        }).join('');
        return '<tr>' + cells + '</tr>';
    }).join('');
    return '<div class="table-wrap"><table class="table"><thead><tr>' + head + '</tr></thead><tbody>' + body + '</tbody></table></div>';
}
