package com.example.autoposter

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Manual dependency injection: one place that builds and wires every long-lived object. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val config = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.GOOGLE_WEB_CLIENT_ID)
    val http by lazy { SupabaseHttp(config) }
    val db by lazy { AppDatabase.get(app) }
    val prefs by lazy { UserPrefs(app.userPrefsStore) }
    val auth by lazy { AuthRepository(http, EncryptedSessionStorage(app)) }
    val accountStore by lazy { AccountStore(app) }
    val profiles by lazy { ProfileRepository(db.profiles(), http, auth) }
    val devices by lazy { DeviceRepository(db.devices(), http, auth, prefs) }
    val accountMeta by lazy { AccountMetaRepository(db.accountMeta(), http, auth) }
    val brands by lazy { BrandRepository(db.brands(), http, auth) }
    val library by lazy { MediaLibraryRepository(app, db) }
    val aiClient by lazy { AiGatewayClient(config.base, config.anonKey, { auth.accessToken() }) }
    val ai by lazy { AiRepository(aiClient, db.aiUsage()) }
    val sync by lazy { SyncCoordinator(auth, profiles, devices, accountMeta, accountStore, brands) }

    /** Restores the session and keeps user-scoped caches in step with sign-in / sign-out. */
    fun start() {
        appScope.launch { runCatching { auth.restore() }.onFailure { AppLog.e("App", "restore failed", it) } }
        appScope.launch {
            auth.state.collect { st ->
                when (st) {
                    is AuthState.SignedIn -> { runCatching { profiles.ensureLocal(st.session) }; SyncScheduler.kick(app) }
                    AuthState.SignedOut -> runCatching { profiles.clearLocal(); devices.clearLocal(); accountMeta.clearLocal(); brands.clearLocal(); ai.clearLocal() }
                    else -> Unit
                }
            }
        }
    }
}

/** Pushes profile edits, this device and social-account metadata. Safe to call at any time; no-op when signed out. */
class SyncCoordinator(
    private val auth: AuthRepository, private val profiles: ProfileRepository, private val devices: DeviceRepository,
    private val accountMeta: AccountMetaRepository, private val accounts: AccountStore,
    private val brands: BrandRepository? = null
) {
    /** True when finished or nothing to do; false when a retry later makes sense (offline / server error). */
    suspend fun syncAll(): Boolean {
        val s = auth.currentSession ?: return true
        if (!auth.configured) return true
        return try {
            profiles.ensureLocal(s)
            devices.registerThisDevice()
            profiles.pushDirty()
            accountMeta.mirror(accounts.all(), AccountHealthCheck.needsAuthIds(PostStore.items.value))
            accountMeta.push()
            devices.pushUnsynced()
            brands?.sync()
            true
        } catch (e: ApiException) {
            AppLog.w("Sync", "sync failed: ${e.kind} ${e.status}")
            !(e.kind == ErrorKind.NETWORK || e.kind == ErrorKind.SERVER)
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        PostStore.init(applicationContext)
        return if (applicationContext.container.sync.syncAll()) Result.success() else Result.retry()
    }
}

object SyncScheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun kick(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "foundation-sync-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build()
        )
    }

    fun schedulePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "foundation-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS).setConstraints(online).build()
        )
    }
}

class AutoPosterApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PostStore.init(this)
        container.start()
        SyncScheduler.schedulePeriodic(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as AutoPosterApp).container
