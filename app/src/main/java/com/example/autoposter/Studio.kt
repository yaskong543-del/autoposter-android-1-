package com.example.autoposter

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@StringRes
private fun titleFor(route: String): Int = when (route) {
    Route.ACCOUNTS -> R.string.acct_title; Route.MEDIA -> R.string.media_title; Route.SETTINGS -> R.string.settings_title
    Route.SUBSCRIPTION -> R.string.settings_subscription; Route.PROFILE -> R.string.settings_profile
    Route.APPEARANCE -> R.string.settings_appearance; Route.LANGUAGE -> R.string.settings_language
    Route.NOTIFICATIONS -> R.string.settings_notifications; Route.SECURITY -> R.string.settings_security
    Route.DEVICES -> R.string.settings_devices; Route.PRIVACY -> R.string.settings_privacy; Route.HELP -> R.string.settings_help
    Route.COMPOSER -> R.string.v7_composer_title; Route.CAMPAIGN -> R.string.v7_campaign_title; Route.BRANDS -> R.string.v7_brands_title
    else -> R.string.app_name
}

private class TabSpec(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val tabSpecs = listOf(
    TabSpec(Route.HOME, R.string.nav_home, Icons.Filled.Home), TabSpec(Route.CREATE, R.string.nav_create, Icons.Filled.AddCircle),
    TabSpec(Route.CALENDAR, R.string.nav_calendar, Icons.Filled.CalendarMonth), TabSpec(Route.QUEUE, R.string.nav_queue, Icons.Filled.Schedule),
    TabSpec(Route.INSIGHTS, R.string.nav_insights, Icons.Filled.Insights)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Studio() {
    val ctx = LocalContext.current
    val container = ctx.container
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    val settingsVm: SettingsViewModel = viewModel(factory = containerFactory(container) { SettingsViewModel(it) })
    val tier = remember { DeviceProfile.detect(ctx) }
    val engine = remember { engineFor(tier) }
    val store = container.accountStore
    val gallery = remember { GalleryRepository(ctx) }
    val draft = remember { Draft() }
    val items by PostStore.items.collectAsState()
    val profile by settingsVm.profile.collectAsStateWithLifecycle()
    val composerVm: ComposerViewModel = viewModel(factory = containerFactory(container) { ComposerViewModel(it) })
    val campaignVm: CampaignViewModel = viewModel(factory = containerFactory(container) { CampaignViewModel(it) })
    val library by container.library.items.collectAsStateWithLifecycle(emptyList())
    val libFolders by container.library.folders.collectAsStateWithLifecycle(emptyList())
    val brands by container.brands.brands.collectAsStateWithLifecycle(emptyList())
    val drafts by container.db.drafts().observe().collectAsStateWithLifecycle(emptyList())
    val aiUsage by container.ai.usage.collectAsStateWithLifecycle(null)
    val zonePref by container.prefs.displayZone.collectAsStateWithLifecycle("")
    var importProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var importNote by remember { mutableStateOf<String?>(null) }

    var accounts by remember { mutableStateOf(store.all()) }
    var activeId by remember { mutableStateOf(store.activeId()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var wifiOnly by remember { mutableStateOf(Settings.wifiOnly(ctx)) }
    var paused by remember { mutableStateOf(Settings.paused(ctx)) }
    var statsNote by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var albums by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var libAlbums by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var libDenied by remember { mutableStateOf(false) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun go(route: String) {
        nav.navigate(route) {
            if (route in Route.tabs) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true }
            launchSingleTop = true
        }
    }

    fun afterAccountsChanged() {
        scope.launch {
            runCatching { container.accountMeta.mirror(store.all(), AccountHealthCheck.needsAuthIds(PostStore.items.value)) }
            if (container.auth.currentSession != null) SyncScheduler.kick(ctx)
        }
    }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    fun reloadAccounts() { accounts = store.all(); activeId = store.activeId() }

    suspend fun addVideos(uris: List<Uri>) {
        busy = "Reading ${uris.size} videos…"
        val refs = withContext(Dispatchers.IO) { uris.map { Media.describe(ctx, it) } }
        val known = draft.videos.map { it.uri }.toSet()
        draft.videos = draft.videos + refs.filter { it.uri !in known }
        busy = null
    }

    suspend fun applyParsed(c: PostCommand) {
        val videos: List<Uri> = if (draft.videos.isNotEmpty()) draft.videos.take(c.count).map { it.uri }
            else withContext(Dispatchers.IO) { gallery.find(c.count, c.folder, c.sinceDays) }
        if (videos.isEmpty()) { toast("No videos found. Pick some on Create or check the folder name."); return }
        if (draft.videos.isEmpty()) addVideos(videos) else draft.videos = draft.videos.take(c.count)

        val ids: Set<String> = when {
            c.accounts.contains("*") -> accounts.map { it.id }.toSet()
            c.accounts.isNotEmpty() -> accounts.filter { a -> c.accounts.any { it.equals(a.label, true) } }.map { it.id }.toSet()
            c.platforms.isNotEmpty() -> accounts.filter { it.platform in c.platforms }.map { it.id }.toSet()
            else -> listOfNotNull(activeId ?: accounts.firstOrNull()?.id).toSet()
        }
        draft.accountIds = ids
        if (c.captions.isNotEmpty()) draft.baseCaption = c.captions.joinToString(" | ")
        if (c.hashtags.isNotEmpty()) draft.baseTags = c.hashtags.joinToString(" ")
        c.sound?.let { draft.sound = it }
        val gapMin = Math.ceil(c.gapSeconds / 60.0).toInt().coerceAtLeast(1)
        ids.forEach { id -> c.startAt?.let { draft.starts[id] = it }; draft.gaps[id] = gapMin }
        go(Route.CREATE)
        toast("Campaign drafted. Review it, then queue.")
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterPermission; afterPermission = null
        if (granted) next?.invoke() else { libDenied = true; toast("Gallery access is needed to import by folder. You can still pick videos manually.") }
    }
    fun withMediaPermission(block: () -> Unit) {
        if (hasMediaPermission(ctx)) block() else { afterPermission = block; permission.launch(mediaPermission) }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val maxPick = if (Build.VERSION.SDK_INT >= 33) minOf(100, MediaStore.getPickImagesMaxLimit()) else 100
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxPick.coerceAtLeast(2))) { uris ->
        if (uris.isNotEmpty()) scope.launch { addVideos(uris) }
    }
    val libraryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxPick.coerceAtLeast(2))) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importNote = null
            val r = container.library.import(uris, null, true) { importProgress = it }
            importProgress = null
            importNote = ctx.getString(R.string.v7_import_done, r.added, r.duplicates.size, r.failed.size)
        }
    }
    fun importFromPicker() = libraryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { command = it }
    }

    fun importFolder(folder: String, n: Int) {
        withMediaPermission {
            scope.launch {
                val uris = withContext(Dispatchers.IO) { gallery.find(n, folder.ifBlank { null }) }
                if (uris.isEmpty()) toast("No videos found in that folder.") else addVideos(uris)
            }
        }
    }

    fun loadLibrary() {
        libDenied = false
        withMediaPermission { scope.launch { libAlbums = withContext(Dispatchers.IO) { gallery.albums() } } }
    }

    fun queueCampaign() {
        val chosen = accounts.filter { it.id in draft.accountIds }
        val (plan, _) = planUploads(draft, items, chosen)
        if (plan.isEmpty()) return
        scope.launch {
            val need = plan.map { it.second }.distinctBy { it.uri }.sumOf { it.size.coerceAtLeast(0) }
            if (need > 0 && Outbox.freeBytes(ctx) < need + (200L shl 20)) {
                toast("Not enough free storage: videos need about ${fmtSize(need)} plus headroom."); return@launch
            }
            val files = HashMap<Uri, java.io.File>()
            val unique = plan.map { it.second }.distinctBy { it.uri }
            try {
                unique.forEachIndexed { i, v ->
                    busy = "Preparing ${i + 1}/${unique.size}…"
                    files[v.uri] = withContext(Dispatchers.IO) { Outbox.copyIn(ctx, v.uri) }
                }
            } catch (e: Exception) {
                Outbox.delete(files.values.map { it.path }); busy = null
                toast("Couldn't prepare videos: ${e.message}"); return@launch
            }
            val campaign = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val counters = HashMap<String, Int>()
            val created = plan.map { (a, v) ->
                val n = counters.getOrDefault(a.id, 0); counters[a.id] = n + 1
                val start = (draft.starts[a.id] ?: 0L).takeIf { it > 0 } ?: now
                PostItem(
                    campaignId = campaign, accountId = a.id, accountLabel = a.label, platform = a.platform,
                    filePath = files.getValue(v.uri).path, name = v.name, fingerprint = v.fingerprint,
                    text = draft.textFor(a.platform, n), scheduledAt = start + n * (draft.gaps[a.id] ?: 0) * 60_000L,
                    sizeBytes = v.size.coerceAtLeast(0)
                )
            }
            PostStore.add(created)
            draft.clear()
            busy = null
            Scheduler.kick(ctx)
            paused = Settings.paused(ctx)
            toast("${created.size} uploads queued.")
            go(Route.QUEUE)
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Route.HOME
    val isTab = route in Route.tabs
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val nowMs = System.currentTimeMillis()
    val needsAuthIds = AccountHealthCheck.needsAuthIds(items)
    val attentionAccounts = accounts.count { AccountHealthCheck.of(it, nowMs, it.id in needsAuthIds).needsAttention }
    val displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: settingsVm.session?.user?.displayName

    ModalNavigationDrawer(
        drawerState = drawerState, gesturesEnabled = isTab,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(displayName ?: "A", 52.dp)
                    Text(displayName ?: stringResource(R.string.profile_local_name), style = MaterialTheme.typography.titleMedium)
                    Text(settingsVm.session?.user?.email ?: stringResource(R.string.profile_local_mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(bottom = 8.dp))
                listOf(
                    Triple(Route.ACCOUNTS, R.string.nav_accounts, Icons.Filled.ManageAccounts),
                    Triple(Route.MEDIA, R.string.nav_media, Icons.Filled.VideoLibrary),
                    Triple(Route.COMPOSER, R.string.v7_composer_title, Icons.Filled.AutoAwesome),
                    Triple(Route.CAMPAIGN, R.string.v7_campaign_title, Icons.Filled.Campaign),
                    Triple(Route.BRANDS, R.string.v7_brands_title, Icons.Filled.WorkspacePremium),
                    Triple(Route.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
                    Triple(Route.SUBSCRIPTION, R.string.nav_subscription, Icons.Filled.WorkspacePremium)
                ).forEach { (r, label, icon) ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(label)) }, icon = { Icon(icon, null) }, selected = route == r,
                        onClick = { scope.launch { drawerState.close() }; go(r) }, modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    if (isTab) TopAppBar(
                        title = {
                            Column {
                                Text("autoposter", fontWeight = FontWeight.Black)
                                Text(stringResource(R.string.brand_tagline), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        },
                        navigationIcon = { IconButton({ scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, stringResource(R.string.action_menu)) } },
                        actions = {
                            Box(Modifier.clickable { go(Route.PROFILE) }.padding(end = 12.dp)) { Avatar(displayName ?: "A", 36.dp) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    ) else TopAppBar(
                        title = { Text(stringResource(titleFor(route)), fontWeight = FontWeight.Bold) },
                        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                    if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            bottomBar = {
                AnimatedVisibility(isTab, enter = expandVertically(), exit = shrinkVertically()) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        tabSpecs.forEach { t ->
                            NavigationBarItem(
                                selected = route == t.route, onClick = { go(t.route) },
                                icon = {
                                    BadgedBox(badge = {
                                        val n = if (t.route == Route.QUEUE) items.count { it.status == Status.FAILED || it.status == Status.NEEDS_AUTH } else 0
                                        if (n > 0) Badge { Text("$n") }
                                    }) { Icon(t.icon, null) }
                                },
                                label = { Text(stringResource(t.label), maxLines = 1) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                AppNavHost(nav, Route.HOME) {
                    composable(Route.HOME) {
                        HomePage(
                            tier, command, { command = it },
                            onSpeak = {
                                runCatching {
                                    speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                                }.onFailure { toast("Speech input isn't available on this device.") }
                            },
                            onBuild = {
                                scope.launch {
                                    val parsed = engine.parse(command)
                                    if (parsed == null) { toast("Couldn't understand that. Try: Post 3 videos caption \"Summer\" on youtube"); return@launch }
                                    val needsGallery = draft.videos.isEmpty()
                                    if (needsGallery) withMediaPermission { scope.launch { applyParsed(parsed) } } else applyParsed(parsed)
                                }
                            },
                            draft = draft, accounts = accounts, items = items,
                            wifiOnly = wifiOnly, onWifiOnly = { wifiOnly = it; Settings.setWifiOnly(ctx, it) },
                            paused = paused,
                            onTogglePause = { if (paused) Scheduler.resume(ctx) else Scheduler.pause(ctx); paused = Settings.paused(ctx) },
                            userName = displayName, attentionAccounts = attentionAccounts, onNavigate = ::go,
                            dashboard = DashboardStats.compute(items, drafts, accounts, nowMs), aiUsage = aiUsage
                        )
                    }
                    composable(Route.CREATE) {
                        Column(Modifier.fillMaxSize()) {
                            Surface(
                                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).clickable { go(Route.COMPOSER) },
                                shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Column(Modifier.padding(start = 12.dp)) {
                                        Text(stringResource(R.string.v7_composer_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text(stringResource(R.string.v7_composer_card), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                CreatePage(
                                    draft, accounts, items, busy,
                                    onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                                    onOpenAlbums = {
                                        withMediaPermission { scope.launch { albums = withContext(Dispatchers.IO) { gallery.albums() } } }
                                    },
                                    albums = albums, onCloseAlbums = { albums = null },
                                    onFolder = { folder, n -> albums = null; importFolder(folder, n) },
                                    onQueue = ::queueCampaign
                                )
                            }
                        }
                    }
                    composable(Route.CALENDAR) {
                        CalendarScreen(
                            items, drafts, accounts, zonePref, onZone = { scope.launch { container.prefs.setDisplayZone(it) } },
                            onCreate = { go(Route.COMPOSER) },
                            onOpenDraft = { id -> scope.launch { composerVm.load(id, zonePref); go(Route.COMPOSER) } }
                        )
                    }
                    composable(Route.QUEUE) {
                        QueuePage(
                            items, paused,
                            onRetry = { id ->
                                PostStore.update(id) { it.copy(status = Status.QUEUED, attempts = 0, scheduledAt = System.currentTimeMillis(), error = null, progress = 0f) }
                                Scheduler.kick(ctx)
                            },
                            onRetryAll = {
                                PostStore.updateWhere({ it.status == Status.FAILED || it.status == Status.NEEDS_AUTH },
                                    { it.copy(status = Status.QUEUED, attempts = 0, scheduledAt = System.currentTimeMillis(), error = null, progress = 0f) })
                                Scheduler.kick(ctx)
                            },
                            onCancel = { id -> PostStore.update(id) { it.copy(status = Status.CANCELED, error = "Canceled") } },
                            onCancelPending = { PostStore.updateWhere({ it.status == Status.QUEUED }, { it.copy(status = Status.CANCELED, error = "Canceled") }) },
                            onClearFinished = {
                                Outbox.delete(PostStore.remove { it.status == Status.DONE || it.status == Status.CANCELED })
                            },
                            onTogglePause = { if (paused) Scheduler.resume(ctx) else Scheduler.pause(ctx); paused = Settings.paused(ctx) }
                        )
                    }
                    composable(Route.INSIGHTS) {
                        InsightsPage(items, statsNote, refreshing) {
                            scope.launch {
                                refreshing = true
                                statsNote = withContext(Dispatchers.IO) { Insights.refreshYouTube(store) }
                                refreshing = false
                            }
                        }
                    }
                    composable(Route.ACCOUNTS) {
                        AccountsPage(
                            accounts, activeId, items,
                            onSave = { a ->
                                val old = store.get(a.id)
                                store.save(a); reloadAccounts()
                                if (old != null && old.accessToken != a.accessToken) {
                                    // A fresh token: give uploads that were waiting for sign-in another try.
                                    PostStore.updateWhere({ it.accountId == a.id && it.status == Status.NEEDS_AUTH },
                                        { it.copy(status = Status.QUEUED, attempts = 0, scheduledAt = System.currentTimeMillis(), error = null, progress = 0f) })
                                    Scheduler.kick(ctx)
                                }
                                afterAccountsChanged()
                            },
                            onRename = { id, name ->
                                store.rename(id, name); reloadAccounts()
                                PostStore.updateWhere({ it.accountId == id }, { it.copy(accountLabel = name) })
                                afterAccountsChanged()
                            },
                            onRemove = { id ->
                                PostStore.updateWhere({ it.accountId == id && it.status.active }, { it.copy(status = Status.CANCELED, error = "Account removed") })
                                store.remove(id); draft.accountIds = draft.accountIds - id; reloadAccounts()
                                Outbox.delete(PostStore.items.value.map { it.filePath }.distinct().filter { !PostStore.fileInUse(it) })
                                afterAccountsChanged()
                            },
                            onActive = { store.setActive(it); reloadAccounts() }
                        )
                    }
                    composable(Route.MEDIA) {
                        LaunchedEffect(Unit) { loadLibrary() }
                        MediaLibraryScreen(
                            library, libFolders, importProgress, importNote, onImport = ::importFromPicker,
                            onUseInCreate = { sel -> scope.launch { draft.videos = (draft.videos + composerVm.asVideoRefs(sel.map { it.id })).distinctBy { it.uri }; go(Route.CREATE) } },
                            onUseInComposer = { sel -> sel.forEach { if (it.id !in composerVm.mediaIds) composerVm.mediaIds.add(it.id) }; composerVm.touch(); go(Route.COMPOSER) },
                            onUseInCampaign = { sel -> sel.forEach { if (it.id !in campaignVm.mediaIds) campaignVm.mediaIds.add(it.id) }; go(Route.CAMPAIGN) },
                            legacyGallery = {
                                MediaLibraryPage(
                                    draft, items, libAlbums, libDenied,
                                    onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                                    onLoadAlbums = ::loadLibrary, onAddAlbum = { f, n -> importFolder(f, n) }, onCreate = { go(Route.CREATE) }
                                )
                            }
                        )
                    }
                    composable(Route.COMPOSER) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                                TextButton({ composerVm.startNew(zonePref) }) { Text(stringResource(R.string.v7_new_post)) }
                            }
                            Box(Modifier.weight(1f)) {
                                ComposerScreen(
                                    composerVm, accounts, library, brands, aiUsage, container.ai.available, items,
                                    onImport = ::importFromPicker, onBrands = { go(Route.BRANDS) }, onFinished = { go(Route.QUEUE) }
                                )
                            }
                        }
                        LaunchedEffect(Unit) { if (container.ai.available) container.ai.refreshUsage() }
                    }
                    composable(Route.CAMPAIGN) {
                        CampaignScreen(campaignVm, accounts, library, brands, items, container.ai.available, ::importFromPicker) { go(Route.QUEUE) }
                    }
                    composable(Route.BRANDS) { BrandScreen(brands) }
                    composable(Route.SETTINGS) { SettingsHomeScreen(settingsVm, ::go) }
                    composable(Route.SUBSCRIPTION) { SubscriptionScreen(settingsVm) }
                    composable(Route.PROFILE) { ProfileScreen(settingsVm) }
                    composable(Route.APPEARANCE) { AppearanceScreen(settingsVm) }
                    composable(Route.LANGUAGE) { LanguageScreen(settingsVm) }
                    composable(Route.NOTIFICATIONS) { NotificationsScreen() }
                    composable(Route.SECURITY) { SecurityScreen(settingsVm) }
                    composable(Route.DEVICES) { DevicesScreen(settingsVm) }
                    composable(Route.PRIVACY) { PrivacyScreen(settingsVm) }
                    composable(Route.HELP) { HelpScreen(items, tier) }
                }
            }
        }
    }
}
