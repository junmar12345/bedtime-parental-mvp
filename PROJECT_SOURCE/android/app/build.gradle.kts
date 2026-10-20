plugins {
    id("com.android.application")
}

val requestedApplicationId = (project.findProperty("BEDTIME_APPLICATION_ID") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "com.master.bedtime.practice"

// STAGING-ONLY safety: do not silently build an APK pointing to the live Worker.
val cloudApiUrl = (project.findProperty("BEDTIME_CLOUD_URL") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: providers.environmentVariable("BEDTIME_CLOUD_URL").orNull
    ?: "https://REPLACE_WITH_STAGING_WORKER_HOST.workers.dev"

if (cloudApiUrl.contains("bedtime-parental-live", ignoreCase = true)) {
    error("Blocked: this staging copy must never build against the known live BEDTIME Worker.")
}
if (!cloudApiUrl.startsWith("https://", ignoreCase = true) || cloudApiUrl.contains("REPLACE_WITH_STAGING")) {
    error("Set BEDTIME_CLOUD_URL to the real HTTPS staging Worker URL before building this staging copy.")
}

android {
    namespace = "com.master.bedtime.practice"
    compileSdk = 36
    defaultConfig {
        applicationId = requestedApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.2.8-parent-trial-countdown-staging"
        buildConfigField("String", "CLOUD_API_URL", "\"$cloudApiUrl\"")
    }
    buildFeatures {
        buildConfig = true
    }
}
