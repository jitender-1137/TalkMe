# Upload APIs (UploadController)

**Base Path:** `/api/v1/uploads`

---

## 1. Upload File

Uploads a multipart file (e.g. image, video, audio, document) to the backend storage service.

*   **URL:** `POST /api/v1/uploads`
*   **Authentication Required:** Yes (Role: `USER` or `GUEST`)
*   **Headers:**
    *   `Content-Type: multipart/form-data`
    *   `X-CSRF-Token: <token>`
*   **Cookies:**
    *   `csrf_token=<token>`

### Request Multipart Form Data
*   `file` (File, required): The binary payload of the file to upload.
*   `type` (string, required): The target location/intent category of the file, e.g. `AVATAR`, `CHAT_MEDIA`, `POST_MEDIA`, `STORY_MEDIA`.

### Success Response (`200 OK`)
```json
{
  "success": true,
  "message": "File uploaded successfully",
  "data": {
    "url": "https://api.talkme.app/api/v1/uploads/city_view_4982.jpg",
    "thumbnail": "https://api.talkme.app/api/v1/uploads/thumb_city_view_4982.jpg",
    "fileName": "city_view.jpg",
    "fileSize": 1024000,
    "mimeType": "image/jpeg",
    "duration": null
  },
  "timestamp": "2026-06-03T00:33:00Z"
}
```
