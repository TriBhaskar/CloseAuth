import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import {
  ReactiveFormsModule,
  FormBuilder,
  FormGroup,
  FormArray,
  Validators,
} from '@angular/forms';

@Component({
  selector: 'app-create-client',
  standalone: true,
  imports: [CommonModule, RouterModule, ReactiveFormsModule],
  template: `
    <div class="bg-gray-50 min-h-screen">
      <div class="max-w-4xl mx-auto py-8 px-6">
        <!-- Header -->
        <div class="mb-8">
          <div class="flex items-center mb-4">
            <button
              (click)="goBack()"
              class="mr-4 p-2 text-gray-600 hover:text-gray-900 rounded-lg hover:bg-gray-200 transition-colors"
            >
              <svg
                class="w-5 h-5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15 19l-7-7 7-7"
                ></path>
              </svg>
            </button>
            <h1 class="text-3xl font-bold text-gray-900">
              Register New Client Application
            </h1>
          </div>
          <p class="text-gray-600">
            Create a new OAuth2 client application to integrate with CloseAuth
          </p>
        </div>

        <!-- Form -->
        <form
          [formGroup]="clientForm"
          (ngSubmit)="onSubmit()"
          class="space-y-8"
        >
          <!-- Basic Information -->
          <div class="bg-white rounded-xl border border-gray-200 p-6">
            <h2 class="text-xl font-semibold text-gray-900 mb-6">
              Basic Information
            </h2>

            <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
              <!-- Application Name -->
              <div class="md:col-span-2">
                <label
                  for="name"
                  class="block text-sm font-medium text-gray-700 mb-2"
                >
                  Application Name <span class="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  id="name"
                  formControlName="name"
                  placeholder="My Web Application"
                  class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors"
                />
                @if (clientForm.get('name')?.errors &&
                clientForm.get('name')?.touched) {
                <p class="mt-1 text-sm text-red-600">
                  Application name is required
                </p>
                }
              </div>

              <!-- Description -->
              <div class="md:col-span-2">
                <label
                  for="description"
                  class="block text-sm font-medium text-gray-700 mb-2"
                >
                  Description
                </label>
                <textarea
                  id="description"
                  formControlName="description"
                  rows="3"
                  placeholder="Brief description of your application"
                  class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors resize-none"
                ></textarea>
              </div>

              <!-- Application Type -->
              <div class="md:col-span-1">
                <label
                  for="type"
                  class="block text-sm font-medium text-gray-700 mb-2"
                >
                  Application Type <span class="text-red-500">*</span>
                </label>
                <select
                  id="type"
                  formControlName="type"
                  class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors bg-white"
                >
                  <option value="">Select application type</option>
                  <option value="spa">Single Page Application (SPA)</option>
                  <option value="web">Web Application</option>
                  <option value="native">Native/Mobile Application</option>
                  <option value="m2m">Machine to Machine</option>
                </select>
                @if (clientForm.get('type')?.errors &&
                clientForm.get('type')?.touched) {
                <p class="mt-1 text-sm text-red-600">
                  Please select an application type
                </p>
                }
              </div>

              <!-- Logo URL -->
              <div class="md:col-span-1">
                <label
                  for="logoUrl"
                  class="block text-sm font-medium text-gray-700 mb-2"
                >
                  Logo URL (Optional)
                </label>
                <input
                  type="url"
                  id="logoUrl"
                  formControlName="logoUrl"
                  placeholder="https://example.com/logo.png"
                  class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors"
                />
                @if (clientForm.get('logoUrl')?.errors &&
                clientForm.get('logoUrl')?.touched) {
                <p class="mt-1 text-sm text-red-600">
                  Please enter a valid URL
                </p>
                }
              </div>
            </div>
          </div>

          <!-- Redirect URIs -->
          <div class="bg-white rounded-xl border border-gray-200 p-6">
            <h2 class="text-xl font-semibold text-gray-900 mb-2">
              Redirect URIs
            </h2>
            <p class="text-gray-600 mb-6">
              Allowed callback URLs for your application after authentication
            </p>

            <div class="space-y-4">
              <div formArrayName="redirectUris">
                @for (control of redirectUrisArray.controls; track $index; let i
                = $index) {
                <div class="flex gap-3">
                  <input
                    type="url"
                    [formControlName]="i"
                    placeholder="https://yourapp.com/callback"
                    class="flex-1 px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors"
                  />
                  @if (redirectUrisArray.length > 1) {
                  <button
                    type="button"
                    (click)="removeRedirectUri(i)"
                    class="px-4 py-3 text-red-600 hover:text-red-800 hover:bg-red-50 rounded-lg transition-colors"
                  >
                    <svg
                      class="w-5 h-5"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                      ></path>
                    </svg>
                  </button>
                  }
                </div>
                }
              </div>

              <button
                type="button"
                (click)="addRedirectUri()"
                class="w-full py-3 border-2 border-dashed border-gray-300 rounded-lg text-gray-600 hover:border-gray-400 hover:text-gray-800 transition-colors"
              >
                Add Another Redirect URI
              </button>
            </div>
          </div>

          <!-- Permissions & Scopes -->
          <div class="bg-white rounded-xl border border-gray-200 p-6">
            <h2 class="text-xl font-semibold text-gray-900 mb-2">
              Permissions & Scopes
            </h2>
            <p class="text-gray-600 mb-6">
              Select the permissions your application needs
            </p>

            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
              @for (scope of availableScopes; track scope.value) {
              <div
                class="flex items-center justify-between p-4 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors"
              >
                <div class="flex-1">
                  <h3 class="font-semibold text-gray-900">{{ scope.name }}</h3>
                  <p class="text-sm text-gray-600">{{ scope.description }}</p>
                </div>
                <label
                  class="relative inline-flex items-center cursor-pointer ml-4"
                >
                  <input
                    type="checkbox"
                    [value]="scope.value"
                    (change)="onScopeChange($event)"
                    class="sr-only peer"
                  />
                  <div
                    class="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-4 peer-focus:ring-blue-300 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-blue-600"
                  ></div>
                  <span class="ml-3 text-sm font-medium text-gray-700">{{
                    scope.value
                  }}</span>
                </label>
              </div>
              }
            </div>
          </div>

          <!-- Form Actions -->
          <div class="flex justify-end space-x-4 pt-6 border-t border-gray-200">
            <button
              type="button"
              (click)="goBack()"
              class="px-6 py-3 border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              [disabled]="clientForm.invalid || isSubmitting"
              class="px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              @if (isSubmitting) {
              <span class="flex items-center">
                <svg
                  class="animate-spin -ml-1 mr-3 h-5 w-5 text-white"
                  fill="none"
                  viewBox="0 0 24 24"
                >
                  <circle
                    class="opacity-25"
                    cx="12"
                    cy="12"
                    r="10"
                    stroke="currentColor"
                    stroke-width="4"
                  ></circle>
                  <path
                    class="opacity-75"
                    fill="currentColor"
                    d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                  ></path>
                </svg>
                Creating...
              </span>
              } @else { Create Client Application }
            </button>
          </div>
        </form>
      </div>
    </div>
  `,
  styles: [],
})
export class CreateClientComponent implements OnInit {
  clientForm!: FormGroup;
  isSubmitting = false;

