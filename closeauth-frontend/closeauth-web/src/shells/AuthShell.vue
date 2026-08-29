<!-- FE-1.10 (spec §4.1): the hosted-auth shell. Moved from layouts/AuthLayout.vue
     — content unchanged, already correct (retokened in FE-1a: flat canvas
     background, bordered card with no shadow/entrance-animation, §3.7-
     compliant). Every hosted-auth view wraps this in TenantBrandingProvider
     (FE-1.3), never the other way around — this shell has no branding logic
     of its own. -->
<template>
  <div
    class="bg-background text-foreground min-h-screen flex flex-col relative w-full selection:bg-primary/20 selection:text-primary"
  >
    <!-- Skip to content (a11y) -->
    <a
      href="#auth-content"
      class="sr-only focus:not-sr-only focus:absolute focus:top-4 focus:left-4 focus:z-[100] focus:px-4 focus:py-2 focus:bg-primary focus:text-primary-foreground focus:rounded-md focus:text-sm focus:font-medium"
    >
      Skip to form
    </a>

    <!-- Header -->
    <header
      class="w-full h-14 flex items-center px-6 fixed top-0 left-0 bg-transparent z-10"
      role="banner"
    >
      <span class="text-lg font-semibold tracking-tighter">CloseAuth</span>
    </header>

    <!-- Main Content -->
    <main id="auth-content" class="flex-grow flex items-center justify-center p-4 z-10" role="main">
      <!-- §3.7: cards are separated by borders, not shadows; §4.1's 400px
           bordered card, no elevation, no entrance animation. -->
      <div
        class="w-full max-w-[400px] bg-card border border-border rounded-xl p-7 flex flex-col gap-5"
      >
        <slot name="above" />
        <slot />
        <slot name="footer" />
      </div>
    </main>

    <!-- Footer Component -->
    <footer class="w-full py-8 bg-transparent z-10 mt-auto" role="contentinfo">
      <div
        class="flex flex-col md:flex-row justify-between items-center px-8 max-w-7xl mx-auto space-y-4 md:space-y-0"
      >
        <span class="text-eyebrow text-muted-foreground font-mono"
          >© 2026 CloseAuth. All rights reserved.</span
        >
        <div class="flex space-x-6">
          <a
            class="text-eyebrow text-muted-foreground hover:underline transition-all font-mono"
            href="#"
            >Privacy policy</a
          >
          <a
            class="text-eyebrow text-muted-foreground hover:underline transition-all font-mono"
            href="#"
            >Terms of service</a
          >
          <a
            class="text-eyebrow text-muted-foreground hover:underline transition-all font-mono"
            href="#"
            >Security</a
          >
        </div>
      </div>
    </footer>
  </div>
</template>
