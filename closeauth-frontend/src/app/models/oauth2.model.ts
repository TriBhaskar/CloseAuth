export interface OAuth2Client {
  id: string;
  clientId: string;
  clientSecret?: string;
  name: string;
  description?: string;
  redirectUris: string[];
  allowedGrantTypes: GrantType[];
  allowedScopes: string[];
  accessTokenTtl: number;
  refreshTokenTtl: number;
  isActive: boolean;
  branding?: ClientBranding;
  createdAt: Date;
  updatedAt: Date;
}

export interface ClientBranding {
  logoUrl?: string;
  primaryColor?: string;
  secondaryColor?: string;
  backgroundColor?: string;
  textColor?: string;
  companyName?: string;
  privacyPolicyUrl?: string;
  termsOfServiceUrl?: string;
  customCss?: string;
}

export enum GrantType {
  AUTHORIZATION_CODE = 'authorization_code',
  CLIENT_CREDENTIALS = 'client_credentials',
  REFRESH_TOKEN = 'refresh_token',
  PASSWORD = 'password',
}

export interface AuthorizationRequest {
  clientId: string;
  redirectUri: string;
  responseType: string;
  scope: string;
  state?: string;
  codeChallenge?: string;
  codeChallengeMethod?: string;
}
