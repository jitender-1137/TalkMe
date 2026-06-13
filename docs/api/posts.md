# Post APIs (PostController)

**Base Path:** `/api/v1/posts`

---

## 1. Create Post

Creates a new post with text content and optional media attachments.

*   **URL:** `POST /api/v1/posts`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "content": "Just had an amazing day exploring the city! Check out this view. #travel #adventure",
  "media": [
    {
      "mediaUrl": "https://api.talkme.app/api/v1/uploads/city_view_4982.jpg",
      "mediaType": "IMAGE"
    }
  ]
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Post created successfully",
  "data": {
    "id": "p8f13b67-42a1-43ff-a12e-a5fe48d9a101",
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
    "content": "Just had an amazing day exploring the city! Check out this view. #travel #adventure",
    "media": [
      {
        "id": "pm4a12c4-42a1-43ff-a12e-a5fe48d9a102",
        "mediaUrl": "https://api.talkme.app/api/v1/uploads/city_view_4982.jpg",
        "mediaType": "IMAGE"
      }
    ],
    "likesCount": 0,
    "likedByMe": false,
    "bookmarkedByMe": false,
    "createdAt": "2026-06-03T00:33:00Z",
    "comments": []
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Get Feed

Retrieves a paginated list of posts for the user's home feed.

*   **URL:** `GET /api/v1/posts/feed`
*   **Authentication Required:** Yes (Role: `USER`)
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
        "id": "p8f13b67-42a1-43ff-a12e-a5fe48d9a101",
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
        "content": "Just had an amazing day exploring the city! Check out this view. #travel #adventure",
        "media": [
          {
            "id": "pm4a12c4-42a1-43ff-a12e-a5fe48d9a102",
            "mediaUrl": "https://api.talkme.app/api/v1/uploads/city_view_4982.jpg",
            "mediaType": "IMAGE"
          }
        ],
        "likesCount": 15,
        "likedByMe": true,
        "bookmarkedByMe": false,
        "createdAt": "2026-06-03T00:33:00Z",
        "comments": [
          {
            "id": "c7b12c45-42a1-43ff-a12e-a5fe48d9a301",
            "username": "johndoe",
            "name": "John Doe",
            "content": "Looks incredible! Wish I was there.",
            "createdAt": "2026-06-03T00:34:00Z"
          }
        ]
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

## 3. Get Profile Feed

Retrieves a paginated list of posts created by a specific user.

*   **URL:** `GET /api/v1/posts/user/{userUuid}`
*   **Authentication Required:** Yes (Role: `USER`)
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
        "id": "p8f13b67-42a1-43ff-a12e-a5fe48d9a101",
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
        "content": "Just had an amazing day exploring the city! Check out this view. #travel #adventure",
        "media": [
          {
            "id": "pm4a12c4-42a1-43ff-a12e-a5fe48d9a102",
            "mediaUrl": "https://api.talkme.app/api/v1/uploads/city_view_4982.jpg",
            "mediaType": "IMAGE"
          }
        ],
        "likesCount": 15,
        "likedByMe": true,
        "bookmarkedByMe": false,
        "createdAt": "2026-06-03T00:33:00Z",
        "comments": []
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

## 4. Delete Post

Deletes a post owned by the current authenticated user.

*   **URL:** `DELETE /api/v1/posts/{id}`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Post deleted successfully",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 5. Like Post

Likes a specific post.

*   **URL:** `POST /api/v1/posts/{id}/like`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Post liked",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 6. Unlike Post

Removes a like from a post.

*   **URL:** `DELETE /api/v1/posts/{id}/like`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Post unliked",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 7. Add Comment

Adds a comment (or reply) to a post.

*   **URL:** `POST /api/v1/posts/{id}/comments`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `Content-Type: application/json`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Body
```json
{
  "content": "This is a comment.",
  "parentId": null
}
```

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Comment added to post",
  "data": {
    "id": "c7b12c45-42a1-43ff-a12e-a5fe48d9a301",
    "username": "janedoe",
    "name": "Jane Doe",
    "content": "This is a comment.",
    "createdAt": "2026-06-03T00:33:00Z"
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 8. Delete Comment

Deletes a comment from a post.

*   **URL:** `DELETE /api/v1/posts/{id}/comments/{commentId}`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Comment deleted from post",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 9. Bookmark Post

Bookmarks a post for the user.

*   **URL:** `POST /api/v1/posts/{id}/bookmark`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Post bookmarked",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 10. Unbookmark Post

Removes a bookmark from a post.

*   **URL:** `DELETE /api/v1/posts/{id}/bookmark`
*   **Authentication Required:** Yes (Role: `USER`)
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "Post unbookmarked",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```
