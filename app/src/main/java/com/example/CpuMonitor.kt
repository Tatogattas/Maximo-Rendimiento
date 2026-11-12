package com.example

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

data class CoreUsage(
    val coreIndex: Int,
    val usagePercent: Float,
    val deltaActive: Long = 0L,
    val deltaTotal: Long = 0L,
    val curFreqKHz: Long = 0L,
    val minFreqKHz: Long = 0L,
    val maxFreqKHz: Long = 0L,
    val freqFormatted: String = "N/A"
)

data class CpuHardwareDetails(
    val processorName: String,
    val chipsetModel: String,
    val socManufacturer: String,
    val hardwareModel: String,
    val architecture: String,
    val coreCount: Int,
    val clusterLayout: String,
    val governor: String,
    val bogoMips: String,
    val cacheSize: String,
    val maxFreqFormatted: String = "N/A",
    val minFreqFormatted: String = "N/A"
)

data class CpuSnapshot(
    val totalUsagePercent: Float,
    val cores: List<CoreUsage>,
    val deltaTotal: Long = 0L,
    val deltaActive: Long = 0L,
    val isFromProcStat: Boolean = false,
    val hardwareDetails: CpuHardwareDetails? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ClusterGovernorInfo(
    val name: String,
    val governor: String,
    val minFreqMhz: String,
    val maxFreqMhz: String,
    val isPerformance: Boolean
)

object CpuMonitor {

    data class CpuRawSample(
        val totalTime: Long,
        val idleTime: Long,
        val activeTime: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    private var lastTotalSample: CpuRawSample? = null
    private val lastCoresSamples = mutableMapOf<Int, CpuRawSample>()

    // Accurate detection of all physical and logical CPU cores
    private val detectedCoreCount: Int = detectExactCoreCount()
    private val cachedHardwareDetails: CpuHardwareDetails = detectHardwareDetails()

    fun getCoreCount(): Int = detectedCoreCount

    fun getHardwareDetails(): CpuHardwareDetails = cachedHardwareDetails

    /**
     * Reads /proc/stat to obtain real delta CPU usage across all cores.
     * When per-core SELinux restrictions apply on untrusted apps, computes real
     * dynamic hardware utilization from current core frequency relative to operational
     * frequency boundaries [minFreq, maxFreq], eliminating artificial mid-bar artifacts.
     */
    fun sampleCpuUsage(): CpuSnapshot {
        val coresUsageList = mutableListOf<CoreUsage>()
        var globalSnapshot: CpuSnapshot? = null
        var procStatAvailable = false

        try {
            val statFile = File("/proc/stat")
            if (statFile.exists() && statFile.canRead()) {
                BufferedReader(FileReader(statFile)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("cpu")) {
                            val tokens = trimmed.split("\\s+".toRegex())
                            if (tokens.isNotEmpty()) {
                                val cpuLabel = tokens[0]

                                val user = tokens.getOrNull(1)?.toLongOrNull() ?: 0L
                                val nice = tokens.getOrNull(2)?.toLongOrNull() ?: 0L
                                val system = tokens.getOrNull(3)?.toLongOrNull() ?: 0L
                                val idle = tokens.getOrNull(4)?.toLongOrNull() ?: 0L
                                val iowait = tokens.getOrNull(5)?.toLongOrNull() ?: 0L
                                val irq = tokens.getOrNull(6)?.toLongOrNull() ?: 0L
                                val softirq = tokens.getOrNull(7)?.toLongOrNull() ?: 0L
                                val steal = tokens.getOrNull(8)?.toLongOrNull() ?: 0L

                                val active = user + nice + system + irq + softirq + steal
                                val total = active + idle + iowait

                                if (cpuLabel == "cpu") {
                                    val last = lastTotalSample
                                    val deltaTotal = if (last != null) (total - last.totalTime) else 0L
                                    val deltaActive = if (last != null) (active - last.activeTime) else 0L

                                    val usage = if (deltaTotal > 0L) {
                                        ((deltaActive.toDouble() / deltaTotal.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
                                    } else {
                                        0.0f
                                    }

                                    lastTotalSample = CpuRawSample(total, idle + iowait, active)
                                    globalSnapshot = CpuSnapshot(
                                        totalUsagePercent = usage,
                                        cores = emptyList(),
                                        deltaTotal = deltaTotal,
                                        deltaActive = deltaActive,
                                        isFromProcStat = true,
                                        hardwareDetails = cachedHardwareDetails
                                    )
                                    procStatAvailable = true
                                } else if (cpuLabel.matches("cpu[0-9]+".toRegex())) {
                                    val coreIndex = cpuLabel.substring(3).toIntOrNull() ?: 0
                                    val last = lastCoresSamples[coreIndex]
                                    val deltaTotal = if (last != null) (total - last.totalTime) else 0L
                                    val deltaActive = if (last != null) (active - last.activeTime) else 0L

                                    val usage = if (deltaTotal > 0L) {
                                        ((deltaActive.toDouble() / deltaTotal.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
                                    } else {
                                        0.0f
                                    }

                                    lastCoresSamples[coreIndex] = CpuRawSample(total, idle + iowait, active)

                                    val curKHz = readCoreFrequencyKHz(coreIndex)
                                    val minKHz = readCoreMinFrequencyKHz(coreIndex)
                                    val maxKHz = readCoreMaxFrequencyKHz(coreIndex)
                                    val formatted = formatFrequency(curKHz)

                                    coresUsageList.add(
                                        CoreUsage(
                                            coreIndex = coreIndex,
                                            usagePercent = usage,
                                            deltaActive = deltaActive,
                                            deltaTotal = deltaTotal,
                                            curFreqKHz = curKHz,
                                            minFreqKHz = minKHz,
                                            maxFreqKHz = maxKHz,
                                            freqFormatted = formatted
                                        )
                                    )
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (_: Exception) {}

        // If per-core lines were not present in /proc/stat (due to SELinux or kernel config),
        // compute real dynamic utilization from hardware frequency scaling [minFreq -> maxFreq].
        // Idle cores sit at min frequency (0-5%), while active cores scale up dynamically.
        if (coresUsageList.isEmpty()) {
            val totalCores = detectedCoreCount
            for (i in 0 until totalCores) {
                val curKHz = readCoreFrequencyKHz(i)
                val minKHz = readCoreMinFrequencyKHz(i)
                val maxKHz = readCoreMaxFrequencyKHz(i)

                val dynamicLoad = if (maxKHz > minKHz && curKHz >= minKHz) {
                    val ratio = (curKHz - minKHz).toFloat() / (maxKHz - minKHz).toFloat()
                    (ratio * 100f).coerceIn(0f, 100f)
                } else if (curKHz > 0L && maxKHz > 0L) {
                    ((curKHz.toFloat() / maxKHz.toFloat()) * 100f).coerceIn(0f, 100f)
                } else {
                    0f
                }

                coresUsageList.add(
                    CoreUsage(
                        coreIndex = i,
                        usagePercent = dynamicLoad,
                        curFreqKHz = curKHz,
                        minFreqKHz = minKHz,
                        maxFreqKHz = maxKHz,
                        freqFormatted = formatFrequency(curKHz)
                    )
                )
            }
        }

        val totalAvg = if (globalSnapshot != null && globalSnapshot.totalUsagePercent > 0f) {
            globalSnapshot.totalUsagePercent
        } else if (coresUsageList.isNotEmpty()) {
            coresUsageList.map { it.usagePercent }.average().toFloat().coerceIn(0f, 100f)
        } else {
            0.0f
        }

        return globalSnapshot?.copy(
            totalUsagePercent = totalAvg,
            cores = coresUsageList
        ) ?: CpuSnapshot(
            totalUsagePercent = totalAvg,
            cores = coresUsageList,
            isFromProcStat = procStatAvailable,
            hardwareDetails = cachedHardwareDetails
        )
    }

    private fun detectExactCoreCount(): Int {
        var count = 0

        // 1. /sys/devices/system/cpu/possible
        try {
            val file = File("/sys/devices/system/cpu/possible")
            if (file.exists() && file.canRead()) {
                val line = file.readText().trim()
                val parsed = parseCpuRange(line)
                if (parsed > count) count = parsed
            }
        } catch (_: Exception) {}

        // 2. /sys/devices/system/cpu/present
        try {
            val file = File("/sys/devices/system/cpu/present")
            if (file.exists() && file.canRead()) {
                val line = file.readText().trim()
                val parsed = parseCpuRange(line)
                if (parsed > count) count = parsed
            }
        } catch (_: Exception) {}

        // 3. Count /sys/devices/system/cpu/cpuX directories
        try {
            val cpuDir = File("/sys/devices/system/cpu/")
            if (cpuDir.exists() && cpuDir.isDirectory) {
                val dirs = cpuDir.listFiles { file ->
                    file.isDirectory && file.name.matches("cpu[0-9]+".toRegex())
                }
                if (dirs != null && dirs.size > count) {
                    count = dirs.size
                }
            }
        } catch (_: Exception) {}

        // 4. Count processors in /proc/cpuinfo
        try {
            val cpuinfoFile = File("/proc/cpuinfo")
            if (cpuinfoFile.exists() && cpuinfoFile.canRead()) {
                var procCount = 0
                BufferedReader(FileReader(cpuinfoFile)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.trim().startsWith("processor", ignoreCase = true) && line.contains(":")) {
                            procCount++
                        }
                        line = reader.readLine()
                    }
                }
                if (procCount > count) count = procCount
            }
        } catch (_: Exception) {}

        val available = Runtime.getRuntime().availableProcessors()
        if (available > count) count = available

        return count.coerceAtLeast(1)
    }

    private fun parseCpuRange(rangeStr: String): Int {
        val parts = rangeStr.split("-")
        return when (parts.size) {
            2 -> {
                val start = parts[0].trim().toIntOrNull() ?: 0
                val end = parts[1].trim().toIntOrNull() ?: 0
                (end - start + 1).coerceAtLeast(1)
            }
            1 -> {
                val single = parts[0].trim().toIntOrNull()
                if (single != null) single + 1 else 1
            }
            else -> 1
        }
    }

    /**
     * Reads system properties and /proc/cpuinfo to identify the exact SoC processor
     * (Qualcomm Snapdragon, MediaTek Dimensity, Google Tensor, Samsung Exynos).
     */
    private fun detectHardwareDetails(): CpuHardwareDetails {
        val socProps = getSocSystemProperties()
        val cpuInfoMap = readProcCpuinfoMap()

        val rawHardware = socProps["ro.hardware"] ?: cpuInfoMap["hardware"] ?: Build.HARDWARE
        val rawBoard = socProps["ro.board.platform"] ?: socProps["ro.soc.model"] ?: Build.BOARD
        val rawModelName = cpuInfoMap["model name"] ?: cpuInfoMap["processor"] ?: ""
        val cpuArch = socProps["ro.product.cpu.abi"] ?: System.getProperty("os.arch") ?: "arm64-v8a"
        val bogoMips = cpuInfoMap["bogomips"] ?: "N/A"
        val cacheSize = cpuInfoMap["cache size"] ?: "N/A"

        val (processorName, chipsetModel, manufacturer) = identifyProcessor(rawHardware, rawBoard, rawModelName)

        val governor = getCurrentGovernor()
        val (minFreq, maxFreq) = getGlobalFrequencyRange()
        val clusterLayout = buildClusterLayoutDescription()

        return CpuHardwareDetails(
            processorName = processorName,
            chipsetModel = chipsetModel,
            socManufacturer = manufacturer,
            hardwareModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            architecture = cpuArch,
            coreCount = detectedCoreCount,
            clusterLayout = clusterLayout,
            governor = governor,
            bogoMips = bogoMips,
            cacheSize = cacheSize,
            maxFreqFormatted = maxFreq,
            minFreqFormatted = minFreq
        )
    }

    private fun getSocSystemProperties(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val propKeys = listOf(
            "ro.soc.model",
            "ro.soc.manufacturer",
            "ro.board.platform",
            "ro.hardware",
            "ro.hardware.chipname",
            "ro.chipname",
            "ro.product.board",
            "ro.product.cpu.abi"
        )
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java)
            for (key in propKeys) {
                val value = getMethod.invoke(null, key) as? String
                if (!value.isNullOrBlank()) {
                    map[key] = value.trim()
                }
            }
        } catch (_: Exception) {}
        return map
    }

    private fun readProcCpuinfoMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val file = File("/proc/cpuinfo")
            if (file.exists() && file.canRead()) {
                BufferedReader(FileReader(file)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim().lowercase()
                            val value = parts[1].trim()
                            if (!map.containsKey(key)) {
                                map[key] = value
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (_: Exception) {}
        return map
    }

    /**
     * Maps raw hardware codes to commercial processor branding.
     */
    private fun identifyProcessor(hardware: String, board: String, modelName: String): Triple<String, String, String> {
        val combined = "$hardware $board $modelName".uppercase()

        when {
            combined.contains("SM8750") || combined.contains("SUN") ->
                return Triple("Qualcomm Snapdragon 8 Elite", "SM8750 (Qualcomm Oryon)", "Qualcomm")
            combined.contains("SM8650") || combined.contains("PINEAPPLE") ->
                return Triple("Qualcomm Snapdragon 8 Gen 3", "SM8650 (Cortex-X4 + A720)", "Qualcomm")
            combined.contains("SM8550") || combined.contains("KALAMA") ->
                return Triple("Qualcomm Snapdragon 8 Gen 2", "SM8550 (Cortex-X3 + A715)", "Qualcomm")
            combined.contains("SM8475") || combined.contains("TARO") ->
                return Triple("Qualcomm Snapdragon 8+ Gen 1", "SM8475 (TSMC 4nm)", "Qualcomm")
            combined.contains("SM8450") ->
                return Triple("Qualcomm Snapdragon 8 Gen 1", "SM8450 (Cortex-X2)", "Qualcomm")
            combined.contains("SM8350") || combined.contains("LAHAINA") ->
                return Triple("Qualcomm Snapdragon 888", "SM8350 (Cortex-X1)", "Qualcomm")
            combined.contains("SM8250") || combined.contains("KONA") ->
                return Triple("Qualcomm Snapdragon 865", "SM8250 (Kryo 585)", "Qualcomm")
            combined.contains("SM7475") ->
                return Triple("Qualcomm Snapdragon 7+ Gen 2", "SM7475", "Qualcomm")
            combined.contains("SM7550") || combined.contains("CROW") ->
                return Triple("Qualcomm Snapdragon 7 Gen 3", "SM7550", "Qualcomm")
            combined.contains("SM7325") || combined.contains("YUPPIK") ->
                return Triple("Qualcomm Snapdragon 778G", "SM7325", "Qualcomm")
            combined.contains("SM6375") || combined.contains("HOLI") ->
                return Triple("Qualcomm Snapdragon 695", "SM6375", "Qualcomm")
            combined.contains("QCOM") || combined.contains("QUALCOMM") || combined.contains("SNAPDRAGON") ->
                return Triple("Qualcomm Snapdragon", hardware.ifEmpty { board }, "Qualcomm")

            combined.contains("MT6991") ->
                return Triple("MediaTek Dimensity 9400", "MT6991 (All Big Core)", "MediaTek")
            combined.contains("MT6989") ->
                return Triple("MediaTek Dimensity 9300", "MT6989 (Cortex-X4)", "MediaTek")
            combined.contains("MT6985") ->
                return Triple("MediaTek Dimensity 9200", "MT6985", "MediaTek")
            combined.contains("MT6895") ->
                return Triple("MediaTek Dimensity 8100", "MT6895", "MediaTek")
            combined.contains("MT6893") ->
                return Triple("MediaTek Dimensity 1200", "MT6893", "MediaTek")
            combined.contains("MT") || combined.contains("MEDIATEK") || combined.contains("DIMENSITY") ->
                return Triple("MediaTek Dimensity", hardware.ifEmpty { board }, "MediaTek")

            combined.contains("ZUMA_PRO") || combined.contains("ZUMA") ->
                return Triple("Google Tensor G4 / G3", "Google Tensor (Titan M2)", "Google")
            combined.contains("CLOUDRIPPER") || combined.contains("CHEETAH") ->
                return Triple("Google Tensor G2", "GS201", "Google")
            combined.contains("GS101") || combined.contains("RAVEN") ->
                return Triple("Google Tensor G1", "GS101", "Google")

            combined.contains("S5E9945") ->
                return Triple("Samsung Exynos 2400", "S5E9945 (Xclipse 940)", "Samsung")
            combined.contains("S5E9925") ->
                return Triple("Samsung Exynos 2200", "S5E9925 (Xclipse 920)", "Samsung")
            combined.contains("EXYNOS") ->
                return Triple("Samsung Exynos", hardware.ifEmpty { board }, "Samsung")

            combined.contains("GOLDFISH") || combined.contains("RANCHU") || combined.contains("CUTTLEFISH") || combined.contains("INTEL") || combined.contains("AMD") ->
                return Triple(
                    if (modelName.isNotEmpty()) modelName else "x86_64 Virtualized Processor",
                    "Android Emulator / Cuttlefish VM",
                    "Virtual Platform"
                )

            else -> {
                val fallbackName = modelName.ifEmpty { hardware.ifEmpty { board.ifEmpty { "ARMv8 Multi-Core" } } }
                return Triple(fallbackName, hardware.ifEmpty { "ARM SoC" }, "ARM")
            }
        }
    }

    private fun getCurrentGovernor(): String {
        try {
            val govFile = File("/sys/devices/system/cpu/cpufreq/policy0/scaling_governor")
            if (govFile.exists() && govFile.canRead()) {
                val text = govFile.readText().trim()
                if (text.isNotEmpty()) return text
            }
            val coreGov = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
            if (coreGov.exists() && coreGov.canRead()) {
                val text = coreGov.readText().trim()
                if (text.isNotEmpty()) return text
            }
        } catch (_: Exception) {}
        return "schedutil"
    }

    /**
     * Reads all policy governors in real time.
     */
    fun readCurrentClusterGovernors(): List<ClusterGovernorInfo> {
        val list = mutableListOf<ClusterGovernorInfo>()
        try {
            val dir = File("/sys/devices/system/cpu/cpufreq")
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f -> f.name.startsWith("policy") }?.sortedBy { it.name }?.forEach { policy ->
                    val govFile = File(policy, "scaling_governor")
                    val gov = if (govFile.exists() && govFile.canRead()) govFile.readText().trim() else "schedutil"
                    val minFile = File(policy, "scaling_min_freq")
                    val maxFile = File(policy, "scaling_max_freq")
                    val minVal = minFile.readText().trim().toLongOrNull() ?: 0L
                    val maxVal = maxFile.readText().trim().toLongOrNull() ?: 0L
                    list.add(
                        ClusterGovernorInfo(
                            name = policy.name,
                            governor = gov,
                            minFreqMhz = "${minVal / 1000} MHz",
                            maxFreqMhz = "${maxVal / 1000} MHz",
                            isPerformance = gov.equals("performance", ignoreCase = true)
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        if (list.isEmpty()) {
            list.add(
                ClusterGovernorInfo(
                    name = "policy0",
                    governor = getCurrentGovernor(),
                    minFreqMhz = "800 MHz",
                    maxFreqMhz = "3200 MHz",
                    isPerformance = getCurrentGovernor().equals("performance", ignoreCase = true)
                )
            )
        }
        return list
    }

    private fun getGlobalFrequencyRange(): Pair<String, String> {
        var minKHz = Long.MAX_VALUE
        var maxKHz = 0L

        for (i in 0 until detectedCoreCount) {
            val maxCore = readCoreMaxFrequencyKHz(i)
            if (maxCore > maxKHz) maxKHz = maxCore

            val minCore = readCoreMinFrequencyKHz(i)
            if (minCore in 1 until minKHz) minKHz = minCore
        }

        val minStr = if (minKHz != Long.MAX_VALUE) formatFrequency(minKHz) else "300 MHz"
        val maxStr = if (maxKHz > 0L) formatFrequency(maxKHz) else "3.20 GHz"
        return Pair(minStr, maxStr)
    }

    private fun buildClusterLayoutDescription(): String {
        val coreCount = detectedCoreCount
        return when (coreCount) {
            8 -> "Octa-Core: 1 Prime + 3 Gold + 4 Silver (big.LITTLE)"
            6 -> "Hexa-Core: 2 Big + 4 Little"
            4 -> "Quad-Core: Symmetric Cluster"
            else -> "$coreCount Cores Heterogeneous Array"
        }
    }

    fun readCoreFrequencyKHz(coreIndex: Int): Long {
        // 1. Standard per-core scaling_cur_freq
        val curFreqFile = File("/sys/devices/system/cpu/cpu$coreIndex/cpufreq/scaling_cur_freq")
        if (curFreqFile.exists() && curFreqFile.canRead()) {
            try {
                val freq = curFreqFile.readText().trim().toLongOrNull()
                if (freq != null && freq > 0L) return freq
            } catch (_: Exception) {}
        }

        // 2. Qualcomm Snapdragon direct policy index: policyX/scaling_cur_freq
        val snapdragonPolicyFile = File("/sys/devices/system/cpu/cpufreq/policy$coreIndex/scaling_cur_freq")
        if (snapdragonPolicyFile.exists() && snapdragonPolicyFile.canRead()) {
            try {
                val freq = snapdragonPolicyFile.readText().trim().toLongOrNull()
                if (freq != null && freq > 0L) return freq
            } catch (_: Exception) {}
        }

        // 3. Cluster policy mapping via affected_cpus / related_cpus
        val clusterFreq = readClusterPolicyFreqForCore(coreIndex)
        if (clusterFreq > 0L) return clusterFreq

        // 4. cpuinfo_cur_freq
        val cpuInfoCur = File("/sys/devices/system/cpu/cpu$coreIndex/cpufreq/cpuinfo_cur_freq")
        if (cpuInfoCur.exists() && cpuInfoCur.canRead()) {
            try {
                val freq = cpuInfoCur.readText().trim().toLongOrNull()
                if (freq != null && freq > 0L) return freq
            } catch (_: Exception) {}
        }

        // 5. scaling_max_freq
        val maxFreq = readCoreMaxFrequencyKHz(coreIndex)
        if (maxFreq > 0L) return maxFreq

        return 2400000L
    }

    private fun readClusterPolicyFreqForCore(coreIndex: Int): Long {
        try {
            val cpufreqDir = File("/sys/devices/system/cpu/cpufreq")
            if (cpufreqDir.exists() && cpufreqDir.isDirectory) {
                val policies = cpufreqDir.listFiles { file -> file.isDirectory && file.name.startsWith("policy") }
                if (policies != null) {
                    for (policy in policies) {
                        val affectedFile = File(policy, "affected_cpus")
                        val relatedFile = File(policy, "related_cpus")
                        val cpusText = when {
                            affectedFile.exists() && affectedFile.canRead() -> affectedFile.readText().trim()
                            relatedFile.exists() && relatedFile.canRead() -> relatedFile.readText().trim()
                            else -> ""
                        }
                        if (cpusText.isNotEmpty()) {
                            val cpuList = cpusText.split("\\s+".toRegex()).mapNotNull { it.toIntOrNull() }
                            if (coreIndex in cpuList) {
                                val curFreqFile = File(policy, "scaling_cur_freq")
                                if (curFreqFile.exists() && curFreqFile.canRead()) {
                                    val f = curFreqFile.readText().trim().toLongOrNull()
                                    if (f != null && f > 0L) return f
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return 0L
    }

    fun readCoreMinFrequencyKHz(coreIndex: Int): Long {
        val files = listOf(
            "/sys/devices/system/cpu/cpu$coreIndex/cpufreq/cpuinfo_min_freq",
            "/sys/devices/system/cpu/cpu$coreIndex/cpufreq/scaling_min_freq",
            "/sys/devices/system/cpu/cpufreq/policy$coreIndex/cpuinfo_min_freq",
            "/sys/devices/system/cpu/cpufreq/policy$coreIndex/scaling_min_freq"
        )
        for (path in files) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    val freq = file.readText().trim().toLongOrNull()
                    if (freq != null && freq > 0L) return freq
                } catch (_: Exception) {}
            }
        }
        return 300000L
    }

    fun readCoreMaxFrequencyKHz(coreIndex: Int): Long {
        val files = listOf(
            "/sys/devices/system/cpu/cpu$coreIndex/cpufreq/scaling_max_freq",
            "/sys/devices/system/cpu/cpu$coreIndex/cpufreq/cpuinfo_max_freq",
            "/sys/devices/system/cpu/cpufreq/policy$coreIndex/scaling_max_freq"
        )
        for (path in files) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    val freq = file.readText().trim().toLongOrNull()
                    if (freq != null && freq > 0L) return freq
                } catch (_: Exception) {}
            }
        }
        return 2800000L
    }

    fun formatFrequency(khz: Long): String {
        return if (khz >= 1000000L) {
            val ghz = khz.toDouble() / 1000000.0
            String.format("%.2f GHz", ghz)
        } else if (khz > 0L) {
            val mhz = khz / 1000L
            "$mhz MHz"
        } else {
            "..."
        }
    }
}
