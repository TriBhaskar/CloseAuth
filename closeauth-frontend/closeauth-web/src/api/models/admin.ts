// ── Admin Domain Models ────────────────────────────────────────────────────────

export type ClientType = 'Confidential' | 'Public'
export type ClientStatus = 'Active' | 'Inactive'

export interface OAuthClient {
  id: string
  name: string
  clientId: string
  type: ClientType
  status: ClientStatus
  requestsToday: number
  lastUsed: string
  createdAt: string
}

export interface CreateClientRequest {
  appName: string
  description: string
  appType: string
  logoUrl?: string
  homepageUrl?: string
  authMethod: string
  redirectUris: string[]
  scopes: string[]
}

export interface CreateClientResponse {
  id: string
  clientId: string
  clientSecret?: string
}

export type UserRole = 'Admin' | 'Moderator' | 'User'
export type UserStatus = 'Active' | 'Inactive'

export interface User {
  id: string
  firstName: string
  lastName: string
  email: string
  username: string
  role: UserRole
  status: UserStatus
  lastLogin: string
  createdAt: string
}

export interface SettingsPayload {
  issuerUrl: string
  defaultAudience: string
  timezone: string
  language: string
}

// ── Dashboard ──────────────────────────────────────────────────────────────────

export type ActivityType = 'error' | 'success' | 'warning' | 'info'
export type Severity = 'Critical' | 'High' | 'Medium' | 'Low'

export interface StatCard {
  label: string
  value: string
  trend: string
  trendUp: boolean | null
}

export interface ChartBar {
  value: number
}

export interface TopClient {
  name: string
  count: string
  pct: number
}

export interface ActivityItem {
  type: ActivityType
  desc: string
  client: string
  time: string
}

export interface SecurityAlert {
  severity: Severity
  title: string
  desc: string
  time: string
}

export interface DashboardData {
  stats: StatCard[]
  chartBars: number[]
  topClients: TopClient[]
  recentActivity: ActivityItem[]
  alerts: SecurityAlert[]
}

// ── Analytics ──────────────────────────────────────────────────────────────────

export interface AnalyticsStatCard {
  label: string
  value: string
  trend: string
  trendUp: boolean
}

export interface ChartDataPoint {
  label: string
  authorizations: number
  tokens: number
  refreshes: number
}

export interface ErrorBreakdownItem {
  label: string
  count: number
  pct: number
}

export interface TokenDistribution {
  active: number
  expired: number
  revoked: number
}

export interface GrantTypeItem {
  label: string
  pct: number
}

export interface AnalyticsData {
  stats: AnalyticsStatCard[]
  chartPoints: ChartDataPoint[]
  errorBreakdown: ErrorBreakdownItem[]
  tokenDistribution: TokenDistribution
  grantTypes: GrantTypeItem[]
}

// ── Security ───────────────────────────────────────────────────────────────────

export interface SecurityStatCard {
  label: string
  value: string
  colorClass: string
}

export interface SecurityEvent {
  id: string
  severity: Severity
  title: string
  description: string
  ip: string
  location: string
  timestamp: string
  resolved: boolean
}

export interface SecurityData {
  stats: SecurityStatCard[]
  events: SecurityEvent[]
}

// ── Users ──────────────────────────────────────────────────────────────────────

export interface UsersStatCard {
  label: string
  value: string
}

export interface UsersData {
  stats: UsersStatCard[]
  users: User[]
}

// ── Clients ────────────────────────────────────────────────────────────────────

export interface ClientsStatCard {
  label: string
  value: string
}

export interface ClientsData {
  stats: ClientsStatCard[]
  clients: OAuthClient[]
}

