import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-logo',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="flex items-center">
      @if (imageUrl) {
      <img [class]="cssClass" [src]="imageUrl" [alt]="alt" />
      } @else {
      <!-- CloseAuth Logo SVG -->
      <svg
        [class]="cssClass"
        viewBox="0 0 400 100"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
      >
        <!-- Database/Server Stack Icon -->
        <g transform="translate(10, 15)">
          <!-- Bottom layer -->
          <ellipse
            cx="35"
            cy="60"
            rx="30"
            ry="8"
            fill="#374151"
            opacity="0.8"
          />
          <rect x="5" y="52" width="60" height="16" rx="8" fill="#374151" />

          <!-- Middle layer -->
          <ellipse
            cx="35"
            cy="45"
            rx="30"
            ry="8"
            fill="#4B5563"
            opacity="0.9"
          />
          <rect x="5" y="37" width="60" height="16" rx="8" fill="#4B5563" />

          <!-- Top layer with teal accent -->
          <ellipse cx="35" cy="30" rx="30" ry="8" fill="#0F766E" />
          <rect x="5" y="22" width="60" height="16" rx="8" fill="#0F766E" />

          <!-- Top ellipse -->
          <ellipse cx="35" cy="22" rx="30" ry="8" fill="#14B8A6" />

          <!-- Curved accent on top layer -->
          <path
            d="M15 25 Q35 18 55 25 Q35 32 15 25"
            fill="#0F766E"
            opacity="0.6"
          />
        </g>

        <!-- CloseAuth Text -->
        <g transform="translate(90, 15)">
          <text
            x="0"
            y="45"
            font-family="Inter, -apple-system, BlinkMacSystemFont, sans-serif"
            font-size="36"
            font-weight="600"
            fill="#374151"
          >
            CloseAuth
          </text>
        </g>
      </svg>
      }
    </div>
  `,
  styles: [],
})
export class LogoComponent {
  @Input() imageUrl?: string;
  @Input() alt: string = 'CloseAuth';
  @Input() size: 'small' | 'medium' | 'large' = 'medium';

  get cssClass(): string {
    const baseClasses = 'w-auto';
    const sizeClasses = {
      small: 'h-6',
      medium: 'h-8',
      large: 'h-12',
    };
    return `${baseClasses} ${sizeClasses[this.size]}`;
  }
}
