import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';

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
  imports: [CommonModule, RouterModule],
  template: `
    <div class="flex flex-col h-full bg-gray-900 text-white w-64">
      <!-- Sidebar header -->
      <div
        class="flex items-center justify-center h-16 px-4 border-b border-gray-700"
      >
        <h2 class="text-lg font-semibold">{{ title }}</h2>
      </div>

      <!-- Navigation -->
      <nav class="flex-1 px-4 py-6 space-y-1 overflow-y-auto">
        @for (item of menuItems; track item.path) {
        <div>
          <a
            [routerLink]="item.path"
            routerLinkActive="bg-gray-800 text-white"
            [routerLinkActiveOptions]="{ exact: false }"
            class="group flex items-center px-3 py-2 text-sm font-medium rounded-md hover:bg-gray-700 transition-colors duration-200"
            [class.text-gray-300]="!isActiveRoute(item.path)"
            [class.hover:text-white]="!isActiveRoute(item.path)"
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
              routerLinkActive="bg-gray-800 text-white"
              class="group flex items-center px-3 py-2 text-sm font-medium rounded-md text-gray-300 hover:bg-gray-700 hover:text-white transition-colors duration-200"
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
      <div class="flex-shrink-0 px-4 py-4 border-t border-gray-700">
        <div class="text-xs text-gray-400 text-center">
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
      clients: `<svg fill="currentColor" viewBox="0 0 20 20"><path d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>`,
      users: `<svg fill="currentColor" viewBox="0 0 20 20"><path d="M9 6a3 3 0 11-6 0 3 3 0 016 0zM17 6a3 3 0 11-6 0 3 3 0 016 0zM12.93 17c.046-.327.07-.66.07-1a6.97 6.97 0 00-1.5-4.33A5 5 0 0119 16v1h-6.07zM6 11a5 5 0 015 5v1H1v-1a5 5 0 015-5z"/></svg>`,
      roles: `<svg fill="currentColor" viewBox="0 0 20 20"><path fill-rule="evenodd" d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v2H2v-4l4.257-4.257A6 6 0 1118 8zm-6-4a1 1 0 100 2 2 2 0 012 2 1 1 0 102 0 4 4 0 00-4-4z" clip-rule="evenodd"/></svg>`,
      settings: `<svg fill="currentColor" viewBox="0 0 20 20"><path fill-rule="evenodd" d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" clip-rule="evenodd"/></svg>`,
      audit: `<svg fill="currentColor" viewBox="0 0 20 20"><path fill-rule="evenodd" d="M3 3a1 1 0 000 2v8a2 2 0 002 2h2.586l-1.293 1.293a1 1 0 101.414 1.414L10 15.414l2.293 2.293a1 1 0 001.414-1.414L12.414 15H15a2 2 0 002-2V5a1 1 0 100-2H3zm11.707 4.707a1 1 0 00-1.414-1.414L10 9.586 8.707 8.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/></svg>`,
    };
    return icons[iconName] || icons['dashboard'];
  }
}
