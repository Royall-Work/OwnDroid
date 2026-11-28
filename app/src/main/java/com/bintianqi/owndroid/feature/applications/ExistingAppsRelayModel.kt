package com.bintianqi.owndroid.feature.applications

data class ExistingAppsRelayEntry(val packageName: String, val label: String, val installedInSource: Boolean)
data class ExistingAppsRelayResult(val requestId: String, val sourceUserId: Int, val apps: List<ExistingAppsRelayEntry>, val error: String? = null)
data class ExistingAppSource(val userId: Int)
data class ExistingUserApp(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable?, val source: ExistingAppSource, val retained: Boolean)
data class ExistingAppsUiState(val scanning: Boolean = false, val installing: Set<String> = emptySet(), val apps: List<ExistingUserApp> = emptyList(), val selected: Set<String> = emptySet(), val sourceUserId: Int = -1, val error: String? = null, val result: String? = null)
