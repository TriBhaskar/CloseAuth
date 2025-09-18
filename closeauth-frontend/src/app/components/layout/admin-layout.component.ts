import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import {
  SidebarComponent,
  type SidebarItem,
} from '../shared/sidebar.component';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, SidebarComponent],
  template: `
    <div class="flex h-screen bg-gray-50">
      <!-- Sidebar -->
      <div class="hidden md:flex md:flex-shrink-0">
        <app-sidebar [menuItems]="sidebarItems"> </app-sidebar>
      </div>

      <!-- Main content area -->
      <div class="flex flex-col flex-1 overflow-hidden">
        <!-- Main content -->
        <main class="flex-1 relative overflow-y-auto focus:outline-none">
          <router-outlet></router-outlet>
        </main>
      </div>
    </div>

    <!-- Mobile sidebar overlay -->
    @if (isMobileSidebarOpen) {
    <div class="fixed inset-0 flex z-40 md:hidden">
      <!-- Overlay -->
      <div
        class="fixed inset-0 bg-gray-600 bg-opacity-75"
        (click)="toggleMobileSidebar()"
      ></div>

      <!-- Sidebar -->
      <div class="relative flex-1 flex flex-col max-w-xs w-full bg-white">
        <div class="absolute top-0 right-0 -mr-12 pt-2">
          <button
            type="button"
            class="ml-1 flex items-center justify-center h-10 w-10 rounded-full focus:outline-none focus:ring-2 focus:ring-inset focus:ring-white"
            (click)="toggleMobileSidebar()"
          >
            <span class="sr-only">Close sidebar</span>
            <svg
              class="h-6 w-6 text-white"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        <app-sidebar [menuItems]="sidebarItems"> </app-sidebar>
      </div>
    </div>
    }
  `,
  styles: [],
})
export class AdminLayoutComponent implements OnInit {
  isMobileSidebarOpen = false;
  currentUser: any = null;

  constructor(private authService: AuthService) {}

  sidebarItems: SidebarItem[] = [
    {
      label: 'Dashboard',
      path: '/admin/dashboard',
      icon: 'dashboard',
    },
    {
      label: 'Users',
      path: '/admin/users',
      icon: 'users',
    },
    {
      label: 'Client Apps',
      path: '/admin/clients',
      icon: 'clients',
    },
    {
      label: 'Security',
      path: '/admin/audit',
      icon: 'security',
    },
    {
      label: 'API Keys',
      path: '/admin/settings',
      icon: 'api',
    },
  ];

  ngOnInit(): void {
    // Initialize any required data
    // Get current user from localStorage for demo
    const storedUser = localStorage.getItem('closeauth_user');
    if (storedUser) {
      this.currentUser = JSON.parse(storedUser);
    }
  }

  toggleMobileSidebar(): void {
    this.isMobileSidebarOpen = !this.isMobileSidebarOpen;
  }
}
