plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 版本号 = git 提交数：本机与 CI 对同一提交算出同一个号，每推一次自动 +1，
// 是应用自更新「远端 versionCode 是否更大」的比较基准。
// git 不可用时回退 1：本地号偏小只影响"我是旧版"，不会让 CI 新包被误判为旧包。
val commitCount = try {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        workingDir = rootDir
    }.standardOutput.asText.get().trim().toInt()
} catch (e: Exception) {
    1
}

android {
    namespace = "com.zhiwo.shiguangjian"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    // 统一签名：keystore 随仓库走，任何环境（本机/CI）构建的包签名指纹一致，
    // 才能互相覆盖安装且不清用户数据。触发数据库升级的是 AppDatabase.VERSION，
    // 与这里的号无关。
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("android/keystore/app.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.zhiwo.shiguangjian"
        minSdk = 26
        targetSdk = 34
        versionCode = commitCount
        versionName = "1.1.$commitCount"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.ui.ExperimentalComposeUiApi")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
    }
}

// Room 把每张表的建表结构导出到 app/schemas/<全类名>/<version>.json，作为"实体与迁移 SQL 是否一致"
// 的离线对账凭据（本机无模拟器，这是唯一能证伪迁移正确性的手段）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Core KTX
    implementation("androidx.core:core-ktx:1.12.0")

    // DataStore (轻量配置存储)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Unit Test
    testImplementation("junit:junit:4.13.2")
}
