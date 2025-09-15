import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  ApiResponse,
  PaginatedResponse,
  Role,
  Permission,
} from '../models/common.model';
import { User } from '../models/user.model';

@Injectable({
  providedIn: 'root',
})
export class UserService {
  private readonly API_BASE = '/api/users';

  constructor(private http: HttpClient) {}

  /**
   * Get all users with pagination and filtering
   */
  getUsers(
    page = 1,
    limit = 10,
    search?: string,
    role?: string
  ): Observable<ApiResponse<PaginatedResponse<User>>> {
    const params: any = { page, limit };
    if (search) {
      params.search = search;
    }
    if (role) {
      params.role = role;
    }

    return this.http.get<ApiResponse<PaginatedResponse<User>>>(this.API_BASE, {
      params,
    });
  }

  /**
   * Get user by ID
   */
  getUser(id: string): Observable<ApiResponse<User>> {
    return this.http.get<ApiResponse<User>>(`${this.API_BASE}/${id}`);
  }

  /**
   * Create new user
   */
  createUser(userData: Partial<User>): Observable<ApiResponse<User>> {
    return this.http.post<ApiResponse<User>>(this.API_BASE, userData);
  }

  /**
   * Update user
   */
  updateUser(
    id: string,
    userData: Partial<User>
  ): Observable<ApiResponse<User>> {
    return this.http.put<ApiResponse<User>>(`${this.API_BASE}/${id}`, userData);
  }

  /**
   * Delete user
   */
  deleteUser(id: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`${this.API_BASE}/${id}`);
  }

  /**
   * Update user roles
   */
  updateUserRoles(
    id: string,
    roleIds: string[]
  ): Observable<ApiResponse<User>> {
    return this.http.put<ApiResponse<User>>(`${this.API_BASE}/${id}/roles`, {
      roleIds,
    });
  }

  /**
   * Enable/disable user account
   */
  toggleUserStatus(
    id: string,
    isActive: boolean
  ): Observable<ApiResponse<User>> {
    return this.http.patch<ApiResponse<User>>(`${this.API_BASE}/${id}/status`, {
      isActive,
    });
  }

  /**
   * Reset user password (admin action)
   */
  resetUserPassword(
    id: string
  ): Observable<ApiResponse<{ temporaryPassword: string }>> {
    return this.http.post<ApiResponse<{ temporaryPassword: string }>>(
      `${this.API_BASE}/${id}/reset-password`,
      {}
    );
  }

  /**
   * Get user activity/audit log
   */
  getUserActivity(
    id: string,
    page = 1,
    limit = 10
  ): Observable<ApiResponse<PaginatedResponse<any>>> {
    return this.http.get<ApiResponse<PaginatedResponse<any>>>(
      `${this.API_BASE}/${id}/activity`,
      {
        params: { page, limit },
      }
    );
  }

  /**
   * Get all available roles
   */
  getRoles(): Observable<ApiResponse<Role[]>> {
    return this.http.get<ApiResponse<Role[]>>('/api/roles');
  }

  /**
   * Get role by ID
   */
  getRole(id: string): Observable<ApiResponse<Role>> {
    return this.http.get<ApiResponse<Role>>(`/api/roles/${id}`);
  }

  /**
   * Create new role
   */
  createRole(roleData: Partial<Role>): Observable<ApiResponse<Role>> {
    return this.http.post<ApiResponse<Role>>('/api/roles', roleData);
  }

  /**
   * Update role
   */
  updateRole(
    id: string,
    roleData: Partial<Role>
  ): Observable<ApiResponse<Role>> {
    return this.http.put<ApiResponse<Role>>(`/api/roles/${id}`, roleData);
  }

  /**
   * Delete role
   */
  deleteRole(id: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`/api/roles/${id}`);
  }

  /**
   * Get all available permissions
   */
  getPermissions(): Observable<ApiResponse<Permission[]>> {
    return this.http.get<ApiResponse<Permission[]>>('/api/permissions');
  }

  /**
   * Update role permissions
   */
  updateRolePermissions(
    roleId: string,
    permissionIds: string[]
  ): Observable<ApiResponse<Role>> {
    return this.http.put<ApiResponse<Role>>(
      `/api/roles/${roleId}/permissions`,
      { permissionIds }
    );
  }
}
