BEDTIME PARENTAL MVP — CLEAN MASTER WORKSPACE
Inihanda: 2026-10-20

MAHALAGA
- Ito ang bagong malinis na SOURCE folder na bubuksan sa VS Code.
- Ang installer sa INSTALLER\Bedtime-Parental-MVP-v1.2.8-STAGING-TEST.apk ay staging/test APK lamang.
- Ang source code ay inihanda mula sa available na clean source copy; HINDI ito na-extract mula sa APK.
- Android namespace: com.master.bedtime.practice. Staging application ID: com.master.bedtime.practice.paymongostaging.
- Default Cloudflare Worker URL: https://bedtime-parental-staging.tdudskie.workers.dev
- Kasama ang Auto Unlock-related code; kailangan pa ng end-to-end testing sa Parent at Child phones.

PRESERVATION RULES
1. Huwag burahin ang lumang backups hanggang mabuksan at masuri ang folder na ito sa VS Code.
2. Huwag patakbuhin ang `wrangler deploy` o baguhin ang Cloudflare Worker nang walang malinaw na pahintulot.
3. Huwag i-install ang bagong APK sa gumaganang phone hangga't hindi napapatunayang tama ang build at signing compatibility.
4. Huwag magpalit ng UI design o existing functions; additive Auto Unlock time lang ang target.
5. Huwag ilagay sa ZIP ang API tokens, passwords, `.env`, o Cloudflare secrets.

PAGBUKAS SA WINDOWS
1. I-extract ang ZIP sa Desktop bilang BEDTIME_CLEAN_MASTER.
2. Buksan ang VS Code.
3. Piliin File > Open Folder.
4. Piliin ang PROJECT_SOURCE folder para makita ang Android at web source.
5. Ang Android source ay nasa android\app\src\main\java\com\master\bedtime.
6. Ang Worker source ay nasa web\src\index.js; staging config ay wrangler.staging.jsonc.

BUILD STATUS
- Staging/test APK ito, hindi production release.
- Matagumpay ang Windows Gradle staging build; kailangan pa rin ang end-to-end testing.
- Hindi pa verified end-to-end ang Auto Unlock at Cloud fallback laban sa staging Worker. Huwag mag-deploy sa live Worker.
- Huwag i-uninstall o i-overwrite ang kasalukuyang gumaganang app.

PAGLIPAT SA USB
- Kopyahin sa USB ang kinakailangang source at documentation; huwag isama ang secrets o private configuration.
- Bago mag-build, gamitin lamang ang staging URL sa build command.
