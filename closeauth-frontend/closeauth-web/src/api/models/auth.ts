// ── Admin Auth Models ──────────────────────────────────────────────────────────

export interface AdminLoginRequest {
  username: string
  password: string
}

export interface AdminLoginResponse {
  token?: string
  redirect_url?: string
}

export interface AdminRegisterRequest {
  firstName: string
  lastName: string
  email: string
  username?: string
  password: string
}

export interface AdminRegisterResponse {
  message: string
}

export interface OtpVerifyRequest {
  email: string
  otp: string
}

export interface OtpVerifyResponse {
  message: string
  redirect_url?: string
}

export interface OtpResendRequest {
  email: string
}

export interface OtpResendResponse {
  message: string
}

export interface AdminUser {
  id: string
  email: string
  username: string
  firstName: string
  lastName: string
  role: string
  createdAt: string
}

// ── Forgot / Reset Password ─────────────────────────────────────────────────

export interface ForgotPasswordRequest {
  email: string
  forgotPasswordLink: string
}

export interface ForgotPasswordResponse {
  message: string
  status: string
}

export interface ResetPasswordRequest {
  token: string
  newPassword: string
  confirmPassword: string
}

export interface ResetPasswordResponse {
  message: string
  status: string
}

export interface ValidateTokenResponse {
  valid: boolean
  message: string
}

