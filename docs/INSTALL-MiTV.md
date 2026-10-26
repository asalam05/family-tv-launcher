# Installing Family TV on the Mi TV (MiTV-AXSO2, Android TV 9)

You need a laptop on the same Wi-Fi as the TV, plus about 20 minutes. Do this once, and later
updates are just step 4 again.

## Quick way (Mac)

After step 1 below, one command does everything else: it installs ADB if needed, installs the app,
grants the permissions and offers to switch off PatchWall. Add a photos folder as a second argument
to copy photos too.

```sh
curl -fsSL https://raw.githubusercontent.com/asalam05/family-tv-launcher/main/scripts/install-tv.sh | bash -s -- 192.168.1.40 ~/Pictures/family
```

Run the same command again whenever there's a new version.

## 1. Turn on developer mode on the TV

1. On the TV, open **Settings → Device Preferences → About**.
2. Select **Build** 7 times until it says you're a developer.
3. Go back to **Device Preferences → Developer options** and turn on **USB debugging**.
4. Find the TV's IP address in **Settings → Network & Internet → (your Wi-Fi)**, for example `192.168.1.40`.

## 2. Install ADB on the laptop

- **Mac:** `brew install android-platform-tools`
- **Windows / Linux:** download "SDK Platform-Tools" from developer.android.com/tools/releases/platform-tools and unzip it.

## 3. Connect to the TV

```sh
adb connect 192.168.1.40:5555
```

The TV shows "Allow USB debugging?". Tick **Always allow from this computer** and choose **OK**. Then check the connection:

```sh
adb devices      # should list 192.168.1.40:5555  device
```

## 4. Install the APK

Download **FamilyTV.apk** from the latest release on the repo's **Releases** page, then run:

```sh
adb install -r FamilyTV.apk
```

## 5. Give Family TV its permissions

First, check whether any other accessibility services are already turned on:

```sh
adb shell settings get secure enabled_accessibility_services
```

If that prints `null` or nothing, run the commands below as they are. If it prints something, add
`:` and that value to the end of the first command so the existing service stays on.

```sh
# Lock screen, warnings and remote buttons
adb shell settings put secure enabled_accessibility_services com.asalam.familytv/com.leanbitlab.ltvL.LauncherAccessibilityService
adb shell settings put secure accessibility_enabled 1

# Counting screen time
adb shell appops set com.asalam.familytv GET_USAGE_STATS allow

# Recording video titles ("What they watched")
adb shell cmd notification allow_listener com.asalam.familytv/com.leanbitlab.ltvL.LauncherNotificationListenerService
```

## 6. Make Family TV the home screen

Press **Home** on the remote. If the TV asks which app to use, pick **Family TV**, then **Always**.

If Home still opens PatchWall, find the old launcher's package name:

```sh
adb shell pm list packages | grep -iE "mitv|patchwall|tvhome|launcher"
```

Then disable it. This only switches it off; nothing is deleted:

```sh
adb shell pm disable-user --user 0 <package name from above>
```

To bring it back at any time, run `adb shell pm enable <package name>`.

## 7. Add family photos to the home screen

Download an album from Google Photos on your laptop, then run:

```sh
adb shell mkdir -p /sdcard/Android/data/com.asalam.familytv/files/Photos
adb push ~/Downloads/family-album/. /sdcard/Android/data/com.asalam.familytv/files/Photos/
```

The home screen shows a different photo every 45 seconds and picks up new photos within about 10 minutes.

## 8. Set up Kids mode

1. On the home screen, go up to the top bar and select the **family icon**, next to Settings.
2. Create a 4-digit parent PIN.
3. Open **Setup & PIN** and check that every item has a ✓.
4. Open **Screen time** and select **Turn Kids mode on**, then adjust the weekly schedule.
5. Open **Remote buttons**, select **Start test** and press each button on the remote once. Set each one to Allowed, Needs PIN or Blocked.
6. Open **Apps** and block anything the kids shouldn't open.
7. Select **Exit**. Parent mode ends as soon as you leave.

## Undo everything

```sh
adb shell settings put secure enabled_accessibility_services ""
adb shell pm enable <old launcher package>
adb uninstall com.asalam.familytv
```

## Known limits

- The Google Assistant/mic button is usually wired into the TV itself and can't be blocked. Turn on
  **Apps → Turn off voice search** so it can't open the search app.
- "Turn the screen off" when time runs out uses Android's lock-screen action. Some TVs ignore it, in
  which case the lock screen stays up instead.
- Video titles only appear for apps that share what's playing (YouTube, YouTube Kids, Netflix, Prime
  Video and most others). HDMI inputs show only the input name.
