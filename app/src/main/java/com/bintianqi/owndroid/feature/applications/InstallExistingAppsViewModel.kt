package com.bintianqi.owndroid.feature.applications

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bintianqi.owndroid.MyApplication
import com.bintianqi.owndroid.PrivilegeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class ExistingAppSource(val userId: Int, val userName: String)

data class ExistingUserApp(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val sources: List<ExistingAppSource>,
    val retained: Boolean
)

data class ExistingAppsUiState(
    val shizukuAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val scanning: Boolean = false,
    val installing: Set<String> = emptySet(),
    val apps: List<ExistingUserApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    val error: String? = null,
    val result: String? = null
)

private data class ScanUser(val id: Int, val name: String)

class InstallExistingAppsViewModel(
    private val application: MyApplication,
    private val ph: PrivilegeHelper
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExistingAppsUiState())
    val uiState = _uiState.asStateFlow()

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            _uiState.update {
                it.copy(
                    permissionGranted = granted,
                    error = if (granted) null else application.getString(com.bintianqi.owndroid.R.string.permission_denied)
                )
            }
        }
    }

    init {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Exception) {
        }
        refreshShizukuState()
    }

    fun refreshShizukuState() {
        val available = try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
        val granted = if (available) {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        } else false
        _uiState.update {
            it.copy(shizukuAvailable = available, permissionGranted = granted)
        }
    }

    fun requestShizukuPermission() {
        refreshShizukuState()
        val state = _uiState.value
        if (!state.shizukuAvailable) return
        if (state.permissionGranted) {
            scan()
            return
        }
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
        }
    }

    fun scan() {
        refreshShizukuState()
        if (!_uiState.value.permissionGranted) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(scanning = true, error = null, result = null, apps = emptyList(), selected = emptySet())
            }
            try {
                val currentUserId = Process.myUid() / USER_ID_RANGE
                val currentPackages = application.packageManager
                    .getInstalledApplications(0)
                    .map { it.packageName }
                    .toSet()

                val usersOutput = runShellCommand("pm list users")
                val users = USER_PATTERN.findAll(usersOutput).mapNotNull {
                    val id = it.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val name = it.groupValues[2].ifBlank { "User " + id }
                    ScanUser(id, name)
                }.distinctBy { it.id }

                if (users.isEmpty()) {
                    throw IllegalStateException(usersOutput.ifBlank { "No users were returned by Android." })
                }

                val packagesByUser = linkedMapOf<String, MutableList<ExistingAppSource>>()
                val allKnownPackages = linkedSetOf<String>()

                users.filter { it.id != currentUserId }.forEach { user ->
                    val installed = parsePackageList(
                        runShellCommand("pm list packages -3 --user " + user.id)
                    )
                    val known = parsePackageList(
                        runShellCommand("pm list packages -3 -u --user " + user.id)
                    )
                    allKnownPackages.addAll(known)
                    installed.forEach { packageName ->
                        packagesByUser.getOrPut(packageName) { mutableListOf() }
                            .add(ExistingAppSource(user.id, user.name))
                    }
                }

                val retainedPackages = allKnownPackages.filter {
                    it !in packagesByUser && it !in currentPackages
                }
                val packageNames = (packagesByUser.keys + retainedPackages)
                    .filter { it !in currentPackages }
                    .distinct()

                val pm = application.packageManager
                val entries = packageNames.mapNotNull { packageName ->
                    val info = try {
                        pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    } catch (_: Exception) {
                        null
                    }
                    if (info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                        return@mapNotNull null
                    }
                    val label = try {
                        info?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
                    } catch (_: Exception) {
                        packageName
                    }
                    val icon = try {
                        info?.loadIcon(pm)
                    } catch (_: Exception) {
                        null
                    }
                    ExistingUserApp(
                        packageName,
                        label,
                        icon,
                        packagesByUser[packageName]?.sortedBy { it.userId } ?: emptyList(),
                        packageName in retainedPackages
                    )
                }.sortedBy { it.label.lowercase() }

                _uiState.update {
                    it.copy(scanning = false, apps = entries, selected = emptySet(), error = null)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        scanning = false,
                        error = application.getString(com.bintianqi.owndroid.R.string.scan_failed) +
                                "\n" + (e.message ?: e.javaClass.simpleName)
                    )
                }
            }
        }
    }

    fun toggleSelection(packageName: String) {
        _uiState.update {
            val selected = if (packageName in it.selected) it.selected - packageName
            else it.selected + packageName
            it.copy(selected = selected)
        }
    }

    fun selectAll() {
        _uiState.update { it.copy(selected = it.apps.map { app -> app.packageName }.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selected = emptySet()) }
    }

    fun clearResult() {
        _uiState.update { it.copy(result = null) }
    }

    fun installSelected() {
        installPackages(_uiState.value.selected.toList())
    }

    fun installPackages(packageNames: List<String>) {
        val targets = packageNames.distinct().filter { it !in _uiState.value.installing }
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(installing = it.installing + targets, error = null, result = null)
            }
            var succeeded = 0
            var failed = 0
            targets.forEach { packageName ->
                val result = try {
                    var installed = false
                    ph.safeDpmCall {
                        installed = dpm.installExistingPackage(dar, packageName)
                    }
                    installed
                } catch (_: Exception) {
                    false
                }
                if (result) succeeded++ else failed++
                _uiState.update {
                    it.copy(
                        apps = if (result) it.apps.filterNot { app -> app.packageName == packageName } else it.apps,
                        selected = it.selected - packageName,
                        installing = it.installing - packageName
                    )
                }
            }
            _uiState.update {
                it.copy(
                    result = application.getString(
                        com.bintianqi.owndroid.R.string.install_existing_apps_result,
                        succeeded, failed
                    )
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun runShellCommand(command: String): String {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command + " 2>&1"), null, null)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(output.ifBlank { "Shell command failed with code " + exitCode })
        }
        return output
    }

    private fun parsePackageList(output: String): Set<String> {
        return output.lineSequence().mapNotNull { line ->
            val value = line.trim()
            if (!value.startsWith("package:")) null
            else value.removePrefix("package:").substringBefore("=").trim().takeIf { it.isNotEmpty() }
        }.toSet()
    }

    override fun onCleared() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Exception) {
        }
        super.onCleared()
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 4817
        const val USER_ID_RANGE = 100000
        val USER_PATTERN = Regex("""UserInfo\{(\d+):([^:}]*)""")
    }
}
