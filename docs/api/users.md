# User Profile APIs (UserController)

**Base Path:** `/api/v1/users`

---

## 1. Get Own Profile

Retrieves the authenticated user's full profile details.

*   **URL:** `GET /api/v1/users/me`
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
    "bio": "Software developer loving real-time web applications.",
    "phone": "+1234567890",
    "age": 28,
    "country": "US",
    "city": "Austin",
    "interests": ["GAMING", "TRAVEL", "CODING"],
    "occupation": "Tech Lead",
    "education": "BS in Computer Science",
    "isVerified": true,
    "isGuest": false,
    "isBlocked": false,
    "presence": "online",
    "lastSeen": "2026-06-03T00:33:00Z",
    "createdAt": "2026-06-01T12:00:00Z",
    "updatedAt": "2026-06-03T00:33:00Z"
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Update Profile

Modifies the authenticated user's profile details. Mapped to both `PATCH` and `PUT`.

*   **URL:** `PATCH /api/v1/users/me` | `PUT /api/v1/users/me`
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
  "bio": "Senior tech developer.",
  "phone": "+1999999999",
  "age": 29,
  "country": "US",
  "city": "Dallas",
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
    "bio": "Senior tech developer.",
    "phone": "+1999999999",
    "age": 29,
    "country": "US",
    "city": "Dallas",
    "interests": ["GAMING", "TRAVEL", "CODING"],
    "occupation": "Tech Lead",
    "education": "BS in Computer Science",
    "isVerified": true,
    "isGuest": false,
    "isBlocked": false,
    "presence": "online",
    "lastSeen": "2026-06-03T00:33:00Z",
    "createdAt": "2026-06-01T12:00:00Z",
    "updatedAt": "2026-06-03T00:33:00Z"
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 3. Upload Avatar

Uploads a new avatar photo.

