import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.movtery.petlib"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
}

/*
 * Rust 交叉编译：经 cargo-ndk 构建 cdylib 并注入 jniLibs。
 * 需要本机安装 rustup、cargo-ndk（cargo install cargo-ndk），
 * 以及四个 Android target（rustup target add <triple>）。
 */
val allRustAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android",
)
// 与 -Darch 保持一致：只构建目标 ABI，减少不必要的交叉编译
val rustAbis = when (val arch = System.getProperty("arch", "all")) {
    "arm64" -> allRustAbis.filterKeys { it == "arm64-v8a" }
    "arm" -> allRustAbis.filterKeys { it == "armeabi-v7a" }
    "x86" -> allRustAbis.filterKeys { it == "x86" }
    "x86_64" -> allRustAbis.filterKeys { it == "x86_64" }
    "all" -> allRustAbis
    else -> error("Unknown arch: $arch")
}
val petlibJniLibs = layout.buildDirectory.dir("petlib-jniLibs")
// Same opt-in as the app: a source-identical ARM64 PetLib is retained outside build/,
// so `clean` cannot silently remove a required runtime library from phone builds.
val usePrebuiltJni = providers.gradleProperty("zl2UsePrebuiltJni").orNull?.toBoolean() ?: false
val prebuiltPetlib = project.file("src/prebuiltJniLibs")

// NDK 的 prebuilt 目录名取决于构建主机；原先硬编码 linux-x86_64 会使 Windows 主机误判 clang 不可用
val ndkHostTag: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("win") -> "windows-x86_64"
        arch == "aarch64" || arch == "arm64" -> "linux-aarch64"
        else -> "linux-x86_64"
    }
}

val cargoBuild = tasks.register("cargoBuild") {
    group = "build"
    description = "Cross-compiles the PetLib Rust core (cdylib) for all ABIs via cargo-ndk."
    inputs.dir("rust/src")
    inputs.file("rust/Cargo.toml")
    inputs.file("rust/Cargo.lock")
    outputs.dir(petlibJniLibs)
    doLast {
        fun tool(command: String, args: List<String>): String? = try {
            val process = ProcessBuilder(listOf(command) + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) output.trim() else null
        } catch (_: java.io.IOException) {
            null
        }

        //Gradle 守护进程可能沿用了旧的 PATH，这里优先使用 CARGO_HOME 下的绝对路径
        fun cargoBin(name: String): String = System.getenv("CARGO_HOME")
            ?.let { File(it, "bin/$name") }
            ?.takeIf { it.isFile }
            ?.absolutePath
            ?: name

        val cargo = cargoBin("cargo")

        if (tool(cargo, listOf("--version")) == null) {
            throw GradleException(
                "Rust toolchain is required to build PetLib. Install it from https://rustup.rs and retry."
            )
        }
        if (tool(cargo, listOf("ndk", "--version")) == null) {
            throw GradleException(
                "cargo-ndk is required to build PetLib. Install it with: cargo install cargo-ndk"
            )
        }
        val installedTargets = tool(cargoBin("rustup"), listOf("target", "list", "--installed")) ?: ""
        val missing = rustAbis.values.filter { triple -> !installedTargets.contains(triple) }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing Rust Android targets for PetLib. Install them with: rustup target add ${missing.joinToString(" ")}"
            )
        }

        val outDir = petlibJniLibs.get().asFile.apply { mkdirs() }

        val ndkHome = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("ANDROID_NDK_ROOT")
            ?: throw GradleException(
                "ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) is required to build PetLib."
            )
        val clangExe = if (ndkHostTag.startsWith("windows")) ".exe" else ""
        val clang = File(ndkHome, "toolchains/llvm/prebuilt/$ndkHostTag/bin/clang$clangExe")
        // arm64 Linux 主机上，NDK 自带的 clang 是 x86_64 可执行文件，无法直接运行；
        // 此时回退到 rust-lld + NDK sysroot 链接（见 docs/BUILD.md）。
        val clangUsable = clang.isFile && tool(clang.absolutePath, listOf("--version")) != null

        if (clangUsable) {
            val command = listOf(
                cargo, "ndk",
                "--platform", "26",
                "--output-dir", outDir.absolutePath,
            ) + rustAbis.values.flatMap { listOf("--target", it) } +
                listOf("build", "--release")
            val process = ProcessBuilder(command)
                .directory(project.file("rust"))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            println(output)
            if (exit != 0) {
                throw GradleException("cargo-ndk build failed with exit code $exit.\n$output")
            }
        } else {
            project.buildWithRustLld(ndkHome, outDir, cargo, ::cargoBin)
        }

    }
}

