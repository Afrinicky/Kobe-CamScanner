package com.kobe.camscanner.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.camscanner.core.common.Formatting
import com.kobe.camscanner.core.ui.components.KobeCard
import com.kobe.camscanner.core.ui.components.KobeChip
import com.kobe.camscanner.core.ui.components.KobeTopBar
import com.kobe.camscanner.core.ui.components.SectionHeader
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.data.settings.ThemePreference
import com.kobe.camscanner.domain.model.PdfCompression
import com.kobe.camscanner.domain.model.PdfPageSize
import com.kobe.camscanner.domain.model.ScanFilter

/**
 * Settings.
 *
 * The privacy card sits at the top rather than in an "About" screen. It is the product's central
 * claim (SDS 47), and stating it plainly where users will actually read it is worth more than a
 * paragraph buried three taps deep.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val storage by viewModel.storageUsage.collectAsStateWithLifecycle()
    val extra = KobeTheme.extra

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item { KobeTopBar(title = "Settings", onBack = onBack) }

            item {
                KobeCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    border = false,
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Rounded.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.size(14.dp))
                        Column {
                            Text(
                                text = "Everything happens on this phone",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Kobe has no internet permission at all. Scanning, " +
                                    "enhancement, text recognition and PDF creation run offline, " +
                                    "and your documents leave only when you share them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            section("Scanning") {
                SwitchRow(
                    title = "Automatic capture",
                    subtitle = "Take the photo once the document is detected and steady.",
                    checked = settings.autoCapture,
                    onCheckedChange = viewModel::setAutoCapture,
                )
                SwitchRow(
                    title = "Framing grid",
                    subtitle = "Show rule-of-thirds guides in the viewfinder.",
                    checked = settings.showGrid,
                    onCheckedChange = viewModel::setShowGrid,
                )
                ChipRow(
                    title = "Default filter",
                    options = ScanFilter.entries.map { it to it.label },
                    selected = settings.defaultFilter,
                    onSelect = viewModel::setDefaultFilter,
                )
            }

            section("Text recognition") {
                SwitchRow(
                    title = "Recognise text automatically",
                    subtitle = "Read each page as it is captured, so search and saving stay instant.",
                    checked = settings.autoOcr,
                    onCheckedChange = viewModel::setAutoOcr,
                )
                SwitchRow(
                    title = "Searchable PDFs",
                    subtitle = "Embed the recognised text so any reader can search and copy it.",
                    checked = settings.searchablePdf,
                    onCheckedChange = viewModel::setSearchablePdf,
                )
                SwitchRow(
                    title = "Suggest document names",
                    subtitle = "Name new scans from what Kobe reads on the page.",
                    checked = settings.smartNaming,
                    onCheckedChange = viewModel::setSmartNaming,
                )
            }

            section("PDF") {
                ChipRow(
                    title = "Page size",
                    options = PdfPageSize.entries.map { it to it.label },
                    selected = settings.defaultPageSize,
                    onSelect = viewModel::setPageSize,
                )
                ChipRow(
                    title = "File size",
                    options = PdfCompression.entries.map { it to it.label },
                    selected = settings.defaultCompression,
                    onSelect = viewModel::setCompression,
                )
            }

            section("Appearance") {
                ChipRow(
                    title = "Theme",
                    options = ThemePreference.entries.map { it to it.label },
                    selected = settings.theme,
                    onSelect = viewModel::setTheme,
                )
            }

            section("Security") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = extra.inkMuted,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App lock",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Require your device screen lock before Kobe opens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.inkMuted,
                        )
                    }
                    Switch(checked = settings.appLock, onCheckedChange = viewModel::setAppLock)
                }
            }

            section("Storage") {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = "Kobe is using " + Formatting.fileSize(storage.usedBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = Formatting.fileSize(storage.freeBytes) + " free on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.inkMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.kobe.camscanner.core.ui.components.KobeOutlineButton(
                            text = "Clear share cache",
                            onClick = viewModel::clearShareCache,
                        )
                        com.kobe.camscanner.core.ui.components.KobeOutlineButton(
                            text = "Empty trash",
                            onClick = viewModel::emptyTrash,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(28.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Kobe CamScanner 1.0",
                        style = MaterialTheme.typography.labelMedium,
                        color = extra.inkFaint,
                    )
                    Text(
                        text = "Your documents. Your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.inkFaint,
                    )
                }
            }
        }
    }
}

/** Groups a set of rows under a heading. Keeps the settings list from becoming one long ladder. */
private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    content: @Composable () -> Unit,
) {
    item {
        Spacer(Modifier.height(28.dp))
        SectionHeader(
            title = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KobeRadius.tile)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = KobeTheme.extra.inkMuted,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChipRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(options) { (value, label) ->
                KobeChip(
                    label = label,
                    selected = value == selected,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}
