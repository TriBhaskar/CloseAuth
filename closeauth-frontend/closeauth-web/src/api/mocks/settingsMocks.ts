import type { ClientCreateConfig, SettingsData } from '@/api/models'

// TODO(api): replace mock with adminService.getSettings()
export const settingsMock: SettingsData = {
  issuerUrl: 'https://auth.company.com',
  defaultAudience: 'https://api.company.com',
  timezone: 'UTC',
  language: 'English',
}

// TODO(api): replace mock with adminService.getClientCreateConfig()
export const clientCreateConfigMock: ClientCreateConfig = {
  availableScopes: [
    { key: 'openid',         label: 'OpenID Connect'      },
    { key: 'email',          label: 'Email Address'       },
    { key: 'profile',        label: 'Profile Information' },
    { key: 'offline_access', label: 'Offline Access'      },
    { key: 'read:users',     label: 'Read Users'          },
    { key: 'write:users',    label: 'Write Users'         },
  ],
}
