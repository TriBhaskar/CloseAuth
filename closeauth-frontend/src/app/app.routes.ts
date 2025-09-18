import { Routes } from '@angular/router';
import { AuthGuard } from './guards/auth.guard';
import { AdminGuard } from './guards/admin.guard';

export const routes: Routes = [
  // Default redirect to home page
  { path: '', redirectTo: '/public/home', pathMatch: 'full' },

  // Authentication routes
  {
    path: 'auth',
    loadComponent: () =>
      import('./components/layout/auth-layout.component').then(
        (m) => m.AuthLayoutComponent
      ),
    children: [
      {
        path: 'login',
        loadComponent: () =>
          import('./features/auth/login.component').then(
            (m) => m.LoginComponent
          ),
      },
      {
        path: 'register',
        loadComponent: () =>
          import('./features/auth/register.component').then(
            (m) => m.RegisterComponent
          ),
      },
      {
        path: 'forgot-password',
        loadComponent: () =>
          import('./features/auth/forgot-password.component').then(
            (m) => m.ForgotPasswordComponent
          ),
      },
    ],
  },

  // Admin routes
  {
    path: 'admin',
    loadComponent: () =>
      import('./components/layout/admin-layout.component').then(
        (m) => m.AdminLayoutComponent
      ),
    canActivate: [AuthGuard, AdminGuard],
    children: [
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./features/admin/dashboard.component').then(
            (m) => m.DashboardComponent
          ),
      },
      {
        path: 'clients',
        loadComponent: () =>
          import('./features/admin/clients.component').then(
            (m) => m.ClientsComponent
          ),
      },
      {
        path: 'create-client',
        loadComponent: () =>
          import('./features/admin/create-client.component').then(
            (m) => m.CreateClientComponent
          ),
      },
      {
        path: 'clients',
        loadComponent: () =>
          import('./features/admin/clients.component').then(
            (m) => m.ClientsComponent
          ),
      },
      {
        path: 'users',
        loadComponent: () =>
          import('./features/admin/users.component').then(
            (m) => m.UsersComponent
          ),
      },
      {
        path: 'roles',
        loadComponent: () =>
          import('./features/admin/roles.component').then(
            (m) => m.RolesComponent
          ),
      },
      {
        path: 'audit',
        loadComponent: () =>
          import('./features/admin/audit.component').then(
            (m) => m.AuditComponent
          ),
      },
      {
        path: 'settings',
        loadComponent: () =>
          import('./features/admin/settings.component').then(
            (m) => m.SettingsComponent
          ),
      },
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
    ],
  },

  // Public routes
  {
    path: 'public',
    loadComponent: () =>
      import('./components/layout/public-layout.component').then(
        (m) => m.PublicLayoutComponent
      ),
    children: [
      {
        path: 'home',
        loadComponent: () =>
          import('./features/public/home.component').then(
            (m) => m.HomeComponent
          ),
      },
      {
        path: 'docs',
        loadComponent: () =>
          import('./features/public/documentation.component').then(
            (m) => m.DocumentationComponent
          ),
      },
      // Redirect /public to /public/home
      { path: '', redirectTo: 'home', pathMatch: 'full' },
    ],
  },

  // Wildcard route - redirect to home page
  { path: '**', redirectTo: '/public/home' },
];
