# Chat APIs (ChatController)

**Base Path:** `/api/v1/chats`

---

## 1. Create Chat

Creates a new 1-to-1 private chat or a group chat.

*   **URL:** `POST /api/v1/chats`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body (1-to-1 Private Chat)
```json
{
  "recipientId": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2"
}
```

### Request Body (Group Chat)
```json
{
  "name": "Project Devs",
  "memberIds": [
    "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
    "d748f210-911a-4f51-b8ef-e328ea48d890"
  ]
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Chat created successfully",
  "data": {
    "id": "c1f729b8-12cd-43ff-be45-e8a6eb4862b3",
    "chatType": "PRIVATE",
    "name": "Jane Smith",
    "otherUser": {
      "id": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
      "name": "Jane Smith",
      "email": "janesmith@example.com",
      "username": "janesmith",
      "avatar": "https://api.talkme.app/api/v1/uploads/jane.jpg",
      "isVerified": true,
      "isGuest": false,
      "createdAt": "2026-06-02T10:00:00Z"
    },
    "lastMessage": null,
    "unreadCount": 0,
    "isPinned": false,
    "isArchived": false,
    "isMuted": false,
    "typingUsers": [],
    "createdAt": "2026-06-03T00:33:00Z"
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Get Chats

Retrieves all active chats for the authenticated user, ordered by pinned status and last message time.

*   **URL:** `GET /api/v1/chats`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": "c1f729b8-12cd-43ff-be45-e8a6eb4862b3",
      "chatType": "PRIVATE",
      "name": "Jane Smith",
      "otherUser": {
        "id": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
        "name": "Jane Smith",
        "email": "janesmith@example.com",
        "username": "janesmith",
        "avatar": "https://api.talkme.app/api/v1/uploads/jane.jpg",
        "isVerified": true,
        "isGuest": false,
        "createdAt": "2026-06-02T10:00:00Z"
      },
      "lastMessage": {
        "id": "m9a12c45-42a1-43ff-a12e-a5fe48d9a202",
        "senderId": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
        "content": "Hey, did you finish the documentation?",
        "messageType": "TEXT",
        "createdAt": "2026-06-03T00:30:00Z",
        "isEdited": false,
        "reactions": [],
        "attachments": [],
        "status": "DELIVERED"
      },
      "unreadCount": 1,
      "isPinned": true,
      "isArchived": false,
      "isMuted": false,
      "typingUsers": [],
      "createdAt": "2026-06-03T00:10:00Z"
    }
  ],
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 3. Get Chat Details

Fetches metadata for a specific chat conversation.

*   **URL:** `GET /api/v1/chats/{id}`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "c1f729b8-12cd-43ff-be45-e8a6eb4862b3",
    "chatType": "PRIVATE",
    "name": "Jane Smith",
    "otherUser": {
      "id": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
      "name": "Jane Smith",
      "email": "janesmith@example.com",
      "username": "janesmith",
      "avatar": "https://api.talkme.app/api/v1/uploads/jane.jpg",
      "isVerified": true,
      "isGuest": false,
      "createdAt": "2026-06-02T10:00:00Z"
    },
    "lastMessage": null,
    "unreadCount": 0,
    "isPinned": false,
    "isArchived": false,
    "isMuted": false,
    "typingUsers": [],
    "createdAt": "2026-06-03T00:33:00Z"
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 4. Archive Chat

Archives or unarchives a conversation thread.

*   **URL:** `PUT /api/v1/chats/{id}/archive`
*   **Authentication Required:** Yes
*   **Query Parameters:**
    *   `archive` (boolean, required): `true` to archive, `false` to unarchive.
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Chat archived successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 5. Mute Chat

Mutes or unmutes notification alerts for a conversation.

*   **URL:** `PUT /api/v1/chats/{id}/mute`
*   **Authentication Required:** Yes
*   **Query Parameters:**
    *   `mute` (boolean, required): `true` to mute, `false` to unmute.
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Chat muted successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 6. Pin Chat

Pins or unpins a conversation.

*   **URL:** `PUT /api/v1/chats/{id}/pin`
*   **Authentication Required:** Yes
*   **Query Parameters:**
    *   `pin` (boolean, required): `true` to pin, `false` to unpin.
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Chat pinned successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 7. Clear Chat

Deletes all message history within a chat thread.

*   **URL:** `DELETE /api/v1/chats/{id}/clear`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Chat cleared successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 8. Delete Chat

Removes a chat thread and exits the membership.

*   **URL:** `DELETE /api/v1/chats/{id}`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Chat deleted successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 9. Mark Read

Marks all unread messages inside a conversation as read.

*   **URL:** `PUT /api/v1/chats/{id}/read`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Chat read status updated",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```
