import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <!-- Hero Section -->
    <div class="bg-gradient-to-br from-gray-50 to-gray-100 pt-16 pb-20">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="text-center">
          <!-- Enterprise Authentication Server Badge -->
          <div
            class="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-teal-100 text-teal-800 mb-8"
          >
            Enterprise Authentication Server
          </div>

          <!-- Main Heading -->
          <h1 class="text-4xl md:text-6xl font-bold text-gray-900 mb-6">
            Lightweight, Scalable Authentication<br />
            for Modern Applications
          </h1>

          <!-- Subtitle -->
          <p class="text-xl text-gray-600 max-w-4xl mx-auto mb-12">
            CloseAuth provides centralized identity management with OAuth2.1 &
            OpenID Connect. Focus on your business logic while we handle user
            authentication, authorization, and security.
          </p>

          <!-- CTA Buttons -->
          <div class="flex flex-col sm:flex-row gap-4 justify-center mb-16">
            <button
              class="bg-blue-600 hover:bg-blue-700 text-white px-8 py-4 rounded-lg text-lg font-semibold transition-colors"
            >
              Start Free Trial →
            </button>
            <button
              class="border border-gray-300 hover:border-gray-400 text-gray-700 px-8 py-4 rounded-lg text-lg font-semibold transition-colors"
            >
              View Demo
            </button>
          </div>

          <!-- Feature Pills -->
          <div
            class="flex flex-wrap justify-center gap-6 text-sm text-gray-600"
          >
            <div class="flex items-center">
              <svg
                class="w-5 h-5 text-green-500 mr-2"
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path
                  fill-rule="evenodd"
                  d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                  clip-rule="evenodd"
                ></path>
              </svg>
              No credit card required
            </div>
            <div class="flex items-center">
              <svg
                class="w-5 h-5 text-green-500 mr-2"
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path
                  fill-rule="evenodd"
                  d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                  clip-rule="evenodd"
                ></path>
              </svg>
              5-minute setup
            </div>
            <div class="flex items-center">
              <svg
                class="w-5 h-5 text-green-500 mr-2"
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path
                  fill-rule="evenodd"
                  d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                  clip-rule="evenodd"
                ></path>
              </svg>
              Enterprise ready
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Built for Modern Authentication Section -->
    <div class="py-20 bg-white">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="text-center mb-16">
          <h2 class="text-4xl font-bold text-gray-900 mb-4">
            Built for Modern Authentication
          </h2>
          <p class="text-xl text-gray-600 max-w-3xl mx-auto">
            CloseAuth implements industry standards and best practices to
            provide secure, scalable authentication for your applications.
          </p>
        </div>

        <!-- Features Grid -->
        <div class="grid grid-cols-1 md:grid-cols-2 gap-8 max-w-6xl mx-auto">
          <!-- OAuth2.1 & OpenID Connect -->
          <div
            class="bg-white border border-gray-200 rounded-xl p-8 hover:shadow-lg transition-shadow"
          >
            <div class="flex items-center mb-4">
              <div
                class="w-12 h-12 bg-blue-100 rounded-lg flex items-center justify-center mr-4"
              >
                <svg
                  class="w-6 h-6 text-blue-600"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"
                  ></path>
                </svg>
              </div>
              <h3 class="text-xl font-semibold text-gray-900">
                OAuth2.1 & OpenID Connect
              </h3>
            </div>
            <p class="text-gray-600">
              Built with modern authentication standards for maximum security
              and compatibility.
            </p>
          </div>

          <!-- Multi-Tenant Support -->
          <div
            class="bg-white border border-gray-200 rounded-xl p-8 hover:shadow-lg transition-shadow"
          >
            <div class="flex items-center mb-4">
              <div
                class="w-12 h-12 bg-teal-100 rounded-lg flex items-center justify-center mr-4"
              >
                <svg
                  class="w-6 h-6 text-teal-600"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"
                  ></path>
                </svg>
              </div>
              <h3 class="text-xl font-semibold text-gray-900">
                Multi-Tenant Support
              </h3>
            </div>
            <p class="text-gray-600">
              Manage multiple applications and organizations with per-tenant
              branding and configuration.
            </p>
          </div>

          <!-- Hybrid Token Strategy -->
          <div
            class="bg-white border border-gray-200 rounded-xl p-8 hover:shadow-lg transition-shadow"
          >
            <div class="flex items-center mb-4">
              <div
                class="w-12 h-12 bg-purple-100 rounded-lg flex items-center justify-center mr-4"
              >
                <svg
                  class="w-6 h-6 text-purple-600"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z"
                  ></path>
                </svg>
              </div>
              <h3 class="text-xl font-semibold text-gray-900">
                Hybrid Token Strategy
              </h3>
            </div>
            <p class="text-gray-600">
              JWT access tokens for performance, opaque refresh tokens for
              security.
            </p>
          </div>

          <!-- Microservices Ready -->
          <div
            class="bg-white border border-gray-200 rounded-xl p-8 hover:shadow-lg transition-shadow"
          >
            <div class="flex items-center mb-4">
              <div
                class="w-12 h-12 bg-green-100 rounded-lg flex items-center justify-center mr-4"
              >
                <svg
                  class="w-6 h-6 text-green-600"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01"
                  ></path>
                </svg>
              </div>
              <h3 class="text-xl font-semibold text-gray-900">
                Microservices Ready
              </h3>
            </div>
            <p class="text-gray-600">
              Seamless integration across distributed systems and microservice
              architectures.
            </p>
          </div>
        </div>
      </div>
    </div>

    <!-- Everything you need for authentication Section -->
    <div class="py-20 bg-gray-50">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-16 items-center">
          <!-- Left side - Content -->
          <div>
            <h2 class="text-4xl font-bold text-gray-900 mb-6">
              Everything you need for<br />
              authentication
            </h2>
            <p class="text-xl text-gray-600 mb-8">
              CloseAuth handles the complexity of modern authentication so you
              can focus on building great products. From user registration to
              advanced security features, we've got you covered.
            </p>

            <!-- Feature List -->
            <div class="space-y-4 mb-8">
              <div class="flex items-center text-gray-700">
                <svg
                  class="w-5 h-5 text-green-500 mr-3"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fill-rule="evenodd"
                    d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                    clip-rule="evenodd"
                  ></path>
                </svg>
                Centralized identity management
              </div>
              <div class="flex items-center text-gray-700">
                <svg
                  class="w-5 h-5 text-green-500 mr-3"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fill-rule="evenodd"
                    d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                    clip-rule="evenodd"
                  ></path>
                </svg>
                Role-based access control
              </div>
              <div class="flex items-center text-gray-700">
                <svg
                  class="w-5 h-5 text-green-500 mr-3"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fill-rule="evenodd"
                    d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                    clip-rule="evenodd"
                  ></path>
                </svg>
                Secure password reset flows
              </div>
              <div class="flex items-center text-gray-700">
                <svg
                  class="w-5 h-5 text-green-500 mr-3"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fill-rule="evenodd"
                    d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                    clip-rule="evenodd"
                  ></path>
                </svg>
                Session management
              </div>
              <div class="flex items-center text-gray-700">
                <svg
                  class="w-5 h-5 text-green-500 mr-3"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fill-rule="evenodd"
                    d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                    clip-rule="evenodd"
                  ></path>
                </svg>
                API key management
              </div>
              <div class="flex items-center text-gray-700">
                <svg
                  class="w-5 h-5 text-green-500 mr-3"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fill-rule="evenodd"
                    d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                    clip-rule="evenodd"
                  ></path>
                </svg>
                Audit logging & monitoring
              </div>
            </div>
          </div>

          <!-- Right side - CTA Card -->
          <div class="bg-white rounded-2xl p-8 shadow-lg">
            <h3 class="text-xl font-semibold text-gray-900 mb-2">
              Ready to get started?
            </h3>
            <p class="text-gray-600 mb-6">
              Join thousands of developers who trust CloseAuth for their
              authentication needs.
            </p>

            <div class="space-y-4">
              <button
                class="w-full bg-blue-600 hover:bg-blue-700 text-white py-3 px-6 rounded-lg font-semibold transition-colors"
              >
                Create Free Account
              </button>
              <button
                class="w-full border border-gray-300 hover:border-gray-400 text-gray-700 py-3 px-6 rounded-lg font-semibold transition-colors"
              >
                Sign In to Dashboard
              </button>
            </div>

            <p class="text-sm text-gray-500 mt-4 text-center">
              Questions? Contact our team for enterprise solutions.
            </p>
          </div>
        </div>
      </div>
    </div>
  `,
})
export class HomeComponent {}
