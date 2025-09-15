import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, BehaviorSubject, tap } from 'rxjs';
import { ApiResponse, AuthTokens } from '../models/common.model';
import {
  User,
  UserLogin,
  UserRegistration,
  PasswordReset,
} from '../models/user.model';

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private readonly API_BASE = '/api/auth';
  private readonly TOKEN_KEY = 'closeauth_tokens';
  private readonly USER_KEY = 'closeauth_user';

  private currentUserSubject = new BehaviorSubject<User | null>(null);
  private isAuthenticatedSubject = new BehaviorSubject<boolean>(false);

  public currentUser$ = this.currentUserSubject.asObservable();
  public isAuthenticated$ = this.isAuthenticatedSubject.asObservable();

  constructor(private http: HttpClient) {
    this.initializeAuth();
  }

  private initializeAuth(): void {
    const tokens = this.getStoredTokens();
    const user = this.getStoredUser();

    if (tokens && user && !this.isTokenExpired(tokens.accessToken)) {
      this.currentUserSubject.next(user);
      this.isAuthenticatedSubject.next(true);
    } else {
      this.clearAuth();
    }
  }

  /**
   * Login user with email and password
   */
  login(
    loginData: UserLogin
  ): Observable<ApiResponse<{ user: User; tokens: AuthTokens }>> {
    return this.http
      .post<ApiResponse<{ user: User; tokens: AuthTokens }>>(
        `${this.API_BASE}/login`,
        loginData
      )
      .pipe(
        tap((response) => {
          if (response.success && response.data) {
            this.setAuth(response.data.user, response.data.tokens);
          }
        })
      );
  }

  /**
   * Register new user
   */
  register(
    registrationData: UserRegistration
  ): Observable<ApiResponse<{ user: User; tokens: AuthTokens }>> {
    return this.http
      .post<ApiResponse<{ user: User; tokens: AuthTokens }>>(
        `${this.API_BASE}/register`,
        registrationData
      )
      .pipe(
        tap((response) => {
          if (response.success && response.data) {
            this.setAuth(response.data.user, response.data.tokens);
          }
        })
      );
  }

  /**
   * Request password reset
   */
  forgotPassword(resetData: PasswordReset): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(
      `${this.API_BASE}/forgot-password`,
      resetData
    );
  }

  /**
   * Reset password with token
   */
  resetPassword(
    token: string,
    newPassword: string
  ): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(
      `${this.API_BASE}/reset-password`,
      { token, newPassword }
    );
  }

  /**
   * Refresh access token
   */
  refreshToken(): Observable<ApiResponse<AuthTokens>> {
    const tokens = this.getStoredTokens();
    if (!tokens?.refreshToken) {
      throw new Error('No refresh token available');
    }

    return this.http
      .post<ApiResponse<AuthTokens>>(`${this.API_BASE}/refresh`, {
        refreshToken: tokens.refreshToken,
      })
      .pipe(
        tap((response) => {
          if (response.success && response.data) {
            const user = this.currentUserSubject.value;
            if (user) {
              this.setAuth(user, response.data);
            }
          }
        })
      );
  }

  /**
   * Logout user
   */
  logout(): Observable<ApiResponse<void>> {
    const tokens = this.getStoredTokens();

    // Clear local auth state immediately
    this.clearAuth();

    // Notify server (optional, can continue even if it fails)
    if (tokens?.accessToken) {
      return this.http.post<ApiResponse<void>>(
        `${this.API_BASE}/logout`,
        { refreshToken: tokens.refreshToken },
        { headers: this.getAuthHeaders() }
      );
    }

    // Return a mock observable if no token
    return new Observable((observer) => {
      observer.next({ success: true });
      observer.complete();
    });
  }

  /**
   * Get current user
   */
  getCurrentUser(): User | null {
    return this.currentUserSubject.value;
  }

  /**
   * Check if user is authenticated
   */
  isAuthenticated(): boolean {
    return this.isAuthenticatedSubject.value;
  }

  /**
   * Get stored authentication tokens
   */
  getStoredTokens(): AuthTokens | null {
    try {
      const tokens = localStorage.getItem(this.TOKEN_KEY);
      return tokens ? JSON.parse(tokens) : null;
    } catch {
      return null;
    }
  }

  /**
   * Get authorization headers
   */
  getAuthHeaders(): HttpHeaders {
    const tokens = this.getStoredTokens();
    if (tokens?.accessToken) {
      return new HttpHeaders({
        Authorization: `Bearer ${tokens.accessToken}`,
      });
    }
    return new HttpHeaders();
  }

  /**
   * Set authentication state
   */
  private setAuth(user: User, tokens: AuthTokens): void {
    localStorage.setItem(this.USER_KEY, JSON.stringify(user));
    localStorage.setItem(this.TOKEN_KEY, JSON.stringify(tokens));

    this.currentUserSubject.next(user);
    this.isAuthenticatedSubject.next(true);
  }

  /**
   * Clear authentication state
   */
  private clearAuth(): void {
    localStorage.removeItem(this.USER_KEY);
    localStorage.removeItem(this.TOKEN_KEY);

    this.currentUserSubject.next(null);
    this.isAuthenticatedSubject.next(false);
  }

  /**
   * Get stored user
   */
  private getStoredUser(): User | null {
    try {
      const user = localStorage.getItem(this.USER_KEY);
      return user ? JSON.parse(user) : null;
    } catch {
      return null;
    }
  }

  /**
   * Check if token is expired
   */
  private isTokenExpired(token: string): boolean {
    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      const exp = payload.exp * 1000; // Convert to milliseconds
      return Date.now() >= exp;
    } catch {
      return true;
    }
  }
}
