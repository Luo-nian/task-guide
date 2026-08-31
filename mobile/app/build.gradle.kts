plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.taskbar.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.taskbar.app"
        minSdk = 26          // Android 8.0，覆盖 vivo OriginOS
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Ktor 服务器端口
        buildConfigField("int", "SERVER_PORT", "8899")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // ===== Compose =====
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ===== Room =====
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ===== WorkManager（定时提醒） =====
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ===== Ktor 服务器（在 Android 上跑 HTTP+WS，用 CIO 纯 Kotlin 引擎） =====
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-cio:2.3.7")
    implementation("io.ktor:ktor-server-websockets:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // ===== JSON 序列化 =====
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // ===== 协程 =====
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ===== NSD（mDNS 服务发现，Android 自带 android.net.nsd） =====
    // 无需额外依赖
}
