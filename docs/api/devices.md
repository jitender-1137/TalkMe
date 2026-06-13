# Device APIs (DeviceController)

**Base Path:** `/api/v1/devices`

---

## 1. Register Device

Registers a push notification token (FCM, APNS, etc.) for the current user's active session.

*   **URL:** `POST /api/v1/devices`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "deviceToken": "fcm_token_1a2b3c4d5e6f7g8h9i0j_talkme_push_service",
  "deviceType": "ANDROID",
  "osVersion": "Android 14"
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Device profile registered successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Unregister Device

Removes the registered device token when the user logs out or toggles off push notifications on a specific device.

*   **URL:** `DELETE /api/v1/devices`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)
*   **Query Parameters:**
    *   `token` (string, required): The registered device token to remove.
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Device token deleted successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```
