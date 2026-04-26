import { apiClient } from '@/api/client'
import type {
  ConsentRequest,
  ConsentResponse,
  OAuthLoginRequest,
  OAuthLoginResponse,
  OAuthOtpResendRequest,
  OAuthOtpVerifyRequest,
  OAuthOtpVerifyResponse,
  OAuthRegisterRequest,
  OAuthRegisterResponse,
  OAuthTheme,
} from '@/api/models'

export const oauthService = {
  // ── Theme ───────────────────────────────────────────────────────────────────

  fetchTheme(clientId: string): Promise<OAuthTheme> {
    return apiClient.get(`/oauth/theme?client_id=${encodeURIComponent(clientId)}`)
  },

  // ── Login ───────────────────────────────────────────────────────────────────

  login(payload: OAuthLoginRequest): Promise<OAuthLoginResponse> {
    return apiClient.post('/oauth/login', payload)
  },

  // ── Register ────────────────────────────────────────────────────────────────

  register(payload: OAuthRegisterRequest): Promise<OAuthRegisterResponse> {
    return apiClient.post('/oauth/register', payload)
  },

  verifyOtp(payload: OAuthOtpVerifyRequest): Promise<OAuthOtpVerifyResponse> {
    return apiClient.post('/oauth/register/verify-otp', payload)
  },

  resendOtp(payload: OAuthOtpResendRequest): Promise<void> {
    return apiClient.post('/oauth/register/resend-otp', payload)
  },

  // ── Consent ─────────────────────────────────────────────────────────────────

  submitConsent(payload: ConsentRequest): Promise<ConsentResponse> {
    return apiClient.post('/oauth/consent', payload)
  },
}

