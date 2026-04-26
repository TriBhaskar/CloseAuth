import type {
  ActivityItem,
  DashboardData,
  SecurityAlert,
  StatCard,
  TopClient,
} from '@/api/models'

const stats: StatCard[] = [
  { label: 'OAuth Clients',   value: '24',     trend: '+2 this week',      trendUp: true  },
  { label: 'Total Users',     value: '12,847', trend: '+324 this month',   trendUp: true  },
  { label: 'Daily Requests',  value: '89.2K',  trend: '+12.5%',            trendUp: true  },
  { label: 'Success Rate',    value: '99.2%',  trend: '+0.3%',             trendUp: true  },
  { label: 'Active Sessions', value: '3,241',  trend: 'Live',              trendUp: null  },
  { label: 'Failed Auth',     value: '156',    trend: '−23% vs yesterday', trendUp: false },
]

const chartBars: number[] = [
  22, 35, 28, 40, 33, 55, 30, 42, 38, 60, 45, 75,
  50, 68, 55, 80, 62, 70, 58, 85, 65, 72, 48, 38,
]

const topClients: TopClient[] = [
  { name: 'Web Application',    count: '45,820', pct: 92 },
  { name: 'Mobile App iOS',     count: '32,100', pct: 64 },
  { name: 'Mobile App Android', count: '28,450', pct: 57 },
  { name: 'Partner API',        count: '15,200', pct: 30 },
  { name: 'Internal Dashboard', count: '7,800',  pct: 16 },
]

const recentActivity: ActivityItem[] = [
  { type: 'error',   desc: 'Failed login attempt',      client: 'Mobile App iOS',  time: '2m ago'  },
  { type: 'success', desc: 'New client registered',     client: 'Admin Portal',    time: '15m ago' },
  { type: 'warning', desc: 'Token refresh rate spike',  client: 'Partner API',     time: '1h ago'  },
  { type: 'success', desc: 'OTP verified successfully', client: 'Web Application', time: '2h ago'  },
  { type: 'info',    desc: 'New admin login location',  client: 'Admin Portal',    time: '3h ago'  },
  { type: 'error',   desc: 'Invalid redirect URI',      client: 'Legacy System',   time: '5h ago'  },
]

const alerts: SecurityAlert[] = [
  { severity: 'Critical', title: 'Brute Force Attack',    desc: '500+ failed logins in 5min', time: '2m ago'  },
  { severity: 'High',     title: 'Unusual Token Pattern', desc: 'Refresh rate 10x normal',    time: '15m ago' },
  { severity: 'Medium',   title: 'New Login Location',    desc: 'Admin login from Berlin',     time: '1h ago'  },
]

// TODO(api): replace mock with adminService.getDashboard()
export const dashboardMock: DashboardData = {
  stats,
  chartBars,
  topClients,
  recentActivity,
  alerts,
}

