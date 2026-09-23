pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Build guard, opt-in. A machine that names a build host in ~/.config/pygmalion/build-host has
// said "builds happen elsewhere" — usually because something else owns this machine's memory.
// On such a machine, refuse to build without real headroom and point at tools/ship.sh, which
// hands the build off by itself. Without that file (a normal dev box, CI) nothing here fires.
// Override knowingly: PYG_BUILD_HERE=1. Threshold: PYG_BUILD_MIN_AVAIL_GIB (default 24).
run {
    val hostFile = File(
        System.getenv("PYG_BUILD_HOST_FILE")
            ?: "${System.getProperty("user.home")}/.config/pygmalion/build-host"
    )
    if (!hostFile.isFile || System.getenv("PYG_BUILD_HERE") == "1") return@run
    val availKb = File("/proc/meminfo").takeIf { it.canRead() }?.useLines { lines ->
        lines.firstOrNull { it.startsWith("MemAvailable:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
    } ?: return@run
    val minGib = System.getenv("PYG_BUILD_MIN_AVAIL_GIB")?.toLongOrNull() ?: 24L
    val availGib = availKb / 1048576
    if (availGib < minGib) {
        throw GradleException(
            "Refusing to build here: $availGib GiB available, need $minGib. This machine delegates " +
                "builds to ${hostFile.readText().trim()} ($hostFile). Run tools/ship.sh instead. " +
                "Override only if you know the memory is free: PYG_BUILD_HERE=1."
        )
    }
}

rootProject.name = "Pygmalion"
include(":app")
include(":core")
