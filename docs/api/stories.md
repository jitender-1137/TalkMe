# Story APIs (StoryController)

**Base Path:** `/api/v1/stories`

---

## 1. Create Story

Publishes a new story (disappearing photo or video status) visible to the user's friends.

*   **URL:** `POST /api/v1/stories`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "mediaUrl": "https://api.talkme.app/api/v1/uploads/story_9281.jpg",
  "caption": "Rise and shine! ☀️"
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Story posted successfully",
  "data": {
    "id": "s3a12c45-42a1-43ff-a12e-a5fe48d9a401",
    "user": {
      "id": "u5c37ab6-c2cf-4b95-a22d-7bb3d07e600d",
      "name": "Jane Doe",
      "email": "janedoe@example.com",
      "username": "janedoe",
      "avatar": "https://api.talkme.app/api/v1/uploads/jane_avatar.png",
      "isVerified": true,
      "isGuest": false,
      "createdAt": "2026-05-15T12:00:00Z",
      "country": "United States",
      "city": "San Francisco",
      "mobileNumber": "+15550199",
      "interests": ["travel", "photography", "hiking"]
    },
    "mediaUrl": "https://api.talkme.app/api/v1/uploads/story_9281.jpg",
    "caption": "Rise and shine! ☀️",
    "createdAt": "2026-06-03T00:33:00Z",
    "expiresAt": "2026-06-04T00:33:00Z",
    "viewedByMe": false
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Get Active Stories

Retrieves a list of active (non-expired) stories from the user and their friends.

*   **URL:** `GET /api/v1/stories/active`
*   **Authentication Required:** Yes (Role: `USER`)

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": "s3a12c45-42a1-43ff-a12e-a5fe48d9a401",
      "user": {
        "id": "u5c37ab6-c2cf-4b95-a22d-7bb3d07e600d",
        "name": "Jane Doe",
        "email": "janedoe@example.com",
        "username": "janedoe",
        "avatar": "https://api.talkme.app/api/v1/uploads/jane_avatar.png",
        "isVerified": true,
        "isGuest": false,
        "createdAt": "2026-05-15T12:00:00Z",
        "country": "United States",
        "city": "San Francisco",
        "mobileNumber": "+15550199",
        "interests": ["travel", "photography", "hiking"]
      },
      "mediaUrl": "https://api.talkme.app/api/v1/uploads/story_9281.jpg",
      "caption": "Rise and shine! ☀️",
      "createdAt": "2026-06-03T00:33:00Z",
      "expiresAt": "2026-06-04T00:33:00Z",
      "viewedByMe": true
    },
    {
      "id": "s5b98c32-12f9-482a-b72e-d82e18d9e201",
      "user": {
        "id": "u7d89ab6-a2cf-4c95-b22d-8bb3d07e700e",
        "name": "Bob Smith",
        "email": "bobsmith@example.com",
        "username": "bobsmith",
        "avatar": "https://api.talkme.app/api/v1/uploads/bob_avatar.png",
        "isVerified": false,
        "isGuest": false,
        "createdAt": "2026-05-20T10:30:00Z",
        "country": "Canada",
        "city": "Toronto",
        "mobileNumber": "+15550288",
        "interests": ["cooking", "gaming"]
      },
      "mediaUrl": "https://api.talkme.app/api/v1/uploads/story_coffee.mp4",
      "caption": "Brewing some fresh coffee ☕️",
      "createdAt": "2026-06-02T22:15:00Z",
      "expiresAt": "2026-06-03T22:15:00Z",
      "viewedByMe": false
    }
  ],
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 3. Delete Story

Deletes a story posted by the current authenticated user.

*   **URL:** `DELETE /api/v1/stories/{id}`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Story deleted successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 4. View Story

Marks a specific story as viewed by the current user.

*   **URL:** `POST /api/v1/stories/{id}/view`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Story viewed",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 5. Get Story Viewers

Retrieves the list of users who have viewed a specific story (only available to the story owner).

*   **URL:** `GET /api/v1/stories/{id}/viewers`
*   **Authentication Required:** Yes (Role: `USER`)

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": "u7d89ab6-a2cf-4c95-b22d-8bb3d07e700e",
      "name": "Bob Smith",
      "email": "bobsmith@example.com",
      "username": "bobsmith",
      "avatar": "https://api.talkme.app/api/v1/uploads/bob_avatar.png",
      "isVerified": false,
      "isGuest": false,
      "createdAt": "2026-05-20T10:30:00Z",
      "country": "Canada",
      "city": "Toronto",
      "mobileNumber": "+15550288",
      "interests": ["cooking", "gaming"]
    }
  ],
  "timestamp": "2026-06-03T00:33:00Z"
}
```
