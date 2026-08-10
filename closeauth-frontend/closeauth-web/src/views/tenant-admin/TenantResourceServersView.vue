<script setup lang="ts">
// Stage UI-3c: resource servers list + create — the list-then-detail
// pattern from TenantUsersView.vue (UI-3b), reused as-is: QueryState ->
// table -> AdminPagination, a Dialog form using FormField for per-field
// validation display.
//
// No search/filter/sort: TenantResourceServerController's list endpoint
// takes only page/size, same discipline as TenantUsersView.vue.
//
// The Source column is the one place the client<->resource-server 1:1
// relationship (auto-creation, §7.6) is surfaced in this UI — there is no
// backend query the other direction (client id from a resource server, or
// vice versa beyond this boolean), so this column is honest about what it
// knows: whether an RS came from a client, not WHICH client.
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableEmpty, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import QueryState from '@/components/admin/QueryState.vue'
import AdminPagination from '@/components/admin/AdminPagination.vue'
import FormField from '@/components/admin/FormField.vue'
import { describeAdminError } from '@/api/tenantAdminProblem'
import {
  createResourceServer,
  listResourceServers,
  DEFAULT_PAGE_SIZE,
  RESOURCE_SERVER_CONFLICT_FIELDS,
  type ResourceServerView,
} from '@/api/tenantAdminResourceServers'
import type { PageView } from '@/api/tenantAdminUsers'

const route = useRoute()
const router = useRouter()
const slug = String(route.params.slug ?? '')

const page = ref(0)
const pageData = ref<PageView<ResourceServerView> | null>(null)
const isLoading = ref(true)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await listResourceServers(slug, page.value, DEFAULT_PAGE_SIZE)
  switch (result.kind) {
    case 'ok':
      pageData.value = result.value
      isLoading.value = false
      break
    case 'reauth':
      break
    default:
      pageData.value = null
      errorMessage.value = describeAdminError(result)
      isLoading.value = false
      break
  }
}

onMounted(load)
watch(page, load)

function openDetail(rsId: string): void {
  void router.push({ name: 'tenant-admin-resource-server-detail', params: { slug, rsId } })
}

// ---- create dialog ---------------------------------------------------

const isCreateOpen = ref(false)
const isCreating = ref(false)
const form = reactive({ slug: '', name: '', audienceIdentifier: '' })
const errors = reactive<Record<string, string>>({})
const banner = ref('')

function resetForm(): void {
  form.slug = ''
  form.name = ''
  form.audienceIdentifier = ''
  banner.value = ''
  for (const key of Object.keys(errors)) delete errors[key]
}

function handleOpenChange(open: boolean): void {
  isCreateOpen.value = open
  if (!open) resetForm()
}

async function handleCreate(): Promise<void> {
  if (isCreating.value) return
  banner.value = ''
  for (const key of Object.keys(errors)) delete errors[key]

  isCreating.value = true
  try {
    const result = await createResourceServer(slug, {
      slug: form.slug,
      name: form.name,
      audienceIdentifier: form.audienceIdentifier,
    })
    switch (result.kind) {
      case 'ok':
        isCreateOpen.value = false
        resetForm()
        page.value = 0
        await load()
        break
      case 'validationErrors':
        Object.assign(errors, result.errors)
        break
      case 'conflict': {
        const field = RESOURCE_SERVER_CONFLICT_FIELDS[result.code]
        if (field) {
          errors[field] = result.message
        } else {
          banner.value = result.message
        }
        break
      }
      case 'reauth':
        break
      default:
        banner.value = describeAdminError(result)
        break
    }
  } finally {
    isCreating.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-semibold tracking-tight">Resource servers</h1>
        <p class="text-sm text-muted-foreground">Manage this tenant's resource servers and their scope catalogs.</p>
      </div>
      <Dialog :open="isCreateOpen" @update:open="handleOpenChange">
        <DialogTrigger as-child>
          <Button id="new-rs-button">New resource server</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create a resource server</DialogTitle>
            <DialogDescription>
              A standalone resource server, independent of any client. Slug and audience must be unique.
            </DialogDescription>
          </DialogHeader>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="handleCreate">
            <FormField id="new-rs-name" label="Name" :error="errors.name">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-rs-name"
                  v-model="form.name"
                  type="text"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <FormField id="new-rs-slug" label="Slug" :error="errors.slug" hint="Lowercase letters, digits, hyphens. Used in scope prefixes.">
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-rs-slug"
                  v-model="form.slug"
                  type="text"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <FormField
              id="new-rs-audience"
              label="Audience identifier"
              :error="errors.audienceIdentifier"
              hint="Placed in the token aud claim. Cannot be changed after creation."
            >
              <template #default="{ hasError, describedBy }">
                <Input
                  id="new-rs-audience"
                  v-model="form.audienceIdentifier"
                  type="text"
                  required
                  :disabled="isCreating"
                  :aria-invalid="hasError"
                  :aria-describedby="describedBy"
                />
              </template>
            </FormField>

            <p v-if="banner" role="alert" class="text-sm text-destructive">{{ banner }}</p>

            <DialogFooter>
              <Button type="submit" :disabled="isCreating">
                {{ isCreating ? 'Creating…' : 'Create resource server' }}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>

    <QueryState :loading="isLoading" :error="errorMessage">
      <div class="flex flex-col gap-4">
        <div class="rounded-lg border border-border overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Slug</TableHead>
                <TableHead>Audience</TableHead>
                <TableHead>Source</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="pageData && pageData.items.length === 0" :colspan="4">
                No resource servers yet.
              </TableEmpty>
              <TableRow
                v-for="rs in pageData?.items ?? []"
                :key="rs.id"
                :data-resource-server-id="rs.id"
                class="cursor-pointer"
                @click="openDetail(rs.id)"
              >
                <TableCell>{{ rs.name }}</TableCell>
                <TableCell class="font-mono text-xs">{{ rs.slug }}</TableCell>
                <TableCell class="font-mono text-xs break-all">{{ rs.audienceIdentifier }}</TableCell>
                <TableCell>
                  <Badge :variant="rs.autoCreated ? 'secondary' : 'outline'">
                    {{ rs.autoCreated ? 'Created with a client' : 'Standalone' }}
                  </Badge>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>

        <AdminPagination
          v-if="pageData"
          :page="pageData.page"
          :size="pageData.size"
          :total-elements="pageData.totalElements"
          :total-pages="pageData.totalPages"
          @update:page="(p) => (page = p)"
        />
      </div>
    </QueryState>
  </div>
</template>
