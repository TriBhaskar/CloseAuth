import type { OAuthScopeInfo } from '@/api/models'

// TODO(api): replace mock with oauthService.getScopeMetadata()
export const oauthScopesMock: OAuthScopeInfo[] = [
  { key: 'openid',         label: 'Sign you in',        description: 'Verify your identity',          iconName: 'KeyRound'  },
  { key: 'profile',        label: 'View your profile',  description: 'Access your name and picture',  iconName: 'User'      },
  { key: 'email',          label: 'Access your email',  description: 'Know your email address',       iconName: 'Mail'      },
  { key: 'offline_access', label: 'Stay signed in',     description: "Access when you're not active", iconName: 'RefreshCw' },
  { key: 'read:users',     label: 'Read users',         description: 'List and view user data',       iconName: 'Eye'       },
  { key: 'write:users',    label: 'Manage users',       description: 'Create and modify users',       iconName: 'PenLine'   },
]
