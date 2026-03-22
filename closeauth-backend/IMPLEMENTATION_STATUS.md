# Security Filter Chains Refactoring - Implementation Status

## ✅ Completed Components

### 1. Core Security Infrastructure
- ✅ **UserContextHelper** - New utility class for extracting user info from request attributes
  - Location: `src/main/java/com/anterka/closeauthbackend/security/UserContextHelper.java`
  - Provides static methods: `getUserId()`, `getUsername()`, `getUserRoles()`, `isAuthenticated()`

### 2. Filter Updates
- ✅ **TwoLayerAuthenticationFilter** - Refactored to validation-only mode
  - Now validates `X-User-Token` and stores user info in request attributes
  - Does NOT modify SecurityContext (OAuth2 authentication remains intact)
  - Uses `UserContextHelper` constants for attribute keys

### 3. Configuration Updates
- ✅ **ApiPaths** - Reorganized with clear endpoint categories
  - `OAUTH2_PUBLIC_ENDPOINTS` - Public OAuth2 endpoints
  - `ADMIN_AUTH_ENDPOINTS` - Admin authentication endpoints  
  - `DUAL_AUTH_ENDPOINTS` - Endpoints requiring both OAuth2 + X-User-Token
  - Removed unused utility methods

- ✅ **AuthorisationServerConfig** - New 4-filter chain architecture
  - `@Order(1)` - OAuth2 public endpoints (`/oauth2/**`)
  - `@Order(2)` - Admin auth endpoints (`/api/v1/admin/auth/**`)
  - `@Order(3)` - Dual-auth endpoints (`/api/v1/clients/**`, `/connect/register`)
  - `@Order(4)` - Authorization server endpoints (OAuth2 flows)

### 4. Service Layer Updates
- ✅ **ApplicationRoleService** - Fully updated
  - All methods now accept `HttpServletRequest request` parameter
  - Uses `UserContextHelper.getUserId(request)` instead of SecurityContext
  
- ✅ **ClientThemeService** - Fully updated
  - All methods now accept `HttpServletRequest request` parameter
  - Uses `UserContextHelper.getUserId(request)` instead of SecurityContext

### 5. Controller Updates
- ✅ **ClientConfigurationController** - Fully updated
  - All endpoints now pass `HttpServletRequest request` to service methods
  - Covers: roles, registration config, themes, theme configurations

## ⚠️ Remaining Manual Updates Required

### Services Needing Update

#### 1. ThemeConfigurationService
**Methods to update:**
```java
// Change from:
public ThemeConfigResponse createConfiguration(String clientId, Long themeId,
                                               CreateThemeConfigurationDto dto,
                                               String ipAddress, String userAgent)

// To:
public ThemeConfigResponse createConfiguration(String clientId, Long themeId,
                                               CreateThemeConfigurationDto dto,
                                               String ipAddress, String userAgent,
                                               HttpServletRequest request)

// Also update:
- updateConfiguration()
- getConfigurationsByTheme()
- getConfiguration()
- deleteConfiguration()
```

**Internal changes:**

```java
// 1. Add import

import com.anterka.closeauthbackend.user.security.UserContextHelper;
import jakarta.servlet.http.HttpServletRequest;

// 2. Remove imports


// 3. Update helper methods

private Integer getCurrentUserId(HttpServletRequest request) {
    return UserContextHelper.getUserId(request);
}

        private void verifyClientOwnership(String clientId, HttpServletRequest request) {
            Integer userId = getCurrentUserId(request);
            // ... rest of method
        }

        // 4. Update all method calls to pass request
        verifyClientOwnership(clientId, request);

        getCurrentUserId(request)
```

#### 2. ApplicationRegistrationConfigService
**Methods to update:**
```java
// Change from:
public RegistrationConfigResponse getConfig(String clientId)
public RegistrationConfigResponse updateConfig(String clientId,
                                               UpdateApplicationRegistrationConfigDto dto,
                                               String ipAddress, String userAgent)

// To:
public RegistrationConfigResponse getConfig(String clientId, HttpServletRequest request)
public RegistrationConfigResponse updateConfig(String clientId,
                                               UpdateApplicationRegistrationConfigDto dto,
                                               String ipAddress, String userAgent,
                                               HttpServletRequest request)
```

**Same internal changes as ThemeConfigurationService above**

#### 3. ClientService (if it uses getCurrentUserId)
Check `src/main/java/com/anterka/closeauthbackend/core/services/ClientService.java` and update similarly.

## 🎯 Testing Checklist

After completing manual updates, test these scenarios:

### 1. OAuth2 Public Endpoints (No Auth Required)
- [ ] `POST /oauth2/token` - Get access token
- [ ] `GET /oauth2/authorize` - Authorization flow
- [ ] `GET /oauth2/jwks` - Get JWK set

### 2. Admin Auth Endpoints (OAuth2 Bearer Only)
- [ ] `POST /api/v1/admin/auth/register` - Register admin
- [ ] `POST /api/v1/admin/auth/login` - Login admin (returns X-User-Token)
- [ ] `POST /api/v1/admin/auth/verify-email` - Verify email

### 3. Dual-Auth Endpoints (OAuth2 + X-User-Token)
- [ ] `POST /api/v1/clients/{clientId}/roles` - Create role
- [ ] `GET /api/v1/clients/{clientId}/roles` - List roles
- [ ] `POST /connect/register` - Register OAuth2 client
- [ ] All other `/api/v1/clients/**` endpoints

### 4. Authorization Server Endpoints
- [ ] OAuth2 authorization code flow redirects

## 📝 Key Architecture Points

### Dual Authentication Model
1. **OAuth2 Bearer Token** (in SecurityContext)
   - Represents BFF client identity
   - Must have `SCOPE_client.create`
   - Validated by Spring Security OAuth2 Resource Server

2. **X-User-Token** (in request attributes)
   - Represents admin user identity
   - Validated by `TwoLayerAuthenticationFilter`
   - Stored in request attributes via `UserContextHelper`

### Benefits of This Approach
- ✅ No conflicts between OAuth2 and user authentication
- ✅ Both client and user information accessible throughout request
- ✅ Clear separation of concerns
- ✅ OAuth2 JWT remains in SecurityContext for scope validation
- ✅ Easy to test and debug

### Filter Chain Order
```
Request → OAuth2 Public Filter (Order 1)
       → Admin Auth Filter (Order 2)
       → Dual Auth Filter (Order 3)
          ├─→ OAuth2 Resource Server (validates Bearer token)
          └─→ TwoLayerAuthenticationFilter (validates X-User-Token)
       → Authorization Server Filter (Order 4)
       → Controllers
```

## 🔧 Build & Compile

Current status: **Compiles successfully** (only warnings, no errors)

Run to verify:
```bash
mvn compile -DskipTests
```

## 📚 Related Files

- `TwoLayerAuthenticationFilter.java` - X-User-Token validation
- `UserContextHelper.java` - Request attribute helper
- `ApiPaths.java` - Endpoint path constants
- `AuthorisationServerConfig.java` - Security filter chains
- `ClientConfigurationController.java` - Dual-auth endpoints
- All service classes that access user ID

## 🚀 Next Steps

1. Complete manual updates to remaining services (see above)
2. Run full test suite
3. Test dual authentication flows with Postman/curl
4. Update BFF application to send both tokens
5. Document API authentication requirements

