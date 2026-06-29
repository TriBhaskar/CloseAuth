<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import {
  ShieldCheck,
  Users,
  KeyRound,
  Server,
  Check,
  Menu,
  X,
  Zap,
  Globe,
  ArrowRight,
} from 'lucide-vue-next'
import { homePageMock } from '@/api/mocks/homeMocks'

const authStore = useAuthStore()
const mobileMenuOpen = ref(false)

// ── Icon mapping (maps iconName from model to component) ───────────────────────
const iconMap: Record<string, typeof ShieldCheck> = { ShieldCheck, Users, KeyRound, Server }

const features = homePageMock.features.map((f) => ({
  ...f,
  icon: iconMap[f.iconName] ?? ShieldCheck,
}))

const stats = homePageMock.stats
const checklist = homePageMock.checklist
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <!-- Skip to content (a11y) -->
    <a
      href="#main-content"
      class="sr-only focus:not-sr-only focus:absolute focus:top-4 focus:left-4 focus:z-[100] focus:px-4 focus:py-2 focus:bg-primary focus:text-primary-foreground focus:rounded-md focus:text-sm focus:font-medium"
    >
      Skip to main content
    </a>

    <!-- Header -->
    <header class="border-b border-border glass sticky top-0 z-50" role="banner">
      <div class="mx-auto w-full max-w-7xl px-4 sm:px-6 lg:px-8">
        <div class="flex justify-between items-center h-16">
          <!-- Logo -->
          <RouterLink to="/" class="flex items-center gap-2.5 group" aria-label="CloseAuth home">
            <div class="h-8 w-8 rounded-lg bg-primary flex items-center justify-center transition-transform group-hover:scale-105">
              <ShieldCheck class="h-4.5 w-4.5 text-primary-foreground" />
            </div>
            <span class="text-base font-semibold tracking-tight">CloseAuth</span>
          </RouterLink>

          <!-- Nav (desktop) -->
          <nav class="hidden md:flex items-center gap-8" aria-label="Primary navigation">
            <a href="#features" class="text-sm text-muted-foreground hover:text-foreground transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-4 rounded-sm">Features</a>
            <a href="#stats" class="text-sm text-muted-foreground hover:text-foreground transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-4 rounded-sm">Performance</a>
            <a href="#" class="text-sm text-muted-foreground hover:text-foreground transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-4 rounded-sm">Documentation</a>
            <a href="#" class="text-sm text-muted-foreground hover:text-foreground transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-4 rounded-sm">API</a>
          </nav>

          <!-- Auth Buttons (desktop) -->
          <div class="hidden md:flex items-center gap-3">
            <template v-if="!authStore.isAuthenticated">
              <RouterLink to="/admin/login">
                <Button variant="ghost" size="sm">Sign in</Button>
              </RouterLink>
              <RouterLink to="/admin/register">
                <Button size="sm">Get Started</Button>
              </RouterLink>
            </template>
            <template v-else>
              <span class="text-sm text-muted-foreground">{{ authStore.email }}</span>
              <RouterLink to="/admin/dashboard">
                <Button size="sm">Dashboard</Button>
              </RouterLink>
            </template>
          </div>

          <!-- Mobile menu toggle -->
          <button
            class="md:hidden inline-flex items-center justify-center h-10 w-10 rounded-lg hover:bg-muted transition-colors"
            :aria-expanded="mobileMenuOpen"
            aria-controls="mobile-nav"
            aria-label="Toggle navigation menu"
            @click="mobileMenuOpen = !mobileMenuOpen"
          >
            <Menu v-if="!mobileMenuOpen" class="h-5 w-5" />
            <X v-else class="h-5 w-5" />
          </button>
        </div>

        <!-- Mobile nav -->
        <Transition
          enter-active-class="transition duration-200 ease-out"
          enter-from-class="opacity-0 -translate-y-2"
          enter-to-class="opacity-100 translate-y-0"
          leave-active-class="transition duration-150 ease-in"
          leave-from-class="opacity-100 translate-y-0"
          leave-to-class="opacity-0 -translate-y-2"
        >
          <nav
            v-if="mobileMenuOpen"
            id="mobile-nav"
            class="md:hidden pb-4 border-t border-border pt-4 space-y-3"
            aria-label="Mobile navigation"
          >
            <a href="#features" class="block text-sm text-muted-foreground hover:text-foreground py-2" @click="mobileMenuOpen = false">Features</a>
            <a href="#stats" class="block text-sm text-muted-foreground hover:text-foreground py-2" @click="mobileMenuOpen = false">Performance</a>
            <a href="#" class="block text-sm text-muted-foreground hover:text-foreground py-2">Documentation</a>
            <a href="#" class="block text-sm text-muted-foreground hover:text-foreground py-2">API</a>
            <div class="flex gap-3 pt-2">
              <template v-if="!authStore.isAuthenticated">
                <RouterLink to="/admin/login" class="flex-1">
                  <Button variant="outline" size="sm" class="w-full">Sign in</Button>
                </RouterLink>
                <RouterLink to="/admin/register" class="flex-1">
                  <Button size="sm" class="w-full">Get Started</Button>
                </RouterLink>
              </template>
              <template v-else>
                <RouterLink to="/admin/dashboard" class="flex-1">
                  <Button size="sm" class="w-full">Dashboard</Button>
                </RouterLink>
              </template>
            </div>
          </nav>
        </Transition>
      </div>
    </header>

    <main id="main-content">
      <!-- Hero Section -->
      <section class="relative overflow-hidden pt-20 pb-16 sm:pt-28 sm:pb-20 bg-mesh-light dark:bg-mesh-dark" aria-labelledby="hero-heading">
        <!-- Decorative background orbs -->
        <div class="absolute inset-0 overflow-hidden pointer-events-none" aria-hidden="true">
          <div class="absolute -top-32 -right-32 w-96 h-96 rounded-full bg-primary/5 blur-3xl animate-pulse-slow" />
          <div class="absolute -bottom-48 -left-24 w-80 h-80 rounded-full bg-violet-500/5 blur-3xl animate-pulse-slow" style="animation-delay: 3s" />
        </div>

        <div class="relative mx-auto w-full max-w-5xl px-4 sm:px-6 lg:px-8 text-center">
          <!-- Badge -->
          <div
            class="inline-flex items-center gap-2 px-4 py-1.5 rounded-full text-xs font-medium bg-primary/10 text-primary border border-primary/20 mb-8 animate-fade-down"
          >
            <Zap class="h-3.5 w-3.5" aria-hidden="true" />
            Enterprise Authentication Server
          </div>

          <!-- Heading -->
          <h1 id="hero-heading" class="text-4xl sm:text-5xl md:text-6xl font-semibold tracking-tight leading-[1.1] animate-fade-up">
            Lightweight, Scalable
            <br class="hidden sm:block" />
            <span class="bg-gradient-to-r from-primary via-violet-500 to-primary bg-clip-text text-transparent">
              Authentication
            </span>
            <br class="hidden sm:block" />
            for Modern Applications
          </h1>

          <!-- Subtitle -->
          <p class="mt-6 text-base sm:text-lg text-muted-foreground max-w-2xl mx-auto leading-relaxed animate-fade-up stagger-1">
            CloseAuth provides centralized identity management with OAuth2.1 &amp; OpenID Connect.
            Focus on your business logic while we handle user authentication, authorization, and security.
          </p>

          <!-- CTA -->
          <div class="flex flex-col sm:flex-row gap-4 justify-center mt-10 animate-fade-up stagger-2">
            <template v-if="!authStore.isAuthenticated">
              <RouterLink to="/admin/register">
                <Button size="lg" class="px-8 gap-2 group">
                  Start Free Trial
                  <ArrowRight class="h-4 w-4 transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
                </Button>
              </RouterLink>
              <RouterLink to="/admin/login">
                <Button variant="outline" size="lg" class="px-8">View Demo</Button>
              </RouterLink>
            </template>
            <template v-else>
              <RouterLink to="/admin/dashboard">
                <Button size="lg" class="px-8 gap-2 group">
                  Go to Dashboard
                  <ArrowRight class="h-4 w-4 transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
                </Button>
              </RouterLink>
            </template>
          </div>

          <!-- Feature Pills -->
          <div class="flex flex-wrap justify-center gap-x-6 gap-y-3 mt-10 text-sm text-muted-foreground animate-fade-up stagger-3">
            <div class="flex items-center gap-2">
              <Check class="h-4 w-4 text-green-500" aria-hidden="true" />
              <span>No credit card required</span>
            </div>
            <div class="flex items-center gap-2">
              <Check class="h-4 w-4 text-green-500" aria-hidden="true" />
              <span>5-minute setup</span>
            </div>
            <div class="flex items-center gap-2">
              <Check class="h-4 w-4 text-green-500" aria-hidden="true" />
              <span>Enterprise ready</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Stats Section -->
      <section id="stats" class="py-12 border-b border-border" aria-label="Platform statistics">
        <div class="mx-auto w-full max-w-5xl px-4 sm:px-6 lg:px-8">
          <div class="grid grid-cols-2 md:grid-cols-4 gap-8">
            <div
              v-for="(stat, index) in stats"
              :key="stat.label"
              class="text-center animate-fade-up"
              :class="`stagger-${index + 1}`"
            >
              <div class="text-2xl sm:text-3xl font-semibold tracking-tight text-foreground">{{ stat.value }}</div>
              <div class="mt-1 text-sm text-muted-foreground">{{ stat.label }}</div>
            </div>
          </div>
        </div>
      </section>

      <!-- Features Section -->
      <section id="features" class="py-20 sm:py-24" aria-labelledby="features-heading">
        <div class="mx-auto w-full max-w-6xl px-4 sm:px-6 lg:px-8">
          <div class="text-center mb-14">
            <h2 id="features-heading" class="text-2xl sm:text-3xl font-semibold tracking-tight mb-4">
              Built for Modern Authentication
            </h2>
            <p class="text-muted-foreground max-w-xl mx-auto leading-relaxed">
              CloseAuth implements industry standards and best practices to provide secure,
              scalable authentication for your applications.
            </p>
          </div>

          <!-- Features Grid -->
          <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
            <article
              v-for="(feature, index) in features"
              :key="feature.title"
              class="rounded-xl border bg-card text-card-foreground p-6 hover-lift group transition-all duration-300 animate-fade-up"
              :class="`stagger-${index + 1}`"
            >
              <div class="flex items-center gap-3 mb-4">
                <div class="h-10 w-10 rounded-lg flex items-center justify-center shrink-0 transition-transform duration-300 group-hover:scale-110" :class="feature.bgClass">
                  <component :is="feature.icon" class="h-5 w-5" :class="feature.textClass" aria-hidden="true" />
                </div>
              </div>
              <h3 class="font-semibold mb-2">{{ feature.title }}</h3>
              <p class="text-sm text-muted-foreground leading-relaxed">
                {{ feature.description }}
              </p>
            </article>
          </div>
        </div>
      </section>

      <!-- Everything You Need Section -->
      <section class="py-20 sm:py-24 bg-muted/40" aria-labelledby="everything-heading">
        <div class="mx-auto w-full max-w-6xl px-4 sm:px-6 lg:px-8">
          <div class="grid grid-cols-1 lg:grid-cols-2 gap-12 lg:gap-16 items-center">
            <!-- Left Content -->
            <div>
              <div class="inline-flex items-center gap-2 px-3 py-1 rounded-full text-xs font-medium bg-green-500/10 text-green-600 border border-green-500/20 mb-6">
                <Globe class="h-3.5 w-3.5" aria-hidden="true" />
                Complete Solution
              </div>
              <h2 id="everything-heading" class="text-2xl sm:text-3xl font-semibold tracking-tight mb-4">
                Everything you need for
                <span class="text-primary">authentication</span>
              </h2>
              <p class="text-muted-foreground mb-8 leading-relaxed">
                CloseAuth handles the complexity of modern authentication so you can focus on building great products.
                From user registration to advanced security features, we've got you covered.
              </p>

              <ul class="space-y-3" role="list">
                <li
                  v-for="(item, index) in checklist"
                  :key="item"
                  class="flex items-center gap-3 text-sm animate-fade-up"
                  :class="`stagger-${index + 1}`"
                >
                  <div class="h-5 w-5 rounded-full bg-green-500/10 flex items-center justify-center shrink-0">
                    <Check class="h-3 w-3 text-green-600" aria-hidden="true" />
                  </div>
                  <span>{{ item }}</span>
                </li>
              </ul>
            </div>

            <!-- Right CTA Card -->
            <Card class="card-elevated">
              <CardHeader>
                <CardTitle class="text-xl">Ready to get started?</CardTitle>
                <CardDescription>
                  Join thousands of developers who trust CloseAuth for their authentication needs.
                </CardDescription>
              </CardHeader>
              <CardContent class="space-y-3">
                <RouterLink to="/admin/register" class="block">
                  <Button class="w-full gap-2 group" size="lg">
                    Create Free Account
                    <ArrowRight class="h-4 w-4 transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
                  </Button>
                </RouterLink>
                <RouterLink to="/admin/login" class="block">
                  <Button variant="outline" class="w-full" size="lg">Sign In to Dashboard</Button>
                </RouterLink>
              </CardContent>
              <CardFooter class="justify-center">
                <p class="text-xs text-muted-foreground text-center">
                  Questions? Contact our team for enterprise solutions.
                </p>
              </CardFooter>
            </Card>
          </div>
        </div>
      </section>
    </main>

    <!-- Footer -->
    <footer class="border-t border-border py-10" role="contentinfo">
      <div class="mx-auto w-full max-w-6xl px-4 sm:px-6 lg:px-8">
        <div class="grid grid-cols-1 sm:grid-cols-3 gap-8 mb-8">
          <!-- Brand -->
          <div>
            <div class="flex items-center gap-2 mb-3">
              <div class="h-6 w-6 rounded-md bg-primary flex items-center justify-center">
                <ShieldCheck class="h-3.5 w-3.5 text-primary-foreground" aria-hidden="true" />
              </div>
              <span class="text-sm font-semibold">CloseAuth</span>
            </div>
            <p class="text-xs text-muted-foreground leading-relaxed">
              Enterprise-grade authentication for modern applications.
            </p>
          </div>

          <!-- Product links -->
          <div>
            <h3 class="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-3">Product</h3>
            <ul class="space-y-2 text-sm">
              <li><a href="#features" class="text-muted-foreground hover:text-foreground transition-colors">Features</a></li>
              <li><a href="#" class="text-muted-foreground hover:text-foreground transition-colors">Documentation</a></li>
              <li><a href="#" class="text-muted-foreground hover:text-foreground transition-colors">API Reference</a></li>
            </ul>
          </div>

          <!-- Company links -->
          <div>
            <h3 class="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-3">Company</h3>
            <ul class="space-y-2 text-sm">
              <li><a href="#" class="text-muted-foreground hover:text-foreground transition-colors">About</a></li>
              <li><a href="#" class="text-muted-foreground hover:text-foreground transition-colors">Privacy Policy</a></li>
              <li><a href="#" class="text-muted-foreground hover:text-foreground transition-colors">Terms of Service</a></li>
            </ul>
          </div>
        </div>

        <div class="border-t border-border pt-6 flex flex-col sm:flex-row items-center justify-between gap-4">
          <p class="text-xs text-muted-foreground">© 2026 CloseAuth. All rights reserved.</p>
          <p class="text-xs font-mono text-muted-foreground">Powered by CloseAuth v2.0</p>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
@media (prefers-reduced-motion: reduce) {
  .animate-fade-up,
  .animate-fade-down,
  .animate-pulse-slow {
    animation: none !important;
  }
}
</style>
