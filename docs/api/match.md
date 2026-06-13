# Match & Random Chat APIs (MatchController)

**Base Path:** `/api/v1/match`

---

## 1. Join Match Queue

Enters the matchmaking queue. If a partner matches your filters, a session is returned immediately.

*   **URL:** `POST /api/v1/match/queue`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body (Optional filters)
```json
{
  "interests": ["GAMING", "TRAVEL"],
  "gender": "FEMALE",
  "minAge": 18,
  "maxAge": 30
}
```

### Success Response (`200 OK` - Match Found)
```json
{
  "success": true,
  "message": "Match found successfully",
  "data": {
    "id": "s8a42b10-671c-43fe-a5fe-e8a6eb4862b5",
    "partner": {
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
    "chatId": "c5b20d18-971c-43fe-a5fe-e8a6eb4862b4",
    "isActive": true
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

### Success Response (`200 OK` - Entered Queue)
```json
{
  "success": true,
  "message": "Entered matchmaking queue",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Leave Match Queue

Exits the matchmaking queue, canceling any pending searches.

*   **URL:** `DELETE /api/v1/match/queue`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Exited matchmaking queue",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 3. Check Active Session

Retrieves active stranger session details if one is already active.

*   **URL:** `GET /api/v1/match/session`
*   **Authentication Required:** Yes

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "s8a42b10-671c-43fe-a5fe-e8a6eb4862b5",
    "partner": {
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
    "chatId": "c5b20d18-971c-43fe-a5fe-e8a6eb4862b4",
    "isActive": true
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 4. Skip Match

Skips the current stranger chat partner and enters the queue to look for a new one.

*   **URL:** `POST /api/v1/match/skip`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK` - Match Found)
```json
{
  "success": true,
  "message": "Match found successfully",
  "data": {
    "id": "s8a42b10-671c-43fe-a5fe-e8a6eb4862b6",
    "partner": {
      "id": "d748f210-911a-4f51-b8ef-e328ea48d890",
      "name": "Alice Green",
      "email": "alice@example.com",
      "username": "alicegreen",
      "avatar": null,
      "isVerified": false,
      "isGuest": true,
      "createdAt": "2026-06-02T12:00:00Z",
      "country": "US",
      "city": "Chicago",
      "mobileNumber": null,
      "interests": ["GAMING"]
    },
    "chatId": "c5b20d18-971c-43fe-a5fe-e8a6eb4862c9",
    "isActive": true
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 5. End Match

Closes and terminates the current stranger session.

*   **URL:** `POST /api/v1/match/end`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Stranger session ended",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 6. Report Stranger

Reports the stranger partner for behavior issues.

*   **URL:** `POST /api/v1/match/report`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "reason": "Harassment",
  "details": "User was saying abusive words."
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Stranger chat report submitted",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```
}
