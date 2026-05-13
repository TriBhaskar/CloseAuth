import type { SecurityData, SecurityEvent } from '@/api/models'

const events: SecurityEvent[] = [
  {
    id: '1',
    severity: 'Critical',
    title: 'Brute Force Attack',
    description: '500+ failed login attempts from IP 192.168.1.100 in 5 minutes',
    ip: '192.168.1.100',
    location: 'Frankfurt, DE',
    timestamp: '2 min ago',
    resolved: false,
  },
  {
    id: '2',
    severity: 'High',
    title: 'Unusual Token Pattern',
    description: 'Refresh token usage 10x above normal baseline for client_web_abc123',
    ip: '10.0.0.55',
    location: 'London, GB',
    timestamp: '15 min ago',
    resolved: false,
  },
  {
    id: '3',
    severity: 'Medium',
    title: 'New Login Location',
    description: 'Administrator logged in from an unrecognised location',
    ip: '203.0.113.42',
    location: 'Berlin, DE',
    timestamp: '1h ago',
    resolved: true,
  },
  {
    id: '4',
    severity: 'Low',
    title: 'Token Expiry Warning',
    description: 'Access token approaching maximum lifetime threshold',
    ip: '—',
    location: '—',
    timestamp: '3h ago',
    resolved: true,
  },
]

// TODO(api): replace mock with adminService.getSecurityData()
export const securityMock: SecurityData = {
  stats: [
    { label: 'Critical Alerts',  value: '3',    colorClass: 'text-red-500'   },
    { label: 'Blocked Attacks',  value: '1,204', colorClass: 'text-amber-500' },
    { label: 'Tokens Revoked',   value: '48',   colorClass: 'text-blue-500'  },
    { label: 'Audit Events',     value: '8,921', colorClass: 'text-green-500' },
  ],
  events,
}

