# OCI Object Storage (prod media backend)

In **production** (`ACTIVE_PROFILE=prod`), user media is stored in a single Oracle
Cloud Object Storage bucket instead of the VM disk, so every backend instance shares
one source of media. **local/dev keep the filesystem flow.**

This is a transparent swap: the stored DB reference, the media pipeline (video
transcode + photo→mp4 mux), the `/uploads/media?path=…` serve endpoint, and the
frontend are all identical. Only where the bytes physically live changes.

## How it's wired

`MediaStorage` (in `com.chat.talkMe.storage`) has two implementations, selected by
`storage.provider`:

- `LocalMediaStorage` — filesystem (default / when `storage.provider` is unset or `local`).
- `OciMediaStorage` — OCI bucket (when `storage.provider=oci`, set only in `application-prod.yml`).

Both share a single configurable root, `storage.media-root` (env `FILE_BASE_DIR`,
default `/media`) — the OCI object keys and the instance disk both sit under it, and
the stored reference is `<media-root>/<key>` (e.g. `/media/conversations/<uuid>/<f>.mp4`).
Local dev overrides it to a home-dir path. The backend uploads/downloads/deletes
objects and streams media back through `GET /uploads/media`.

## One-time OCI setup

1. **Bucket** — Console → *Storage → Object Storage & Archive Storage* → your
   compartment → *Create Bucket* → name `neochathub-media`, **Private**.
2. **API key** — Console → avatar → *User Settings → API Keys → Add API Key*.
   Download the private key PEM and note: Tenancy OCID, User OCID, Fingerprint, Region.
3. **Config file** on the server (`~/.oci/config` for the app user):
   ```ini
   [DEFAULT]
   user=ocid1.user.oc1..xxxx
   fingerprint=aa:bb:cc:dd:...
   tenancy=ocid1.tenancy.oc1..xxxx
   region=ap-mumbai-1
   key_file=/home/ubuntu/.oci/oci_api_key.pem
   ```
   Place the PEM at `key_file` (`chmod 600`).
4. **IAM policy** — allow the user's group to `manage objects` in that bucket.

## Environment variables (prod)

| Var | Default | Notes |
|---|---|---|
| `STORAGE_PROVIDER` | `oci` | Set `local` to fall back to the VM disk. |
| `FILE_BASE_DIR` | `/media` | Shared media root (`storage.media-root`) — OCI keys + instance disk. |
| `OCI_NAMESPACE` | *(auto-resolved)* | Object Storage namespace; auto-detected at startup if blank. |
| `OCI_REGION` | `ap-mumbai-1` | Must match the config file region. |
| `OCI_BUCKET` | `neochathub-media` | |
| `OCI_CONFIG_FILE` | `~/.oci/config` | API-key config file path. |
| `OCI_CONFIG_PROFILE` | `DEFAULT` | Profile section in the config file. |

Note: existing files under an old media root on a running prod VM are **not**
migrated — new uploads go to OCI going forward.
