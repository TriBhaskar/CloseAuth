export interface User {
  id: string;
  email: string;
  username?: string;
  firstName?: string;
  lastName?: string;
  roles: string[];
  permissions: string[];
  isActive: boolean;
  isEmailVerified: boolean;
  lastLoginAt?: Date;
  createdAt: Date;
  updatedAt: Date;
}

export interface UserRegistration {
  email: string;
  username?: string;
  firstName?: string;
  lastName?: string;
  password: string;
  confirmPassword: string;
  acceptTerms: boolean;
}

export interface UserLogin {
  email: string;
  password: string;
  rememberMe?: boolean;
}

export interface PasswordReset {
  email: string;
}

export interface PasswordResetConfirm {
  token: string;
  newPassword: string;
  confirmPassword: string;
}
