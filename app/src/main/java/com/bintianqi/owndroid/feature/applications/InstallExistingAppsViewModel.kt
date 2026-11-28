package com.bintianqi.owndroid.feature.applications

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bintianqi.owndroid.CrossProfileAppsRelay
import com.bintianqi.owndroid.MyApplication
import com.bintianqi.owndroid.PrivilegeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class InstallExistingAppsViewModel(
    private val application: MyApplication,
    private val ph: PrivilegeHelper
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExistingAppsUiState())
    val uiState = _uiState.asStateFlow()
    private var pendingRequestId: String? = null

    init {
        viewModelScope.launch {
            application.container.existingAppsRelayResult.collect { result ->
                if (result != null && result.requestId == pendingRequestId) {
                    pendingRequestId = null
                    acceptRelayResult(result)
                }
            }
        }
    }

    fun scan() {
        val currentUserId = Process.myUid() / USER_ID_RANGE
        val sourceUserId = when (currentUserId) {
            0 -> 10
            10 -> 0
            else -> {
                _uiState.update { it.copy(scanning = false, error = "This feature supports only users 0 and 10.") }
                return
            }
        }
        if (Build.VERSION.SDK_INT < 28) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(scanning = true, sourceUserId = sourceUserId, error = null, result = null, apps = emptyList(), selected = emptySet())
            }
            val requestId = UUID.randomUUID().toString()
            pendingRequestId = requestId
            try {
                if (currentUserId == 10) {
                    if (!ph.myDpm.isProfileOwnerApp(application.packageName) || !ph.myDpm.isAffiliatedUser) {
                        throw SecurityException("User 10 must have an affiliated Profile Owner.")
                    }
                    CrossProfileAppsRelay.ensureIntentFilters(application, ph.myDpm, ph.myDar)
                }
                application.startActivity(CrossProfileAppsRelay.createRequestIntent(application, currentUserId, requestId))
                delay(15000)
                if (pendingRequestId == requestId) {
                    pendingRequestId = null
                    _uiState.update {
                        it.copy(
                            scanning = false,
                            error = "No response from user " + sourceUserId + ". Open OwnDroid in both users 0 and 10 and verify the managed profile is active."
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(scanning = false, error = if (e is SecurityException) e.message ?: "Profile-owner permission is required."
                        else "Unable to contact user " + sourceUserId + ". Open OwnDroid once in both users 0 and 10, then retry.\n" + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun acceptRelayResult(result: ExistingAppsRelayResult) {
        if (result.error != null) {
            _uiState.update { it.copy(scanning = false, error = result.error) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = application.packageManager
                val installedHere = pm.getInstalledApplications(0).mapTo(hashSetOf()) { it.packageName }
                val entries = result.apps.asSequence().filter { it.packageName !in installedHere }.mapNotNull { source ->
                    val info = try {
                        pm.getApplicationInfo(source.packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    } catch (_: Exception) {
                        null
                    }
                    if (info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
                    val icon = try { info?.loadIcon(pm) } catch (_: Exception) { null }
                    ExistingUserApp(source.packageName, source.label, icon, ExistingAppSource(result.sourceUserId), !source.installedInSource)
                }.sortedBy { it.label.lowercase() }.toList()
                _uiState.update {
                    it.copy(scanning = false, apps = entries, selected = emptySet(), sourceUserId = result.sourceUserId, error = null)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(scanning = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun toggleSelection(name: String) {
        _uiState.update { it.copy(selected = if (name in it.selected) it.selected - name else it.selected + name) }
    }
    fun selectAll() { _uiState.update { it.copy(selected = it.apps.map { app -> app.packageName }.toSet()) } }
    fun clearSelection() { _uiState.update { it.copy(selected = emptySet()) } }
    fun clearResult() { _uiState.update { it.copy(result = null) } }
    fun installSelected() { installPackages(_uiState.value.selected.toList()) }

    fun installPackages(packageNames: List<String>) {
        val targets = packageNames.distinct().filter { it !in _uiState.value.installing }
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(installing = it.installing + targets, error = null, result = null) }
            var succeeded = 0
            var failed = 0
            targets.forEach { name ->
                val installed = try {
                    var value = false
                    ph.safeDpmCall { value = dpm.installExistingPackage(dar, name) }
                    value
                } catch (_: Exception) { false }
                if (installed) succeeded++ else failed++
                _uiState.update {
                    it.copy(
                        apps = if (installed) it.apps.filterNot { app -> app.packageName == name } else it.apps,
                        selected = it.selected - name,
                        installing = it.installing - name
                    )
                }
            }
            _uiState.update {
                it.copy(result = application.getString(com.bintianqi.owndroid.R.string.install_existing_apps_result, succeeded, failed))
            }
        }
    }

    private companion object { const val USER_ID_RANGE = 100000 }
}
