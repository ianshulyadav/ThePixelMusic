package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.addons.AddonCheckReport
import com.unshoo.pixelmusic.data.addons.AddonDiscoverResult
import com.unshoo.pixelmusic.data.addons.AddonHealthTracker
import com.unshoo.pixelmusic.data.addons.AddonManager
import com.unshoo.pixelmusic.data.addons.CatalogAddonEntry
import com.unshoo.pixelmusic.data.addons.InstalledAddon
import com.unshoo.pixelmusic.data.addons.MonochromeInstancePool
import com.unshoo.pixelmusic.data.addons.UnifiedSourceEntry
import com.unshoo.pixelmusic.data.addons.UnifiedSourceId
import com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver
import com.unshoo.pixelmusic.data.lossless.constants.AddonsEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.AddonsFastConcurrentKey
import com.unshoo.pixelmusic.data.lossless.constants.AddonsTimeoutMsKey
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import com.unshoo.pixelmusic.data.lossless.constants.LosslessStreamingEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.MonochromeInstancesKey
import com.unshoo.pixelmusic.data.preferences.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class AddonsUiState(
    val addonsEnabled: Boolean = true,
    val losslessSourcesMasterEnabled: Boolean = false,
    val fastConcurrent: Boolean = true,
    val perAddonTimeoutMs: Long = AddonManager.DEFAULT_PER_ADDON_TIMEOUT_MS,
    val installedAddons: List<InstalledAddon> = emptyList(),
    val unifiedChain: List<UnifiedSourceEntry> = emptyList(),
    val monochromeCustomInstances: String = "",
    val monochromeDefaultCount: Int = MonochromeInstancePool.DEFAULT_INSTANCES.size,
)

