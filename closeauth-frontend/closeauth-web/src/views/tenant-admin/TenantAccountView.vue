<script setup lang="ts">
// FE-4d (spec §6.4.8): "My account" — reachable by every tenant user, admin
// or not (guard: 'tenantSession', not 'tenantAdmin'; see app/guards.ts and
// handleAdminCallback's own relaxed session-creation check). Tabs: Profile
// (read-only — no PATCH endpoint exists, same reality as everywhere else in
// this codebase) · Password (POST /v1/me/change-password, a REAL current-
// password field this time, unlike rotation's field-that-doesn't-exist) ·
// Sessions (list + per-session revoke).
//
// Session identification gap, disclosed rather than faked (decision #3):
// no JWT claim or other signal lets this page determine which listed
// session is the browser's own current one — inventing a heuristic (e.g.
// "most recently active") would be actively misleading, since access-token
// validation is JWT-only and browsing this page doesn't necessarily bump
// lastAccessedAt. Every session renders identically and is revocable; the
// page states plainly that "current" can't be identified yet.
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import QueryState from '@/components/admin/QueryState.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import StateBadge, { userStatusTone } from '@/components/common/StateBadge.vue'
import NewPasswordFields from '@/components/common/NewPasswordFields.vue'
import { describeAdminError, errorStateProps } from '@/api/problem'
import {
  getMe,
  listMySessions,
  revokeMySession,
  changeMyPassword,
  type MeProfileView,
  type MySessionView,
} from '@/api/meAccount'
import { useTenantAdminSessionStore } from '@/stores/tenantAdmin'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')
const sessionStore = useTenantAdminSessionStore()

// ---- tabs, URL-linkable ----------------------------------------------

type AccountTab = 'profile' | 'password' | 'sessions'
const VALID_TABS: AccountTab[] = ['profile', 'password', 'sessions']

function initialTab(): AccountTab {
  const q = route.query.tab
  return typeof q === 'string' && VALID_TABS.includes(q as AccountTab)
    ? (q as AccountTab)
    : 'profile'
}

const activeTab = ref<AccountTab>(initialTab())

function onTabChange(v: string | number): void {
  activeTab.value = v as AccountTab
  void router.replace({ query: { ...route.query, tab: activeTab.value } })
}

// ---- profile -----------------------------------------------------------

const profile = ref<MeProfileView | null>(null)
const isProfileLoading = ref(true)
const profileError = ref<string | null>(null)
const profileErrorRetryable = ref(true)

async function loadProfile(): Promise<void> {
  isProfileLoading.value = true
  profileError.value = null
  const result = await getMe(slug)
  switch (result.kind) {
    case 'ok':
      profile.value = result.value
      isProfileLoading.value = false
      break
    case 'reauth':
      break
    default: {
      const props = errorStateProps(result)
      profileError.value = props.message
      profileErrorRetryable.value = props.retryable
      isProfileLoading.value = false
      break
    }
  }
}

// ---- password ------------------------------------------------------------

const currentPassword = ref('')
const currentPasswordError = ref('')
const passwordFieldsRef = ref<InstanceType<typeof NewPasswordFields> | null>(null)
const isChangingPassword = ref(false)
const passwordBanner = ref('')
const passwordChanged = ref(false)

async function handlePasswordSubmit(): Promise<void> {
  if (isChangingPassword.value) return
  currentPasswordError.value = ''
  passwordBanner.value = ''

  const newPassword = passwordFieldsRef.value?.validate()
  if (!newPassword) return
  if (!currentPassword.value) {
    currentPasswordError.value = 'Enter your current password.'
    return
  }

  isChangingPassword.value = true
  try {
    const result = await changeMyPassword(slug, {
      currentPassword: currentPassword.value,
      newPassword,
    })
    switch (result.kind) {
      case 'ok':
        passwordChanged.value = true
        break
      case 'reauth':
        break
      case 'error':
        // A wrong current password is InvalidCredentialsException — FORBIDDEN
        // category, HTTP 403, code user.invalid_credentials. Not a 409, so
        // it arrives as 'error', not 'conflict' — land it on the field it
        // actually concerns instead of a generic banner.
        if (result.code === 'user.invalid_credentials') {
          currentPasswordError.value = 'Incorrect current password.'
        } else {
          passwordBanner.value = describeAdminError(result)
        }
        break
      default:
        passwordBanner.value = describeAdminError(result)
        break
    }
  } finally {
    isChangingPassword.value = false
  }
}

