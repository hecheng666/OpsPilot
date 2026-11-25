import axios from 'axios'

const api = axios.create({ baseURL: '/api', timeout: 15000 })

export default {
  overview: () => api.get('/overview'),
  faultStatus: () => api.get('/faults/status'),
  injectFault: (type, intensity, durationSeconds) =>
    api.post('/faults/inject', { type, intensity, durationSeconds }),
  recoverFault: (type) => api.post('/faults/recover', type ? { type } : {}),
  metricQuery: (metric, windowMinutes = 30) =>
    api.get('/metrics/query', { params: { metric, windowMinutes } }),
  logSearch: (params) => api.get('/logs/search', { params }),

  tools: () => api.get('/tools'),
  executeTool: (name, args) => api.post('/tools/execute', { name, args }),
  runbooks: () => api.get('/runbooks'),

  incidents: (status) => api.get('/incidents', { params: status ? { status } : {} }),
  incident: (id) => api.get(`/incidents/${id}`),
  closeIncident: (id) => api.post(`/incidents/${id}/close`),

  actions: (status) => api.get('/actions', { params: status ? { status } : {} }),
  approveAction: (id) => api.post(`/actions/${id}/approve`, { actor: 'admin' }),
  rejectAction: (id, reason) => api.post(`/actions/${id}/reject`, { actor: 'admin', reason }),

  agentRuns: () => api.get('/agent/runs'),
  agentRun: (id) => api.get(`/agent/runs/${id}`),
  investigate: (payload) => api.post('/agent/investigate', payload, { timeout: 300000 }),

  evalScenarios: () => api.get('/eval/scenarios'),
  startEval: (payload) => api.post('/eval/run', payload),
  evalRuns: (limit = 20) => api.get('/eval/runs', { params: { limit } }),
  evalRun: (id) => api.get(`/eval/runs/${id}`),
  evalExport: (id) => api.get(`/eval/runs/${id}/export`)
}
