rootProject.name = "forma"

include(":core")
// Android-модуль подключается только там, где есть SDK (CI / Android Studio).
if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) include(":app")