function signOutAfterPasswordChange(): void {
  sessionStore.signOut()
}

// ---- sessions --------------------------------------------------------

const sessions = ref<MySessionView[]>([])
const isSessionsLoading = ref(true)
const sessionsError = ref<string | null>(null)
const sessionsErrorRetryable = ref(true)
const revokeTarget = ref<MySessionView | null>(null)
const isRevoking = ref(false)
const revokeError = ref('')

async function loadSessions(): Promise<void> {
  isSessionsLoading.value = true
  sessionsError.value = null
  const result = await listMySessions(slug)
  switch (result.kind) {
    case 'ok':
      sessions.value = result.value
      isSessionsLoading.value = false
      break
    case 'reauth':
      break
    default: {
      const props = errorStateProps(result)
      sessionsError.value = props.message
      sessionsErrorRetryable.value = props.retryable
      isSessionsLoading.value = false
      break
    }
  }
}

async function confirmRevoke(): Promise<void> {
  if (isRevoking.value || !revokeTarget.value) return
  revokeError.value = ''
  isRevoking.value = true
  try {
    const result = await revokeMySession(slug, revokeTarget.value.id)
    switch (result.kind) {
      case 'ok':
        sessions.value = sessions.value.filter((s) => s.id !== revokeTarget.value?.id)
        revokeTarget.value = null
        break
      case 'reauth':
        break
      default:
        revokeError.value = describeAdminError(result)
        break
    }
  } finally {
    isRevoking.value = false
  }
}

onMounted(() => {
  void loadProfile()
  void loadSessions()
})

