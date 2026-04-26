import type { User, UsersData } from '@/api/models'

const users: User[] = [
  {
    id: '1',
    firstName: 'Alice',
    lastName: 'Smith',
    email: 'alice@example.com',
    username: 'alice',
    role: 'Admin',
    status: 'Active',
    lastLogin: '2 min ago',
    createdAt: '2023-11-01',
  },
  {
    id: '2',
    firstName: 'Bob',
    lastName: 'Jones',
    email: 'bob@example.com',
    username: 'bjones',
    role: 'Moderator',
    status: 'Active',
    lastLogin: '1h ago',
    createdAt: '2024-01-10',
  },
  {
    id: '3',
    firstName: 'Carol',
    lastName: 'Williams',
    email: 'carol@example.com',
    username: 'cwilliams',
    role: 'User',
    status: 'Active',
    lastLogin: '3h ago',
    createdAt: '2024-02-14',
  },
  {
    id: '4',
    firstName: 'Dave',
    lastName: 'Brown',
    email: 'dave@example.com',
    username: 'dbrown',
    role: 'User',
    status: 'Inactive',
    lastLogin: '30 days ago',
    createdAt: '2023-08-22',
  },
]

// TODO(api): replace mock with adminService.getUsers()
export const usersMock: UsersData = {
  stats: [
    { label: 'Total Users',     value: '12,847' },
    { label: 'Active Users',    value: '11,203' },
    { label: 'Administrators',  value: '8'      },
    { label: 'New This Week',   value: '124'    },
  ],
  users,
}

