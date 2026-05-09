import { apiClient } from '@/api/client'
import type {
  AdminLoginRequest,
  AdminLoginResponse,
  AdminRegisterRequest,
  AdminRegisterResponse,
  AdminUser,
  CreateClientRequest,
  CreateClientResponse,
  OtpResendRequest,
  OtpResendResponse,
  OtpVerifyRequest,
  OtpVerifyResponse,
  SettingsPayload,
} from '@/api/models'

export const adminService = {
  // ── Auth ────────────────────────────────────────────────────────────────────

  login(payload: AdminLoginRequest): Promise<AdminLoginResponse> {
    return apiClient.post('/admin/login', payload)
  },

  register(payload: AdminRegisterRequest): Promise<AdminRegisterResponse> {
    return apiClient.post('/admin/register', payload)
  },

  verifyOtp(payload: OtpVerifyRequest): Promise<OtpVerifyResponse> {
    return apiClient.post('/admin/register/verify-otp', payload)
  },

  resendOtp(payload: OtpResendRequest): Promise<OtpResendResponse> {
    return apiClient.post('/admin/register/resend-otp', payload)
  },

  getMe(): Promise<AdminUser> {
    return apiClient.get('/admin/me')
  },

  logout(): Promise<void> {
    return apiClient.post('/admin/logout',{})
  },

  // ── Clients ─────────────────────────────────────────────────────────────────

  createClient(payload: CreateClientRequest): Promise<CreateClientResponse> {
    return apiClient.post('/admin/clients', payload)
  },

  // ── Settings ────────────────────────────────────────────────────────────────

  saveSettings(payload: SettingsPayload): Promise<void> {
    return apiClient.put('/admin/settings', payload)
  },
}

