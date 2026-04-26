import type { ClientsData, OAuthClient } from '@/api/models'

const clients: OAuthClient[] = [
  {
    id: '1',
    name: 'Web Application',
    clientId: 'client_web_abc123',
    type: 'Confidential',
    status: 'Active',
    requestsToday: 4582,
    lastUsed: '2 min ago',
    createdAt: '2024-01-15',
  },
  {
    id: '2',
    name: 'Mobile App iOS',
    clientId: 'client_ios_def456',
    type: 'Public',
    status: 'Active',
    requestsToday: 3210,
    lastUsed: '5 min ago',
    createdAt: '2024-02-20',
  },
  {
    id: '3',
    name: 'Mobile App Android',
    clientId: 'client_and_ghi789',
    type: 'Public',
    status: 'Active',
    requestsToday: 2845,
    lastUsed: '12 min ago',
    createdAt: '2024-02-20',
  },
  {
    id: '4',
    name: 'Partner API',
    clientId: 'client_partner_jkl012',
    type: 'Confidential',
    status: 'Active',
    requestsToday: 1520,
    lastUsed: '1h ago',
    createdAt: '2024-03-10',
  },
  {
    id: '5',
    name: 'Legacy System',
    clientId: 'client_legacy_mno345',
    type: 'Confidential',
    status: 'Inactive',
    requestsToday: 0,
    lastUsed: '30 days ago',
    createdAt: '2023-06-01',
  },
]

// TODO(api): replace mock with adminService.getClients()
export const clientsMock: ClientsData = {
  stats: [
    { label: 'Auth Success Rate', value: '99.2%' },
    { label: 'Avg Latency (P95)', value: '143ms' },
    { label: 'Active Sessions',   value: '3,241' },
  ],
  clients,
}

