plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "dev.vehiclebridge.byd"
 compileSdk = 36
 defaultConfig { applicationId = "dev.vehiclebridge.byd"; minSdk = 26; targetSdk = 36; versionCode = 23; versionName = "0.5.1-dev"; testInstrumentationRunner = "dev.vehiclebridge.byd.BridgeSmokeInstrumentation" }
 buildFeatures { viewBinding = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

 buildTypes { getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}
