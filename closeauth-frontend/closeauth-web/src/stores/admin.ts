import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiClient } from '@/api/client'
import type {
  DashboardData,
  UsersData,
  ClientsData,
  AnalyticsData,
  SecurityData,
} from '@/api/models'

/**
 * Admin data store — fetches dashboard/users/clients/analytics/security data from the Go API.
 * Falls back to mock data when the API returns "not_implemented".
 */
export const useAdminStore = defineStore('admin', () => {
  // ── Dashboard ─────────────────────────────────────────────────────────────────
  const dashboardData = ref<DashboardData | null>(null)
  const dashboardLoading = ref(false)
  const dashboardError = ref('')

  async function fetchDashboard(): Promise<void> {
    dashboardLoading.value = true
    dashboardError.value = ''
    try {
      dashboardData.value = await apiClient.get<DashboardData>('/admin/dashboard')
    } catch (err: unknown) {
      dashboardError.value = err instanceof Error ? err.message : 'Failed to load dashboard'
    } finally {
      dashboardLoading.value = false
    }
  }

  // ── Users ─────────────────────────────────────────────────────────────────────
  const usersData = ref<UsersData | null>(null)
  const usersLoading = ref(false)
  const usersError = ref('')

  async function fetchUsers(): Promise<void> {
    usersLoading.value = true
    usersError.value = ''
    try {
      usersData.value = await apiClient.get<UsersData>('/admin/users')
    } catch (err: unknown) {
      usersError.value = err instanceof Error ? err.message : 'Failed to load users'
    } finally {
      usersLoading.value = false
    }
  }

  // ── Clients ───────────────────────────────────────────────────────────────────
  const clientsData = ref<ClientsData | null>(null)
  const clientsLoading = ref(false)
  const clientsError = ref('')

  async function fetchClients(): Promise<void> {
    clientsLoading.value = true
    clientsError.value = ''
    try {
      clientsData.value = await apiClient.get<ClientsData>('/admin/clients')
    } catch (err: unknown) {
      clientsError.value = err instanceof Error ? err.message : 'Failed to load clients'
    } finally {
      clientsLoading.value = false
    }
  }

  // ── Analytics ─────────────────────────────────────────────────────────────────
  const analyticsData = ref<AnalyticsData | null>(null)
  const analyticsLoading = ref(false)
  const analyticsError = ref('')

  async function fetchAnalytics(): Promise<void> {
    analyticsLoading.value = true
    analyticsError.value = ''
    try {
      analyticsData.value = await apiClient.get<AnalyticsData>('/admin/analytics')
    } catch (err: unknown) {
      analyticsError.value = err instanceof Error ? err.message : 'Failed to load analytics'
    } finally {
      analyticsLoading.value = false
    }
  }

  // ── Security ──────────────────────────────────────────────────────────────────
  const securityData = ref<SecurityData | null>(null)
  const securityLoading = ref(false)
  const securityError = ref('')

  async function fetchSecurity(): Promise<void> {
    securityLoading.value = true
    securityError.value = ''
    try {
      securityData.value = await apiClient.get<SecurityData>('/admin/security')
    } catch (err: unknown) {
      securityError.value = err instanceof Error ? err.message : 'Failed to load security data'
    } finally {
      securityLoading.value = false
    }
  }

  return {
    dashboardData, dashboardLoading, dashboardError, fetchDashboard,
    usersData, usersLoading, usersError, fetchUsers,
    clientsData, clientsLoading, clientsError, fetchClients,
    analyticsData, analyticsLoading, analyticsError, fetchAnalytics,
    securityData, securityLoading, securityError, fetchSecurity,
  }
})

