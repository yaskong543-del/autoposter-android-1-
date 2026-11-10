package com.example.autoposter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Gallery albums plus the clips chosen for the next campaign. Albums load only after the person grants gallery access. */
@Composable
fun MediaLibraryPage(
    draft: Draft, items: List<PostItem>, albums: List<Pair<String, Int>>?, denied: Boolean,
    onPick: () -> Unit, onLoadAlbums: () -> Unit, onAddAlbum: (String, Int) -> Unit, onCreate: () -> Unit
) {
    val waiting = items.filter { it.status != Status.DONE && it.status != Status.CANCELED }.distinctBy { it.filePath }
    PageList {
        item { SectionTitle(stringResource(R.string.media_title), stringResource(R.string.media_subtitle)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(stringResource(R.string.media_selected), draft.videos.size.toString(), Modifier.weight(1f))
                StatCard(stringResource(R.string.media_waiting), waiting.size.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                StatCard(stringResource(R.string.media_storage), fmtSize(waiting.sumOf { it.sizeBytes }).ifBlank { "0 KB" }, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onPick, Modifier.weight(1f)) { Text(stringResource(R.string.media_pick)) }
                FilledTonalButton(onCreate, Modifier.weight(1f), enabled = draft.videos.isNotEmpty()) { Text(stringResource(R.string.media_use)) }
            }
        }
        if (draft.videos.isNotEmpty()) {
            item { Text(stringResource(R.string.media_selected_title), style = MaterialTheme.typography.titleSmall) }
            items(draft.videos.take(30), key = { "v-" + it.uri.toString() }) { v ->
                StudioCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(v.name, maxLines = 1, style = MaterialTheme.typography.titleSmall); Muted(fmtSize(v.size)) }
                        TextButton({ draft.videos = draft.videos.filter { it.uri != v.uri } }) { Text(stringResource(R.string.action_remove)) }
                    }
                }
            }
            if (draft.videos.size > 30) item { Muted(stringResource(R.string.media_more, draft.videos.size - 30)) }
        }
        item { Text(stringResource(R.string.media_albums), style = MaterialTheme.typography.titleSmall) }
        when {
            denied -> item { ErrorState(stringResource(R.string.media_denied_body), stringResource(R.string.media_grant), onLoadAlbums, title = stringResource(R.string.media_denied_title)) }
            albums == null -> items(3) { SkeletonCard(1) }
            albums.isEmpty() -> item { EmptyState(Icons.Filled.VideoLibrary, stringResource(R.string.media_empty_title), stringResource(R.string.media_empty_body)) }
            else -> items(albums, key = { "a-" + it.first }) { (name, count) ->
                StudioCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleSmall); Muted(stringResource(R.string.media_clips, count)) }
                        FilledTonalButton({ onAddAlbum(name, minOf(count, 500)) }) { Text(stringResource(R.string.media_add_album)) }
                    }
                }
            }
        }
    }
}
