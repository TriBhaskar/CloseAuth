// ── OAuth Models ───────────────────────────────────────────────────────────────

export interface OAuthTheme {
  clientName: string
  clientLogoUrl: string
  themeButton: string
  themeBackground: string
  themeText: string
}

export interface OAuthLoginRequest {
  usernameOrEmail: string
  password: string
  rememberMe: boolean
  client_id: string
}

export interface OAuthLoginResponse {
  redirect_url: string
}

export interface OAuthRegisterRequest {
  firstName: string
  lastName: string
  email: string
  username?: string
  password: string
  client_id: string
}

export interface OAuthRegisterResponse {
  message: string
}

export interface OAuthOtpVerifyRequest {
  email: string
  otp: string
  client_id: string
}

export interface OAuthOtpVerifyResponse {
  redirect_url?: string
}

export interface OAuthOtpResendRequest {
  email: string
  client_id: string
}

export interface ConsentRequest {
  action: 'approve' | 'deny'
  client_id: string
  state: string
  redirect_uri: string
  scopes?: string[]
}

export interface ConsentResponse {
  redirect_url: string
}

