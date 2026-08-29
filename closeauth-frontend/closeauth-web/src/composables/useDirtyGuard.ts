// FE-5.7 (spec §5.7, §7.5's "no autosave anywhere"): the settings page's
// dirty-state guard. Called ONCE, in the settings shell (TenantSettingsView.vue)
// — not per tab — because the two paths it protects share one `isDirty` ref
// and one ConfirmDialog:
//
//   1. A real route change (clicking a different console nav item). Vue
//      Router's onBeforeRouteLeave accepts returning a Promise<boolean>, so
//      this shows the SAME ConfirmDialog and awaits the answer rather than
//      falling back to a native window.confirm — there is no other
//      onBeforeRouteLeave anywhere in src/**, so this is the precedent.
//   2. A tab switch within the settings page. Settings tabs are a query
//      param, not a route (TenantSettingsView.vue's own header explains
//      why), so switching tabs never triggers onBeforeRouteLeave at all —
//      guardTabChange() is what a caller must route every tab-change
//      through instead. Critically, the caller must NOT mutate its own
//      `activeTab` state until `next()` runs — otherwise reka-ui's Tabs
//      would already have switched (and unmounted the dirty tab) before the
//      confirmation is shown.
//
// Each settings tab component reports its own dirty state up via
// `v-model:dirty` (an `update:dirty` emit) rather than this composable
// being called per-tab — an inactive, unmounted tab has no state left to
// protect, and only one tab is ever being edited at a time.
import { ref, type Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

export interface DirtyGuard {
  /** Bind to a ConfirmDialog's `open` prop. */
  confirmOpen: Ref<boolean>
  /** Wrap a tab-switch assignment: `guardTabChange(() => (activeTab = next))`. Calls `next()` immediately when not dirty. */
  guardTabChange: (next: () => void) => void
  /** Bind to the ConfirmDialog's `confirm` event. */
  confirmDiscard: () => void
  /** Bind to the ConfirmDialog's `update:open` event when it closes without confirming. */
  cancelDiscard: () => void
}

export function useDirtyGuard(isDirty: Ref<boolean>): DirtyGuard {
  const confirmOpen = ref(false)

  // Exactly one of these is set at a time — whichever path opened the
  // dialog most recently is the one `confirmDiscard`/`cancelDiscard` resolve.
  let resolveNavigation: ((allow: boolean) => void) | null = null
  let pendingTabChange: (() => void) | null = null

  onBeforeRouteLeave(() => {
    if (!isDirty.value) return true
    return new Promise<boolean>((resolve) => {
      pendingTabChange = null
      resolveNavigation = resolve
      confirmOpen.value = true
    })
  })

  function guardTabChange(next: () => void): void {
    if (!isDirty.value) {
      next()
      return
    }
    resolveNavigation = null
    pendingTabChange = next
    confirmOpen.value = true
  }

  function confirmDiscard(): void {
    confirmOpen.value = false
    isDirty.value = false
    if (resolveNavigation) {
      resolveNavigation(true)
      resolveNavigation = null
    } else if (pendingTabChange) {
      const action = pendingTabChange
      pendingTabChange = null
      action()
    }
  }

  function cancelDiscard(): void {
    confirmOpen.value = false
    if (resolveNavigation) {
      resolveNavigation(false)
      resolveNavigation = null
    }
    pendingTabChange = null
  }

  return { confirmOpen, guardTabChange, confirmDiscard, cancelDiscard }
}
