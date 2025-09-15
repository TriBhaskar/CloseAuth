import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import {
  ReactiveFormsModule,
  FormBuilder,
  FormGroup,
  Validators,
} from '@angular/forms';
import { LoadingSpinnerComponent } from '../../components/shared/loading-spinner.component';
import { AlertComponent } from '../../components/shared/alert.component';

@Component({
  selector: 'app-forgot-password',
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
          Reset your password
        </h2>
        <p class="mt-2 text-sm text-gray-600">
          Enter your email address and we'll send you a link to reset your
          password.
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
      } @if (!isSubmitted) {
      <!-- Reset form -->
      <form
        class="mt-8 space-y-6"
        [formGroup]="resetForm"
        (ngSubmit)="onSubmit()"
      >
        <div>
          <!-- Email field -->
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
              placeholder="Enter your email address"
            />
          </div>
          @if (resetForm.get('email')?.invalid &&
          resetForm.get('email')?.touched) {
          <p class="mt-1 text-sm text-red-600">
            @if (resetForm.get('email')?.errors?.['required']) { Email is
            required } @if (resetForm.get('email')?.errors?.['email']) { Please
            enter a valid email address }
          </p>
          }
        </div>

        <!-- Submit button -->
        <div>
          <button
            type="submit"
            [disabled]="resetForm.invalid || isLoading"
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
                d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
              />
            </svg>
            Send reset link }
          </button>
        </div>

        <!-- Back to login -->
        <div class="text-center">
          <a
            routerLink="/auth/login"
            class="text-sm text-blue-600 hover:text-blue-500"
          >
            ← Back to sign in
          </a>
        </div>
      </form>
      } @else {
      <!-- Success state -->
      <div class="text-center space-y-4">
        <div
          class="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-green-100"
        >
          <svg
            class="h-8 w-8 text-green-600"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
            />
          </svg>
        </div>

        <div>
          <h3 class="text-lg font-medium text-gray-900">Check your email</h3>
          <p class="mt-1 text-sm text-gray-600">
            We've sent a password reset link to
            <strong>{{ submittedEmail }}</strong>
          </p>
        </div>

        <div class="space-y-3">
          <p class="text-xs text-gray-500">
            Didn't receive the email? Check your spam folder or
          </p>
          <button
            type="button"
            (click)="resendEmail()"
            [disabled]="resendCooldown > 0"
            class="text-sm text-blue-600 hover:text-blue-500 disabled:text-gray-400 disabled:cursor-not-allowed"
          >
            @if (resendCooldown > 0) { Resend in {{ resendCooldown }}s } @else {
            Click to resend }
          </button>
        </div>

        <div class="pt-4">
          <a
            routerLink="/auth/login"
            class="text-sm text-blue-600 hover:text-blue-500"
          >
            ← Back to sign in
          </a>
        </div>
      </div>
      }
    </div>
  `,
  styles: [],
})
export class ForgotPasswordComponent implements OnInit {
  resetForm!: FormGroup;
  isLoading = false;
  isSubmitted = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  submittedEmail = '';
  resendCooldown = 0;

  constructor(private fb: FormBuilder) {}

  ngOnInit(): void {
    this.resetForm = this.fb.group({
      email: ['', [Validators.required, Validators.email]],
    });
  }

  onSubmit(): void {
    if (this.resetForm.valid && !this.isLoading) {
      this.isLoading = true;
      this.errorMessage = null;

      const email = this.resetForm.get('email')?.value;
      console.log('Password reset request for:', email);

      // Simulate API call
      setTimeout(() => {
        this.isLoading = false;
        this.isSubmitted = true;
        this.submittedEmail = email;
        this.startResendCooldown();
      }, 2000);
    }
  }

  resendEmail(): void {
    if (this.resendCooldown > 0) return;

    console.log('Resending email to:', this.submittedEmail);
    this.successMessage = 'Reset link sent again!';
    this.startResendCooldown();
  }

  private startResendCooldown(): void {
    this.resendCooldown = 60;
    const timer = setInterval(() => {
      this.resendCooldown--;
      if (this.resendCooldown <= 0) {
        clearInterval(timer);
      }
    }, 1000);
  }
}
