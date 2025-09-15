import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiResponse, PaginatedResponse } from '../models/common.model';
import { OAuth2Client, AuthorizationRequest } from '../models/oauth2.model';

@Injectable({
  providedIn: 'root',
})
export class ClientService {
  private readonly API_BASE = '/api/clients';

  constructor(private http: HttpClient) {}

  /**
   * Get all OAuth2 clients with pagination
   */
  getClients(
    page = 1,
    limit = 10,
    search?: string
  ): Observable<ApiResponse<PaginatedResponse<OAuth2Client>>> {
    const params: any = { page, limit };
    if (search) {
      params.search = search;
    }

    return this.http.get<ApiResponse<PaginatedResponse<OAuth2Client>>>(
      this.API_BASE,
      { params }
    );
  }

  /**
   * Get client by ID
   */
  getClient(id: string): Observable<ApiResponse<OAuth2Client>> {
    return this.http.get<ApiResponse<OAuth2Client>>(`${this.API_BASE}/${id}`);
  }

  /**
   * Create new OAuth2 client
   */
  createClient(
    clientData: Partial<OAuth2Client>
  ): Observable<ApiResponse<OAuth2Client>> {
    return this.http.post<ApiResponse<OAuth2Client>>(this.API_BASE, clientData);
  }

  /**
   * Update OAuth2 client
   */
  updateClient(
    id: string,
    clientData: Partial<OAuth2Client>
  ): Observable<ApiResponse<OAuth2Client>> {
    return this.http.put<ApiResponse<OAuth2Client>>(
      `${this.API_BASE}/${id}`,
      clientData
    );
  }

  /**
   * Delete OAuth2 client
   */
  deleteClient(id: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`${this.API_BASE}/${id}`);
  }

  /**
   * Generate new client secret
   */
  generateClientSecret(
    id: string
  ): Observable<ApiResponse<{ clientSecret: string }>> {
    return this.http.post<ApiResponse<{ clientSecret: string }>>(
      `${this.API_BASE}/${id}/secret`,
      {}
    );
  }

  /**
   * Get client statistics
   */
  getClientStats(id: string): Observable<ApiResponse<any>> {
    return this.http.get<ApiResponse<any>>(`${this.API_BASE}/${id}/stats`);
  }

  /**
   * Validate authorization request
   */
  validateAuthorizationRequest(
    request: AuthorizationRequest
  ): Observable<ApiResponse<any>> {
    return this.http.post<ApiResponse<any>>(
      '/api/oauth2/authorize/validate',
      request
    );
  }

  /**
   * Process authorization consent
   */
  processConsent(
    authorizationCode: string,
    approved: boolean,
    scopes?: string[]
  ): Observable<ApiResponse<any>> {
    return this.http.post<ApiResponse<any>>('/api/oauth2/consent', {
      authorizationCode,
      approved,
      scopes,
    });
  }
}
