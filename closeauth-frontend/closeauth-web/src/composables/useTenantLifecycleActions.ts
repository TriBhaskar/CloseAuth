// FE-3b: extracted from PlatformTenantsView.vue (originally FE-3a) once a
// genuine second consumer arrived in the same session — the new tenant
// detail page (PlatformTenantDetailView.vue) needs the exact same
// suspend/activate/delete confirm-then-mutate flow the list view already
// had, and duplicating ~40 lines of state + two dialogs' wiring between them
// would be the exact drift this codebase's conventions warn against.
//
// `onSuccess` is caller-supplied rather than hardcoded to a `load()` call:
// the list view re-fetches its own page; the detail page re-fetches the one
// tenant and, on a successful delete, navigates back to the list instead
// (there's nothing left on this page to show).
import { computed, ref } from 'vue'
import {
  activateTenant,
  deleteTenant,
  suspendTenant,
  type TenantLifecycleAction,
  type TenantView,
} from '@/api/platformAdminTenants'
import { describeAdminError } from '@/api/problem'

export interface TenantLifecycleConfirmState {
  tenantId: string
  slug: string
  name: string
  action: TenantLifecycleAction
}

// `onSuccess` receives the action that just succeeded — the detail page
// needs this to tell "delete" (nothing left to show, navigate back to the
// list) apart from suspend/activate (re-fetch this one tenant in place);
// by the time onSuccess runs, confirmState has already been cleared, so the
// action can't be read back off it.
export function useTenantLifecycleActions(onSuccess: (action: TenantLifecycleAction) => void | Promise<void>) {
  const actionPending = ref(false)
  const actionError = ref('')
  const confirmState = ref<TenantLifecycleConfirmState | null>(null)

  function startAction(tenant: TenantView, action: TenantLifecycleAction): void {
    if (actionPending.value) return
    actionError.value = ''
    confirmState.value = { tenantId: tenant.id, slug: tenant.slug, name: tenant.name, action }
  }

  function cancelAction(): void {
    confirmState.value = null
  }

  async function confirmAction(): Promise<void> {
    if (!confirmState.value || actionPending.value) return
    const { tenantId, action } = confirmState.value
    actionError.value = ''
    actionPending.value = true
    try {
      const result =
        action === 'activate' ? await activateTenant(tenantId)
        : action === 'suspend' ? await suspendTenant(tenantId)
        : await deleteTenant(tenantId)
      switch (result.kind) {
        case 'ok':
          confirmState.value = null
          await onSuccess(action)
          break
        case 'reauth':
          // Never actually produced on this surface — parsePlatformResult
          // maps a session-expired outcome to the 'error' arm instead.
          break
        default:
          actionError.value = describeAdminError(result)
          break
      }
    } finally {
      actionPending.value = false
    }
  }

  // Spec §6.3.2/§6.3.5-literal copy for suspend; activate gets an
  // analogous, non-spec-quoted but consistent confirmation. Delete's copy
  // lives on the caller's own TypedConfirmDialog usage, not here.
  const confirmTitle = computed(() => {
    const name = confirmState.value?.name
    return confirmState.value?.action === 'suspend' ? `Suspend ${name}?` : `Activate ${name}?`
  })
  const confirmDescription = computed(() =>
    confirmState.value?.action === 'suspend'
      ? "Users won't be able to sign in and all active tokens stop working immediately."
      : 'This tenant becomes ACTIVE. Its users (once it has any) will be able to sign in.',
  )

  const isConfirmDialogOpen = computed(() => confirmState.value !== null && confirmState.value.action !== 'delete')
  const isDeleteDialogOpen = computed(() => confirmState.value?.action === 'delete')

  return {
    actionPending,
    actionError,
    confirmState,
    startAction,
    cancelAction,
    confirmAction,
    confirmTitle,
    confirmDescription,
    isConfirmDialogOpen,
    isDeleteDialogOpen,
  }
}
