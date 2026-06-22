// ── OAuth Models ───────────────────────────────────────────────────────────────

export interface OAuthTheme {
  clientName: string
  clientLogoUrl: string
  themeButton: string
  themeBackground: string
  themeText: string
}

export interface OAuthLoginRequest {
  username: string
  password: string
  rememberMe: boolean
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

export interface ConsentDataResponse {
  client_id: string
  client_name: string
  logo_url?: string
  username: string
  scopes: string[]
  state: string
  csrf_token: string
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

// ── OAuth Scope Metadata ───────────────────────────────────────────────────────

export interface OAuthScopeInfo {
  key: string
  label: string
  description: string
  iconName: string
}


