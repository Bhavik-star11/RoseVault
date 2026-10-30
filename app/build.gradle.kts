import java.util.Properties
import java.net.URI

plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
// Matrix is the cloud messenger. Keep the previous backend disabled during migration.
val enableCloud = false
val supabaseUrl = ""
val publishableKey = ""
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
 namespace = "com.example"
 compileSdk = 36
 buildToolsVersion = "36.0.0"
 defaultConfig {
  applicationId = "com.aistudio.rosevault.kxpqzm"
  minSdk = 31
  targetSdk = 36
  versionCode = 8
  versionName = "2.0.0-alpha.1"
  buildConfigField("boolean", "CLOUD_ENABLED", enableCloud.toString())
  buildConfigField("boolean", "LOCAL_MATRIX_DEV", "false")
  buildConfigField("String", "SUPABASE_URL", quoted(if (enableCloud) supabaseUrl else ""))
  buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quoted(if (enableCloud) publishableKey else ""))
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  providers.gradleProperty("targetAbi").orNull?.let { abi ->
   require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) { "Unsupported targetAbi" }
   ndk { abiFilters += abi }
  }
 }
 buildTypes {
  create("local") {
   initWith(getByName("debug"))
   applicationIdSuffix = ".local"
   versionNameSuffix = "-local"
   signingConfig = signingConfigs.getByName("debug")
   matchingFallbacks += "debug"
   buildConfigField("boolean", "LOCAL_MATRIX_DEV", "true")
  }
 }
 testBuildType = providers.gradleProperty("instrumentedBuildType").orElse("debug").get().also {
  require(it in setOf("debug", "local")) { "Use debug or local for instrumentedBuildType" }
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation("org.matrix.rustcomponents:sdk-android:26.10.23")
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
