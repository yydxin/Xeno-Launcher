import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.gradle.tasks.MergeSourceSetFolders
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    id("com.google.devtools.ksp")
    id("kotlinx-serialization")
    id("kotlin-parcelize")
    id("com.movtery.buildkeys")
}

val zalithPackageName = "com.movtery.zalithlauncher"
// 社区版独立包名，与官方版（com.movtery.zalithlauncher.v2）共存不覆盖
val communityApplicationId = "io.github.yydxin.xenolauncher"
val launcherAPPName = project.findProperty("launcher_app_name") as? String ?: error("The \"launcher_app_name\" property is not set in gradle.properties.")
val launcherName = project.findProperty("launcher_name") as? String ?: error("The \"launcher_name\" property is not set in gradle.properties.")
val launcherShortName = project.findProperty("launcher_short_name") as? String ?: error("The \"launcher_short_name\" property is not set in gradle.properties.")
val launcherUrl = project.findProperty("url_home") as? String ?: error("The \"url_home\" property is not set in gradle.properties.")

val launcherVersionName = project.findProperty("launcher_version_name") as? String ?: error("The \"launcher_version_name\" property is not set in gradle.properties.")
// 版本号唯一规则：major*10000 + minor*100 + patch（预发布后缀不参与 versionCode）
val launcherVersionCode: Int = run {
    val parts = launcherVersionName.substringBefore('-').split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    major * 10_000 + minor * 100 + patch
}

val defaultOAuthClientID = project.findProperty("oauth_client_id") as? String
val defaultCurseForgeApiKey = project.findProperty("curseforge_api_key") as? String

val projectArch: String = System.getProperty("arch", "all")

// ---------------------------------------------------------------------------
// Release 签名：只从环境变量指定的 keystore.properties 或项目根目录的
// keystore.properties 读取。文件/密码不提交仓库；缺少配置时 release 构建直接失败。
// ---------------------------------------------------------------------------
val keystorePropertiesFile: File = System.getenv("XENO_KEYSTORE_PROPERTIES")
    ?.let { File(it) }
    ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning: Boolean = keystorePropertiesFile.isFile &&
        !keystoreProperties.getProperty("storeFile").isNullOrBlank() &&
        !keystoreProperties.getProperty("storePassword").isNullOrBlank() &&
        !keystoreProperties.getProperty("keyAlias").isNullOrBlank() &&
        !keystoreProperties.getProperty("keyPassword").isNullOrBlank()
fun signingFile(path: String): File {
    val f = File(path)
    return if (f.isAbsolute) f else rootProject.file(path)
}

// arm64 Linux 主机无法运行 x86_64 的 NDK 工具链（ndk-build/clang 均为 linux-x86_64 预编译），
// 此时可复用官方基线构建产物中的 JNI .so（源码未改动）完成打包验证。
// 正式发布请在 x86_64 主机上使用默认的 ndk-build 流程。
val usePrebuiltJni: Boolean = (project.findProperty("zl2UsePrebuiltJni") as? String)?.toBoolean() ?: false

fun localProperty(key: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.isFile) return null
    return runCatching {
        Properties().apply { file.inputStream().use { load(it) } }.getProperty(key)
    }.getOrNull()
}

fun getKeyFromLocal(envKey: String, fileName: String? = null, default: String? = null): String {
    // 优先级：环境变量 / GitHub Secrets → local.properties → 旧文本文件 → Gradle 属性
    val key = System.getenv(envKey)
        ?: localProperty(envKey.lowercase())
        ?: localProperty(envKey)
    return key ?: fileName?.let {
        val file = File(rootDir, fileName)
        if (file.canRead() && file.isFile) file.readText().trim() else null
    } ?: default ?: run {
        logger.warn("BUILD: $envKey not set; related features may throw exceptions.")
        ""
    }
}

