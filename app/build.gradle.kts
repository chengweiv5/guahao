plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "cn.guahao"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.guahao"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization)
    implementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.uiautomator)
}
