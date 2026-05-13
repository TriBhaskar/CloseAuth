<template>
  <div class="bg-background text-foreground min-h-screen flex flex-col relative w-full bg-mesh-light dark:bg-mesh-dark selection:bg-primary/20 selection:text-primary">
    <!-- Header -->
    <header class="w-full h-14 flex items-center px-6 fixed top-0 left-0 bg-transparent z-10">
      <span class="text-lg font-semibold tracking-tighter">CloseAuth</span>
    </header>

    <!-- Main Content -->
    <main class="flex-grow flex items-center justify-center p-4 z-10">
      <div class="w-full max-w-[400px] bg-card border border-border rounded-xl p-8 flex flex-col gap-6 card-elevated animate-scale-in">
        <slot name="above" />
        <slot />
        <slot name="footer" />
      </div>
    </main>

    <!-- Footer Component -->
    <footer class="w-full py-8 bg-transparent z-10 mt-auto">
      <div class="flex flex-col md:flex-row justify-between items-center px-8 max-w-7xl mx-auto space-y-4 md:space-y-0">
        <span class="text-[11px] uppercase tracking-wider text-muted-foreground font-mono">© 2024 CloseAuth. All rights reserved.</span>
        <div class="flex space-x-6">
          <a class="text-[11px] uppercase tracking-wider text-muted-foreground hover:underline transition-all font-mono" href="#">Privacy Policy</a>
          <a class="text-[11px] uppercase tracking-wider text-muted-foreground hover:underline transition-all font-mono" href="#">Terms of Service</a>
          <a class="text-[11px] uppercase tracking-wider text-muted-foreground hover:underline transition-all font-mono" href="#">Security</a>
        </div>
      </div>
    </footer>

    <!-- Background Decorative Elements -->
    <div class="fixed inset-0 -z-10 overflow-hidden pointer-events-none">
      <div class="absolute -top-[10%] -left-[10%] w-[40%] h-[40%] blur-[100px] opacity-30 bg-primary rounded-full animate-pulse-slow"></div>
      <div class="absolute -bottom-[10%] -right-[10%] w-[40%] h-[40%] blur-[100px] opacity-20 bg-primary/60 rounded-full"></div>
    </div>
  </div>
</template>
