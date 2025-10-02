# CloseAuth Frontend Documentation

## Overview

CloseAuth Frontend is an Angular 20 application that provides a comprehensive authentication and authorization management interface. It serves as the administrative dashboard and public-facing interface for the CloseAuth Authorization Server.

## Table of Contents

1. [Architecture](#architecture)
2. [Routes & Navigation](#routes--navigation)
3. [Features & Components](#features--components)
4. [Pages & Layouts](#pages--layouts)
5. [Services & Guards](#services--guards)
6. [Models & Types](#models--types)
7. [Getting Started](#getting-started)

## Architecture

### Technology Stack

- **Framework**: Angular 20 (Standalone Components)
- **Styling**: TailwindCSS 4.1.13
- **Build System**: Angular CLI 20.1.0
- **Language**: TypeScript 5.8.2

### Project Structure

```
src/
├── app/
│   ├── components/
│   │   ├── layout/           # Layout components
│   │   └── shared/           # Reusable UI components
│   ├── features/
│   │   ├── admin/            # Admin dashboard features
│   │   ├── auth/             # Authentication features
│   │   └── public/           # Public pages
│   ├── guards/               # Route guards
│   ├── models/               # TypeScript interfaces
│   ├── services/             # Business logic services
│   ├── app.config.ts         # App configuration
│   ├── app.routes.ts         # Route definitions
│   └── app.ts                # Root component
├── assets/                   # Static assets
└── styles.scss               # Global styles
```

## Routes & Navigation

### Root Routes

| Route | Description                            | Access Level |
| ----- | -------------------------------------- | ------------ |
| `/`   | Redirects to `/public/home`            | Public       |
| `/**` | Wildcard - redirects to `/public/home` | Public       |

### Authentication Routes (`/auth`)

Protected by: None (Public)
Layout: `AuthLayoutComponent`

| Route                   | Component                 | Description            |
| ----------------------- | ------------------------- | ---------------------- |
| `/auth/login`           | `LoginComponent`          | User login page        |
| `/auth/register`        | `RegisterComponent`       | User registration page |
| `/auth/forgot-password` | `ForgotPasswordComponent` | Password reset page    |

### Admin Routes (`/admin`)

Protected by: `AuthGuard` + `AdminGuard`
Layout: `AdminLayoutComponent`

| Route                  | Component                       | Description              |
| ---------------------- | ------------------------------- | ------------------------ |
| `/admin`               | Redirects to `/admin/dashboard` | -                        |
| `/admin/dashboard`     | `DashboardComponent`            | Admin dashboard overview |
| `/admin/users`         | `UsersComponent`                | User management          |
| `/admin/clients`       | `ClientsComponent`              | OAuth2 client management |
| `/admin/create-client` | `CreateClientComponent`         | Create new OAuth2 client |
| `/admin/roles`         | `RolesComponent`                | Role management          |
| `/admin/audit`         | `AuditComponent`                | Security audit logs      |
| `/admin/settings`      | `SettingsComponent`             | System settings          |

### Public Routes (`/public`)

Protected by: None (Public)
Layout: `PublicLayoutComponent`

| Route          | Component                   | Description       |
| -------------- | --------------------------- | ----------------- |
| `/public`      | Redirects to `/public/home` | -                 |
| `/public/home` | `HomeComponent`             | Landing page      |
| `/public/docs` | `DocumentationComponent`    | API documentation |

## Features & Components

### Layout Components

#### 1. AuthLayoutComponent

- **Purpose**: Layout for authentication pages
- **Features**:
  - Simplified header (no navigation)
  - Centered content area
  - Minimal footer
- **Used by**: Login, Register, Forgot Password pages

#### 2. AdminLayoutComponent

- **Purpose**: Layout for admin dashboard
- **Features**:
  - Responsive sidebar navigation
  - Mobile-friendly hamburger menu
  - Admin-specific navigation items
- **Navigation Items**:
  - Dashboard
  - Users
  - Client Apps
  - Security (Audit)
  - API Keys (Settings)

#### 3. PublicLayoutComponent

- **Purpose**: Layout for public pages
- **Features**:
  - Full header with navigation
  - Auth buttons (Sign in/Sign up)
  - Footer
- **Navigation Items**:
  - Home
  - Documentation
  - API

### Shared Components

#### 1. HeaderComponent

- **Purpose**: Application header
- **Features**:
  - Configurable navigation items
  - User menu (when authenticated)
  - Authentication buttons
  - Responsive design

#### 2. SidebarComponent

- **Purpose**: Sidebar navigation
- **Features**:
  - Hierarchical menu structure
  - Active route highlighting
  - Badge support for menu items
  - Expandable/collapsible sections

#### 3. FooterComponent

- **Purpose**: Application footer
- **Features**:
  - Copyright information
  - Links to important pages

#### 4. AlertComponent

- **Purpose**: Display notifications
- **Features**:
  - Success, error, warning, info types
  - Dismissible alerts
  - Auto-dismiss functionality

#### 5. LoadingSpinnerComponent

- **Purpose**: Loading indicators
- **Features**:
  - Multiple sizes (sm, md, lg)
  - Customizable colors
  - Overlay support

#### 6. LogoComponent

- **Purpose**: Application logo
- **Features**:
  - Multiple sizes
  - Consistent branding

### Authentication Features

#### 1. LoginComponent

- **Purpose**: User authentication
- **Features**:
  - Email/password login
  - Remember me option
  - Password visibility toggle
  - OAuth provider buttons (Google, GitHub)
  - Form validation
  - Error handling

#### 2. RegisterComponent

- **Purpose**: User registration
- **Features**:
  - Personal information collection
  - Password strength indicator
  - Password confirmation
  - Terms acceptance
  - Email validation
  - Real-time form validation

#### 3. ForgotPasswordComponent

- **Purpose**: Password reset
- **Features**:
  - Email-based reset
  - Success confirmation
  - Resend functionality with cooldown
  - Error handling

### Admin Features

#### 1. DashboardComponent

- **Purpose**: Admin overview
- **Features**:
  - Statistics cards (Users, Clients, Sessions, Security Events)
  - Recent client applications
  - Quick action cards
  - Navigation shortcuts

#### 2. UsersComponent

- **Purpose**: User management
- **Features**:
  - User listing and filtering
  - User status management
  - Role assignment
  - User creation/editing
  - Statistics overview

#### 3. ClientsComponent

- **Purpose**: OAuth2 client management
- **Features**:
  - Client application listing
  - Client status management
  - Client configuration
  - Application type filtering

#### 4. CreateClientComponent

- **Purpose**: OAuth2 client creation
- **Features**:
  - Basic information form
  - Redirect URI management
  - Scope/permission selection
  - Application type selection
  - Form validation

#### 5. RolesComponent

- **Purpose**: Role and permission management
- **Features**:
  - Role creation and editing
  - Permission assignment
  - Role hierarchy management

#### 6. AuditComponent

- **Purpose**: Security monitoring
- **Features**:
  - Audit log viewing
  - Security event tracking
  - Filtering and search
  - Export functionality

#### 7. SettingsComponent

- **Purpose**: System configuration
- **Features**:
  - Authentication policies
  - Security settings
  - API key management
  - System preferences

### Public Features

#### 1. HomeComponent

- **Purpose**: Landing page
- **Features**:
  - Hero section with CTA
  - Feature highlights
  - Benefits overview
  - Call-to-action sections
  - Responsive design

#### 2. DocumentationComponent

- **Purpose**: API documentation
- **Features**:
  - Integration guides
  - API reference
  - Code examples
  - SDK information

## Pages & Layouts

### Authentication Flow

1. **Landing Page** (`/public/home`)

   - Marketing content
   - Sign up/Sign in buttons
   - Feature overview

2. **Login Page** (`/auth/login`)

   - Email/password form
   - OAuth providers
   - Forgot password link
   - Register link

3. **Registration Page** (`/auth/register`)

   - Personal information
   - Account creation
   - Terms acceptance
   - Password strength validation

4. **Password Reset** (`/auth/forgot-password`)
   - Email submission
   - Reset confirmation
   - Resend functionality

### Admin Dashboard Flow

1. **Dashboard** (`/admin/dashboard`)

   - System overview
   - Quick statistics
   - Recent activity
   - Navigation cards

2. **User Management** (`/admin/users`)

   - User listing
   - User creation/editing
   - Role assignment
   - Status management

3. **Client Management** (`/admin/clients`)

   - OAuth2 client listing
   - Client configuration
   - Application management

4. **Security & Audit** (`/admin/audit`)
   - Security monitoring
   - Audit logs
   - Event tracking

## Services & Guards

### Services

#### 1. AuthService

- **Purpose**: Authentication state management
- **Features**:
  - Login/logout functionality
  - Token management
  - User session handling
  - Authentication status

#### 2. UserService

- **Purpose**: User data management
- **Features**:
  - User CRUD operations
  - Profile management
  - Role assignment

#### 3. ClientService

- **Purpose**: OAuth2 client management
- **Features**:
  - Client CRUD operations
  - Application configuration
  - Credential management

#### 4. BrandingService

- **Purpose**: UI customization
- **Features**:
  - Theme management
  - Logo handling
  - Brand colors

### Guards

#### 1. AuthGuard

- **Purpose**: Protect authenticated routes
- **Behavior**: Redirects to login if not authenticated
- **Used by**: Admin routes

#### 2. AdminGuard

- **Purpose**: Protect admin-only routes
- **Behavior**: Checks for admin role
- **Used by**: Admin dashboard routes

## Models & Types

### User Models

```typescript
interface User {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  roles: string[];
  permissions: string[];
  isActive: boolean;
  isEmailVerified: boolean;
  createdAt: Date;
  updatedAt: Date;
}
```

### OAuth2 Models

```typescript
interface Client {
  id: string;
  name: string;
  type: "spa" | "web" | "native" | "m2m";
  description?: string;
  logoUrl?: string;
  redirectUris: string[];
  scopes: string[];
  isActive: boolean;
  createdAt: Date;
  updatedAt: Date;
}
```

### Common Models

```typescript
interface ApiResponse<T> {
  data: T;
  message: string;
  success: boolean;
  errors?: string[];
}

interface PaginatedResponse<T> extends ApiResponse<T[]> {
  pagination: {
    page: number;
    limit: number;
    total: number;
    totalPages: number;
  };
}
```

## Getting Started

### Prerequisites

- Node.js 18+
- npm or yarn
- Angular CLI 20+

### Installation

1. Clone the repository
2. Install dependencies:
   ```bash
   npm install
   ```

### Development

1. Start the development server:
   ```bash
   npm start
   ```
2. Navigate to `http://localhost:4200`

### Building

1. Build for production:
   ```bash
   npm run build
   ```

### Testing

1. Run unit tests:
   ```bash
   npm test
   ```

### Available Scripts

- `npm start` - Start development server
- `npm run build` - Build for production
- `npm test` - Run unit tests
- `npm run watch` - Build in watch mode

## Security Features

### Authentication

- OAuth2.1 & OpenID Connect support
- JWT access tokens
- Secure refresh tokens
- Session management
- Password strength validation

### Authorization

- Role-based access control (RBAC)
- Route guards
- Permission-based UI elements
- Multi-tenant support

### Security Best Practices

- CSRF protection
- XSS prevention
- Secure token storage
- Input validation
- Error handling

## API Integration

The frontend integrates with the CloseAuth Authorization Server API for:

- User authentication and management
- OAuth2 client management
- Role and permission management
- Audit logging
- System configuration

## Browser Support

- Chrome (latest)
- Firefox (latest)
- Safari (latest)
- Edge (latest)

## Contributing

1. Follow Angular style guide
2. Use TypeScript strict mode
3. Write unit tests for components
4. Follow conventional commits
5. Use TailwindCSS for styling

## License

© 2025 CloseAuth. All rights reserved.
