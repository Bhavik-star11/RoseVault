import java.util.Properties
import java.net.URI

plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
val cloudProperties = Properties().apply {
 rootProject.file("supabase.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val supabaseUrl = providers.environmentVariable("SUPABASE_URL").orNull ?: cloudProperties.getProperty("SUPABASE_URL", "")
val publishableKey = providers.environmentVariable("SUPABASE_PUBLISHABLE_KEY").orNull ?: cloudProperties.getProperty("SUPABASE_PUBLISHABLE_KEY", "")
val enableCloud = providers.gradleProperty("enableCloud").orNull?.toBooleanStrict() ?: (supabaseUrl.isNotBlank() && publishableKey.isNotBlank())
if (enableCloud) {
 require(supabaseUrl.startsWith("https://") && URI(supabaseUrl).let { it.host != null && it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null && it.path in listOf("", "/") }) { "Use your project's HTTPS Supabase origin" }
 require(publishableKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "Use a Supabase publishable key; never put a secret/service-role key in the app" }
}
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
 namespace = "com.example"
 compileSdk = 36
 buildToolsVersion = "36.0.0"
 defaultConfig {
  applicationId = "com.aistudio.rosevault.kxpqzm"
  minSdk = 31
  targetSdk = 36
  versionCode = 7
  versionName = "1.5.0"
  buildConfigField("boolean", "CLOUD_ENABLED", enableCloud.toString())
  buildConfigField("String", "SUPABASE_URL", quoted(if (enableCloud) supabaseUrl else ""))
  buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quoted(if (enableCloud) publishableKey else ""))
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.08.00"))
 implementation("androidx.core:core-ktx:1.16.0")
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.fragment:fragment-ktx:1.8.9")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
 implementation("androidx.biometric:biometric:1.1.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
 implementation("io.github.webrtc-sdk:android:144.7559.15")
 implementation("com.google.android.gms:play-services-nearby:19.3.0")
 testImplementation("junit:junit:4.13.2")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
