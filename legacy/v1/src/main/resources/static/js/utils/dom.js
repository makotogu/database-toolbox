export function $(selector, root) {
    return (root || document).querySelector(selector);
}

export function $all(selector, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(selector));
}

export function escapeHtml(value) {
    if (value === null || value === undefined) {
        return '';
    }
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

export function formData(form) {
    const result = {};
    Array.from(new FormData(form).entries()).forEach(entry => {
        result[entry[0]] = entry[1];
    });
    return result;
}

export function datasourceOptions(datasources, selected) {
    return datasources.map(item => {
        const isSelected = selected && selected === item.id ? ' selected' : '';
        return '<option value="' + escapeHtml(item.id) + '"' + isSelected + '>' +
            escapeHtml(item.name + ' · ' + item.type) + '</option>';
    }).join('');
}

export function keyValueLines(text) {
    const map = {};
    String(text || '').split(/\r?\n/).forEach(line => {
        const trimmed = line.trim();
        if (!trimmed) {
            return;
        }
        const index = trimmed.indexOf('=');
        if (index > 0) {
            map[trimmed.slice(0, index).trim()] = trimmed.slice(index + 1).trim();
        }
    });
    return map;
}
