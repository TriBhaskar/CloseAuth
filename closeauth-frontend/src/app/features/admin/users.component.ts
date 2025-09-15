import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="space-y-6">
      <div>
        <h1 class="text-3xl font-bold text-gray-900">User Management</h1>
        <p class="mt-2 text-sm text-gray-600">
          Manage user accounts and permissions
        </p>
      </div>

      <div class="bg-white shadow rounded-lg p-6">
        <p class="text-gray-500">
          User management interface will be implemented here.
        </p>
      </div>
    </div>
  `,
})
export class UsersComponent {}
