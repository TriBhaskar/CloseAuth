import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from '../shared/header.component';
import { FooterComponent } from '../shared/footer.component';

@Component({
  selector: 'app-public-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, HeaderComponent, FooterComponent],
  template: `
    <div class="min-h-screen bg-white">
      <!-- Header -->
      <app-header
        [showNavigation]="true"
        [showUserMenu]="false"
        [showAuthButtons]="true"
        [navigationItems]="publicNavItems"
      >
      </app-header>

      <!-- Main content -->
      <main class="flex-1">
        <router-outlet></router-outlet>
      </main>

      <!-- Footer -->
      <app-footer></app-footer>
    </div>
  `,
  styles: [],
})
export class PublicLayoutComponent {
  publicNavItems = [
    { label: 'Home', path: '/public/home' },
    { label: 'Documentation', path: '/public/docs' },
    { label: 'API', path: '/public/docs' }, // Could point to API section in docs
    { label: 'Pricing', path: '/public/home' }, // Could point to pricing section in home
  ];
}
