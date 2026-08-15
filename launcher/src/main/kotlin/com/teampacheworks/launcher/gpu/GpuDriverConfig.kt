package com.teampacheworks.launcher.gpu

import android.content.Context
import java.io.File

data class GpuDriverConfig(
    val bundledAssetPath: String? = null,
    val bundledLabel: String = "Bundled driver",
    val storageDirectory: (Context) -> File = { File(it.filesDir, "gpu-drivers") },
    val prefsKey: String = "gpu_driver",
    val maxFiles: Int = 512,
    val maxUncompressedBytes: Long = 192L * 1024L * 1024L
) {
    init { require(maxFiles in 1..4096); require(maxUncompressedBytes > 0) }
}

data class GpuDriver(
    val id: String,
    val label: String,
    val directory: File,
    val libraryName: String,
    val version: String?,
    val vendor: String?,
    val description: String?,
    val imported: Boolean
) {
    val selectionValue: String get() = if (imported) "custom:$id" else "bundled"
    val libraryFile: File get() = File(directory, libraryName)
}
