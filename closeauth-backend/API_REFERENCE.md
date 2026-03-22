# Client Configuration API Reference

## Authentication
All endpoints require:
- **OAuth2 Bearer Token** with `client.create` scope
- **X-User-Token** header containing user JWT

```
Authorization: Bearer {oauth2_access_token}
X-User-Token: {user_jwt_token}
```

## Base URL
```
/api/v1/clients
```

---

## Application Roles

### Create Role
```http
POST /api/v1/clients/{clientId}/roles
```

**Request Body:**
```json
{
  "roleName": "ADMIN",
  "description": "Administrator with full access",
  "permissions": "{\"read\":[\"users\",\"reports\"],\"write\":[\"settings\"],\"delete\":[\"users\"]}",
  "isDefault": false
}
```

**Response:**
```json
{
  "timestamp": "2025-12-21T10:30:00",
  "status": "SUCCESS",
  "message": "Role created successfully",
  "data": {
    "id": 1,
    "clientId": "my-client",
    "roleName": "ADMIN",
    "description": "Administrator with full access",
    "permissions": "{\"read\":[\"users\",\"reports\"],\"write\":[\"settings\"],\"delete\":[\"users\"]}",
    "isDefault": false,
    "createdAt": "2025-12-21T10:30:00",
    "updatedAt": "2025-12-21T10:30:00"
  }
}
```

### List All Roles
```http
GET /api/v1/clients/{clientId}/roles
```

### Get Specific Role
```http
GET /api/v1/clients/{clientId}/roles/{roleId}
```

### Update Role
```http
PUT /api/v1/clients/{clientId}/roles/{roleId}
```

**Request Body:**
```json
{
  "description": "Updated description",
  "permissions": "{\"read\":[\"users\"],\"write\":[\"users\"]}",
  "isDefault": true
}
```

### Delete Role
```http
DELETE /api/v1/clients/{clientId}/roles/{roleId}
```

---

## Registration Configuration

### Get Registration Config
```http
GET /api/v1/clients/{clientId}/registration-config
```

**Response:**
```json
{
  "timestamp": "2025-12-21T10:30:00",
  "status": "SUCCESS",
  "message": "Registration config retrieved successfully",
  "data": {
    "id": 1,
    "clientId": "my-client",
    "verificationMethod": "EMAIL",
    "requireEmailVerification": true,
    "requirePhoneVerification": false,
    "requireAdminApproval": false,
    "allowSelfRegistration": true,
    "registrationEnabled": true,
    "requirePhone": false,
    "requireFirstName": true,
    "requireLastName": true,
    "verificationTokenExpiry": 24,
    "phoneVerificationMethod": "SMS",
    "phoneVerificationTokenExpiry": 10,
    "welcomeEmailEnabled": true,
    "createdAt": "2025-12-21T10:00:00",
    "updatedAt": "2025-12-21T10:00:00"
  }
}
```

### Update Registration Config
```http
PUT /api/v1/clients/{clientId}/registration-config
```

**Request Body:**
```json
{
  "verificationMethod": "EMAIL_AND_PHONE",
  "requireEmailVerification": true,
  "requirePhoneVerification": true,
  "requireAdminApproval": false,
  "autoApproveDomains": "[\"trusted-domain.com\"]",
  "allowSelfRegistration": true,
  "registrationEnabled": true,
  "requirePhone": true,
  "requireFirstName": true,
  "requireLastName": true,
  "customFields": "[{\"name\":\"department\",\"type\":\"text\",\"required\":true}]",
  "verificationTokenExpiry": 48,
  "phoneVerificationMethod": "SMS",
  "phoneVerificationTokenExpiry": 15,
  "approvalNotificationEmail": "admin@example.com",
  "welcomeEmailEnabled": true,
  "redirectAfterRegistration": "https://myapp.com/welcome"
}
```

**Validation Rules:**
- `verificationMethod`: Must be `EMAIL`, `PHONE`, `ADMIN_APPROVAL`, `EMAIL_AND_PHONE`, or `AUTO_APPROVE`
- `verificationTokenExpiry`: 1-168 hours
- `phoneVerificationTokenExpiry`: 1-60 minutes
- `phoneVerificationMethod`: Must be `SMS`, `CALL`, or `WHATSAPP`

---

## Themes

### Create Theme
```http
POST /api/v1/clients/{clientId}/themes
```

**Request Body:**
```json
{
  "themeName": "corporate",
  "logoUrl": "https://cdn.example.com/logo.png",
  "lightPrimaryColor": "#0066CC",
  "lightBackgroundColor": "#FFFFFF",
  "lightButtonColor": "#0066CC",
  "lightTextColor": "#333333",
  "darkPrimaryColor": "#1A8FFF",
  "darkBackgroundColor": "#1E1E1E",
  "darkButtonColor": "#1A8FFF",
  "darkTextColor": "#FFFFFF",
  "defaultMode": "light",
  "allowModeToggle": true,
  "isDefault": false
}
```

**Validation:**
- Colors must match pattern: `^#[0-9A-Fa-f]{6}$`
- `defaultMode` must be: `light`, `dark`, or `system`

### List All Themes
```http
GET /api/v1/clients/{clientId}/themes
```

