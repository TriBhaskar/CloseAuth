import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { LogoComponent } from './logo.component';

export interface SidebarItem {
  label: string;
  path: string;
  icon: string;
  children?: SidebarItem[];
  badge?: string;
  badgeColor?: 'blue' | 'red' | 'green' | 'yellow';
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, RouterModule, LogoComponent],
  template: `
    <div class="flex flex-col h-full bg-white border-r border-gray-200 w-64">
      <!-- Sidebar header with logo -->
      <div class="flex items-center px-6 py-4 border-b border-gray-200">
        <app-logo size="medium"></app-logo>
      </div>

      <!-- Navigation -->
      <nav class="flex-1 px-4 py-6 space-y-2 overflow-y-auto">
        @for (item of menuItems; track item.path) {
        <div>
          <a
            [routerLink]="item.path"
            routerLinkActive="bg-teal-50 text-teal-700 border-r-2 border-teal-500"
            [routerLinkActiveOptions]="{ exact: false }"
            class="group flex items-center px-3 py-2 text-sm font-medium rounded-l-md hover:bg-gray-50 transition-colors duration-200 text-gray-700 hover:text-gray-900"
          >
            <!-- Icon -->
            <div
              class="mr-3 h-5 w-5 flex-shrink-0"
              [innerHTML]="getIcon(item.icon)"
            ></div>

            <!-- Label -->
            <span class="flex-1">{{ item.label }}</span>

            <!-- Badge -->
            @if (item.badge) {
            <span
              class="ml-2 inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
              [class]="getBadgeClasses(item.badgeColor || 'blue')"
            >
              {{ item.badge }}
            </span>
            }

            <!-- Chevron for expandable items -->
            @if (item.children && item.children.length > 0) {
            <svg
              class="ml-2 h-4 w-4 transform transition-transform duration-200"
              [class.rotate-90]="isExpanded(item.path)"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M9 5l7 7-7 7"
              />
            </svg>
            }
          </a>

          <!-- Submenu -->
          @if (item.children && item.children.length > 0 &&
          isExpanded(item.path)) {
          <div class="ml-8 mt-1 space-y-1">
            @for (child of item.children; track child.path) {
            <a
              [routerLink]="child.path"
              routerLinkActive="bg-teal-50 text-teal-700"
              class="group flex items-center px-3 py-2 text-sm font-medium rounded-md text-gray-600 hover:bg-gray-50 hover:text-gray-900 transition-colors duration-200"
            >
              <div
                class="mr-3 h-4 w-4 flex-shrink-0"
                [innerHTML]="getIcon(child.icon)"
              ></div>
              <span>{{ child.label }}</span>
              @if (child.badge) {
              <span
                class="ml-auto inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                [class]="getBadgeClasses(child.badgeColor || 'blue')"
              >
                {{ child.badge }}
              </span>
              }
            </a>
            }
          </div>
          }
        </div>
        }
      </nav>

      <!-- Sidebar footer -->
      @if (showFooter) {
      <div class="flex-shrink-0 px-4 py-4 border-t border-gray-200">
        <div class="text-xs text-gray-500 text-center">
          {{ footerText }}
        </div>
      </div>
      }
    </div>
  `,
  styles: [],
})
export class SidebarComponent {
  @Input() title: string = 'Menu';
  @Input() menuItems: SidebarItem[] = [];
  @Input() showFooter: boolean = true;
  @Input() footerText: string = '© 2025 CloseAuth';

  private expandedItems = new Set<string>();

  isActiveRoute(path: string): boolean {
    // This would typically use Router service to check current route
    return false;
  }

  isExpanded(path: string): boolean {
    return this.expandedItems.has(path);
  }

  toggleExpanded(path: string): void {
    if (this.expandedItems.has(path)) {
      this.expandedItems.delete(path);
    } else {
      this.expandedItems.add(path);
    }
  }

  getBadgeClasses(color: string): string {
    const colorClasses = {
      blue: 'bg-blue-100 text-blue-800',
      red: 'bg-red-100 text-red-800',
      green: 'bg-green-100 text-green-800',
      yellow: 'bg-yellow-100 text-yellow-800',
    };
    return (
      colorClasses[color as keyof typeof colorClasses] || colorClasses.blue
    );
  }

  getIcon(iconName: string): string {
    const icons: Record<string, string> = {
      dashboard: `<svg fill="currentColor" viewBox="0 0 20 20"><path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z"/></svg>`,
      users: `<svg fill="currentColor" viewBox="0 0 20 20"><path d="M9 6a3 3 0 11-6 0 3 3 0 016 0zM17 6a3 3 0 11-6 0 3 3 0 016 0zM12.93 17c.046-.327.07-.66.07-1a6.97 6.97 0 00-1.5-4.33A5 5 0 0119 16v1h-6.07zM6 11a5 5 0 015 5v1H1v-1a5 5 0 015-5z"/></svg>`,
      clients: `<svg fill="currentColor" viewBox="0 0 20 20"><path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z"/></svg>`,
      security: `<svg fill="currentColor" viewBox="0 0 20 20"><path fill-rule="evenodd" d="M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" clip-rule="evenodd"/></svg>`,
      api: `<svg fill="currentColor" viewBox="0 0 20 20"><path fill-rule="evenodd" d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v2H2v-4l4.257-4.257A6 6 0 1118 8zm-6-4a1 1 0 100 2 2 2 0 012 2 1 1 0 102 0 4 4 0 00-4-4z" clip-rule="evenodd"/></svg>`,
    };
    return icons[iconName] || icons['dashboard'];
  }
}
