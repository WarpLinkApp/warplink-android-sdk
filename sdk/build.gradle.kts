import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "app.warplink"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Single source of truth for the SDK version: derive it from
        // VERSION_NAME (which the release workflow sets from the git tag) so the
        // runtime version can never drift from the published Maven coordinate.
        buildConfigField(
            "String",
            "SDK_VERSION",
            "\"${project.property("VERSION_NAME")}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Forward the loopback stub's answer delay into the test JVM.
//
// Gradle forks the unit tests, so a -D on the Gradle command line does not
// reach them. Forwarded ONLY when it is actually set, so a normal run passes
// no extra system property and is byte-identical to what it was before.
//
// Usage: ./gradlew :sdk:testDebugUnitTest -Dwarplink.test.loopbackDelayMs=300
// See LoopbackJsonServer.answerDelayMs: it exists to prove the suite does not
// depend on a fast socket. A failure under it is a broken test, and the delay
// must never be raised or dropped to make one pass.
tasks.withType<Test>().configureEach {
    System.getProperty("warplink.test.loopbackDelayMs")?.let {
        systemProperty("warplink.test.loopbackDelayMs", it)
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    implementation("com.android.installreferrer:installreferrer:2.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = true))

    coordinates(
        groupId = property("GROUP").toString(),
        artifactId = property("POM_ARTIFACT_ID").toString(),
        version = property("VERSION_NAME").toString(),
    )

    // POM metadata (name, description, url, licenses, developers, scm) is
    // populated automatically from the POM_* keys in gradle.properties. Do not
    // re-declare a pom { } block here: the vanniktech plugin appends list
    // elements, so an explicit block duplicates <license>/<developer>.
}
