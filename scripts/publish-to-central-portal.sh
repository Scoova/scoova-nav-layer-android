#!/usr/bin/env bash
# Publish the staged multi-module Maven layout under
# build/staging-deploy/info/scoo-va/scoova-nav-layer-*  to the Sonatype
# Central Portal in one bundle. Each subproject writes its own
# scoova-nav-layer-<name>/<version>/ tree; we zip the whole
# info/ directory and POST it as a single deployment.
#
# Env required: OSSRH_USERNAME, OSSRH_PASSWORD
# Optional:     PUBLISHING_TYPE (AUTOMATIC | USER_MANAGED), STAGING_DIR
#
# Publishing type AUTOMATIC means Central Portal auto-releases after
# successful validation. USER_MANAGED requires manual "release" click at
# https://central.sonatype.com/publishing/deployments.

set -euo pipefail

: "${OSSRH_USERNAME:?OSSRH_USERNAME not set}"
: "${OSSRH_PASSWORD:?OSSRH_PASSWORD not set}"

cd "$(dirname "$0")/.."

STAGING_DIR="${STAGING_DIR:-build/staging-deploy}"
PUBLISHING_TYPE="${PUBLISHING_TYPE:-AUTOMATIC}"

if [[ ! -d "$STAGING_DIR" ]]; then
  echo "✗ staging dir not found: $STAGING_DIR" >&2
  exit 2
fi

# Pick any one artifactId to read its published version — every module
# publishes the same $version, since they share `allprojects { version = ... }`.
VERSION=""
for d in "$STAGING_DIR"/info/scoo-va/*/; do
  V=$(find "$d" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; 2>/dev/null | sort -V | tail -1)
  if [[ -n "$V" ]]; then VERSION="$V"; break; fi
done

if [[ -z "$VERSION" ]]; then
  echo "✗ couldn't find any published version under $STAGING_DIR/info/scoo-va/" >&2
  echo "  contents:" >&2
  ls -R "$STAGING_DIR" >&2 || true
  exit 3
fi

ARTIFACT_NAME="scoova-nav-layer-android-$VERSION"
BUNDLE="build/$ARTIFACT_NAME.zip"

echo "Staging dir : $STAGING_DIR"
echo "Version     : $VERSION"
echo "Bundle      : $BUNDLE"

rm -f "$BUNDLE"
( cd "$STAGING_DIR" && zip -r -q -X "$OLDPWD/$BUNDLE" info -x '*/maven-metadata.xml*' )

echo
echo "Bundle contents (first 20 entries):"
unzip -l "$BUNDLE" | awk 'NR<=24'

TOKEN=$(printf '%s:%s' "$OSSRH_USERNAME" "$OSSRH_PASSWORD" | base64 | tr -d '\n')

echo
echo "Uploading to Central Portal (publishingType=$PUBLISHING_TYPE) ..."

HTTP_OUT=$(mktemp)
HTTP_CODE=$(curl -sS \
  -o "$HTTP_OUT" -w "%{http_code}" \
  -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -F "bundle=@$BUNDLE" \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=$PUBLISHING_TYPE&name=$ARTIFACT_NAME")

BODY=$(cat "$HTTP_OUT" | head -c 4096)
rm -f "$HTTP_OUT"

if [[ "$HTTP_CODE" =~ ^2[0-9][0-9]$ ]]; then
  echo "✓ Accepted. Deployment id: $BODY"
  echo "Track at: https://central.sonatype.com/publishing/deployments"
  echo "Artifacts will be queryable at:"
  for d in "$STAGING_DIR"/info/scoo-va/*/; do
    id=$(basename "$d")
    echo "  https://repo1.maven.org/maven2/info/scoo-va/$id/$VERSION/"
  done
  exit 0
fi

echo "✗ Upload failed: HTTP $HTTP_CODE" >&2
echo "$BODY" >&2
exit 1
