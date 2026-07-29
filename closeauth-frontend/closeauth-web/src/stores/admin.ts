import { defineStore } from 'pinia'

// TODO(ui-3): rebuild this store as part of the tenant-admin dashboard
// (clients, users, roles, resource servers, branding, registration config,
// audit log). The deleted version fetched dashboard/users/clients/analytics/
// security data from endpoints that no longer exist and silently fell back to
// mock data on failure — don't reintroduce that pattern. A view that can't get
// real data should show a visible error/loading state, never a silent
// fallback to fake data.
export const useAdminStore = defineStore('admin', () => {
  return {}
})
