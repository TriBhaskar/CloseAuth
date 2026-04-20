<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div
    class="min-h-screen flex flex-col items-center justify-center p-4 bg-background text-foreground"
    style="background-color: var(--theme-background); color: var(--theme-text)"
  >
    <div class="w-full max-w-sm bg-card text-card-foreground rounded-xl border border-border shadow-sm p-8">
      <RouterView />
    </div>
    <div class="text-[11px] font-mono text-muted-foreground/50 mt-6">Powered by CloseAuth</div>
  </div>
</template>
