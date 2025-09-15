import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { ClientBranding } from '../models/oauth2.model';

export interface ThemeConfig {
  primaryColor: string;
  secondaryColor: string;
  backgroundColor: string;
  textColor: string;
  logoUrl?: string;
  companyName: string;
  customCss?: string;
}

@Injectable({
  providedIn: 'root',
})
export class BrandingService {
  private readonly DEFAULT_THEME: ThemeConfig = {
    primaryColor: '#2563eb', // blue-600
    secondaryColor: '#64748b', // slate-500
    backgroundColor: '#ffffff',
    textColor: '#111827', // gray-900
    companyName: 'CloseAuth',
  };

  private currentThemeSubject = new BehaviorSubject<ThemeConfig>(
    this.DEFAULT_THEME
  );
  public currentTheme$ = this.currentThemeSubject.asObservable();

  constructor() {
    this.initializeTheme();
  }

  /**
   * Initialize theme from client context or default
   */
  private initializeTheme(): void {
    // In a real app, this would detect the client from URL parameters
    // or authorization request context
    const clientId = this.getClientIdFromContext();

    if (clientId) {
      this.loadClientBranding(clientId);
    } else {
      this.applyTheme(this.DEFAULT_THEME);
    }
  }

  /**
   * Load branding for specific client
   */
  loadClientBranding(clientId: string): void {
    // In a real app, this would fetch from your API
    // For demo purposes, we'll simulate different client themes
    const mockClientBranding = this.getMockClientBranding(clientId);

    if (mockClientBranding) {
      const theme: ThemeConfig = {
        primaryColor:
          mockClientBranding.primaryColor || this.DEFAULT_THEME.primaryColor,
        secondaryColor:
          mockClientBranding.secondaryColor ||
          this.DEFAULT_THEME.secondaryColor,
        backgroundColor:
          mockClientBranding.backgroundColor ||
          this.DEFAULT_THEME.backgroundColor,
        textColor: mockClientBranding.textColor || this.DEFAULT_THEME.textColor,
        logoUrl: mockClientBranding.logoUrl,
        companyName:
          mockClientBranding.companyName || this.DEFAULT_THEME.companyName,
        customCss: mockClientBranding.customCss,
      };

      this.applyTheme(theme);
    }
  }

  /**
   * Apply theme configuration
   */
  applyTheme(theme: ThemeConfig): void {
    this.currentThemeSubject.next(theme);
    this.updateCssVariables(theme);

    if (theme.customCss) {
      this.injectCustomCss(theme.customCss);
    }
  }

  /**
   * Get current theme
   */
  getCurrentTheme(): ThemeConfig {
    return this.currentThemeSubject.value;
  }

  /**
   * Reset to default theme
   */
  resetToDefault(): void {
    this.applyTheme(this.DEFAULT_THEME);
  }

  /**
   * Update CSS custom properties
   */
  private updateCssVariables(theme: ThemeConfig): void {
    const root = document.documentElement;

    root.style.setProperty('--color-primary', theme.primaryColor);
    root.style.setProperty('--color-secondary', theme.secondaryColor);
    root.style.setProperty('--color-background', theme.backgroundColor);
    root.style.setProperty('--color-text', theme.textColor);
  }

  /**
   * Inject custom CSS
   */
  private injectCustomCss(customCss: string): void {
    // Remove existing custom CSS
    const existingStyle = document.getElementById('client-custom-css');
    if (existingStyle) {
      existingStyle.remove();
    }

    // Add new custom CSS
    const style = document.createElement('style');
    style.id = 'client-custom-css';
    style.textContent = customCss;
    document.head.appendChild(style);
  }

  /**
   * Get client ID from current context (URL params, etc.)
   */
  private getClientIdFromContext(): string | null {
    // Check URL parameters for client_id
    const urlParams = new URLSearchParams(window.location.search);
    return urlParams.get('client_id');
  }

  /**
   * Mock client branding data (replace with real API call)
   */
  private getMockClientBranding(clientId: string): ClientBranding | null {
    const mockBranding: Record<string, ClientBranding> = {
      'demo-app': {
        primaryColor: '#059669', // green-600
        secondaryColor: '#6b7280', // gray-500
        backgroundColor: '#f9fafb', // gray-50
        textColor: '#111827', // gray-900
        logoUrl:
          'https://via.placeholder.com/200x50/059669/ffffff?text=DemoApp',
        companyName: 'Demo Application',
        privacyPolicyUrl: 'https://demo-app.com/privacy',
        termsOfServiceUrl: 'https://demo-app.com/terms',
      },
      'test-client': {
        primaryColor: '#dc2626', // red-600
        secondaryColor: '#64748b', // slate-500
        backgroundColor: '#ffffff',
        textColor: '#1f2937', // gray-800
        logoUrl:
          'https://via.placeholder.com/200x50/dc2626/ffffff?text=TestClient',
        companyName: 'Test Client Corp',
        customCss: `
          .auth-form {
            border-radius: 12px;
            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
          }
        `,
      },
    };

    return mockBranding[clientId] || null;
  }

  /**
   * Validate hex color
   */
  private isValidHexColor(color: string): boolean {
    return /^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/.test(color);
  }

  /**
   * Convert hex to RGB
   */
  hexToRgb(hex: string): { r: number; g: number; b: number } | null {
    if (!this.isValidHexColor(hex)) {
      return null;
    }

    const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
    return result
      ? {
          r: parseInt(result[1], 16),
          g: parseInt(result[2], 16),
          b: parseInt(result[3], 16),
        }
      : null;
  }

  /**
   * Calculate contrast ratio for accessibility
   */
  getContrastRatio(color1: string, color2: string): number {
    const rgb1 = this.hexToRgb(color1);
    const rgb2 = this.hexToRgb(color2);

    if (!rgb1 || !rgb2) return 0;

    const luminance1 = this.calculateLuminance(rgb1);
    const luminance2 = this.calculateLuminance(rgb2);

    const lighter = Math.max(luminance1, luminance2);
    const darker = Math.min(luminance1, luminance2);

    return (lighter + 0.05) / (darker + 0.05);
  }

  /**
   * Calculate relative luminance
   */
  private calculateLuminance(rgb: { r: number; g: number; b: number }): number {
    const { r, g, b } = rgb;
    const [rs, gs, bs] = [r, g, b].map((c) => {
      c = c / 255;
      return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs;
  }
}
