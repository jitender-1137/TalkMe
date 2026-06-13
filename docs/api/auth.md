# Auth APIs (AuthController)

**Base Path:** `/api/v1/auth`

---

## 🔐 Token Security Model

| Token | Transport | Readable by JS? | Notes |
|---|---|---|---|
| `accessToken` | Response body | ✅ Yes | Short-lived JWT (15 min). Used as `Authorization: Bearer <token>` header. |
| `refreshToken` | `HttpOnly` cookie | ❌ No | Long-lived opaque token. Set by server. Never exposed in response body. |
| `csrf_token` | `HttpOnly` cookie | ❌ No | CSRF protection token. Set by server on login/refresh. Client must mirror it in `X-CSRF-Token` header on mutating requests. |

> **How CSRF works**: On login/refresh the server sets a `csrf_token` HttpOnly cookie. The client reads this cookie value (via a separate non-HttpOnly mirror cookie or a prior API response) and echoes it in the `X-CSRF-Token` request header on all `POST`, `PUT`, `PATCH`, `DELETE` calls. The server compares the header value against the cookie value to validate origin.

---

## 1. Sign Up

Registers a new user account.

*   **URL:** `POST /api/v1/auth/signup`
*   **Authentication Required:** No
*   **Headers:**
    *   `Content-Type: application/json`

### Request Body
```json
{
  "username": "johndoe",
  "email": "johndoe@example.com",
  "password": "Password123!",
  "name": "John Doe",
  "age": 28,
  "gender": "MALE"
}
```

