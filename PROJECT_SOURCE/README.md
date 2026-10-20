# BEDTIME — Local Wi-Fi Primary + Optional Cloud Fallback

**STAGING / TEST ONLY (2026-10-20):** version `1.2.8-parent-trial-countdown-staging`, versionCode `11`; application ID `com.master.bedtime.practice.paymongostaging`; Worker `https://bedtime-parental-staging.tdudskie.workers.dev`.

## Connection behavior

- **Default for a new Child setup: Local Wi-Fi only.** Automatic Parent discovery, local Child registration, local heartbeat, and local LOCK/UNLOCK remain the primary route.
- **Optional Cloud fallback:** Child can check `Enable Cloud fallback (optional)` during setup, or enable it later under Child → Settings → `Cloud Fallback`.
- If enabled, Child registers with the existing Cloudflare API using the same six-digit code where possible. Parent can issue the same LOCK/UNLOCK through local Wi-Fi and cloud. Local Wi-Fi remains authoritative whenever the local Parent is reachable and paired; cloud is used if local is unavailable.
- If Cloud registration cannot use the same code as the local Child, this build disables Cloud fallback for that pairing rather than create duplicate Child entries. The local connection is not removed.
- Parent auto-syncs local pairings to cloud only for Child records that opted into Cloud fallback. The opt-in flag is synced over the authenticated local heartbeat; toggling Cloud does not re-register the Child or rotate the local token. Parent's current account/subscription API remains on Cloudflare as before. Turning fallback off stops Child cloud polling and preserves the saved cloud token so enabling it again does not force a new role setup.
- Daily Auto-Unlock at 05:00, Change Role, Change Parent, local discovery and the current UI remain in the project. The Cloudflare Worker/D1 service is not deployed or modified by this source-only add-on.

## Requirements and limitations

- Local-only Child operation needs the same reachable non-Guest Wi-Fi and Parent local service.
- Cloud fallback requires internet on both phones and an available Cloudflare Worker. It improves reachability across networks but is polling-based in this build; it is not a guarantee against Android Force Stop, powered-off phones, revoked permissions, or manufacturer battery limits.
- The foreground Child service continues to poll local Wi-Fi first and uses cloud only when enabled and when the local Parent is unavailable/unpaired.
- No Realme/Tecno-only checks or vendor whitelist were added. Android API baseline remains 26+.

## Build on Windows

Run in VS Code PowerShell:

```powershell
$Project = "C:\PATH\TO\YOUR_REPOSITORY\PROJECT_SOURCE"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location "$Project\android"
.\gradlew.bat :app:assembleDebug "-PBEDTIME_CLOUD_URL=https://bedtime-parental-staging.tdudskie.workers.dev" "-PBEDTIME_APPLICATION_ID=com.master.bedtime.practice.paymongostaging"
```

APK output: `android\app\build\outputs\apk\debug\app-debug.apk`. Test local-only mode first on the same Wi-Fi. Then turn on Cloud fallback on the Child phone, refresh Parent, and verify the Child is paired by the same six-digit code and commands work when the Parent is on another network.


## Historical source notes from the 2026-10-12 iteration

This version is based on `BEDTIME_HYBRID_LOCAL_PRIMARY_OPTIONAL_CLOUD_20261012` and preserves Local Wi-Fi as primary, optional Cloud fallback, role pairing, and the 05:00 daily unlock.

Additive fixes in this follow-up:
- Makes Child setup/dashboard vertically scrollable and moves **Change Role (Parent / Child)** near the top of the Child dashboard so it is not clipped on smaller screens.
- Gives Local heartbeat and Parent-local command calls short timeouts so an unreachable LAN route does not wait the default six seconds before Cloud fallback is attempted.
- Polls Cloud more frequently while the local Parent is unreachable/unpaired, while keeping a five-second cloud heartbeat in healthy local-primary mode so cloud online status remains fresh.
- Adds a Background Reliability Settings helper with Android/OEM battery settings guidance.
- Restarts the monitor worker if a service start request arrives while its prior worker thread is no longer alive.

A Windows Gradle staging build completed successfully during preparation. End-to-end Parent/Child, payment webhook, subscription activation, and Auto Unlock tests are still required.


## Current verification boundary

This repository contains a staging/test APK built successfully during preparation. Build success does not prove complete end-to-end verification. Do not deploy the Worker or use this as a production release without explicit approval.
