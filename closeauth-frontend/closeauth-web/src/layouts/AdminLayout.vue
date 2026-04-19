<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-background">
    <aside class="w-60 shrink-0 bg-muted flex flex-col">
      <div class="h-14 flex items-center px-4 text-sm font-semibold">CloseAuth</div>
    </aside>
    <main class="flex-1 overflow-y-auto px-8 py-8">
      <RouterView />
    </main>
  </div>
</template>
