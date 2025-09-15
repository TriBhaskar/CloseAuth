import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="space-y-6">
      <div>
        <h1 class="text-3xl font-bold text-gray-900">Roles & Permissions</h1>
        <p class="mt-2 text-sm text-gray-600">
          Configure user roles and access permissions
        </p>
      </div>

      <div class="bg-white shadow rounded-lg p-6">
        <p class="text-gray-500">
          Role management interface will be implemented here.
        </p>
      </div>
    </div>
  `,
})
export class RolesComponent {}
