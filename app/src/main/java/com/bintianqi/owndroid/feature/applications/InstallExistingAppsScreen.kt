package com.bintianqi.owndroid.feature.applications

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bintianqi.owndroid.R
import com.bintianqi.owndroid.ui.NavIcon
import com.bintianqi.owndroid.utils.adaptiveInsets
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallExistingAppsFromUsersScreen(vm: InstallExistingAppsViewModel, onNavigateUp: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val filteredApps = state.apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }

    LaunchedEffect(Unit) { vm.scan() }
    LaunchedEffect(state.result) {
        val result = state.result ?: return@LaunchedEffect
        snackbar.showSnackbar(result)
        vm.clearResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.sourceUserId >= 0) "Install from user " + state.sourceUserId else stringResource(R.string.install_existing_apps)) },
                navigationIcon = { NavIcon(onNavigateUp) },
                actions = {
                    IconButton(vm::scan, enabled = !state.scanning && state.installing.isEmpty()) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                }
            )
        },
        bottomBar = {
            if (state.selected.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(vm::selectAll) { Text(stringResource(R.string.select_all)) }
                    TextButton(vm::clearSelection) { Text(stringResource(R.string.clear_selection)) }
                    Spacer(Modifier.weight(1f))
                    Button(vm::installSelected, enabled = state.installing.isEmpty()) {
                        Text(stringResource(R.string.install_selected, state.selected.size))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = adaptiveInsets()
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            item {
                Text(stringResource(R.string.info_install_existing_apps), Modifier.padding(top = 10.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.scanning && state.apps.isNotEmpty()) item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true, label = { Text(stringResource(R.string.search)) })
            }
            if (state.scanning) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.scanning_users_apps), Modifier.padding(vertical = 8.dp))
            }
            state.error?.let { error ->
                item { Text(error, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (!state.scanning && filteredApps.isEmpty() && state.error == null) item {
                Text(stringResource(R.string.no_existing_apps), Modifier.fillMaxWidth().padding(vertical = 24.dp).alpha(0.7f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(filteredApps, key = { it.packageName }) { app ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Checkbox(checked = app.packageName in state.selected, onCheckedChange = { vm.toggleSelection(app.packageName) }, enabled = app.packageName !in state.installing)
                    if (app.icon != null) Image(rememberDrawablePainter(app.icon), null, Modifier.size(38.dp)) else Icon(painterResource(R.drawable.apps_fill0), null, Modifier.size(38.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleSmall)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (app.retained) stringResource(R.string.retained_by_android)
                            else stringResource(R.string.available_in_users, "User " + app.source.userId),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    TextButton({ vm.installPackages(listOf(app.packageName)) }, enabled = app.packageName !in state.installing) { Text(stringResource(R.string.install)) }
                }
            }
        }
    }
}
