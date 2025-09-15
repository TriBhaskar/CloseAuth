import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';

export type AlertType = 'success' | 'error' | 'warning' | 'info';

@Component({
  selector: 'app-alert',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (isVisible) {
    <div
      class="flex items-center p-4 mb-4 rounded-lg border"
      [class]="alertClasses"
      role="alert"
    >
      <!-- Icon -->
      <div class="flex-shrink-0">
        @switch (type) { @case ('success') {
        <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path
            fill-rule="evenodd"
            d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
            clip-rule="evenodd"
          />
        </svg>
        } @case ('error') {
        <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path
            fill-rule="evenodd"
            d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
            clip-rule="evenodd"
          />
        </svg>
        } @case ('warning') {
        <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path
            fill-rule="evenodd"
            d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
            clip-rule="evenodd"
          />
        </svg>
        } @case ('info') {
        <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path
            fill-rule="evenodd"
            d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z"
            clip-rule="evenodd"
          />
        </svg>
        } }
      </div>

      <!-- Content -->
      <div class="ml-3 flex-1">
        @if (title) {
        <h3 class="text-sm font-medium">{{ title }}</h3>
        }
        <div class="text-sm" [class]="messageClass">
          {{ message }}
        </div>
      </div>

      <!-- Close button -->
      @if (dismissible) {
      <button
        type="button"
        class="ml-auto -mx-1.5 -my-1.5 rounded-lg p-1.5 inline-flex h-8 w-8 hover:bg-gray-200 focus:ring-2 focus:ring-gray-300"
        [class]="closeButtonClass"
        (click)="dismiss()"
        aria-label="Close"
      >
        <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path
            fill-rule="evenodd"
            d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
            clip-rule="evenodd"
          />
        </svg>
      </button>
      }
    </div>
    }
  `,
  styles: [],
})
export class AlertComponent {
  @Input() type: AlertType = 'info';
  @Input() title?: string;
  @Input() message: string = '';
  @Input() dismissible: boolean = true;
  @Input() isVisible: boolean = true;
  @Output() dismissed = new EventEmitter<void>();

  get alertClasses(): string {
    const baseClasses = 'flex items-center p-4 mb-4 rounded-lg border';
    const typeClasses = {
      success: 'text-green-800 bg-green-50 border-green-200',
      error: 'text-red-800 bg-red-50 border-red-200',
      warning: 'text-yellow-800 bg-yellow-50 border-yellow-200',
      info: 'text-blue-800 bg-blue-50 border-blue-200',
    };
    return `${baseClasses} ${typeClasses[this.type]}`;
  }

  get messageClass(): string {
    if (this.title) return 'mt-1';
    return '';
  }

  get closeButtonClass(): string {
    const colors = {
      success: 'text-green-500 hover:bg-green-200 focus:ring-green-300',
      error: 'text-red-500 hover:bg-red-200 focus:ring-red-300',
      warning: 'text-yellow-500 hover:bg-yellow-200 focus:ring-yellow-300',
      info: 'text-blue-500 hover:bg-blue-200 focus:ring-blue-300',
    };
    return colors[this.type];
  }

  dismiss(): void {
    this.isVisible = false;
    this.dismissed.emit();
  }
}