const displayName = computed(() =>
  profile.value
    ? [profile.value.firstName, profile.value.lastName].filter(Boolean).join(' ') ||
      'No name on file'
    : '',
)
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">My account</h1>
      <p class="text-sm text-muted-foreground">
        Your own profile, password, and sessions for this tenant.
      </p>
    </div>

    <Tabs :model-value="activeTab" @update:model-value="onTabChange">
      <TabsList>
        <!-- reka-ui's TabsTrigger generates its OWN id (Primitive :id="triggerId"),
             which wins over any id we pass in — data-tab is the stable test/CSS hook instead. -->
        <TabsTrigger data-tab="profile" value="profile">Profile</TabsTrigger>
        <TabsTrigger data-tab="password" value="password">Password</TabsTrigger>
        <TabsTrigger data-tab="sessions" value="sessions">Sessions</TabsTrigger>
      </TabsList>

      <TabsContent value="profile">
        <QueryState
          :loading="isProfileLoading"
          :error="profileError"
          :retryable="profileErrorRetryable"
          @retry="loadProfile"
        >
          <div v-if="profile" class="rounded-xl border border-border p-6 flex flex-col gap-4">
            <div class="flex items-center justify-between">
              <div class="flex flex-col gap-1">
                <h2 id="account-profile-email" class="text-lg font-semibold tracking-tight">
                  {{ profile.email }}
                </h2>
                <p class="text-sm text-muted-foreground">{{ displayName }}</p>
              </div>
              <StateBadge :tone="userStatusTone(profile.status)" :label="profile.status" />
            </div>
            <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
              <dt class="text-muted-foreground">User ID</dt>
              <dd><IdentifierChip kind="user" :value="profile.id" /></dd>
              <dt class="text-muted-foreground">Roles</dt>
              <dd>{{ profile.roles.join(', ') || 'None' }}</dd>
            </dl>
            <p class="text-xs text-muted-foreground">
              Profile fields are read-only here — there is no way to edit your name, email, or phone
              from this console yet.
            </p>
          </div>
        </QueryState>
      </TabsContent>

      <TabsContent value="password">
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <div v-if="passwordChanged" class="flex flex-col gap-3">
            <p role="status" class="text-sm text-foreground">
              Password updated. You've been signed out of every session — signing you out now so you
              can sign back in with your new password.
            </p>
            <Button id="account-password-signout" @click="signOutAfterPasswordChange"
              >Continue</Button
            >
          </div>
          <form
            v-else
            id="account-password-form"
            class="flex flex-col gap-4"
            novalidate
            @submit.prevent="handlePasswordSubmit"
          >
            <div class="flex flex-col gap-1.5">
              <Label for="account-current-password">Current password</Label>
              <Input
                id="account-current-password"
                v-model="currentPassword"
                type="password"
                autocomplete="current-password"
                required
                :disabled="isChangingPassword"
                :aria-invalid="Boolean(currentPasswordError)"
              />
              <p v-if="currentPasswordError" role="alert" class="text-sm text-destructive">
                {{ currentPasswordError }}
              </p>
            </div>

            <NewPasswordFields
              ref="passwordFieldsRef"
              id-prefix="account-password"
              bare
              show-checklist
              :min-length="8"
              :is-submitting="isChangingPassword"
            />

            <p class="text-xs text-muted-foreground">
              Changing your password signs you out of every session, including this one.
            </p>
            <p v-if="passwordBanner" role="alert" class="text-sm text-destructive">
              {{ passwordBanner }}
            </p>

            <Button
              id="account-password-submit"
              type="submit"
              class="self-start"
              :disabled="isChangingPassword"
            >
              {{ isChangingPassword ? 'Changing…' : 'Change password' }}
            </Button>
          </form>
        </div>
      </TabsContent>

      <TabsContent value="sessions">
        <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
          <h2 class="text-lg font-semibold tracking-tight">Sessions</h2>
          <p class="text-xs text-muted-foreground">
            This list can't yet identify which session is the one you're using right now — revoking
            any session below, including your current one, signs that device out immediately.
          </p>

          <QueryState
            :loading="isSessionsLoading"
            :error="sessionsError"
            :retryable="sessionsErrorRetryable"
            @retry="loadSessions"
          >
            <div class="flex flex-col gap-3">
              <p v-if="sessions.length === 0" class="text-sm text-muted-foreground">
                No active sessions.
              </p>

              <div
                v-for="session in sessions"
                :key="session.id"
                :data-session-id="session.id"
                class="flex items-center justify-between gap-3 rounded-md border border-line p-3"
              >
                <div class="flex flex-col gap-1 text-sm">
                  <span class="font-mono text-xs">{{ session.userAgent ?? 'Unknown device' }}</span>
                  <span class="text-xs text-muted-foreground">
                    {{ session.ipAddress ?? 'Unknown IP' }} · last active
                    <RelativeTime :value="session.lastAccessedAt" />
                  </span>
                </div>
                <Button
                  :id="`account-revoke-session-${session.id}`"
                  variant="outline"
                  size="sm"
                  :disabled="isRevoking"
                  @click="revokeTarget = session"
                >
                  Revoke
                </Button>
              </div>

              <p v-if="revokeError" role="alert" class="text-sm text-destructive">
                {{ revokeError }}
              </p>
            </div>
          </QueryState>
        </div>
      </TabsContent>
    </Tabs>

    <ConfirmDialog
      :open="revokeTarget !== null"
      title="Revoke this session?"
      description="That device is signed out immediately — if it's the one you're using right now, you'll be signed out too."
      confirm-label="Revoke"
      :pending="isRevoking"
      @update:open="
        (open: boolean) => {
          if (!open) revokeTarget = null
        }
      "
      @confirm="confirmRevoke"
    />
  </div>
</template>