android {
    namespace = zalithPackageName
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("xenoRelease") {
                storeFile = signingFile(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
        // Debug 使用 AGP 默认的 ~/.android/debug.keystore，不再把调试密钥提交仓库
    }

    defaultConfig {
        applicationId = communityApplicationId
        minSdk = 26
        targetSdk = 34
        versionCode = launcherVersionCode
        versionName = launcherVersionName
        manifestPlaceholders["launcher_name"] = launcherAPPName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("xenoRelease") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    splits {
        val arch = projectArch.takeIf { it != "all" } ?: return@splits
        abi {
            isEnable = true
            reset()
            when (arch) {
                "arm" -> include("armeabi-v7a")
                "arm64" -> include("arm64-v8a")
                "x86" -> include("x86")
                "x86_64" -> include("x86_64")
            }
        }
    }

    ndkVersion = "25.2.9519653"

    if (!usePrebuiltJni) {
        externalNativeBuild {
            ndkBuild {
                path = file("src/main/jni/Android.mk")
            }
        }
    }

    sourceSets {
        getByName("main") {
            if (usePrebuiltJni) {
                // 与 src/main/jniLibs 并列，避免与仓库内预置的第三方 .so 冲突
                jniLibs.srcDir("src/prebuiltJniLibs")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
            if (usePrebuiltJni) {
                // NDK 的 llvm-strip 也是 x86_64 产物，arm64 主机无法执行；保留符号跳过 strip
                keepDebugSymbols += "**/*.so"
            }
        }
    }

    compileOptions {
        // sora-editor language-textmate
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            //让 android.util.Log 等框架方法在本地单测中返回默认值而非抛出异常
            isReturnDefaultValues = true
        }
    }
}

gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any {
        it.name == "assembleRelease" || it.name == "bundleRelease" || it.name == "packageRelease"
    }
    if (releaseRequested && !hasReleaseSigning) {
        throw GradleException(
            "缺少 Release 签名配置：请在项目根目录创建 keystore.properties，或设置环境变量 XENO_KEYSTORE_PROPERTIES。"
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
                afterEvaluate {
                    val task = tasks.named("merge${variantName}Assets").get() as MergeSourceSetFolders
                    task.inputs.property("lwjglArch", projectArch)
                    task.doLast {
                        val assetsDir = task.outputDir.get().asFile
                        val tag = "JREAssetsCleanup"
                        logger.lifecycle("[$tag] arch: $projectArch")
                        val jreList = listOf("jre-8", "jre-17", "jre-21", "jre-25")
                        jreList.forEach { jreVersion ->
                            val runtimeDir = File("$assetsDir/runtimes/$jreVersion")
                            logger.lifecycle("[$tag] runtimeDir: ${runtimeDir.absolutePath}")
                            runtimeDir.listFiles()?.forEach {
                                if (projectArch != "all" && it.name != "version" && !it.name.contains("universal") && it.name != "bin-$projectArch.tar.xz") {
                                    logger.lifecycle("[$tag] delete: $it : ${it.delete()}")
                                }
                            }
                        }

                        if (projectArch == "all") return@doLast
                        val abi = when (projectArch) {
                            "arm" -> "armeabi-v7a"
                            "arm64" -> "arm64-v8a"
                            "x86" -> "x86"
                            "x86_64" -> "x86_64"
                            else -> return@doLast
                        }
                        val lwjglVersions = file("libs").listFiles { f ->
                            f.name.matches(Regex("lwjgl-\\d+\\.\\d+\\.\\d+-natives-release\\.aar"))
                        }
                            ?.map { Regex("lwjgl-(\\d+\\.\\d+\\.\\d+)-natives-release\\.aar").find(it.name)!!.groupValues[1] }
                            ?: emptyList()
                        lwjglVersions.forEach { version ->
                            val nativesDir = File(assetsDir, "app_runtime/lwjgl/$version/natives")
                            if (nativesDir.isDirectory) {
                                nativesDir.listFiles()?.forEach { dir ->
                                    if (dir.isDirectory && dir.name != abi) {
                                        logger.lifecycle("Removing non-target-arch natives: $dir")
                                        dir.deleteRecursively()
                                    }
                                }
                            }
                        }
                    }
                }

                (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                    val baseName = "$launcherName-${if (variant.buildType == "release") launcherVersionName else "Debug-$launcherVersionName"}"
                    output.outputFileName = if (abi == "all") "$baseName.apk" else "$baseName-$abi.apk"
                }
            }
        }
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

buildKeys {
    string("OAUTH_CLIENT_ID", getKeyFromLocal("OAUTH_CLIENT_ID", ".oauth_client_id.txt", defaultOAuthClientID), true)
    string("LAUNCHER_NAME", launcherAPPName, true)
    string("LAUNCHER_IDENTIFIER", launcherName, true)
    string("LAUNCHER_SHORT_NAME", launcherShortName, true)
    string("URL_HOME", launcherUrl, true)
    string("CURSEFORGE_API", getKeyFromLocal("CURSEFORGE_API_KEY", ".curseforge_api.txt", defaultCurseForgeApiKey), true)
    string("BUILD_ARCH", projectArch)
}

dependencies {
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.nav3)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.ktor3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.material.color.utilities)
    implementation(libs.materialKolor)
    implementation(libs.reorderable)
    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui)
    implementation(libs.richtext.ui.material3)
    implementation(platform(libs.editor.bom))
    implementation(libs.editor)
    implementation(libs.editor.language.textmate)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    //Project
    implementation(project(":LayerController"))
    implementation(project(":ColorPicker"))
    implementation(project(":CardGrid"))
    implementation(project(":Terracotta"))
    implementation(project(":InputMap"))
    implementation(project(":Guide"))
    implementation(project(":PetLib"))
    //Utils
    implementation(libs.bytehook)
    implementation(libs.gson)
    implementation(libs.jetbrains.annotations)
    implementation(libs.commons.io)
    implementation(libs.commons.codec)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zip4j)
    implementation(libs.okio)
    implementation(libs.okhttp)
    implementation(libs.ktor.http)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.minidns.hla)
    implementation(libs.toml4j)
    implementation(libs.maven.artifact)
    implementation(libs.mmkv)
    implementation(libs.fishnet)
    implementation(libs.process.phoenix)
    implementation(libs.lunarcalendar)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    //Safe
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)
    //Support
    implementation(libs.proxy.client.android)
    //Hilt
    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    //Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
