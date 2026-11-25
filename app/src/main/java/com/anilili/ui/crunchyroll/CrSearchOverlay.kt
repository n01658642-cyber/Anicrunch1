package com.anilili.ui.crunchyroll

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.anilili.data.AppGraph
import com.anilili.data.model.Media
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CrSearchOverlay(
    onDismiss: () -> Unit,
    onAnimeClick: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Media>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = CrColors.accent
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        delay(80)
        focus.requestFocus()
        keyboard?.show()
    }

    fun fetch(q: String, pageNum: Int, append: Boolean) {
        if (q.isBlank()) {
            results = emptyList()
            hasNext = false
            loading = false
            loadingMore = false
            error = null
            return
        }
        if (append) loadingMore = true else loading = true
        error = null
        searchJob?.cancel()
        searchJob = scope.launch {
            if (!append) delay(280)
            runCatching {
                AppGraph.aniList.search(
                    query = q.trim(),
                    page = pageNum,
                    perPage = 50,
                    hideAdult = true,
                )
            }.onSuccess { pageResult ->
                results = if (append) {
                    (results + pageResult.items).distinctBy { it.id }
                } else {
                    pageResult.items
                }
                page = pageNum
                hasNext = pageResult.hasNextPage
                loading = false
                loadingMore = false
            }.onFailure {
                error = it.message ?: "Search failed"
                loading = false
                loadingMore = false
            }
        }
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total > 0 && last >= total - 3
        }
    }
    LaunchedEffect(nearEnd, hasNext, query, loadingMore, loading) {
        if (nearEnd && hasNext && !loadingMore && !loading && query.isNotBlank()) {
            fetch(query, page + 1, append = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(CrColors.Black)
                .statusBarsPadding()
                .padding(top = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        page = 1
                        fetch(it, 1, append = false)
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Search all anime", color = CrColors.TextMuted) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = accent) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        fetch(query, 1, append = false)
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = CrColors.Divider,
                        cursorColor = accent,
                        focusedContainerColor = CrColors.Card,
                        unfocusedContainerColor = CrColors.Card,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = CrColors.TextSecondary)
                }
                query.isNotBlank() && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results", color = CrColors.TextSecondary)
                }
                query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Type a title to search the full catalogue", color = CrColors.TextSecondary)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { it.id }) { media ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAnimeClick(media.id)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = media.coverImage.best,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(52.dp)
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(4.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    media.title.english ?: media.title.romaji ?: "",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sub = listOfNotNull(
                                    media.format,
                                    media.averageScore?.let { "★ $it%" },
                                ).joinToString(" · ")
                                if (sub.isNotEmpty()) {
                                    Text(sub, color = CrColors.TextMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
    }
}