androidComponents {
    onVariants { variant ->
        // cargo-ndk 产物目录（build/petlib-jniLibs/<abi>/libpetlib.so）。
        variant.sources.jniLibs?.addStaticSourceDirectory(
            (if (usePrebuiltJni) prebuiltPetlib else petlibJniLibs.get().asFile.apply { mkdirs() }).absolutePath
        )
    }
}

tasks.named("preBuild") {
    if (usePrebuiltJni) {
        doFirst {
            val missing = rustAbis.keys.filter { !File(prebuiltPetlib, "$it/libpetlib.so").isFile }
            check(missing.isEmpty()) { "Missing prebuilt PetLib ABI(s): $missing. Build Rust normally or supply matching artifacts." }
        }
    } else dependsOn(cargoBuild)
}

/**
 * 在无法执行 NDK clang 的主机（典型：arm64 Linux）上，用 rust-lld + NDK sysroot 交叉链接 PetLib。
 * 需要 rustup 提供的 rust-lld（随 host 工具链安装）与 cargo 的 Android target。
 */
fun Project.buildWithRustLld(
    ndkHome: String,
    outDir: java.io.File,
    cargo: String,
    cargoBin: (String) -> String,
) {
    fun tool(command: String, args: List<String>): String? = try {
        val process = ProcessBuilder(listOf(command) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        if (process.exitValue() == 0) output.trim() else null
    } catch (_: java.io.IOException) {
        null
    }

    val hostTriple = tool(cargoBin("rustc"), listOf("-vV"))
        ?.lineSequence()
        ?.firstOrNull { it.startsWith("host:") }
        ?.removePrefix("host:")?.trim()
        ?: throw GradleException("Unable to detect Rust host triple.")
    val rustSysroot = tool(cargoBin("rustc"), listOf("--print", "sysroot"))
        ?: throw GradleException("Unable to detect Rust sysroot.")
    val rustLld = File(rustSysroot, "lib/rustlib/$hostTriple/bin/rust-lld")
    if (!rustLld.isFile) {
        throw GradleException("rust-lld not found at $rustLld; run: rustup component add rust-src? (rust-lld ships with the host toolchain)")
    }

    val lldDir = File(project.layout.buildDirectory.get().asFile, "rust-lld").apply { mkdirs() }
    val lld = File(lldDir, "ld.lld")
    rustLld.copyTo(lld, overwrite = true)
    lld.setExecutable(true)

    val prebuilt = File(ndkHome, "toolchains/llvm/prebuilt/$ndkHostTag")
    val sysroot = File(prebuilt, "sysroot")
    val clangLibRoot = File(prebuilt, "lib64/clang")
    val clangResource = clangLibRoot.listFiles()?.maxByOrNull { it.name } ?: clangLibRoot

    val archDir = mapOf(
        "aarch64-linux-android" to "aarch64",
        "armv7-linux-androideabi" to "arm",
        "i686-linux-android" to "i686",
        "x86_64-linux-android" to "x86_64",
    )
    // NDK sysroot 的库目录名与 Rust triple 并不完全一致（armv7 使用 arm-linux-androideabi）
    val ndkLibDir = mapOf(
        "aarch64-linux-android" to "aarch64-linux-android",
        "armv7-linux-androideabi" to "arm-linux-androideabi",
        "i686-linux-android" to "i686-linux-android",
        "x86_64-linux-android" to "x86_64-linux-android",
    )

    rustAbis.forEach { (abi, triple) ->
        val unwindLib = File(clangResource, "lib/linux/${archDir[triple]}")
        val libDir = ndkLibDir[triple]
        val rustflags = listOf(
            "-C", "link-arg=--sysroot=$sysroot",
            "-C", "link-arg=-L${sysroot}/usr/lib/$libDir/26",
            "-C", "link-arg=-L${sysroot}/usr/lib/$libDir",
            "-C", "link-arg=-L$unwindLib",
        ).joinToString(" ")

        val process = ProcessBuilder(cargo, "build", "--release", "--target", triple)
            .directory(project.file("rust"))
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment()["CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER"] = lld.absolutePath
                builder.environment()["RUSTFLAGS"] = rustflags
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        println(output)
        if (exit != 0) {
            throw GradleException("rust-lld build for $triple failed with exit code $exit.\n$output")
        }

        val built = project.file("rust/target/$triple/release/libpetlib.so")
        if (!built.isFile) {
            throw GradleException("PetLib output missing for $triple: $built")
        }
        File(outDir, abi).apply { mkdirs() }.resolve("libpetlib.so").also { dest ->
            built.copyTo(dest, overwrite = true)
        }
    }
}
