import { apiClient } from '@/api/client'
import type {
  ConsentDataResponse,
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

  fetchConsentData(params: {client_id?: string; scope?: string; state?:string}): Promise<ConsentDataResponse> {
    const query = new URLSearchParams()
    if(params.client_id) query.set('client_id', params.client_id)
    if(params.state) query.set('state', params.state)
    if(params.scope) query.set('scope', params.scope)
    return apiClient.get(`/oauth/consent-data?'${query.toString()}`)
  },
  submitConsent(payload: ConsentRequest): Promise<ConsentResponse> {
    return apiClient.post('/oauth/consent', payload)
  },
}

