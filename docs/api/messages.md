# Message APIs (MessageController)

**Base Path:** `/api/v1/chats/{chatId}/messages`

---

## 1. Send Message

Sends a new message (text or media attachment) inside a chat.

*   **URL:** `POST /api/v1/chats/{chatId}/messages`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body (Text Message)
```json
{
  "content": "Hello, how are you?",
  "messageType": "TEXT"
}
```

### Request Body (Reply/Quote Message)
```json
{
  "content": "I am doing well, thank you!",
  "messageType": "TEXT",
  "parentMessageId": "m9a12c45-42a1-43ff-a12e-a5fe48d9a202"
}
```

### Request Body (Media Attachment Message)
```json
{
  "content": "Check out this document",
  "messageType": "DOCUMENT",
  "fileName": "resume.pdf",
  "fileSize": 102400,
  "fileUrl": "https://api.talkme.app/api/v1/uploads/resume_e32.pdf",
  "mimeType": "application/pdf"
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Message sent successfully",
  "data": {
    "id": "m9a12c45-42a1-43ff-a12e-a5fe48d9a203",
    "senderId": "e3037ab6-c2cf-4b95-a22d-7bb3d07e600d",
    "content": "Hello, how are you?",
    "messageType": "TEXT",
    "createdAt": "2026-06-03T00:33:00Z",
    "isEdited": false,
    "reactions": [],
    "attachments": [],
    "status": "SENT",
    "parentMessage": null
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Get Messages

Loads chat history messages.

*   **URL:** `GET /api/v1/chats/{chatId}/messages`
*   **Authentication Required:** Yes
*   **Query Parameters:**
    *   `page` (number, optional, default: 0)
    *   `size` (number, optional, default: 50)

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "content": [
      {
        "id": "m9a12c45-42a1-43ff-a12e-a5fe48d9a203",
        "senderId": "e3037ab6-c2cf-4b95-a22d-7bb3d07e600d",
        "content": "Hello, how are you?",
        "messageType": "TEXT",
        "createdAt": "2026-06-03T00:33:00Z",
        "isEdited": false,
        "reactions": [],
        "attachments": [],
        "status": "SENT",
        "parentMessage": null
      }
    ],
    "totalPages": 1,
    "totalElements": 1,
    "size": 50,
    "number": 0,
    "first": true,
    "last": true
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 3. Delete Message

Deletes a message from the conversation thread history.

*   **URL:** `DELETE /api/v1/chats/{chatId}/messages/{messageId}`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Message deleted successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 4. Add Message Reaction

Reacts to a specific message using a WhatsApp-style emoji.

*   **URL:** `POST /api/v1/chats/{chatId}/messages/{messageId}/react`
*   **Authentication Required:** Yes
*   **Query Parameters:**
    *   `emoji` (string, required): The emoji reaction string, e.g. `👍`, `❤️`.
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Reaction added",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 5. Remove Message Reaction

Removes a reaction from a message.

*   **URL:** `DELETE /api/v1/chats/{chatId}/messages/{messageId}/react`
*   **Authentication Required:** Yes
*   **Query Parameters:**
    *   `emoji` (string, required): The emoji reaction to remove.
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Reaction removed",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```
