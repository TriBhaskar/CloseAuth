import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { LogoComponent } from './logo.component';

@Component({
  selector: 'app-footer',
  standalone: true,
  imports: [CommonModule, RouterModule, LogoComponent],
  template: `
    <footer class="bg-white border-t border-gray-200">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-12">
        <!-- Footer Content -->
        <div class="grid grid-cols-1 md:grid-cols-4 gap-8">
          <!-- Brand Section -->
          <div class="col-span-1 md:col-span-2">
            <div class="flex items-center mb-4">
              <app-logo size="medium"></app-logo>
            </div>
            <p class="text-gray-600 mb-4 max-w-md">
              Enterprise-grade authentication server built with OAuth2.1 &
              OpenID Connect
            </p>
          </div>

          <!-- Documentation Links -->
          <div>
            <h3
              class="text-sm font-semibold text-gray-900 tracking-wider uppercase mb-4"
            >
              Resources
            </h3>
            <ul class="space-y-3">
              <li>
                <a
                  href="#"
                  class="text-gray-600 hover:text-gray-900 transition-colors"
                >
                  Documentation
                </a>
              </li>
              <li>
                <a
                  href="#"
                  class="text-gray-600 hover:text-gray-900 transition-colors"
                >
                  API Reference
                </a>
              </li>
              <li>
                <a
                  href="#"
                  class="text-gray-600 hover:text-gray-900 transition-colors"
                >
                  Support
                </a>
              </li>
            </ul>
          </div>

          <!-- Legal Links -->
          <div>
            <h3
              class="text-sm font-semibold text-gray-900 tracking-wider uppercase mb-4"
            >
              Legal
            </h3>
            <ul class="space-y-3">
              <li>
                <a
                  href="#"
                  class="text-gray-600 hover:text-gray-900 transition-colors"
                >
                  Privacy
                </a>
              </li>
              <li>
                <a
                  href="#"
                  class="text-gray-600 hover:text-gray-900 transition-colors"
                >
                  Terms
                </a>
              </li>
            </ul>
          </div>
        </div>

        <!-- Bottom Section -->
        <div class="border-t border-gray-200 mt-8 pt-8">
          <div class="flex flex-col md:flex-row justify-between items-center">
            <p class="text-gray-500 text-sm">
              © 2024 CloseAuth. All rights reserved.
            </p>
            <div class="flex space-x-6 mt-4 md:mt-0">
              <a
                href="#"
                class="text-gray-500 hover:text-gray-600 text-sm transition-colors"
              >
                Documentation
              </a>
              <a
                href="#"
                class="text-gray-500 hover:text-gray-600 text-sm transition-colors"
              >
                API Reference
              </a>
              <a
                href="#"
                class="text-gray-500 hover:text-gray-600 text-sm transition-colors"
              >
                Support
              </a>
              <a
                href="#"
                class="text-gray-500 hover:text-gray-600 text-sm transition-colors"
              >
                Privacy
              </a>
              <a
                href="#"
                class="text-gray-500 hover:text-gray-600 text-sm transition-colors"
              >
                Terms
              </a>
            </div>
          </div>
        </div>
      </div>
    </footer>
  `,
  styles: [],
})
export class FooterComponent {}