*   **URL:** `POST /api/v1/users/me/avatar`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `Content-Type: multipart/form-data`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```text
file: <image binary>  (jpg/png/webp/heic, max 5 MB)
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Avatar uploaded successfully",
  "data": {
    "avatarUrl": "https://api.talkme.app/api/v1/uploads/avatar_e3037ab6.jpg"
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 4. Remove Avatar

Deletes the user's avatar.

*   **URL:** `DELETE /api/v1/users/me/avatar`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Avatar removed",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 5. Get User by ID

Gets public details of another user by UUID.

*   **URL:** `GET /api/v1/users/{userId}`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
    "name": "Jane Smith",
    "email": "janesmith@example.com",
    "username": "janesmith",
    "avatar": "https://api.talkme.app/api/v1/uploads/jane.jpg",
    "bio": "Travel blogger and photographer.",
    "phone": "+1987654321",
    "age": 25,
    "country": "UK",
    "city": "London",
    "interests": ["TRAVEL", "MUSIC"],
    "occupation": "Blogger",
    "education": "BA in Arts",
    "isVerified": true,
    "isGuest": false,
    "isBlocked": false,
    "presence": "offline",
    "lastSeen": "2026-06-02T18:00:00Z",
    "createdAt": "2026-06-02T10:00:00Z",
    "updatedAt": "2026-06-02T18:00:00Z"
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 6. Search Users

Searches the directory by matching name, username, or email.

*   **URL:** `GET /api/v1/users/search`
*   **Authentication Required:** Yes
*   **Query Parameters:**
    *   `q` (string, required): Query string (min 2 chars).
    *   `limit` (number, optional, default: 20)
    *   `cursor` (string, optional): Page index value for next elements fetch.

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "items": [
      {
        "id": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
        "name": "Jane Smith",
        "email": "janesmith@example.com",
        "username": "janesmith",
        "avatar": "https://api.talkme.app/api/v1/uploads/jane.jpg",
        "bio": "Travel blogger.",
        "phone": "+1987654321",
        "age": 25,
        "country": "UK",
        "city": "London",
        "interests": ["TRAVEL", "MUSIC"],
        "occupation": "Blogger",
        "education": "BA in Arts",
        "isVerified": true,
        "isGuest": false,
        "isBlocked": false,
        "presence": "offline",
        "lastSeen": "2026-06-02T18:00:00Z",
        "createdAt": "2026-06-02T10:00:00Z",
        "updatedAt": "2026-06-02T18:00:00Z"
      }
    ],
    "pagination": {
      "cursor": "1",
      "hasNext": true,
      "hasPrevious": false,
      "total": 15
    }
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 7. Block User

Blocks a user.

*   **URL:** `POST /api/v1/users/{userId}/block`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "User blocked",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 8. Unblock User

Removes a block on a user.

*   **URL:** `DELETE /api/v1/users/{userId}/block`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "User unblocked",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 9. Get Blocked Users

Fetches a list of blocked users.

*   **URL:** `GET /api/v1/users/blocked`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "items": [
      {
        "id": "d748f210-911a-4f51-b8ef-e328ea48d890",
        "name": "Annoying Spammer",
        "avatar": "https://api.talkme.app/api/v1/uploads/spammer.jpg",
        "blockedAt": "2026-06-02T15:20:00Z"
      }
    ],
    "pagination": {
      "cursor": null,
      "hasNext": false,
      "hasPrevious": false,
      "total": 1
    }
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 10. Report User

Reports a user for abusive behavior.

*   **URL:** `POST /api/v1/users/{userId}/report`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "reason": "harassment",
  "description": "Sending inappropriate messages persistently."
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Report submitted",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 11. Get User Posts

Gets public feed posts created by target user.

*   **URL:** `GET /api/v1/users/{userId}/posts`
*   **Authentication Required:** Yes
*   **Query Parameters:**
    *   `page` (number, optional, default: 0)
    *   `size` (number, optional, default: 20)

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "content": [
      {
        "id": "b3e94a82-12ac-4cf9-be32-5a431c19b02a",
        "content": "Sunset in Dallas!",
        "likesCount": 10,
        "commentsCount": 2,
        "likedByMe": false,
        "bookmarkedByMe": false,
        "createdAt": "2026-06-02T19:00:00Z"
      }
    ],
    "totalPages": 1,
    "totalElements": 1,
    "size": 20,
    "number": 0,
    "first": true,
    "last": true
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 12. Get User Profile (Public)

Fetches public profile card. Identical structure to UserResponse.

*   **URL:** `GET /api/v1/users/{userId}/profile`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
    "name": "Jane Smith",
    "email": "janesmith@example.com",
    "username": "janesmith",
    "avatar": "https://api.talkme.app/api/v1/uploads/jane.jpg",
    "bio": "Travel blogger.",
    "phone": "+1987654321",
    "age": 25,
    "country": "UK",
    "city": "London",
    "interests": ["TRAVEL", "MUSIC"],
    "occupation": "Blogger",
    "education": "BA in Arts",
    "isVerified": true,
    "isGuest": false,
    "isBlocked": false,
    "presence": "offline",
    "lastSeen": "2026-06-02T18:00:00Z",
    "createdAt": "2026-06-02T10:00:00Z",
    "updatedAt": "2026-06-02T18:00:00Z"
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 13. Get Mutual Friends

Retrieves the list and count of mutual friends between current user and target user.

*   **URL:** `GET /api/v1/users/{userId}/mutual-friends`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "count": 1,
    "users": [
      {
        "id": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
        "name": "Jane Smith",
        "email": "janesmith@example.com",
        "username": "janesmith",
        "avatar": "https://api.talkme.app/api/v1/uploads/jane.jpg",
        "bio": "Travel blogger.",
        "phone": "+1987654321",
        "age": 25,
        "country": "UK",
        "city": "London",
        "interests": ["TRAVEL", "MUSIC"],
        "occupation": "Blogger",
        "education": "BA in Arts",
        "isVerified": true,
        "isGuest": false,
        "isBlocked": false,
        "presence": "offline",
        "lastSeen": "2026-06-02T18:00:00Z",
        "createdAt": "2026-06-02T10:00:00Z",
        "updatedAt": "2026-06-02T18:00:00Z"
      }
    ]
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```
