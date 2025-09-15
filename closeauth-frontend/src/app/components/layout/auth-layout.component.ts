import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from '../shared/header.component';

@Component({
  selector: 'app-auth-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, HeaderComponent],
  template: `
    <div class="min-h-screen bg-gray-50">
      <!-- Header for auth pages (simplified) -->
      <app-header
        [showNavigation]="false"
        [showUserMenu]="false"
        [showAuthButtons]="false"
      >
      </app-header>

      <!-- Main content -->
      <main class="flex-1">
        <div class="max-w-md mx-auto pt-12 pb-16 px-4 sm:px-6 lg:px-8">
          <router-outlet></router-outlet>
        </div>
      </main>

      <!-- Footer -->
      <footer class="bg-white border-t border-gray-200">
        <div class="max-w-7xl mx-auto py-6 px-4 sm:px-6 lg:px-8">
          <div class="text-center text-sm text-gray-500">
            <p>&copy; 2025 CloseAuth. All rights reserved.</p>
            <div class="mt-2 space-x-4">
              <a href="#" class="hover:text-gray-700">Privacy Policy</a>
              <a href="#" class="hover:text-gray-700">Terms of Service</a>
              <a href="#" class="hover:text-gray-700">Support</a>
            </div>
          </div>
        </div>
      </footer>
    </div>
  `,
  styles: [],
})
export class AuthLayoutComponent {}
