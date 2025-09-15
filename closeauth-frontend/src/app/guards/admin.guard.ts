import { Injectable } from '@angular/core';
import { CanActivate, Router, UrlTree } from '@angular/router';
import { Observable } from 'rxjs';
import { AuthService } from '../services/auth.service';

@Injectable({
  providedIn: 'root',
})
export class AdminGuard implements CanActivate {
  constructor(private authService: AuthService, private router: Router) {}

  canActivate():
    | Observable<boolean | UrlTree>
    | Promise<boolean | UrlTree>
    | boolean
    | UrlTree {
    // For demo purposes, check localStorage directly
    const storedUser = localStorage.getItem('closeauth_user');

    if (storedUser) {
      const user = JSON.parse(storedUser);
      if (this.hasAdminRole(user.roles)) {
        return true;
      }
    }

    // Redirect to unauthorized page or login
    return this.router.createUrlTree(['/auth/login']);
  }

  private hasAdminRole(roles: string[]): boolean {
    const adminRoles = ['admin', 'super_admin', 'system_admin'];
    return roles.some((role) => adminRoles.includes(role.toLowerCase()));
  }
}
