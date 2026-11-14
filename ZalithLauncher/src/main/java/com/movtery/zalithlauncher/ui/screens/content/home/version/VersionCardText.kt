package com.movtery.zalithlauncher.ui.screens.content.home.version

import com.movtery.zalithlauncher.game.version.installed.Version

/** Ignore only separators; never discard a genuinely user-supplied instance name. */
internal fun normalizedVersionLabel(text: String): String =
    text.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)

internal fun versionCardTitle(name: String, details: String?): String =
    if (!details.isNullOrBlank() && normalizedVersionLabel(name) == normalizedVersionLabel(details))
        "Minecraft" else name

internal fun Version.homeCardTitle(): String =
    versionCardTitle(getVersionName(), getVersionInfo()?.getInfoString())

internal fun Version.homeCardDescription(): String? {
    if (!isSummaryValid()) return null // getVersionSummary otherwise repeats the loader details.
    val description = getVersionSummary()
    val normalized = normalizedVersionLabel(description)
    return description.takeUnless {
        normalized == normalizedVersionLabel(getVersionName()) ||
            normalized == normalizedVersionLabel(getVersionInfo()?.getInfoString().orEmpty())
    }
}