### Response Cookies Set by Server
| Cookie | Attributes | Description |
|---|---|---|
| `refreshToken` | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` | Long-lived refresh token |
| `csrf_token` | `HttpOnly; Secure; SameSite=Strict; Path=/` | CSRF protection token |

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "User Registered Successfully",
  "data": {
    "user": {
      "id": "e3037ab6-c2cf-4b95-a22d-7bb3d07e600d",
      "name": "John Doe",
      "email": "johndoe@example.com",
      "username": "johndoe",
      "avatar": null,
      "isVerified": false,
      "isGuest": false,
      "createdAt": "2026-06-03T00:33:00Z"
    },
    "tokens": {
      "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqb2huZG9lIiwiaWF0IjoxNzg0ODMyMTAwLCJleHAiOjE3ODQ4MzMwMDB9.xxxxxx",
      "expiresIn": 900
    }
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

> **Note:** `refreshToken` and `csrf_token` are **not** present in the response body. They are sent exclusively via `Set-Cookie` response headers by the server.

---

## 2. Login

Authenticates a user via credentials or starts a guest session.

*   **URL:** `POST /api/v1/auth/login`
*   **Authentication Required:** No
*   **Headers:**
    *   `Content-Type: application/json`

### Request Body (Standard credentials)
```json
{
  "username": "johndoe",
  "password": "Password123!"
}
```

### Request Body (Guest Mode)
```json
{
  "isGuest": true,
  "name": "GuestNinja",
  "age": 22,
  "gender": "MALE"
}
```

### Response Cookies Set by Server
| Cookie | Attributes | Description |
|---|---|---|
| `refreshToken` | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` | Long-lived refresh token |
| `csrf_token` | `HttpOnly; Secure; SameSite=Strict; Path=/` | CSRF protection token |

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Login Successful",
  "data": {
    "user": {
      "id": "e3037ab6-c2cf-4b95-a22d-7bb3d07e600d",
      "name": "John Doe",
      "email": "johndoe@example.com",
      "username": "johndoe",
      "avatar": null,
      "isVerified": true,
      "isGuest": false,
      "createdAt": "2026-06-03T00:33:00Z"
    },
    "tokens": {
      "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqb2huZG9lIiwiaWF0IjoxNzg0ODMyMTAwLCJleHAiOjE3ODQ4MzMwMDB9.xxxxxx",
      "expiresIn": 900
    }
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

> **Note:** `refreshToken` and `csrf_token` are **not** present in the response body. They are sent exclusively via `Set-Cookie` response headers by the server.

---

## 3. Refresh Token

Rotates the access token using the `refreshToken` HttpOnly cookie. Also rotates the refresh token and CSRF token cookies (refresh token rotation).

*   **URL:** `POST /api/v1/auth/refresh`
*   **Authentication Required:** No
*   **Request Cookies Required:**
    *   `refreshToken=<token>` — automatically sent by browser from the HttpOnly cookie set on login.

### Response Cookies Rotated by Server
| Cookie | Attributes | Description |
|---|---|---|
| `refreshToken` | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` | New rotated refresh token (old one invalidated) |
| `csrf_token` | `HttpOnly; Secure; SameSite=Strict; Path=/` | New rotated CSRF token |

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Token Refreshed Successfully",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqb2huZG9lIiwiaWF0IjoxNzg0ODMyMTAwLCJleHAiOjE3ODQ4MzMwMDB9.yyyyyy",
    "expiresIn": 900
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

> **Note:** The new `refreshToken` and new `csrf_token` are **not** in the response body. They are rotated via `Set-Cookie` response headers. The old refresh token is immediately invalidated server-side.

---

## 4. Logout

Terminates the active session and clears all auth cookies.

*   **URL:** `POST /api/v1/auth/logout`
*   **Authentication Required:** Yes
*   **Request Cookies Required:**
    *   `refreshToken=<token>`
*   **Headers:**
    *   `X-CSRF-Token: <token>` (mirrored from `csrf_token` cookie)
*   **Cookies:**
    *   `csrf_token=<token>`

### Response Cookies Cleared by Server
| Cookie | Action |
|---|---|
| `refreshToken` | Cleared (`Max-Age=0`) |
| `csrf_token` | Cleared (`Max-Age=0`) |

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Logout Successful",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 5. Get Current User

Returns the authenticated user's metadata.

*   **URL:** `GET /api/v1/auth/me`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "e3037ab6-c2cf-4b95-a22d-7bb3d07e600d",
    "name": "John Doe",
    "email": "johndoe@example.com",
    "username": "johndoe",
    "avatar": "https://api.talkme.app/api/v1/uploads/avatar.jpg",
    "isVerified": true,
    "isGuest": false,
    "createdAt": "2026-06-03T00:33:00Z",
    "country": "US",
    "city": "Dallas",
    "mobileNumber": "+1234567890",
    "interests": ["GAMING", "TRAVEL"]
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 6. Update Profile

Modifies current profile info.

*   **URL:** `PUT /api/v1/auth/me`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "name": "Johnathan Doe",
  "profileImage": "https://api.talkme.app/api/v1/uploads/avatar.jpg",
  "country": "US",
  "city": "Austin",
  "mobileNumber": "+1999999999",
  "interests": ["GAMING", "TRAVEL", "CODING"]
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Profile updated successfully",
  "data": {
    "id": "e3037ab6-c2cf-4b95-a22d-7bb3d07e600d",
    "name": "Johnathan Doe",
    "email": "johndoe@example.com",
    "username": "johndoe",
    "avatar": "https://api.talkme.app/api/v1/uploads/avatar.jpg",
    "isVerified": true,
    "isGuest": false,
    "createdAt": "2026-06-03T00:33:00Z",
    "country": "US",
    "city": "Austin",
    "mobileNumber": "+1999999999",
    "interests": ["GAMING", "TRAVEL", "CODING"]
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 7. Get Sessions

Retrieves active device sessions logged in for the current user.

*   **URL:** `GET /api/v1/auth/sessions`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": "a3b50c18-971c-43fe-a5fe-e8a6eb4862b1",
      "deviceName": "iPhone 15",
      "deviceType": "MOBILE",
      "browser": "Safari",
      "os": "iOS",
      "ipAddress": "192.168.1.50",
      "location": "Austin, US",
      "lastActive": "2026-06-03T00:33:00Z",
      "isCurrent": true,
      "createdAt": "2026-06-01T12:00:00Z"
    }
  ],
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 8. Revoke Session

Terminates a specific login session.

*   **URL:** `DELETE /api/v1/auth/sessions/{id}`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Session terminated successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 9. Revoke All Other Sessions

Invalidates all sessions except the current one.

*   **URL:** `POST /api/v1/auth/sessions/revoke-all`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "All other sessions revoked successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 10. Forgot Password

Triggers password reset link.

*   **URL:** `POST /api/v1/auth/forgot-password`
*   **Authentication Required:** No
*   **Headers:**
    *   `Content-Type: application/json`

### Request Body
```json
{
  "email": "johndoe@example.com"
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Password reset link sent successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 11. Reset Password

Updates password using the reset token.

*   **URL:** `POST /api/v1/auth/reset-password`
*   **Authentication Required:** No
*   **Headers:**
    *   `Content-Type: application/json`

### Request Body
```json
{
  "token": "reset-token-value",
  "newPassword": "NewSecurePassword123!"
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Password reset successful",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 12. Change Password

Changes password while logged in.

*   **URL:** `POST /api/v1/auth/change-password`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "oldPassword": "Password123!",
  "newPassword": "NewSecurePassword123!"
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Password changed successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```
