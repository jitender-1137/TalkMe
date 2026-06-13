# Friend & Block APIs (FriendController)

**Base Path:** `/api/v1/friends`

---

## 1. Send Friend Request

Sends a friend request to another user.

*   **URL:** `POST /api/v1/friends/requests`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "receiverId": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2"
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Friend request sent successfully",
  "data": {
    "id": "r8b91a78-43cd-42ee-ae56-e8a6eb4862b4",
    "sender": {
      "id": "e3037ab6-c2cf-4b95-a22d-7bb3d07e600d",
      "name": "John Doe",
      "email": "johndoe@example.com",
      "username": "johndoe",
      "avatar": "https://api.talkme.app/api/v1/uploads/avatar.jpg",
      "isVerified": true,
      "isGuest": false,
      "createdAt": "2026-06-01T12:00:00Z",
      "country": "US",
      "city": "Austin",
      "mobileNumber": "+1234567890",
      "interests": ["GAMING", "TRAVEL"]
    },
    "status": "PENDING"
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Accept Friend Request

Accepts an incoming pending friend request.

*   **URL:** `PUT /api/v1/friends/requests/{id}/accept`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Friend request accepted",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 3. Decline Friend Request

Declines an incoming pending friend request.

*   **URL:** `PUT /api/v1/friends/requests/{id}/decline`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Friend request rejected",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 4. Cancel Friend Request

Cancels an outgoing pending friend request.

*   **URL:** `DELETE /api/v1/friends/requests/{id}/cancel`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Friend request canceled",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 5. Get Friends List

Gets a list of all current friends.

*   **URL:** `GET /api/v1/friends`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
      "name": "Jane Smith",
      "email": "janesmith@example.com",
      "username": "janesmith",
      "avatar": "https://api.talkme.app/api/v1/uploads/jane.jpg",
      "isVerified": true,
      "isGuest": false,
      "createdAt": "2026-06-02T10:00:00Z",
      "country": "UK",
      "city": "London",
      "mobileNumber": "+1987654321",
      "interests": ["TRAVEL", "MUSIC"]
    }
  ],
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 6. Get Friend Requests

Gets a list of all incoming pending friend requests.

*   **URL:** `GET /api/v1/friends/requests`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": "r8b91a78-43cd-42ee-ae56-e8a6eb4862b4",
      "sender": {
        "id": "f8a42b10-671c-43fe-a5fe-e8a6eb4862b2",
        "name": "Jane Smith",
        "email": "janesmith@example.com",
        "username": "janesmith",
        "avatar": "https://api.talkme.app/api/v1/uploads/jane.jpg",
        "isVerified": true,
        "isGuest": false,
        "createdAt": "2026-06-02T10:00:00Z",
        "country": "UK",
        "city": "London",
        "mobileNumber": "+1987654321",
        "interests": ["TRAVEL", "MUSIC"]
      },
      "status": "PENDING"
    }
  ],
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 7. Remove Friend

Deletes a friend relation (unfriends a user).

*   **URL:** `DELETE /api/v1/friends/{id}`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Friend removed successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 8. Block Friend

Blocks a friend by UUID.

*   **URL:** `POST /api/v1/friends/block/{id}`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "User blocked successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 9. Unblock Friend

Unblocks a friend by UUID.

*   **URL:** `DELETE /api/v1/friends/block/{id}`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "User unblocked successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```
