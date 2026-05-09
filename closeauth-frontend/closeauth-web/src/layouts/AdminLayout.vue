<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterView, RouterLink } from 'vue-router'
import { Sun, Moon, Info, Settings, LogOut, User } from 'lucide-vue-next'
import AppSidebar from '@/components/app/AppSidebar.vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useColorScheme } from '@/composables/useColorScheme'
import { useAuthStore } from '@/stores/auth'

const sidebarCollapsed = ref(false)
const { isDark, toggle } = useColorScheme()

const authStore = useAuthStore()
const userEmail = computed(() => authStore.email || 'admin@closeauth.dev')
const initials = computed(() =>
  (userEmail.value.split('@')[0] ?? userEmail.value).slice(0, 2).toUpperCase(),
)
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-background bg-mesh-light dark:bg-mesh-dark">
    <AppSidebar v-model="sidebarCollapsed" />
    <div class="flex flex-col flex-1 overflow-hidden relative">
      <!-- Header bar -->
      <header class="h-14 shrink-0 flex items-center justify-end gap-1 px-6 border-b border-border bg-background/60 backdrop-blur-sm">
        <!-- About -->
        <RouterLink to="/about">
          <Button variant="ghost" size="icon-sm" class="text-muted-foreground hover:text-foreground">
            <Info class="h-4 w-4" />
          </Button>
        </RouterLink>

        <!-- Dark mode toggle -->
        <Button variant="ghost" size="icon-sm" @click="toggle" class="text-muted-foreground hover:text-foreground">
          <Sun v-if="isDark" class="h-4 w-4" />
          <Moon v-else class="h-4 w-4" />
        </Button>

        <!-- User profile dropdown -->
        <DropdownMenu>
          <DropdownMenuTrigger as-child>
            <button
              class="h-8 w-8 rounded-full bg-primary/10 flex items-center justify-center text-xs font-semibold text-primary ring-1 ring-border hover:ring-primary/40 transition-all cursor-pointer ml-1"
            >
              {{ initials }}
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" class="w-56">
            <DropdownMenuLabel class="font-normal">
              <div class="flex flex-col gap-1">
                <p class="text-sm font-medium leading-none">{{ userEmail.split('@')[0] }}</p>
                <p class="text-xs text-muted-foreground leading-none">{{ userEmail }}</p>
              </div>
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuItem as-child>
                <RouterLink to="/admin/settings" class="flex items-center gap-2 cursor-pointer">
                  <Settings class="h-4 w-4" />
                  <span>Settings</span>
                </RouterLink>
              </DropdownMenuItem>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuItem as-child>
              <button @click="authStore.logout()" class="flex items-center gap-2 cursor-pointer text-destructive focus:text-destructive w-full">
                <LogOut class="h-4 w-4" />
                <span>Log out</span>
              </button>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </header>
      <main class="flex-1 overflow-y-auto px-8 py-8 text-foreground">
        <RouterView />
      </main>
    </div>
  </div>
</template>
