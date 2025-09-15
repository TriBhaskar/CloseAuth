import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import {
  ReactiveFormsModule,
  FormBuilder,
  FormGroup,
  Validators,
  AbstractControl,
} from '@angular/forms';
import { LoadingSpinnerComponent } from '../../components/shared/loading-spinner.component';
import { AlertComponent } from '../../components/shared/alert.component';

// Custom validator for password confirmation
function passwordMatchValidator(
  control: AbstractControl
): { [key: string]: boolean } | null {
  const password = control.get('password');
  const confirmPassword = control.get('confirmPassword');

  if (!password || !confirmPassword) {
    return null;
  }

  return password.value === confirmPassword.value
    ? null
    : { passwordMismatch: true };
}

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    ReactiveFormsModule,
    LoadingSpinnerComponent,
    AlertComponent,
  ],
  template: `
    <div class="w-full max-w-md space-y-8">
      <!-- Header -->
      <div class="text-center">
        <h2 class="text-3xl font-extrabold text-gray-900">
          Create your account
        </h2>
        <p class="mt-2 text-sm text-gray-600">
          Already have an account?
          <a
            routerLink="/auth/login"
            class="font-medium text-blue-600 hover:text-blue-500"
          >
            Sign in here
          </a>
        </p>
      </div>

      <!-- Alert messages -->
      @if (errorMessage) {
      <app-alert
        type="error"
        [message]="errorMessage"
        (dismissed)="errorMessage = null"
      >
      </app-alert>
      } @if (successMessage) {
      <app-alert
        type="success"
        [message]="successMessage"
        (dismissed)="successMessage = null"
      >
      </app-alert>
      }

      <!-- Registration form -->
      <form
        class="mt-8 space-y-6"
        [formGroup]="registerForm"
        (ngSubmit)="onSubmit()"
      >
        <div class="space-y-4">
          <!-- Name fields -->
          <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label
                for="firstName"
                class="block text-sm font-medium text-gray-700"
              >
                First name
              </label>
              <div class="mt-1">
                <input
                  id="firstName"
                  name="firstName"
                  type="text"
                  formControlName="firstName"
                  autocomplete="given-name"
                  class="appearance-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                  placeholder="First name"
                />
              </div>
              @if (registerForm.get('firstName')?.invalid &&
              registerForm.get('firstName')?.touched) {
              <p class="mt-1 text-sm text-red-600">First name is required</p>
              }
            </div>

            <div>
              <label
                for="lastName"
                class="block text-sm font-medium text-gray-700"
              >
                Last name
              </label>
              <div class="mt-1">
                <input
                  id="lastName"
                  name="lastName"
                  type="text"
                  formControlName="lastName"
                  autocomplete="family-name"
                  class="appearance-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                  placeholder="Last name"
                />
              </div>
              @if (registerForm.get('lastName')?.invalid &&
              registerForm.get('lastName')?.touched) {
              <p class="mt-1 text-sm text-red-600">Last name is required</p>
              }
            </div>
          </div>

          <!-- Email field -->
          <div>
            <label for="email" class="block text-sm font-medium text-gray-700">
              Email address
            </label>
            <div class="mt-1">
              <input
                id="email"
                name="email"
                type="email"
                formControlName="email"
                autocomplete="email"
                required
                class="appearance-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Enter your email"
              />
            </div>
            @if (registerForm.get('email')?.invalid &&
            registerForm.get('email')?.touched) {
            <p class="mt-1 text-sm text-red-600">
              @if (registerForm.get('email')?.errors?.['required']) { Email is
              required } @if (registerForm.get('email')?.errors?.['email']) {
              Please enter a valid email address }
            </p>
            }
          </div>

          <!-- Username field -->
          <div>
            <label
              for="username"
              class="block text-sm font-medium text-gray-700"
            >
              Username (optional)
            </label>
            <div class="mt-1">
              <input
                id="username"
                name="username"
                type="text"
                formControlName="username"
                autocomplete="username"
                class="appearance-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Choose a username"
              />
            </div>
            @if (registerForm.get('username')?.invalid &&
            registerForm.get('username')?.touched) {
            <p class="mt-1 text-sm text-red-600">
              Username must be at least 3 characters long
            </p>
            }
          </div>

          <!-- Password field -->
          <div>
            <label
              for="password"
              class="block text-sm font-medium text-gray-700"
            >
              Password
            </label>
            <div class="mt-1 relative">
              <input
                id="password"
                name="password"
                [type]="showPassword ? 'text' : 'password'"
                formControlName="password"
                autocomplete="new-password"
                required
                class="appearance-none relative block w-full px-3 py-2 pr-10 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Create a password"
              />
              <button
                type="button"
                class="absolute inset-y-0 right-0 pr-3 flex items-center"
                (click)="togglePasswordVisibility()"
              >
                @if (showPassword) {
                <svg
                  class="h-5 w-5 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.878 9.878L3 3m6.878 6.878L21 21"
                  />
                </svg>
                } @else {
                <svg
                  class="h-5 w-5 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                  />
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                  />
                </svg>
                }
              </button>
            </div>
            @if (registerForm.get('password')?.invalid &&
            registerForm.get('password')?.touched) {
            <p class="mt-1 text-sm text-red-600">
              Password must be at least 8 characters long and contain uppercase,
              lowercase, number, and special character
            </p>
            }

            <!-- Password strength indicator -->
            @if (registerForm.get('password')?.value) {
            <div class="mt-2">
              <div class="flex space-x-1">
                @for (item of passwordStrength; track $index) {
                <div class="flex-1 h-1 rounded-full" [class]="item.class"></div>
                }
              </div>
              <p class="mt-1 text-xs" [class]="passwordStrengthText.class">
                {{ passwordStrengthText.text }}
              </p>
            </div>
            }
          </div>

          <!-- Confirm password field -->
          <div>
            <label
              for="confirmPassword"
              class="block text-sm font-medium text-gray-700"
            >
              Confirm password
            </label>
            <div class="mt-1">
              <input
                id="confirmPassword"
                name="confirmPassword"
                [type]="showConfirmPassword ? 'text' : 'password'"
                formControlName="confirmPassword"
                autocomplete="new-password"
                required
                class="appearance-none relative block w-full px-3 py-2 pr-10 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Confirm your password"
              />
              <button
                type="button"
                class="absolute inset-y-0 right-0 pr-3 flex items-center"
                (click)="toggleConfirmPasswordVisibility()"
              >
                @if (showConfirmPassword) {
                <svg
                  class="h-5 w-5 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.878 9.878L3 3m6.878 6.878L21 21"
                  />
                </svg>
                } @else {
                <svg
                  class="h-5 w-5 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                  />
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                  />
                </svg>
                }
              </button>
            </div>
            @if (registerForm.errors?.['passwordMismatch'] &&
            registerForm.get('confirmPassword')?.touched) {
            <p class="mt-1 text-sm text-red-600">Passwords do not match</p>
            }
          </div>

          <!-- Terms and conditions -->
          <div class="flex items-center">
            <input
              id="acceptTerms"
              name="acceptTerms"
              type="checkbox"
              formControlName="acceptTerms"
              class="h-4 w-4 text-blue-600 focus:ring-blue-500 border-gray-300 rounded"
            />
            <label for="acceptTerms" class="ml-2 block text-sm text-gray-900">
              I agree to the
              <a href="#" class="text-blue-600 hover:text-blue-500"
                >Terms of Service</a
              >
              and
              <a href="#" class="text-blue-600 hover:text-blue-500"
                >Privacy Policy</a
              >
            </label>
          </div>
          @if (registerForm.get('acceptTerms')?.invalid &&
          registerForm.get('acceptTerms')?.touched) {
          <p class="mt-1 text-sm text-red-600">
            You must accept the terms and conditions
          </p>
          }
        </div>

        <!-- Submit button -->
        <div>
          <button
            type="submit"
            [disabled]="registerForm.invalid || isLoading"
            class="group relative w-full flex justify-center py-2 px-4 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            @if (isLoading) {
            <app-loading-spinner size="sm" color="white"></app-loading-spinner>
            } @else {
            <svg
              class="w-5 h-5 mr-2"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z"
              />
            </svg>
            Create account }
          </button>
        </div>
      </form>
    </div>
  `,
  styles: [],
})
export class RegisterComponent implements OnInit {
  registerForm!: FormGroup;
  isLoading = false;
  showPassword = false;
  showConfirmPassword = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  constructor(private fb: FormBuilder) {}

