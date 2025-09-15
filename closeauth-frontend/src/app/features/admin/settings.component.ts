import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="space-y-6">
      <div>
        <h1 class="text-3xl font-bold text-gray-900">System Settings</h1>
        <p class="mt-2 text-sm text-gray-600">
          Configure system-wide settings and preferences
        </p>
      </div>

      <div class="bg-white shadow rounded-lg p-6">
        <p class="text-gray-500">
          Settings interface will be implemented here.
        </p>
      </div>
    </div>
  `,
})
export class SettingsComponent {}
