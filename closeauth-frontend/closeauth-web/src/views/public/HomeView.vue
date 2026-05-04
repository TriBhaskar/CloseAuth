<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { ShieldCheck, Users, KeyRound, Server, Check } from 'lucide-vue-next'

const authStore = useAuthStore()
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <!-- Header -->
    <header class="border-b border-border bg-background/80 backdrop-blur-sm sticky top-0 z-50">
      <div class="mx-auto w-full px-6">
        <div class="flex justify-between items-center h-14">
          <!-- Logo -->
          <div class="flex items-center gap-2.5">
            <div class="h-7 w-7 rounded-md bg-primary flex items-center justify-center">
              <ShieldCheck class="h-4 w-4 text-primary-foreground" />
            </div>
            <span class="text-base font-semibold tracking-tight">CloseAuth</span>
          </div>

          <!-- Nav (desktop) -->
          <nav class="hidden md:flex items-center gap-10">
            <a href="#features" class="text-sm text-muted-foreground hover:text-foreground transition-colors">Features</a>
            <a href="#" class="text-sm text-muted-foreground hover:text-foreground transition-colors">Documentation</a>
            <a href="#" class="text-sm text-muted-foreground hover:text-foreground transition-colors">API</a>
          </nav>

          <!-- Auth Buttons -->
          <div class="flex items-center gap-3">
            <template v-if="!authStore.isAuthenticated">
              <RouterLink to="/admin/login">
                <Button variant="ghost" size="sm">Sign in</Button>
              </RouterLink>
              <RouterLink to="/admin/register">
                <Button size="sm">Sign up</Button>
              </RouterLink>
            </template>
            <template v-else>
              <span class="text-sm text-muted-foreground">{{ authStore.email }}</span>
              <RouterLink to="/admin/dashboard">
                <Button size="sm">Dashboard</Button>
              </RouterLink>
            </template>
          </div>
        </div>
      </div>
    </header>

    <!-- Hero Section -->
    <section class="relative overflow-hidden pt-14 pb-14 bg-mesh-light dark:bg-mesh-dark">
      <div class="mx-auto w-full px-12 text-center">
        <!-- Badge -->
        <div
          class="inline-flex items-center px-3 py-3 rounded-full text-xs font-medium bg-primary/10 text-primary mb-12 animate-fade-down"
        >
          Enterprise Authentication Server
        </div>

        <!-- Heading -->
        <h1 class="text-3xl sm:text-4xl md:text-5xl font-semibold tracking-tight leading-[1.15] animate-fade-up">
          Lightweight, Scalable Authentication<br class="hidden sm:block" />
          <span class="text-primary">for Modern Applications</span>
        </h1>

        <!-- Subtitle -->
        <p class="inline-flex text-base sm:text-lg text-muted-foreground max-w-2xl py-3 animate-fade-up stagger-1">
          CloseAuth provides centralized identity management with OAuth2.1 &amp; OpenID Connect.
          Focus on your business logic while we handle user authentication, authorization, and security.
        </p>

        <!-- CTA -->
        <div class="flex flex-col sm:flex-row gap-3 justify-center mb-8 py-3 animate-fade-up stagger-2">
          <template v-if="!authStore.isAuthenticated">
            <RouterLink to="/admin/register">
              <Button size="lg" class="px-8">Start Free Trial →</Button>
            </RouterLink>
            <RouterLink to="/admin/login">
              <Button variant="outline" size="lg" class="px-8">View Demo</Button>
            </RouterLink>
          </template>
          <template v-else>
            <RouterLink to="/admin/dashboard">
              <Button size="lg" class="px-8">Go to Dashboard →</Button>
            </RouterLink>
          </template>
        </div>

        <!-- Feature Pills -->
        <div class="flex flex-wrap justify-center gap-5 text-sm text-muted-foreground animate-fade-up stagger-3">
          <div class="flex items-center gap-1.5">
            <Check class="h-4 w-4 text-green-500" />
            <span>No credit card required</span>
          </div>
          <div class="flex items-center gap-1.5">
            <Check class="h-4 w-4 text-green-500" />
            <span>5-minute setup</span>
          </div>
          <div class="flex items-center gap-1.5">
            <Check class="h-4 w-4 text-green-500" />
            <span>Enterprise ready</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Features Section -->
    <section id="features" class="py-14">
      <div class="mx-auto w-full px-30">
        <div class="text-center mb-10">
          <h2 class="text-2xl sm:text-3xl font-semibold tracking-tight mb-6 py-2">Built for Modern Authentication</h2>
          <p class="text-muted-foreground mx-auto">
            CloseAuth implements industry standards and best practices to provide secure,
            scalable authentication for your applications.
          </p>
        </div>

        <!-- Features Grid -->
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4 py-4">
          <div class="rounded-xl border bg-card text-card-foreground p-6 hover-lift">
            <div class="flex items-center gap-3 mb-3">
              <div class="h-10 w-10 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
                <ShieldCheck class="h-5 w-5 text-primary" />
              </div>
              <h3 class="font-semibold">OAuth2.1 &amp; OpenID Connect</h3>
            </div>
            <p class="text-sm text-muted-foreground leading-relaxed">
              Built with modern authentication standards for maximum security and compatibility.
            </p>
          </div>

          <div class="rounded-xl border bg-card text-card-foreground p-6 hover-lift">
            <div class="flex items-center gap-3 mb-3">
              <div class="h-10 w-10 rounded-lg bg-green-500/10 flex items-center justify-center shrink-0">
                <Users class="h-5 w-5 text-green-600" />
              </div>
              <h3 class="font-semibold">Multi-Tenant Support</h3>
            </div>
            <p class="text-sm text-muted-foreground leading-relaxed">
              Manage multiple applications and organizations with per-tenant branding and configuration.
            </p>
          </div>

          <div class="rounded-xl border bg-card text-card-foreground p-6 hover-lift">
            <div class="flex items-center gap-3 mb-3">
              <div class="h-10 w-10 rounded-lg bg-violet-500/10 flex items-center justify-center shrink-0">
                <KeyRound class="h-5 w-5 text-violet-600" />
              </div>
              <h3 class="font-semibold">Hybrid Token Strategy</h3>
            </div>
            <p class="text-sm text-muted-foreground leading-relaxed">
              JWT access tokens for performance, opaque refresh tokens for security.
            </p>
          </div>

          <div class="rounded-xl border bg-card text-card-foreground p-6 hover-lift">
            <div class="flex items-center gap-3 mb-3">
              <div class="h-10 w-10 rounded-lg bg-amber-500/10 flex items-center justify-center shrink-0">
                <Server class="h-5 w-5 text-amber-600" />
              </div>
              <h3 class="font-semibold">Microservices Ready</h3>
            </div>
            <p class="text-sm text-muted-foreground leading-relaxed">
              Seamless integration across distributed systems and microservice architectures.
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- Everything You Need Section -->
    <section class="py-14 bg-muted/40">
      <div class="mx-auto w-full px-30">
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-10 items-center">
          <!-- Left Content -->
          <div>
            <h2 class="text-2xl sm:text-3xl font-semibold tracking-tight mb-4">
              Everything you need for<br />authentication
            </h2>
            <p class="text-muted-foreground mb-6 leading-relaxed py-2">
              CloseAuth handles the complexity of modern authentication so you can focus on building great products.
              From user registration to advanced security features, we've got you covered.
            </p>

            <div class="space-y-2.5">
              <div v-for="item in [
                'Centralized identity management',
                'Role-based access control',
                'Secure password reset flows',
                'Session management',
                'API key management',
                'Audit logging & monitoring',
              ]" :key="item" class="flex items-center gap-2.5 text-sm">
                <Check class="h-4 w-4 text-green-500 shrink-0" />
                <span>{{ item }}</span>
              </div>
            </div>
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
                <Button class="w-full" size="lg">Create Free Account</Button>
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

    <!-- Footer -->
    <footer class="border-t border-border py-6">
      <div class="mx-auto w-full max-w-6xl px-6 flex flex-col sm:flex-row items-center justify-between gap-4">
        <div class="flex items-center gap-2">
          <div class="h-5 w-5 rounded bg-primary flex items-center justify-center">
            <ShieldCheck class="h-3 w-3 text-primary-foreground" />
          </div>
          <span class="text-xs font-mono text-muted-foreground">Powered by CloseAuth</span>
        </div>
        <p class="text-xs text-muted-foreground">© 2026 CloseAuth. All rights reserved.</p>
      </div>
    </footer>
  </div>
</template>
