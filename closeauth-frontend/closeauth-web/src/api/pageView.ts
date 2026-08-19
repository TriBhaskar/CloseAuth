// FE-1.11: the shared paging envelope, de-duplicated out of tenantAdminUsers.ts
// and platformAdminTenants.ts (both declared this identically — mirrors
// common/web/PageView.java exactly, field is `items`, not `content`). Not
// tenant- or platform-specific, so a shared home doesn't cross either
// surface's own boundary; both re-export it from their existing path so no
// downstream import site needs to change.
//
// Trust the response's echoed page/size over what was requested — the
// backend clamps size to [1, 100] and page to >= 0.
export interface PageView<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
