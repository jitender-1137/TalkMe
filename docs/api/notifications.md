# Notification APIs (NotificationController)

**Base Path:** `/api/v1/notifications`

---

## 1. Get Notifications

Retrieves a paginated list of notifications for the authenticated user.

*   **URL:** `GET /api/v1/notifications`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)
*   **Query Parameters:**
    *   `page` (number, optional, default: 0)
    *   `size` (number, optional, default: 20)
    *   `sort` (string, optional, default: `createdAt,desc`)

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "content": [
      {
        "id": "n2f98c32-12f9-482a-b72e-d82e18d9e301",
        "title": "New Friend Request",
        "content": "Bob Smith sent you a friend request.",
        "type": "FRIEND_REQUEST",
        "isRead": false,
        "referenceId": "f7b12c45-42a1-43ff-a12e-a5fe48d9a105",
        "createdAt": "2026-06-03T00:30:00Z"
      },
      {
        "id": "n3f98c32-12f9-482a-b72e-d82e18d9e302",
        "title": "New Comment",
        "content": "Bob Smith commented on your post.",
        "type": "POST_COMMENT",
        "isRead": true,
        "referenceId": "p8f13b67-42a1-43ff-a12e-a5fe48d9a101",
        "createdAt": "2026-06-02T18:15:00Z"
      }
    ],
    "totalPages": 1,
    "totalElements": 2,
    "size": 20,
    "number": 0,
    "first": true,
    "last": true
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Mark Notification as Read

Marks a specific notification as read.

*   **URL:** `PUT /api/v1/notifications/{id}/read`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Notification marked as read",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 3. Mark All Notifications as Read

Marks all notifications for the authenticated user as read.

*   **URL:** `PUT /api/v1/notifications/read-all`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "All notifications marked as read",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```
