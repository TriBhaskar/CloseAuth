<script setup lang="ts">
import { ref } from 'vue'
import { RouterView } from 'vue-router'
import AppSidebar from '@/components/app/AppSidebar.vue'

const sidebarCollapsed = ref(false)
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-background">
    <AppSidebar v-model="sidebarCollapsed" />
    <div class="flex flex-col flex-1 overflow-hidden">
      <main class="flex-1 overflow-y-auto px-8 py-8 text-foreground">
        <RouterView />
      </main>
    </div>
  </div>
</template>
