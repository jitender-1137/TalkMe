# Settings APIs (UserSettingController)

**Base Path:** `/api/v1/settings`

---

## 1. Get User Settings

Retrieves the app configuration preferences (theme, language, alerts/notifications, safe search filters, sound options) for the authenticated user.

*   **URL:** `GET /api/v1/settings`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "s8f13b67-42a1-43ff-a12e-a5fe48d9a109",
    "theme": "DARK",
    "language": "en",
    "notificationsEnabled": true,
    "safeModeEnabled": false,
    "soundEnabled": true
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Update User Settings

Modifies the app configuration preferences for the authenticated user.

*   **URL:** `PUT /api/v1/settings`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "theme": "LIGHT",
  "language": "es",
  "notificationsEnabled": false,
  "safeModeEnabled": true,
  "soundEnabled": false
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Settings updated successfully",
  "data": {
    "id": "s8f13b67-42a1-43ff-a12e-a5fe48d9a109",
    "theme": "LIGHT",
    "language": "es",
    "notificationsEnabled": false,
    "safeModeEnabled": true,
    "soundEnabled": false
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```