  ngOnInit(): void {
    this.registerForm = this.fb.group(
      {
        firstName: ['', Validators.required],
        lastName: ['', Validators.required],
        email: ['', [Validators.required, Validators.email]],
        username: ['', Validators.minLength(3)],
        password: ['', [Validators.required, this.passwordValidator]],
        confirmPassword: ['', Validators.required],
        acceptTerms: [false, Validators.requiredTrue],
      },
      { validators: passwordMatchValidator }
    );

    // Watch password changes for strength indicator
    this.registerForm.get('password')?.valueChanges.subscribe(() => {
      this.updatePasswordStrength();
    });
  }

  passwordValidator(
    control: AbstractControl
  ): { [key: string]: boolean } | null {
    const value = control.value;
    if (!value) return null;

    const hasUpperCase = /[A-Z]/.test(value);
    const hasLowerCase = /[a-z]/.test(value);
    const hasNumeric = /[0-9]/.test(value);
    const hasSpecialChar = /[!@#$%^&*(),.?":{}|<>]/.test(value);
    const isLengthValid = value.length >= 8;

    const isValid =
      hasUpperCase &&
      hasLowerCase &&
      hasNumeric &&
      hasSpecialChar &&
      isLengthValid;
    return isValid ? null : { passwordStrength: true };
  }

  passwordStrength: Array<{ class: string }> = [];
  passwordStrengthText = { text: '', class: '' };

  updatePasswordStrength(): void {
    const password = this.registerForm.get('password')?.value || '';
    let score = 0;

    // Check different criteria
    if (password.length >= 8) score++;
    if (/[A-Z]/.test(password)) score++;
    if (/[a-z]/.test(password)) score++;
    if (/[0-9]/.test(password)) score++;
    if (/[!@#$%^&*(),.?":{}|<>]/.test(password)) score++;

    // Update strength bars
    this.passwordStrength = Array(5)
      .fill(null)
      .map((_, index) => ({
        class:
          index < score
            ? score <= 2
              ? 'bg-red-500'
              : score <= 3
              ? 'bg-yellow-500'
              : 'bg-green-500'
            : 'bg-gray-200',
      }));

    // Update strength text
    if (score <= 2) {
      this.passwordStrengthText = {
        text: 'Weak password',
        class: 'text-red-600',
      };
    } else if (score <= 3) {
      this.passwordStrengthText = {
        text: 'Medium password',
        class: 'text-yellow-600',
      };
    } else {
      this.passwordStrengthText = {
        text: 'Strong password',
        class: 'text-green-600',
      };
    }
  }

  togglePasswordVisibility(): void {
    this.showPassword = !this.showPassword;
  }

  toggleConfirmPasswordVisibility(): void {
    this.showConfirmPassword = !this.showConfirmPassword;
  }

  onSubmit(): void {
    if (this.registerForm.valid && !this.isLoading) {
      this.isLoading = true;
      this.errorMessage = null;

      const formData = this.registerForm.value;
      console.log('Registration attempt:', formData);

      // Simulate API call
      setTimeout(() => {
        this.isLoading = false;
        this.successMessage =
          'Account created successfully! Please check your email to verify your account.';
      }, 2000);
    }
  }
}
