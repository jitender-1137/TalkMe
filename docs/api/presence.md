# Presence APIs (PresenceController)

**Base Path:** `/api/v1/presence`

---

## 1. Set Presence Status

Updates the user's manual presence status (e.g. `ONLINE`, `OFFLINE`, `AWAY`, `IDLE`, `INVISIBLE`).

*   **URL:** `PUT /api/v1/presence/status`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)
*   **Query Parameters:**
    *   `status` (string, required): Allowed values are `ONLINE`, `OFFLINE`, `AWAY`, `IDLE`, `INVISIBLE`.
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Presence status updated successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Toggle Ghost Mode

Enables or disables Ghost Mode. When Ghost Mode is enabled, the user's presence status appears offline or restricted to other users, hiding their last-seen timestamp.

*   **URL:** `PUT /api/v1/presence/ghost`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Query Parameters:**
    *   `enabled` (boolean, required): `true` to enable ghost mode, `false` to disable.
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Ghost Mode enabled",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 3. Toggle Invisible Mode

Enables or disables Invisible Mode. When Invisible Mode is enabled, the user always appears offline to other users.

*   **URL:** `PUT /api/v1/presence/invisible`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Query Parameters:**
    *   `enabled` (boolean, required): `true` to enable invisible mode, `false` to disable.
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Invisible Mode enabled",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 4. Reset Presence Settings

Resets all presence status settings (status, ghost mode, invisible mode) back to their default values (e.g. `ONLINE`, modes disabled).

*   **URL:** `DELETE /api/v1/presence/reset`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Presence properties reset successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 5. Get Presence Status

Retrieves the presence status of a specific user. Privacy settings (like ghost mode and invisible mode) are automatically handled; retrieving your own presence shows configuration states, whereas retrieving someone else's presence hides those flags and may hide their last-seen timestamp.

*   **URL:** `GET /api/v1/presence/{username}`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)

### Success Response (`200 OK` - Retrieving Own Presence)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "username": "janedoe",
    "status": "ONLINE",
    "lastSeenAt": "2026-06-03T00:33:00Z",
    "ghostModeEnabled": true,
    "invisibleModeEnabled": false
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

### Success Response (`200 OK` - Retrieving Other User's Presence)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "username": "bobsmith",
    "status": "OFFLINE",
    "lastSeenAt": null,
    "ghostModeEnabled": false,
    "invisibleModeEnabled": false
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```
