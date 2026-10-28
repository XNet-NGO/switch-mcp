import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// --- portmux prebuilt artifact fetch ----------------------------------------
// switch-mcp consumes portmux as prebuilt static libraries (GitHub release),
// not as source, exactly like Switchboard. Per-ABI tarballs are downloaded
// into app/.portmux/ and the .a files extracted to app/.portmux/libs/<abi>/.
// CMake imports them. If the libs are already present (copied from a sibling
// checkout / CI cache), the fetch is skipped.
val portmuxVersion = (project.findProperty("portmuxVersion") as String?) ?: "v0.4.0"
val portmuxRepo = "XNet-NGO/portmux"
val portmuxAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val portmuxDir = layout.projectDirectory.dir(".portmux")
val portmuxLibsDir = portmuxDir.dir("libs")
val portmuxIncludeDir = portmuxDir.dir("include")

val fetchPortmux by tasks.registering {
    description = "Download and extract portmux $portmuxVersion prebuilt static libs."
    val marker = portmuxDir.file(".fetched-$portmuxVersion").asFile
    outputs.file(marker)
    outputs.dir(portmuxLibsDir)
    doLast {
        // Already satisfied if every ABI has both archives present.
        val haveAll = portmuxAbis.all { abi ->
            File(portmuxLibsDir.dir(abi).asFile, "libportmux.a").exists() &&
                File(portmuxLibsDir.dir(abi).asFile, "libhev-task-system.a").exists()
        } && File(portmuxIncludeDir.asFile, "portmux.h").exists()
        if (haveAll) {
            if (!marker.exists()) marker.writeText("portmux $portmuxVersion present (not fetched)\n")
            return@doLast
        }
        if (marker.exists()) return@doLast
        portmuxDir.asFile.mkdirs()
        portmuxLibsDir.asFile.mkdirs()
        portmuxIncludeDir.asFile.mkdirs()
        portmuxAbis.forEach { abi ->
            val tgz = portmuxDir.file("portmux-$portmuxVersion-android-$abi.tar.gz").asFile
            val url =
                "https://github.com/$portmuxRepo/releases/download/" +
                    "$portmuxVersion/portmux-$portmuxVersion-android-$abi.tar.gz"
            logger.lifecycle("portmux: fetching $abi from $url")
            ant.invokeMethod("get", mapOf("src" to url, "dest" to tgz.absolutePath, "verbose" to false))
            val outAbi = portmuxLibsDir.dir(abi).asFile
            outAbi.mkdirs()
            val proc = ProcessBuilder(
                "tar", "-xzf", tgz.absolutePath, "-C", portmuxDir.asFile.absolutePath,
            ).inheritIO().start()
            if (proc.waitFor() != 0) throw GradleException("tar extract failed for $abi")
            val extracted = portmuxDir.dir("android").dir(abi).asFile
            extracted.listFiles()?.filter { it.extension == "a" }?.forEach { a ->
                a.copyTo(File(outAbi, a.name), overwrite = true)
            }
        }
        marker.writeText("portmux $portmuxVersion fetched\n")
        logger.lifecycle("portmux: artifacts ready under ${portmuxDir.asFile}")
    }
}

tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildTask>().configureEach {
    dependsOn(fetchPortmux)
}
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchPortmux) }

android {
    namespace = "org.xnet.switchmcp"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.xnet.switchmcp"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"

        ndk {
            abiFilters += portmuxAbis
        }
        externalNativeBuild {
            cmake {
                arguments += "-DPORTMUX_LIBS_DIR=${portmuxLibsDir.asFile.absolutePath}"
                arguments += "-DPORTMUX_INCLUDE_DIR=${portmuxIncludeDir.asFile.absolutePath}"
                cppFlags += "-std=c11"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            // Credentials come from the environment, never hardcoded in the
            // repo. Set XNET_KEYSTORE / XNET_KEYSTORE_PASS / XNET_KEY_ALIAS.
            // When unset, release builds are produced unsigned.
            val ksPath = System.getenv("XNET_KEYSTORE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("XNET_KEYSTORE_PASS")
                keyAlias = System.getenv("XNET_KEY_ALIAS") ?: "xnet-key"
                keyPassword = System.getenv("XNET_KEY_PASS")
                    ?: System.getenv("XNET_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (System.getenv("XNET_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // The MCP SDK + Ktor pull in META-INF bits that collide across jars.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
        }
    }
}

val mcpVersion = "0.15.0"
val ktorVersion = "3.5.1"

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Official Model Context Protocol Kotlin SDK (server side) + Ktor engine.
    implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
}
