export async function apiGet(url) {
    return request(url, { method: 'GET' });
}

export async function apiPost(url, body) {
    return request(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body || {})
    });
}

export async function apiDelete(url) {
    return request(url, { method: 'DELETE' });
}

async function request(url, options) {
    const response = await fetch(url, options || {});
    const contentType = response.headers.get('content-type') || '';
    if (!contentType.includes('application/json')) {
        if (!response.ok) {
            throw new Error('请求失败: ' + response.status);
        }
        return response;
    }
    const payload = await response.json();
    if (!response.ok || payload.success === false) {
        throw new Error(payload.message || '请求失败');
    }
    return payload.data;
}
