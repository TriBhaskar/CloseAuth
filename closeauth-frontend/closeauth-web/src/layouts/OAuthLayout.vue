<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div
    class="min-h-screen flex flex-col items-center justify-center p-4"
    style="background-color: hsl(var(--theme-background, var(--background)))"
  >
    <div class="w-full max-w-sm bg-card rounded-xl border border-border shadow-sm p-8">
      <RouterView />
    </div>
    <div class="text-[11px] font-mono text-muted-foreground/50 mt-6">Powered by CloseAuth</div>
  </div>
</template>
