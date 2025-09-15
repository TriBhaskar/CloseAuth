import { Injectable } from '@angular/core';
import { CanActivate, Router, UrlTree } from '@angular/router';
import { Observable } from 'rxjs';
import { AuthService } from '../services/auth.service';

@Injectable({
  providedIn: 'root',
})
export class AuthGuard implements CanActivate {
  constructor(private authService: AuthService, private router: Router) {}

  canActivate():
    | Observable<boolean | UrlTree>
    | Promise<boolean | UrlTree>
    | boolean
    | UrlTree {
    // For demo purposes, check localStorage directly
    const storedUser = localStorage.getItem('closeauth_user');
    const storedTokens = localStorage.getItem('closeauth_tokens');

    if (storedUser && storedTokens) {
      return true;
    }

    // Redirect to login page
    return this.router.createUrlTree(['/auth/login']);
  }
}
