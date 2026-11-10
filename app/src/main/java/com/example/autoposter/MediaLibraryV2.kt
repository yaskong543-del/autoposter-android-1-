package com.example.autoposter

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Pick library items (multi-select). Used by the composer and the campaign builder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPickerDialog(
    library: List<MediaItemEntity>, selected: Set<String>, onDismiss: () -> Unit, onImport: () -> Unit, onDone: (Set<String>) -> Unit
) {
    var sel by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.v7_pick_media)) },
        confirmButton = { TextButton({ onDone(sel) }) { Text(stringResource(R.string.v7_use_n, sel.size)) } },
        dismissButton = { Row { TextButton(onImport) { Text(stringResource(R.string.v7_import)) }; TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } } },
        text = {
            if (library.isEmpty()) Text(stringResource(R.string.v7_library_empty_body))
            else LazyVerticalGrid(GridCells.Fixed(3), Modifier.heightIn(max = 420.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(library, key = { it.id }) { m ->
                    MediaThumb(m, Modifier.aspectRatio(1f).combinedClickable { sel = if (m.id in sel) sel - m.id else sel + m.id }, selected = m.id in sel)
                }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MediaLibraryScreen(
    items: List<MediaItemEntity>, folders: List<MediaFolderEntity>, progress: ImportProgress?, importNote: String?,
    onImport: () -> Unit, onUseInCreate: (List<MediaItemEntity>) -> Unit, onUseInComposer: (List<MediaItemEntity>) -> Unit,
    onUseInCampaign: (List<MediaItemEntity>) -> Unit, legacyGallery: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val repo = ctx.container.library
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(MediaFilter()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var detail by remember { mutableStateOf<MediaItemEntity?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }       // "folder" | "tags" | "move" | "delete"
    var text by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf<StorageSummary?>(null) }
    LaunchedEffect(items.size, progress == null) { storage = repo.storage() }

    val folderNames = folders.associate { it.id to it.name }
    val shown = remember(items, filter, folders) { filter.apply(items, folderNames) }
    val dupes = remember(items) { MediaDuplicates.duplicateIds(items) }
    val tags = remember(items) { MediaDuplicates.allTags(items) }
    val chosen = items.filter { it.id in selected }

    Column(Modifier.fillMaxSize()) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.v7_tab_library)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.v7_tab_gallery)) })
        }
        if (tab == 1) { legacyGallery(); return@Column }

        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter.query, { filter = filter.copy(query = it) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.v7_search)) })
                Button(onImport) { Text(stringResource(R.string.v7_import)) }
            }
            progress?.let {
                LinearProgressIndicator(progress = { it.overall }, Modifier.fillMaxWidth())
                Muted(stringResource(R.string.v7_importing, it.index, it.total, it.name))
            }
            importNote?.let { Banner(it, warning = false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter.kind == null && !filter.duplicatesOnly, { filter = filter.copy(kind = null, duplicatesOnly = false) }, { Text(stringResource(R.string.v7_all)) })
                FilterChip(filter.kind == MediaKind.VIDEO, { filter = filter.copy(kind = if (filter.kind == MediaKind.VIDEO) null else MediaKind.VIDEO) }, { Text(stringResource(R.string.v7_videos)) })
                FilterChip(filter.kind == MediaKind.IMAGE, { filter = filter.copy(kind = if (filter.kind == MediaKind.IMAGE) null else MediaKind.IMAGE) }, { Text(stringResource(R.string.v7_images)) })
                FilterChip(filter.duplicatesOnly, { filter = filter.copy(duplicatesOnly = !filter.duplicatesOnly) }, { Text(stringResource(R.string.v7_duplicates, dupes.size)) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter.unfiledOnly, { filter = filter.copy(unfiledOnly = !filter.unfiledOnly, folderId = null) }, { Text(stringResource(R.string.v7_unfiled)) })
                folders.forEach { f -> FilterChip(filter.folderId == f.id, { filter = filter.copy(folderId = if (filter.folderId == f.id) null else f.id, unfiledOnly = false) }, { Text(f.name) }) }
                AssistChip({ text = ""; dialog = "folder" }, { Text("+ " + stringResource(R.string.v7_folder)) })
            }
            if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.take(20).forEach { t -> FilterChip(filter.tag == t, { filter = filter.copy(tag = if (filter.tag == t) null else t) }, { Text("#$t") }) }
            }
            if (selected.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.v7_selected_n, selected.size), Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
                    TextButton({ onUseInComposer(chosen) }) { Text(stringResource(R.string.v7_to_composer)) }
                    TextButton({ onUseInCampaign(chosen) }) { Text(stringResource(R.string.v7_to_campaign)) }
                    TextButton({ onUseInCreate(chosen.filter { it.kindEnum == MediaKind.VIDEO }) }) { Text(stringResource(R.string.v7_to_create)) }
                    TextButton({ text = ""; dialog = "tags" }) { Text(stringResource(R.string.v7_tag)) }
                    TextButton({ dialog = "move" }) { Text(stringResource(R.string.v7_move)) }
                    TextButton({ dialog = "delete" }) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) }
                    TextButton({ selected = emptySet() }) { Text(stringResource(R.string.v7_clear)) }
                }
            }
            if (items.isEmpty()) EmptyState(Icons.Filled.VideoLibrary, stringResource(R.string.v7_library_empty_title), stringResource(R.string.v7_library_empty_body), actionLabel = stringResource(R.string.v7_import), onAction = onImport)
            else if (shown.isEmpty()) Muted(stringResource(R.string.v7_no_match))
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { m ->
                    MediaThumb(
                        m, Modifier.aspectRatio(1f).combinedClickable(
                            onClick = { if (selected.isNotEmpty()) selected = if (m.id in selected) selected - m.id else selected + m.id else detail = m },
                            onLongClick = { selected = selected + m.id }
                        ), selected = m.id in selected, flagged = m.id in dupes
                    )
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    StudioCard {
                        Text(stringResource(R.string.v7_storage), style = MaterialTheme.typography.titleSmall)
                        storage?.let { s ->
                            Muted(stringResource(R.string.v7_storage_line, fmtSize(s.libraryBytes).ifBlank { "0 KB" }, fmtSize(s.queueBytes).ifBlank { "0 KB" }, fmtSize(s.thumbBytes).ifBlank { "0 KB" }, fmtSize(s.freeBytes)))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ scope.launch { repo.clearThumbnailCache(); ThumbMemory.cache.evictAll(); storage = repo.storage() } }) { Text(stringResource(R.string.v7_clear_thumbs)) }
                            val removable = MediaDuplicates.removable(items)
                            OutlinedButton({ scope.launch { repo.delete(removable.map { it.id }); storage = repo.storage() } }, enabled = removable.isNotEmpty()) { Text(stringResource(R.string.v7_remove_dupes, removable.size)) }
                        }
                    }
                }
            }
        }
    }

    detail?.let { m ->
        var tagText by remember(m.id) { mutableStateOf(m.tagList.joinToString(" ")) }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(m.name, maxLines = 2) },
            confirmButton = { TextButton({ scope.launch { repo.setTags(m.id, tagText) }; detail = null }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton({ detail = null }) { Text(stringResource(R.string.action_cancel)) } },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MediaThumb(m, Modifier.fillMaxWidth().height(180.dp))
                    Muted(listOf(stringResource(if (m.kindEnum == MediaKind.VIDEO) R.string.v7_video else R.string.v7_image), fmtSize(m.sizeBytes),
                        MediaFormat.duration(m.durationMs), MediaFormat.resolution(m.width, m.height)).filter { it.isNotBlank() }.joinToString(" · "))
                    Muted(stringResource(R.string.v7_added, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.addedAt))))
                    m.folderId?.let { folderNames[it] }?.let { Muted(stringResource(R.string.v7_in_folder, it)) }
                    if (m.id in dupes) Text(stringResource(R.string.v7_is_duplicate), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(tagText, { tagText = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_tags)) })
                }
            }
        )
    }

    when (dialog) {
        "folder", "tags" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (dialog == "folder") R.string.v7_new_folder else R.string.v7_add_tags)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = {
                TextButton({
                    val which = dialog; dialog = null
                    scope.launch { if (which == "folder") repo.createFolder(text) else { repo.addTags(selected.toList(), text) } }
                }) { Text(stringResource(R.string.action_save)) }
            }
        )
        "move" -> AlertDialog(
            onDismissRequest = { dialog = null }, confirmButton = {},
            dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.v7_move)) },
            text = {
                Column {
                    TextButton({ scope.launch { repo.moveToFolder(selected.toList(), null) }; dialog = null; selected = emptySet() }) { Text(stringResource(R.string.v7_unfiled)) }
                    folders.forEach { f -> TextButton({ scope.launch { repo.moveToFolder(selected.toList(), f.id) }; dialog = null; selected = emptySet() }) { Text(f.name) } }
                }
            }
        )
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.v7_delete_title, selected.size)) },
            text = { Text(stringResource(R.string.v7_delete_body)) },
            dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = { TextButton({ scope.launch { repo.delete(selected.toList()) }; dialog = null; selected = emptySet() }) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) } }
        )
    }
}
