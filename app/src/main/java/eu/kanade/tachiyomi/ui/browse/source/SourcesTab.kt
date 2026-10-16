package eu.kanade.tachiyomi.ui.browse.source

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.SourceCategoriesDialog
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.source.rules.JsonRuleRegistry
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import exh.ui.smartsearch.SmartSearchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.sourcesTab(
    smartSearchConfig: SmartSearchConfig? = null,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ruleRegistry = remember(context) { JsonRuleRegistry.get(context) }
    val installedRules by ruleRegistry.rules.collectAsStateWithLifecycle()
    val subscriptions by ruleRegistry.subscriptions.collectAsStateWithLifecycle()
    var manageRules by remember { mutableStateOf(false) }
    var subscriptionDialog by remember { mutableStateOf(false) }
    var subscriptionUrl by remember { mutableStateOf("") }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                            val output = StringBuilder()
                            val data = CharArray(8192)
                            while (true) {
                                val count = reader.read(data)
                                if (count == -1) break
                                output.append(data, 0, count)
                                require(output.length <= 2_000_000) { "JSON exceeds 2 MB" }
                            }
                            require(output.isNotEmpty()) { "Empty JSON file" }
                            output.toString()
                        } ?: error("Could not open JSON document")
                    }
                    ruleRegistry.importJson(text)
                }
                Toast.makeText(
                    context,
                    result.fold(
                        onSuccess = { "Imported $it JSON sources" },
                        onFailure = { "Import failed: ${it.message}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    // SY -->
    val viewModel = viewModel<SourcesViewModel>(
        factory = SourcesViewModel.Factory,
        extras = CreationExtras {
            set(SourcesViewModel.SMART_SEARCH_CONFIG_KEY, smartSearchConfig)
        },
    )
    // SY <--
    val state by viewModel.state.collectAsStateWithLifecycle()

    return TabContent(
        // SY -->
        titleRes = when (smartSearchConfig == null) {
            true -> MR.strings.label_sources
            false -> SYMR.strings.find_in_another_source
        },
        actions = listOf(
            AppBar.Action(
                title = "Import JSON sources",
                icon = Icons.Outlined.FileOpen,
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            ),
            AppBar.Action(
                title = "Manage imported JSON sources",
                icon = Icons.Outlined.ListAlt,
                onClick = { manageRules = true },
            ),
            AppBar.Action(
                title = "Subscribe to JSON rule URL",
                icon = Icons.Outlined.Link,
                onClick = { subscriptionDialog = true },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalSearchScreen(smartSearchConfig?.origTitle ?: "")) },
            ),
        ).let {
            when (smartSearchConfig) {
                null -> {
                    it.plus(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            onClick = { navigator.push(SourcesFilterScreen()) },
                        ),
                    )
                }
                else -> it
            }
        },
        // SY <--
        content = { contentPadding, snackbarHostState ->
            if (subscriptionDialog) {
                AlertDialog(
                    onDismissRequest = { subscriptionDialog = false },
                    title = { Text("Subscribe to JSON rules") },
                    text = {
                        OutlinedTextField(
                            value = subscriptionUrl,
                            onValueChange = { subscriptionUrl = it },
                            label = { Text("HTTPS JSON URL") },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val address = subscriptionUrl.trim()
                            subscriptionDialog = false
                            scope.launch {
                                val result = runCatching { ruleRegistry.importSubscription(address) }
                                Toast.makeText(
                                    context,
                                    result.fold(
                                        onSuccess = { "Subscribed: $it sources" },
                                        onFailure = { "Subscription failed: ${it.message}" },
                                    ),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }) { Text("Subscribe") }
                    },
                    dismissButton = {
                        TextButton(onClick = { subscriptionDialog = false }) { Text("Cancel") }
                    },
                )
            }
            if (manageRules) {
                AlertDialog(
                    onDismissRequest = { manageRules = false },
                    title = { Text("Imported JSON sources") },
                    text = {
                        if (installedRules.isEmpty() && subscriptions.isEmpty()) {
                            Text("No JSON sources imported yet.")
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                if (subscriptions.isNotEmpty()) {
                                    item { Text("HTTPS subscriptions") }
                                    items(subscriptions) { address ->
                                        Text(address)
                                        TextButton(onClick = { ruleRegistry.removeSubscription(address) }) {
                                            Text("Unsubscribe")
                                        }
                                    }
                                    item { Text("Installed rules") }
                                }
                                items(installedRules) { rule ->
                                    val sourceName = rule.optString("bookSourceName")
                                        .ifBlank { rule.optString("name") }
                                    val sourceUrl = rule.optString("bookSourceUrl")
                                        .ifBlank { rule.optString("baseUrl") }
                                    val sourceLang = rule.optString("bookSourceLanguage").ifBlank { "zh" }
                                    Text(sourceName)
                                    TextButton(onClick = { ruleRegistry.remove(sourceUrl, sourceName, sourceLang) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            manageRules = false
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }) { Text("Import JSON") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    val result = ruleRegistry.refreshSubscriptions()
                                    val failed = result.count { it.value.isFailure }
                                    Toast.makeText(
                                        context,
                                        "Refreshed ${result.size - failed}/${result.size} subscriptions",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }) { Text("Refresh URLs") }
                            TextButton(onClick = { manageRules = false }) { Text("Close") }
                        }
                    },
                )
            }
            SourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    // SY -->
                    val screen = when {
                        smartSearchConfig != null -> SmartSearchScreen(source.id, smartSearchConfig)
                        listing == Listing.Popular && state.useNewSourceNavigation -> SourceFeedScreen(source.id)
                        else -> BrowseSourceScreen(source.id, listing.query)
                    }
                    navigator.push(screen)
                    // SY <--
                },
                onClickPin = viewModel::togglePin,
                onLongClickItem = viewModel::showSourceDialog,
            )

            when (val dialog = state.dialog) {
                is SourcesViewModel.Dialog.SourceLongClick -> {
                    val source = dialog.source
                    SourceOptionsDialog(
                        source = source,
                        onClickPin = {
                            viewModel.togglePin(source)
                            viewModel.closeDialog()
                        },
                        onClickDisable = {
                            viewModel.toggleSource(source)
                            viewModel.closeDialog()
                        },
                        // SY -->
                        onClickSetCategories = {
                            viewModel.showSourceCategoriesDialog(source)
                        }.takeIf { state.categories.isNotEmpty() },
                        onClickToggleDataSaver = {
                            viewModel.toggleExcludeFromDataSaver(source)
                            viewModel.closeDialog()
                        }.takeIf { state.dataSaverEnabled },
                        onDismiss = viewModel::closeDialog,
                    )
                }
                is SourcesViewModel.Dialog.SourceCategories -> {
                    val source = dialog.source
                    SourceCategoriesDialog(
                        source = source,
                        categories = state.categories,
                        onClickCategories = { categories ->
                            viewModel.setSourceCategories(source, categories)
                            viewModel.closeDialog()
                        },
                        onDismissRequest = viewModel::closeDialog,
                    )
                }
                null -> Unit
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                viewModel.events.collectLatest { event ->
                    when (event) {
                        SourcesViewModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
