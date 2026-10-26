#!/usr/bin/env bash
# Installs Family TV on an Android TV over Wi-Fi and grants the permissions Kids mode needs.
#
#   ./install-tv.sh <TV-IP> [photos-folder]
#
# Before running: on the TV, enable Developer options -> USB debugging (see docs/INSTALL-MiTV.md).
set -euo pipefail

TV_IP="${1:-}"
PHOTOS="${2:-}"
if [[ -z "$TV_IP" ]]; then
  echo "Usage: $0 <TV-IP> [photos-folder]"
  exit 1
fi

PKG="com.asalam.familytv"
ACC_SVC="$PKG/com.leanbitlab.ltvL.LauncherAccessibilityService"
NOTIF_SVC="$PKG/com.leanbitlab.ltvL.LauncherNotificationListenerService"
APK_URL="https://github.com/asalam05/family-tv-launcher/releases/latest/download/FamilyTV.apk"
TARGET="$TV_IP:5555"

say() { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }

if ! command -v adb >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    say "Installing ADB with Homebrew"
    brew install android-platform-tools
  else
    echo "ADB is missing. Install 'SDK Platform-Tools' from https://developer.android.com/tools/releases/platform-tools"
    exit 1
  fi
fi

say "Connecting to the TV at $TARGET"
echo "If the TV asks 'Allow USB debugging?', tick 'Always allow from this computer' and choose OK."
adb connect "$TARGET" >/dev/null || true
for _ in $(seq 1 30); do
  state="$(adb -s "$TARGET" get-state 2>/dev/null || true)"
  [[ "$state" == "device" ]] && break
  sleep 2
  adb connect "$TARGET" >/dev/null 2>&1 || true
done
if [[ "$(adb -s "$TARGET" get-state 2>/dev/null || true)" != "device" ]]; then
  echo "Couldn't connect. Check the IP address, that USB debugging is on, and accept the prompt on the TV."
  exit 1
fi
A=(adb -s "$TARGET")

say "Downloading the latest Family TV"
TMP="$(mktemp -d)"
curl -fL --progress-bar -o "$TMP/FamilyTV.apk" "$APK_URL"

say "Installing"
if ! "${A[@]}" install -r "$TMP/FamilyTV.apk"; then
  echo
  echo "Install failed. If it mentions a signature mismatch, uninstall the old copy first:"
  echo "  adb -s $TARGET uninstall $PKG"
  exit 1
fi

say "Granting permissions"
current="$("${A[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ -z "$current" || "$current" == "null" ]]; then
  services="$ACC_SVC"
elif [[ "$current" == *"$ACC_SVC"* ]]; then
  services="$current"
else
  services="$current:$ACC_SVC"
fi
"${A[@]}" shell settings put secure enabled_accessibility_services "$services"
"${A[@]}" shell settings put secure accessibility_enabled 1
echo "  ✓ Kids mode service (lock screen, warnings, remote buttons)"
"${A[@]}" shell appops set "$PKG" GET_USAGE_STATS allow && echo "  ✓ Usage access (screen time)"
"${A[@]}" shell cmd notification allow_listener "$NOTIF_SVC" && echo "  ✓ Notification access (video titles)"

if [[ -n "$PHOTOS" ]]; then
  say "Copying photos from $PHOTOS"
  DEST="/sdcard/Android/data/$PKG/files/Photos"
  "${A[@]}" shell mkdir -p "$DEST"
  "${A[@]}" push "$PHOTOS/." "$DEST/"
fi

say "Other home screens on this TV"
others="$("${A[@]}" shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
  | tr -d '\r' | grep -E 'packageName=' | sed 's/.*packageName=//' | sort -u | grep -v "^$PKG$" || true)"
if [[ -z "$others" ]]; then
  others="$("${A[@]}" shell pm list packages | tr -d '\r' | sed 's/package://' \
    | grep -iE 'tvhome|patchwall|launcher' | grep -v "^$PKG$" || true)"
fi
# The TV settings app registers a fallback home screen; never disable it.
others="$(echo "$others" | grep -vE '^com\.android\.tv\.settings$|^com\.android\.settings$' || true)"

if [[ -n "$others" ]]; then
  echo "$others" | sed 's/^/  - /'
  echo
  read -r -p "Switch these off so Home and the Mi button always open Family TV? (undo any time with 'adb shell pm enable <name>') [y/N] " answer </dev/tty
  if [[ "$answer" =~ ^[Yy]$ ]]; then
    while read -r p; do
      [[ -z "$p" ]] && continue
      "${A[@]}" shell pm disable-user --user 0 "$p" && echo "  disabled $p"
    done <<< "$others"
  fi
fi

"${A[@]}" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true

say "Done"
cat <<EOF
On the TV:
  1. Press Home. If asked, choose Family TV -> Always.
  2. In the top bar select the family icon, create your parent PIN.
  3. Setup & PIN should show four ticks. Then Screen time -> Turn Kids mode on.
EOF
