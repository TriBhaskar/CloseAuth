import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { LogoComponent } from './logo.component';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, RouterModule, LogoComponent],
  template: `
    <header class="bg-white shadow-sm border-b border-gray-200">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex justify-between items-center h-16">
          <!-- Logo and brand -->
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <app-logo
                [imageUrl]="logoUrl"
                [alt]="brandName"
                size="medium"
              ></app-logo>
            </div>

            @if (showNavigation) {
            <nav class="hidden md:ml-6 md:flex md:space-x-8">
              @for (item of navigationItems; track item.path) {
              <a
                [routerLink]="item.path"
                routerLinkActive="text-blue-600 border-blue-500"
                class="text-gray-500 hover:text-gray-700 px-3 py-2 text-sm font-medium border-b-2 border-transparent hover:border-gray-300"
              >
                {{ item.label }}
              </a>
              }
            </nav>
            }
          </div>

          <!-- User menu -->
          @if (showUserMenu && user) {
          <div class="flex items-center space-x-4">
            <span class="text-sm text-gray-700">{{ user.email }}</span>
            <div class="relative">
              <button
                type="button"
                class="flex items-center text-sm rounded-full focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                (click)="toggleUserMenu()"
              >
                <img
                  class="h-8 w-8 rounded-full"
                  [src]="user.avatar || defaultAvatar"
                  [alt]="user.email"
                />
              </button>

              @if (isUserMenuOpen) {
              <div
                class="origin-top-right absolute right-0 mt-2 w-48 rounded-md shadow-lg bg-white ring-1 ring-black ring-opacity-5 z-50"
              >
                <div class="py-1">
                  <a
                    href="#"
                    class="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-100"
                    >Profile</a
                  >
                  <a
                    href="#"
                    class="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-100"
                    >Settings</a
                  >
                  <hr class="my-1" />
                  <button
                    type="button"
                    class="block w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100"
                    (click)="logout()"
                  >
                    Sign out
                  </button>
                </div>
              </div>
              }
            </div>
          </div>
          } @else if (showAuthButtons) {
          <div class="flex items-center space-x-4">
            <a
              routerLink="/auth/login"
              class="text-gray-500 hover:text-gray-700 px-3 py-2 text-sm font-medium"
            >
              Sign in
            </a>
            <a
              routerLink="/auth/register"
              class="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-md text-sm font-medium"
            >
              Sign up
            </a>
          </div>
          }
        </div>
      </div>
    </header>
  `,
  styles: [],
})
export class HeaderComponent {
  @Input() brandName: string = 'CloseAuth';
  @Input() logoUrl?: string;
  @Input() showNavigation: boolean = true;
  @Input() showUserMenu: boolean = true;
  @Input() showAuthButtons: boolean = false;
  @Input() user?: { email: string; avatar?: string };
  @Input() navigationItems: { label: string; path: string }[] = [
    { label: 'Dashboard', path: '/admin/dashboard' },
    { label: 'Clients', path: '/admin/clients' },
    { label: 'Users', path: '/admin/users' },
    { label: 'Roles', path: '/admin/roles' },
  ];

  isUserMenuOpen = false;
  defaultAvatar =
    'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMzIiIGhlaWdodD0iMzIiIHZpZXdCb3g9IjAgMCAzMiAzMiIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPGNpcmNsZSBjeD0iMTYiIGN5PSIxNiIgcj0iMTYiIGZpbGw9IiNEMUQ1REIiLz4KPHBhdGggZD0iTTE2IDEyQzE3LjEwNDYgMTIgMTggMTEuMTA0NiAxOCAxMEMxOCA4Ljg5NTQzIDE3LjEwNDYgOCAxNiA4QzE0Ljg5NTQgOCAxNCA4Ljg5NTQzIDE0IDEwQzE0IDExLjEwNDYgMTQuODk1NCAxMiAxNiAxMloiIGZpbGw9IndoaXRlIi8+CjxwYXRoIGQ9Ik0xNiAyNEMyMC40MTgzIDI0IDI0IDIwLjQxODMgMjQgMTZIMTZWMjRaIiBmaWxsPSJ3aGl0ZSIvPgo8L3N2Zz4K';

  toggleUserMenu(): void {
    this.isUserMenuOpen = !this.isUserMenuOpen;
  }

  logout(): void {
    this.isUserMenuOpen = false;
    // Emit logout event or call auth service
    console.log('Logout clicked');
  }
}
