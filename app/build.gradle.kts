/*
 * EDUVPN - one-tap OpenVPN client.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.eduvpn.onetap"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.eduvpn.onetap"
        // 24 keeps the door open for older devices; nothing in the app needs 26+
        // because the Base64 decoder in domain/Base64Compat.kt is self-contained.
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        // ics-openvpn declares two flavor dimensions. `skeleton` is its no-UI
        // build - exactly what an embedding app wants - and `ovpn23` selects the
        // OpenVPN 2.x native implementation.
        missingDimensionStrategy("implementation", "skeleton")
        missingDimensionStrategy("ovpnimpl", "ovpn23")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // ics-openvpn compiles with Java 17; an app module cannot target a lower
    // bytecode level than the library it embeds.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The vendored ics-openvpn module drags in BouncyCastle and a few jars that
    // carry duplicate META-INF entries.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ---------------------------------------------------------------- OpenVPN
    // The vendored ics-openvpn module. There is no Maven coordinate for it; see
    // the comment in settings.gradle.kts.
    if (findProject(":openvpn") != null) {
        implementation(project(":openvpn"))
    } else {
        logger.error(
            "EDUVPN: :openvpn is missing. The build will fail on " +
                "'unresolved reference: de.blinkt.openvpn'. " +
                "See docs/IMPLEMENTATION_GUIDE.md step 2.",
        )
    }

    // ------------------------------------------------------------------- UI
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.material)

    // ----------------------------------------------------------- networking
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // ----------------------------------------------------------------- tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