### Get Specific Theme
```http
GET /api/v1/clients/{clientId}/themes/{themeId}
```

### Get Active Theme
```http
GET /api/v1/clients/{clientId}/themes/active
```

### Update Theme
```http
PUT /api/v1/clients/{clientId}/themes/{themeId}
```

**Request Body** (all fields optional):
```json
{
  "logoUrl": "https://cdn.example.com/new-logo.png",
  "lightPrimaryColor": "#FF6600",
  "darkPrimaryColor": "#FF8800"
}
```

### Activate Theme
```http
PATCH /api/v1/clients/{clientId}/themes/{themeId}/activate
```

**Note:** This will automatically deactivate all other themes for the client.

### Delete Theme
```http
DELETE /api/v1/clients/{clientId}/themes/{themeId}
```

**Note:** Cannot delete the active theme. Activate another theme first.

---

## Theme Configurations

### Create Configuration
```http
POST /api/v1/clients/{clientId}/themes/{themeId}/configurations
```

**Request Body:**
```json
{
  "configKey": "border-radius",
  "configValue": "8px",
  "configType": "string"
}
```

**Config Types:**
- `string`
- `number`
- `boolean`
- `json`
- `color`

### List All Configurations
```http
GET /api/v1/clients/{clientId}/themes/{themeId}/configurations
```

### Get Specific Configuration
```http
GET /api/v1/clients/{clientId}/themes/{themeId}/configurations/{configId}
```

### Update Configuration
```http
PUT /api/v1/clients/{clientId}/themes/{themeId}/configurations/{configId}
```

**Request Body:**
```json
{
  "configValue": "12px",
  "configType": "string"
}
```

### Delete Configuration
```http
DELETE /api/v1/clients/{clientId}/themes/{themeId}/configurations/{configId}
```

---

## Error Responses

### 400 Bad Request
```json
{
  "message": "Invalid hex color format for light primary color",
  "status": "BAD_REQUEST",
  "timestamp": "2025-12-21T10:30:00"
}
```

### 403 Forbidden (Ownership)
```json
{
  "message": "You do not have permission to modify this client",
  "status": "FORBIDDEN",
  "timestamp": "2025-12-21T10:30:00"
}
```

### 404 Not Found
```json
{
  "message": "Theme not found",
  "status": "NOT_FOUND",
  "timestamp": "2025-12-21T10:30:00"
}
```

### 409 Conflict
```json
{
  "message": "Role 'ADMIN' already exists for this client",
  "status": "CONFLICT",
  "timestamp": "2025-12-21T10:30:00"
}
```

---

## Permissions JSON Schema

Recommended schema for the `permissions` field in roles:

```json
{
  "read": ["users", "reports", "settings"],
  "write": ["users", "settings"],
  "delete": ["users"],
  "custom": {
    "canApproveUsers": true,
    "maxFileUploadSize": 10485760
  }
}
```

**Common Permission Arrays:**
- `read`: Resources the role can view
- `write`: Resources the role can create/update
- `delete`: Resources the role can delete
- `custom`: Custom permissions specific to your application

**Example Roles:**

**Admin:**
```json
{
  "read": ["*"],
  "write": ["*"],
  "delete": ["*"]
}
```

**Manager:**
```json
{
  "read": ["users", "reports", "analytics"],
  "write": ["users", "reports"],
  "delete": ["reports"]
}
```

**Viewer:**
```json
{
  "read": ["reports", "analytics"],
  "write": [],
  "delete": []
}
```

**Default User:**
```json
{
  "read": ["profile"],
  "write": ["own_profile"]
}
```

---

## Audit Logging

All operations are automatically logged to the `audit_logs` table with:

- **client_id**: The client being modified
- **user_id**: The user making the change
- **action**: Type of action (e.g., `ROLE_CREATED`, `THEME_UPDATED`)
- **ip_address**: Request IP address
- **user_agent**: Request user agent
- **metadata**: JSON with before/after states and change details
- **success**: Whether the operation succeeded
- **error_message**: Error message if operation failed
- **created_at**: Timestamp

**Example Audit Log Entry:**
```json
{
  "id": 123,
  "clientId": "my-client",
  "userId": 456,
  "action": "ROLE_CREATED",
  "ipAddress": "192.168.1.1",
  "userAgent": "Mozilla/5.0...",
  "metadata": "{\"roleName\":\"ADMIN\",\"isDefault\":false}",
  "success": true,
  "errorMessage": null,
  "createdAt": "2025-12-21T10:30:00"
}
```

---

## Client Registration Flow

When a client is registered via `/connect/register`, the following happens automatically:

1. **RegisteredClient** created in OAuth2 tables
2. **ClientOwnership** record created linking user to client
3. **Default Registration Config** created:
   - EMAIL verification
   - 24-hour token expiry
   - Self-registration enabled
4. **Default Theme** created:
   - Named "default"
   - Active and set as default
   - Light/dark mode colors
5. **Default USER Role** created:
   - Basic read/write permissions
   - Auto-assigned to client owner
6. **UserClientMap** created with APPROVED status
7. **UserApplicationRole** assignment created
8. **Audit log** entry: `CLIENT_INITIALIZED`

All these can be modified through the configuration endpoints afterward.

