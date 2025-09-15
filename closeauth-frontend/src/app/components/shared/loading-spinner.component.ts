import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-loading-spinner',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="flex items-center justify-center" [class]="containerClass">
      <div
        class="animate-spin rounded-full border-solid border-current border-r-transparent align-[-0.125em] motion-reduce:animate-[spin_1.5s_linear_infinite]"
        [class]="spinnerClass"
        role="status"
      >
        <span class="sr-only">Loading...</span>
      </div>
      @if (message) {
      <span class="ml-3 text-sm font-medium" [class]="textClass">{{
        message
      }}</span>
      }
    </div>
  `,
  styles: [],
})
export class LoadingSpinnerComponent {
  @Input() size: 'sm' | 'md' | 'lg' = 'md';
  @Input() message?: string;
  @Input() color: 'primary' | 'secondary' | 'white' = 'primary';

  get containerClass(): string {
    const base = 'flex items-center justify-center';
    const sizes = {
      sm: 'h-8',
      md: 'h-12',
      lg: 'h-16',
    };
    return `${base} ${sizes[this.size]}`;
  }

  get spinnerClass(): string {
    const sizes = {
      sm: 'h-4 w-4 border-2',
      md: 'h-6 w-6 border-2',
      lg: 'h-8 w-8 border-4',
    };

    const colors = {
      primary: 'text-blue-600',
      secondary: 'text-gray-600',
      white: 'text-white',
    };

    return `${sizes[this.size]} ${colors[this.color]}`;
  }

  get textClass(): string {
    const colors = {
      primary: 'text-gray-700',
      secondary: 'text-gray-600',
      white: 'text-white',
    };
    return colors[this.color];
  }
}
