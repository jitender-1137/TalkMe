# Discovery APIs (DiscoverController)

**Base Path:** `/api/v1/discover`

---

## 1. Get Discover Feed

Retrieves recommended discovery profiles based on interests, verified status, online visibility, and search terms.

*   **URL:** `GET /api/v1/discover`
*   **Authentication Required:** Yes
*   **Query Parameters:**
    *   `q` (string, optional): Keyword query (searches username, name, email).
    *   `interests` (string, optional): Comma-separated list of interests to filter by (e.g. `GAMING,TRAVEL`).
    *   `distance` (double, optional): Custom geographical radius search (in km).
    *   `verified` (boolean, optional): `true` to filter by verified badges.
    *   `isOnline` (boolean, optional): `true` to filter by online status.
    *   `cursor` (string, optional): Next page cursor index.
    *   `limit` (number, optional, default: 20): Size of page to return.

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
        "age": 25,
        "username": "janesmith",
        "bio": "Travel blogger and photographer.",
        "location": "London, UK",
        "distance": "2 miles away",
        "distanceKm": 3.2,
        "occupation": "Blogger",
        "education": "BA in Arts",
        "interests": ["TRAVEL", "MUSIC"],
        "images": ["https://api.talkme.app/api/v1/uploads/jane.jpg"],
        "isVerified": true,
        "isOnline": true,
        "isLiked": false,
        "isFriend": false,
        "mutualFriendsCount": 2
      }
    ],
    "pagination": {
      "cursor": "1",
      "hasNext": true,
      "hasPrevious": false,
      "total": 5
    }
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 2. Like Discover Profile

Saves a like action on a discovery user profile.

*   **URL:** `POST /api/v1/discover/{userId}/like`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "User profile liked",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```

---

## 3. Unlike Discover Profile

Removes a like action from a discovery user profile.

*   **URL:** `DELETE /api/v1/discover/{userId}/like`
*   **Authentication Required:** Yes
*   **Headers:**
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "User profile unliked",
  "data": null,
  "timestamp": "2026-06-03T00:33:00Z"
}
```