@HiltViewModel
class AddonsSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val dataStore = context.dataStore

    private val _busyTags = MutableStateFlow<Set<String>>(emptySet())
    val busyTags: StateFlow<Set<String>> = _busyTags.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _catalogDialog = MutableStateFlow<AddonDiscoverResult.Catalog?>(null)
    val catalogDialog: StateFlow<AddonDiscoverResult.Catalog?> = _catalogDialog.asStateFlow()

    val checkReports: StateFlow<Map<String, AddonCheckReport>> = AddonManager.checkReports

    init {
        AddonManager.initialize(context)
    }

    val uiState: StateFlow<AddonsUiState> = combine(
        dataStore.data,
        AddonManager.installedAddons,
    ) { prefs: Preferences, addons: List<InstalledAddon> ->
        val orderIds = AddonManager.readUnifiedOrder(prefs, addons)
        val addonMap = addons.associateBy { it.id }
        val losslessMaster = prefs[LosslessStreamingEnabledKey] ?: false
        val addonsMaster = prefs[AddonsEnabledKey] ?: true

        val chainEntries = orderIds.mapNotNull { sourceId ->
            if (UnifiedSourceId.isAddon(sourceId)) {
                val id = UnifiedSourceId.addonIdOrNull(sourceId) ?: return@mapNotNull null
                val addon = addonMap[id] ?: return@mapNotNull null
                val health = AddonHealthTracker.stateOf(sourceId)
                val healthLabel = when {
                    health.inCooldown -> "Cooling down"
                    health.ewmaLatencyMs > 0 -> "${health.ewmaLatencyMs} ms"
                    else -> null
                }
                UnifiedSourceEntry(
                    sourceId = sourceId,
                    title = addon.name,
                    subtitle = buildString {
                        append(addon.protocol.displayLabel)
                        if (addon.version.isNotBlank()) append(" · v${addon.version}")
                        if (!addon.allowDownloads) append(" · Stream-only")
                        if (healthLabel != null) append(" · $healthLabel")
                    },
                    badge = addon.protocol.badge,
                    isBuiltIn = false,
                    enabled = addonsMaster && addon.enabled,
                    addon = addon,
                    builtInType = null,
                )
            } else {
                val type = UnifiedSourceId.builtInTypeOrNull(sourceId) ?: return@mapNotNull null
                val individualEnabled = AddonManager.isBuiltInEnabled(type, prefs)
                val requiresLosslessMaster = type in setOf(
                    AudioSourceType.MONOCHROME,
                    AudioSourceType.TIDAL,
                    AudioSourceType.QOBUZ,
                    AudioSourceType.DEEZER,
                    AudioSourceType.APPLE,
                )
                val effectiveEnabled = if (requiresLosslessMaster) {
                    losslessMaster && individualEnabled
                } else {
                    individualEnabled
                }
                UnifiedSourceEntry(
                    sourceId = sourceId,
                    title = UnifiedSourceId.builtInTitle(type),
                    subtitle = buildString {
                        append(UnifiedSourceId.builtInSubtitle(type))
                        if (requiresLosslessMaster && !losslessMaster) {
                            append(" (Lossless Sources switch off)")
                        }
                    },
                    badge = "BUILT-IN",
                    isBuiltIn = true,
                    enabled = effectiveEnabled,
                    addon = null,
                    builtInType = type,
                )
            }
        }

        AddonsUiState(
            addonsEnabled = addonsMaster,
            losslessSourcesMasterEnabled = losslessMaster,
            fastConcurrent = prefs[AddonsFastConcurrentKey] ?: true,
            perAddonTimeoutMs = prefs[AddonsTimeoutMsKey] ?: AddonManager.DEFAULT_PER_ADDON_TIMEOUT_MS,
            installedAddons = addons,
            unifiedChain = chainEntries,
            monochromeCustomInstances = prefs[MonochromeInstancesKey].orEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AddonsUiState(),
    )

    fun consumeToast() {
        _toastMessage.value = null
    }

    fun dismissCatalogDialog() {
        _catalogDialog.value = null
    }

    private fun runBusy(tag: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _busyTags.update { it + tag }
            try {
                block()
            } catch (e: Throwable) {
                Timber.tag("AddonsSettings").w(e, "Operation %s failed", tag)
                _toastMessage.value = e.message ?: "Operation failed"
            } finally {
                _busyTags.update { it - tag }
            }
        }
    }

    fun setAddonsEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { it[AddonsEnabledKey] = enabled }
            LosslessStreamResolver.clearAll()
        }
    }

    fun setFastConcurrent(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { it[AddonsFastConcurrentKey] = enabled }
        }
    }

    fun setPerAddonTimeoutMs(timeoutMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { it[AddonsTimeoutMsKey] = timeoutMs.coerceIn(2_000L, 20_000L) }
        }
    }

    fun installFromUrl(rawUrl: String) = runBusy("install") {
        val result = AddonManager.discoverOrInstallUrl(context, rawUrl)
        when (result) {
            is AddonDiscoverResult.Installed -> {
                LosslessStreamResolver.clearAll()
                _toastMessage.value = "Installed ${result.addon.name} (${result.addon.protocol.displayLabel})"
            }
            is AddonDiscoverResult.Catalog -> {
                _catalogDialog.value = result
            }
        }
    }

    fun installCatalogItem(entry: CatalogAddonEntry, indexUrl: String) = runBusy("catalog_${entry.id}") {
        val installed = AddonManager.installCatalogEntry(context, entry, indexUrl)
        LosslessStreamResolver.clearAll()
        _toastMessage.value = "Installed ${installed.name}"
    }

    fun installAllCatalogItems(entries: List<CatalogAddonEntry>, indexUrl: String) = runBusy("catalog_all") {
        var count = 0
        for (entry in entries) {
            runCatching {
                AddonManager.installCatalogEntry(context, entry, indexUrl)
                count++
            }
        }
        _catalogDialog.value = null
        LosslessStreamResolver.clearAll()
        _toastMessage.value = "Installed $count module(s)"
    }

    fun importFromFileUri(uri: Uri, fileName: String) = runBusy("import") {
        val result = AddonManager.importFromUri(context, uri, fileName)
        when (result) {
            is AddonDiscoverResult.Installed -> {
                LosslessStreamResolver.clearAll()
                _toastMessage.value = "Imported ${result.addon.name} (${result.addon.protocol.displayLabel})"
            }
            is AddonDiscoverResult.Catalog -> {
                _catalogDialog.value = result
            }
        }
    }

    fun setAddonEnabled(addonId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            AddonManager.setAddonEnabled(context, addonId, enabled)
            LosslessStreamResolver.clearAll()
        }
    }

    fun toggleChainEntry(entry: UnifiedSourceEntry, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (entry.isAddon && entry.addon != null) {
                AddonManager.setAddonEnabled(context, entry.addon.id, enabled)
            } else if (entry.builtInType != null) {
                if (enabled && entry.builtInType in setOf(
                        AudioSourceType.MONOCHROME,
                        AudioSourceType.TIDAL,
                        AudioSourceType.QOBUZ,
                        AudioSourceType.DEEZER,
                        AudioSourceType.APPLE,
                    )
                ) {
                    dataStore.edit { it[LosslessStreamingEnabledKey] = true }
                }
                AddonManager.setBuiltInEnabled(context, entry.builtInType, enabled)
            }
            LosslessStreamResolver.clearAll()
        }
    }

    fun updateAddonSetting(
        addonId: String,
        key: String,
        value: String,
        forMobileNetwork: Boolean = false,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            AddonManager.updateAddonSetting(context, addonId, key, value, forMobileNetwork)
            LosslessStreamResolver.clearAll()
        }
    }

    fun testAddon(addonId: String) = runBusy("test_$addonId") {
        val report = AddonManager.checkAddon(context, addonId)
        _toastMessage.value = report.summary
    }

    fun refreshAddon(addonId: String) = runBusy("refresh_$addonId") {
        val updated = AddonManager.refreshAddon(context, addonId)
        LosslessStreamResolver.clearAll()
        _toastMessage.value = "Updated ${updated.name} (v${updated.version})"
    }

    fun removeAddon(addonId: String) = runBusy("remove_$addonId") {
        val name = AddonManager.addonById(addonId)?.name ?: addonId
        AddonManager.removeAddon(context, addonId)
        LosslessStreamResolver.clearAll()
        _toastMessage.value = "Removed $name"
    }

    fun saveUnifiedOrder(newOrderIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            AddonManager.saveUnifiedOrder(context, newOrderIds)
            LosslessStreamResolver.clearAll()
            _toastMessage.value = "Unified fallback order saved"
        }
    }

    fun resetUnifiedOrderToDefault() {
        viewModelScope.launch(Dispatchers.IO) {
            val defaultOrder = UnifiedSourceId.buildUnifiedOrder(
                savedUnifiedCsv = null,
                legacyBuiltInCsv = null,
                addons = uiState.value.installedAddons,
            )
            AddonManager.saveUnifiedOrder(context, defaultOrder)
            LosslessStreamResolver.clearAll()
            _toastMessage.value = "Reset unified fallback order to default"
        }
    }

    fun saveMonochromeCustomInstances(rawLines: String) {
        val cleaned = rawLines
            .lineSequence()
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
            .joinToString("\n")
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { prefs ->
                if (cleaned.isBlank()) prefs.remove(MonochromeInstancesKey) else prefs[MonochromeInstancesKey] = cleaned
            }
            LosslessStreamResolver.markMonochromeUp()
            LosslessStreamResolver.clearAll()
            _toastMessage.value = "Saved Monochrome custom instances"
        }
    }
}
