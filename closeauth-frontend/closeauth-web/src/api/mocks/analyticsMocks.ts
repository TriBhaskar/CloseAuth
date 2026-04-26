import type { AnalyticsData } from '@/api/models'

// TODO(api): replace mock with adminService.getAnalytics()
export const analyticsMock: AnalyticsData = {
  stats: [
    { label: 'Total Requests',     value: '623,841', trend: '+18.2% vs last week', trendUp: true  },
    { label: 'Tokens Issued',      value: '98,432',  trend: '+5.1% vs last week',  trendUp: true  },
    { label: 'Avg Response Time',  value: '143ms',   trend: '−12ms vs last week',  trendUp: true  },
    { label: 'Error Rate',         value: '0.8%',    trend: '+0.1% vs last week',  trendUp: false },
  ],
  chartPoints: [
    { label: 'Mon', authorizations: 820,  tokens: 450,  refreshes: 210 },
    { label: 'Tue', authorizations: 932,  tokens: 510,  refreshes: 230 },
    { label: 'Wed', authorizations: 1100, tokens: 620,  refreshes: 290 },
    { label: 'Thu', authorizations: 870,  tokens: 480,  refreshes: 200 },
    { label: 'Fri', authorizations: 1280, tokens: 740,  refreshes: 340 },
    { label: 'Sat', authorizations: 650,  tokens: 320,  refreshes: 140 },
    { label: 'Sun', authorizations: 540,  tokens: 270,  refreshes: 110 },
  ],
  errorBreakdown: [
    { label: 'Invalid credentials', count: 1240, pct: 45 },
    { label: 'Expired token',       count: 820,  pct: 30 },
    { label: 'Invalid redirect',    count: 410,  pct: 15 },
    { label: 'Rate limited',        count: 165,  pct: 6  },
    { label: 'Other',               count: 110,  pct: 4  },
  ],
  tokenDistribution: {
    active:  62,
    expired: 25,
    revoked: 13,
  },
  grantTypes: [
    { label: 'Authorization Code', pct: 68 },
    { label: 'Refresh Token',      pct: 22 },
    { label: 'Client Credentials', pct: 8  },
    { label: 'Password',           pct: 2  },
  ],
}

