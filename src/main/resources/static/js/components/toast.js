export function toast(message, type) {
    const root = document.getElementById('toast-root');
    const node = document.createElement('div');
    node.className = 'toast' + (type === 'error' ? ' error' : '');
    node.textContent = message;
    root.appendChild(node);
    setTimeout(() => {
        node.remove();
    }, 3600);
}
