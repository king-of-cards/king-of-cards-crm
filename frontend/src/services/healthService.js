import api from '../lib/api.js';

export const healthEndpoints = [
  { id: 'identity-service', label: 'Identity Service', path: '/api/identity/health' },
  { id: 'crm-service', label: 'CRM Service', path: '/api/crm/health' },
];

export async function fetchServiceHealth(endpoint) {
  const response = await api.get(endpoint.path);
  return response.data;
}
