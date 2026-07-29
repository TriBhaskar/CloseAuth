// TODO(ui-1/ui-2): rebuild this composable against the current backend's
// principal model (TENANT_ADMIN vs PLATFORM_ADMIN vs tenant end-user — vision
// §7.8) once src/stores/auth.ts and the internal/backend-backed login/
// register/verify endpoints exist. The deleted version wrapped a single flat
// "admin" login/register/logout flow that no longer matches the backend.
export function useAuth() {
  return {}
}
