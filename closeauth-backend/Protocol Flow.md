Protocol Flow

     +--------+                               +---------------+
     |        |--(A)- Authorization Request ->|   Resource    |
     |        |                               |     Owner     |
     |        |<-(B)-- Authorization Grant ---|               |
     |        |                               +---------------+
     |        |
     |        |                               +---------------+
     |        |--(C)-- Authorization Grant -->| Authorization |
     | Client |                               |     Server    |
     |        |<-(D)----- Access Token -------|               |
     |        |                               +---------------+
     |        |
     |        |                               +---------------+
     |        |--(E)----- Access Token ------>|    Resource   |
     |        |                               |     Server    |
     |        |<-(F)--- Protected Resource ---|               |
     +--------+                               +---------------+

                     Figure 1: Abstract Protocol Flow

The abstract OAuth 2.0 flow illustrated in Figure 1 describes the
interaction between the four roles and includes the following steps:

(A)  The client requests authorization from the resource owner.  The
authorization request can be made directly to the resource owner
(as shown), or preferably indirectly via the authorization
server as an intermediary.

(B)  The client receives an authorization grant, which is a
credential representing the resource owner's authorization,
expressed using one of four grant types defined in this
specification or using an extension grant type.  The
authorization grant type depends on the method used by the
client to request authorization and the types supported by the
authorization server.

(C)  The client requests an access token by authenticating with the
authorization server and presenting the authorization grant.

(D)  The authorization server authenticates the client and validates
the authorization grant, and if valid, issues an access token.


Some of the important links

https://www.oauth.com/oauth2-servers/client-registration/registering-new-application/
https://github.com/rominalodolo/UdemyOAuth2.0?tab=readme-ov-file


# Client Registration Flow

There are two approaches to client registration in **CloseAuth**:

---

## 1. Dynamic Client Registration

- The client application sends a registration request to the **Client Registration Endpoint**.
- The request typically includes details such as:
    - Client name
    - Redirect URIs
    - Grant types
    - Other metadata
- The Authorization Server processes the request and responds with a **Client ID** and **Client Secret** (if applicable).

⚠️ **Note**:  
To register a client dynamically:
- The client must have an **access token** with the `client.create` scope.
- To obtain this token, you must use the **Client ID and Secret** of an **already registered client** that has the `client.create` scope.

### 🔄 Example Flow

#### Step 1: Get Access Token with `client.create` Scope

**Request:**
```http
POST http://localhost:9088/closeauth/oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
client_id=test1
client_secret=test1
redirect_url=http://127.0.0.1:8083/login/oauth2/code/public-client-react
scope=client.create
```

**Response:**
```json
{
  "access_token": "eyJraWQiOiJjNzkwMTBlYS01OWM5LTQwYzctOTdjYi1iNDU0MDM0YWI3YTIiLCJhbGciOiJSUzI1NiJ9...",
  "scope": "client.create",
  "token_type": "Bearer",
  "expires_in": 299
}
```

---

#### Step 2: Use Access Token to Register a New Client

**Request:**
```http
POST http://localhost:9088/closeauth/oauth2/register
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "client_name": "testnewww3",
  "grant_types": ["authorization_code", "refresh_token", "client_credentials"],
  "token_endpoint_auth_method": "client_secret_post",
  "scope": "openid email",
  "redirect_uris": ["https://oauthdebugger.com/debug"]
}
```

**Response:**
```json
{
  "grant_types": [
    "refresh_token",
    "client_credentials",
    "authorization_code"
  ],
  "scope": "openid client.create email",
  "registration_client_uri": "http://localhost:9088/connect/register?client_id=1PwNedXYr0QvdoVzSOdb-n_IA76aywD0uQ_XT7tU8rs",
  "client_id_issued_at": 1757253664,
  "client_secret": "WwuJZVIJF3zMzh75EwZURXmox5HzRUdZ2CWgT0ilf9TTatlgewppL-c-3XZhWRpm",
  "redirect_uris": [
    "https://oauthdebugger.com/debug"
  ],
  "client_name": "testnewww3",
  "client_id": "1PwNedXYr0QvdoVzSOdb-n_IA76aywD0uQ_XT7tU8rs",
  "token_endpoint_auth_method": "client_secret_post",
  "response_types": ["code"],
  "id_token_signed_response_alg": "RS256",
  "registration_access_token": "eyJraWQiOiJjNzkwMTBlYS01OWM5LTQwYzctOTdjYi1iNDU0MDM0YWI3YTIiLCJhbGciOiJSUzI1NiJ9...",
  "client_secret_expires_at": 0
}
```

---

## 2. Manual Client Registration

- The client developer registers the application **manually** with the Authorization Server.
- This process may involve:
    - Filling out a form via the server’s **Admin UI**, or
    - Contacting the **server administrator**.
- After registration, the Authorization Server provides the **Client ID** and **Client Secret**.

⚠️ **Note**:  
This API is only accessible to **super-admin users**.

### 🔄 Example Flow

**Request:**
```http
POST http://localhost:9088/closeauth/admin/clients/create
Content-Type: application/json

{
  "clientId": "test1",
  "clientSecret": "test1",
  "authenticationMethods": ["client_secret_basic", "client_secret_post"],
  "authorizationGrantTypes": ["authorization_code", "refresh_token", "client_credentials"],
  "redirectUris": ["https://oauthdebugger.com/debug"],
  "scopes": ["openid", "profile", "client.create", "client.read"],
  "requireProofKey": "true"
}
```