  availableScopes = [
    {
      value: 'openid',
      name: 'OpenID Connect',
      description: 'Basic identity information',
    },
    {
      value: 'profile',
      name: 'Profile',
      description: 'User profile information',
    },
    {
      value: 'email',
      name: 'Email',
      description: 'User email address',
    },
    {
      value: 'offline_access',
      name: 'Offline Access',
      description: 'Refresh tokens',
    },
    {
      value: 'read:users',
      name: 'Read Users',
      description: 'Read user data',
    },
    {
      value: 'write:users',
      name: 'Write Users',
      description: 'Modify user data',
    },
  ];

  selectedScopes: string[] = [];

  constructor(private fb: FormBuilder, private router: Router) {}

  ngOnInit(): void {
    this.initializeForm();
  }

  initializeForm(): void {
    this.clientForm = this.fb.group({
      name: ['', [Validators.required]],
      description: [''],
      type: ['', [Validators.required]],
      logoUrl: [''],
      redirectUris: this.fb.array([this.fb.control('', [Validators.required])]),
    });
  }

  get redirectUrisArray(): FormArray {
    return this.clientForm.get('redirectUris') as FormArray;
  }

  addRedirectUri(): void {
    this.redirectUrisArray.push(this.fb.control('', [Validators.required]));
  }

  removeRedirectUri(index: number): void {
    if (this.redirectUrisArray.length > 1) {
      this.redirectUrisArray.removeAt(index);
    }
  }

  onScopeChange(event: Event): void {
    const target = event.target as HTMLInputElement;
    const value = target.value;

    if (target.checked) {
      this.selectedScopes.push(value);
    } else {
      this.selectedScopes = this.selectedScopes.filter(
        (scope) => scope !== value
      );
    }
  }

  onSubmit(): void {
    if (this.clientForm.valid) {
      this.isSubmitting = true;

      const clientData = {
        ...this.clientForm.value,
        scopes: this.selectedScopes,
      };

      console.log('Creating client:', clientData);

      // Simulate API call
      setTimeout(() => {
        this.isSubmitting = false;
        // Show success message and redirect
        this.router.navigate(['/admin/clients']);
      }, 2000);
    }
  }

  goBack(): void {
    this.router.navigate(['/admin/clients']);
  }
}
