#!/usr/bin/env bash
#
# Migrate existing on-disk media into the OCI Object Storage bucket so that the
# app's OLD database references (/opt/media/talkMe/...) keep resolving.
#
# The app derives an OCI object key from a stored reference by stripping the
# media-root, else the leading slash. Old references look like
#   /opt/media/talkMe/conversations/<cid>/<file>.jpg
# so the matching object key is
#   opt/media/talkMe/conversations/<cid>/<file>.jpg          (leading slash removed)
# This script uploads each file under exactly that key. No DB changes needed.
#
# Uses the OCI CLI + your ~/.oci/config (point it at the account/tenancy that owns
# the bucket — the source machine's own tenancy is irrelevant to the upload).
#
# Usage:
#   ./migrate-media-to-oci.sh
#   SRC_DIR=/data/talkMe DRY_RUN=true ./migrate-media-to-oci.sh
#   OCI_BUCKET=neochathub-media OCI_CONFIG_PROFILE=BEE SKIP_EXISTING=true ./migrate-media-to-oci.sh
#
set -euo pipefail

# ── Config (override via env) ─────────────────────────────────────────────────
SRC_DIR="${SRC_DIR:-/opt/media/talkMe}"            # where the files live NOW
# Object-key prefix. The app strips its media-root (/opt/media/talkMe) off references
# to get the key, so the keys it reads are the CLEAN relative paths (e980b5fc.jpg,
# conversations/<cid>/<f>.jpg, …) — same key space as new media. Hence: NO prefix.
# (Only set this if the app's media-root differs from SRC_DIR.)
KEY_PREFIX="${KEY_PREFIX:-}"
BUCKET="${OCI_BUCKET:-bucket-20260717-0011}"
OCI_CONFIG_FILE="${OCI_CONFIG_FILE:-$HOME/.oci/config}"
OCI_PROFILE="${OCI_CONFIG_PROFILE:-DEFAULT}"
NAMESPACE="${OCI_NAMESPACE:-bmkec0mqetma}"          # auto-resolved if empty
REGION="${OCI_REGION:-ap-mumbai-1}"                 # region where the bucket lives
DRY_RUN="${DRY_RUN:-false}"                          # true = list keys, upload nothing
SKIP_EXISTING="${SKIP_EXISTING:-false}"             # true = skip objects already in the bucket

# ── Pre-flight ────────────────────────────────────────────────────────────────
command -v oci >/dev/null 2>&1 || {
  echo "ERROR: OCI CLI not found. Install it first: https://docs.oracle.com/en-us/iaas/Content/API/SDKDocs/cliinstall.htm" >&2
  exit 1
}
[ -d "$SRC_DIR" ] || { echo "ERROR: source dir not found: $SRC_DIR" >&2; exit 1; }

OCI=(oci --config-file "$OCI_CONFIG_FILE" --profile "$OCI_PROFILE" --region "$REGION")

if [ -z "$NAMESPACE" ]; then
  NAMESPACE="$("${OCI[@]}" os ns get --query 'data' --raw-output)"
fi

# ── Content-Type by extension (so <img>/<video> render, not download) ─────────
content_type() {
  case "${1,,}" in
    *.jpg|*.jpeg) echo image/jpeg ;;
    *.png)        echo image/png ;;
    *.gif)        echo image/gif ;;
    *.webp)       echo image/webp ;;
    *.bmp)        echo image/bmp ;;
    *.svg)        echo image/svg+xml ;;
    *.mp4)        echo video/mp4 ;;
    *.mov)        echo video/quicktime ;;
    *.webm)       echo video/webm ;;
    *.mp3)        echo audio/mpeg ;;
    *.m4a)        echo audio/mp4 ;;
    *.aac)        echo audio/aac ;;
    *.ogg|*.oga)  echo audio/ogg ;;
    *.wav)        echo audio/wav ;;
    *.pdf)        echo application/pdf ;;
    *)            echo application/octet-stream ;;
  esac
}

total="$(find "$SRC_DIR" -type f | wc -l | tr -d ' ')"
echo "──────────────────────────────────────────────────────────────"
echo " Source dir   : $SRC_DIR"
echo " Bucket       : $BUCKET   (namespace $NAMESPACE, region $REGION)"
echo " Key prefix   : $KEY_PREFIX/"
echo " Profile      : $OCI_PROFILE   ($OCI_CONFIG_FILE)"
echo " Files found  : $total"
echo " Dry run      : $DRY_RUN     Skip existing: $SKIP_EXISTING"
echo "──────────────────────────────────────────────────────────────"

n=0; ok=0; skipped=0; failed=0
while IFS= read -r -d '' file; do
  n=$((n + 1))
  rel="${file#"$SRC_DIR"/}"          # path relative to SRC_DIR, e.g. conversations/<cid>/<f>.jpg
  # Object key = KEY_PREFIX + relative path. Empty prefix → key IS the relative path
  # (use this when the app's media-root already equals SRC_DIR, so it strips the whole
  # /opt/media/talkMe prefix and looks up just conversations/<cid>/<f>.jpg).
  if [ -n "$KEY_PREFIX" ]; then key="$KEY_PREFIX/$rel"; else key="$rel"; fi
  ct="$(content_type "$file")"

  if [ "$DRY_RUN" = "true" ]; then
    printf '[%d/%d] would upload → %s (%s)\n' "$n" "$total" "$key" "$ct"
    continue
  fi

  if [ "$SKIP_EXISTING" = "true" ] && \
     "${OCI[@]}" os object head --namespace "$NAMESPACE" --bucket-name "$BUCKET" --name "$key" >/dev/null 2>&1; then
    skipped=$((skipped + 1))
    printf '[%d/%d] skip (exists) %s\n' "$n" "$total" "$key"
    continue
  fi

  if out="$("${OCI[@]}" os object put \
        --namespace "$NAMESPACE" --bucket-name "$BUCKET" \
        --name "$key" --file "$file" --content-type "$ct" \
        --force 2>&1)"; then
    ok=$((ok + 1))
    printf '[%d/%d] ✔ %s (%s)\n' "$n" "$total" "$key" "$ct"
  else
    failed=$((failed + 1))
    printf '[%d/%d] x FAILED %s\n' "$n" "$total" "$key" >&2
    echo "----- OCI error -----" >&2
    echo "$out" >&2
    echo "---------------------" >&2
    # A first-file failure is almost always systemic (wrong account/region, missing
    # bucket, or no IAM policy) and will hit every file — stop so it's obvious.
    if [ "$ok" -eq 0 ]; then
      echo "Aborting: first upload failed. Fix the error above, then re-run." >&2
      echo "Most common cause: the bucket '$BUCKET' isn't in namespace '$NAMESPACE' /" >&2
      echo "region for this profile — create it there, or point OCI_CONFIG_PROFILE/" >&2
      echo "OCI_NAMESPACE/OCI_REGION at the account that owns it." >&2
      exit 1
    fi
  fi
done < <(find "$SRC_DIR" -type f -print0)

echo "──────────────────────────────────────────────────────────────"
echo " Done. uploaded=$ok  skipped=$skipped  failed=$failed  (of $total)"
[ "$failed" -eq 0 ] || { echo " Some uploads failed — see the ✗ lines above." >&2; exit 1; }
