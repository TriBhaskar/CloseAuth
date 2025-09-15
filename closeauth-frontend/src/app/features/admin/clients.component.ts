import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-clients',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="space-y-6">
      <div>
        <h1 class="text-3xl font-bold text-gray-900">OAuth2 Clients</h1>
        <p class="mt-2 text-sm text-gray-600">
          Manage your OAuth2 client applications
        </p>
      </div>

      <div class="bg-white shadow rounded-lg p-6">
        <p class="text-gray-500">
          Client management interface will be implemented here.
        </p>
      </div>
    </div>
  `,
})
export class ClientsComponent {}
