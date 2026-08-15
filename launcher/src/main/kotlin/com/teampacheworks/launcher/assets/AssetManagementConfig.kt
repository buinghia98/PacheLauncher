package com.teampacheworks.launcher.assets

import android.content.Context
import com.teampacheworks.launcher.LauncherContract
import java.io.File

/** One optional quality tier stored alongside the common game data. */
data class AssetTierConfig(
    val value: String,
    val label: String,
    val importFolderName: String,
    val relativePaths: List<String>
) {
    init {
        require(relativePaths.isNotEmpty()) { "Asset tier '$value' has no paths" }
    }
}

/**
 * Host-owned description of a Common + two-tier asset installation.
 *
 * [rootDirectory] is the directory whose children mirror the import package. The selected SAF
 * folder may contain `Common`, [highTier].[AssetTierConfig.importFolderName], and
 * [lowTier].[AssetTierConfig.importFolderName]; every component is merged into [rootDirectory].
 */
data class AssetManagementConfig(
    val gameId: String,
    val rootDirectory: (Context) -> File?,
    val commonImportFolderName: String = "Common",
    val commonRequiredPaths: List<String>,
    val highTier: AssetTierConfig,
    val lowTier: AssetTierConfig,
    val optionKey: String = "asset_quality",
    val defaultTierValue: String = lowTier.value
) {
    init {
        require(commonRequiredPaths.isNotEmpty()) { "Common assets need at least one required path" }
        require(highTier.value != lowTier.value) { "High and low asset values must differ" }
        require(defaultTierValue == highTier.value || defaultTierValue == lowTier.value) {
            "defaultTierValue must select highTier or lowTier"
        }
    }

    val prefsKey: String get() = "opt_$optionKey"
    val extraName: String get() = LauncherContract.extraNameFor(optionKey)
    fun tier(value: String): AssetTierConfig? = when (value) {
        highTier.value -> highTier
        lowTier.value -> lowTier
        else -> null
    }
}

data class AssetComponentState(val installed: Boolean, val bytes: Long, val files: Long)

data class AssetInstallState(
    val common: AssetComponentState,
    val high: AssetComponentState,
    val low: AssetComponentState
) {
    fun tierInstalled(value: String, config: AssetManagementConfig): Boolean = when (value) {
        config.highTier.value -> high.installed
        config.lowTier.value -> low.installed
        else -> false
    }
}
