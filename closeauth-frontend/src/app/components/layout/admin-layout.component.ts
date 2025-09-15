import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from '../shared/header.component';
import {
  SidebarComponent,
  type SidebarItem,
} from '../shared/sidebar.component';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, HeaderComponent, SidebarComponent],
  template: `
    <div class="flex h-screen bg-gray-100">
      <!-- Sidebar -->
      <div class="hidden md:flex md:flex-shrink-0">
        <app-sidebar title="Admin Panel" [menuItems]="sidebarItems">
        </app-sidebar>
      </div>

      <!-- Main content area -->
      <div class="flex flex-col flex-1 overflow-hidden">
        <!-- Header -->
        <app-header
          [showNavigation]="false"
          [showUserMenu]="true"
          [showAuthButtons]="false"
          [user]="currentUser"
        >
        </app-header>

        <!-- Main content -->
        <main
          class="flex-1 relative overflow-y-auto focus:outline-none bg-white"
        >
          <div class="py-6">
            <div class="max-w-7xl mx-auto px-4 sm:px-6 md:px-8">
              <router-outlet></router-outlet>
            </div>
          </div>
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
      <div class="relative flex-1 flex flex-col max-w-xs w-full bg-gray-900">
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

        <app-sidebar title="Admin Panel" [menuItems]="sidebarItems">
        </app-sidebar>
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
      label: 'Client Management',
      path: '/admin/clients',
      icon: 'clients',
      children: [
        { label: 'All Clients', path: '/admin/clients', icon: 'clients' },
        { label: 'Add Client', path: '/admin/clients/create', icon: 'clients' },
      ],
    },
    {
      label: 'User Management',
      path: '/admin/users',
      icon: 'users',
      children: [
        { label: 'All Users', path: '/admin/users', icon: 'users' },
        { label: 'User Roles', path: '/admin/users/roles', icon: 'roles' },
      ],
    },
    {
      label: 'Roles & Permissions',
      path: '/admin/roles',
      icon: 'roles',
    },
    {
      label: 'Audit Logs',
      path: '/admin/audit',
      icon: 'audit',
    },
    {
      label: 'System Settings',
      path: '/admin/settings',
      icon: 'settings',
      children: [
        { label: 'General', path: '/admin/settings/general', icon: 'settings' },
        {
          label: 'Email Templates',
          path: '/admin/settings/email',
          icon: 'settings',
        },
        {
          label: 'Branding',
          path: '/admin/settings/branding',
          icon: 'settings',
        },
      ],
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
