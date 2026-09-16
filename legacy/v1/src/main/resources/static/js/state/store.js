import { apiGet } from '../api/client.js';

const state = {
    datasources: []
};

export async function loadDatasources() {
    state.datasources = await apiGet('/api/datasources');
    return state.datasources;
}

export function getDatasources() {
    return state.datasources;
}
