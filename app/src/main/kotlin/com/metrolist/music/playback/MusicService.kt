@file:Suppress("DEPRECATION")

package com.metrolist.music.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.*
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.constants.*
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.*
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.extensions.*
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.models.PersistQueue
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.*
import com.metrolist.music.utils.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

// --- CrossfadeManager ---
class CrossfadeManager(
    private val scope: CoroutineScope,
    private val primaryPlayer: ExoPlayer,
    private val context: Context,
    private val createMediaSourceFactory: () -> DefaultMediaSourceFactory,
    private val createRenderersFactory: () -> DefaultRenderersFactory
) {
    private var secondaryPlayer: ExoPlayer? = null
    private var crossfadeJob: Job? = null
    private var isCrossfading = false

    private val _crossfadeEnabled = MutableStateFlow(false)
    val crossfadeEnabled = _crossfadeEnabled.asStateFlow()

    private val _crossfadeDuration = MutableStateFlow(3)
    val crossfadeDuration = _crossfadeDuration.asStateFlow()

    fun updateSettings(enabled: Boolean, duration: Int) {
        _crossfadeEnabled.value = enabled
        _crossfadeDuration.value = duration
    }

    fun startCrossfade(nextMediaItem: MediaItem) {
        if (!crossfadeEnabled.value || isCrossfading) return

        isCrossfading = true
        crossfadeJob?.cancel()

        secondaryPlayer = createSecondaryPlayer().apply {
            setMediaItem(nextMediaItem)
            prepare()
            volume = 0f
            playWhenReady = true
        }

        crossfadeJob = scope.launch {
            performCrossfade()
        }
    }

    private suspend fun performCrossfade() {
        val duration = crossfadeDuration.value * 1000L
        val steps = 50
        val stepDuration = duration / steps

        repeat(steps) { step ->
            val progress = step.toFloat() / steps

            primaryPlayer.volume = 1f - progress
            secondaryPlayer?.volume = progress

            delay(stepDuration)
        }

        completeCrossfade()
    }

    private fun completeCrossfade() {
        primaryPlayer.pause()

        secondaryPlayer?.let { secondary ->
            val currentPosition = secondary.currentPosition
            val mediaItem = secondary.currentMediaItem

            primaryPlayer.setMediaItem(mediaItem!!)
            primaryPlayer.seekTo(currentPosition)
            primaryPlayer.volume = 1f
            primaryPlayer.playWhenReady = true

            secondary.release()
            secondaryPlayer = null
        }

        isCrossfading = false
    }

    private fun createSecondaryPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setRenderersFactory(createRenderersFactory())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            .build()
    }

    fun shouldStartCrossfade(currentPosition: Long, duration: Long): Boolean {
        if (!crossfadeEnabled.value || isCrossfading) return false

        val crossfadeDurationMs = crossfadeDuration.value * 1000L
        val timeRemaining = duration - currentPosition

        return timeRemaining <= crossfadeDurationMs && timeRemaining > 0
    }

    fun release() {
        crossfadeJob?.cancel()
        secondaryPlayer?.release()
        secondaryPlayer = null
    }
}

// --- MusicService ---
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {

    @Inject lateinit var database: MusicDatabase
    @Inject lateinit var lyricsHelper: LyricsHelper
    @Inject lateinit var syncUtils: SyncUtils
    @Inject lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private var scope = CoroutineScope(Dispatchers.Main) + Job()
    private val binder = MusicBinder()
    private lateinit var connectivityManager: ConnectivityManager

    private val audioQuality by enumPreference(
        this, AudioQualityKey, com.metrolist.music.constants.AudioQuality.AUTO
    )

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null

    val currentMediaMetadata = MutableStateFlow<com.metrolist.music.models.MediaMetadata?>(null)
    private val currentSong =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.song(mediaMetadata?.id)
        }.stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    private val normalizeFactor = MutableStateFlow(1f)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    lateinit var sleepTimer: SleepTimer

    @Inject @PlayerCache lateinit var playerCache: SimpleCache
    @Inject @DownloadCache lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    private var isAudioEffectSessionOpened = false
    private var discordRpc: DiscordRPC? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())

    // Crossfade variables
    private lateinit var crossfadeManager: CrossfadeManager
    private var crossfadeCheckJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this, { NOTIFICATION_ID }, CHANNEL_ID, R.string.music_player
            ).apply {
                setSmallIcon(R.drawable.small_icon)
            }
        )
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setRenderersFactory(createRenderersFactory())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                addListener(this@MusicService)
                sleepTimer = SleepTimer(scope, this)
                addListener(sleepTimer)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
            }

        // Initialize CrossfadeManager
        crossfadeManager = CrossfadeManager(
            scope = scope,
            primaryPlayer = player,
            context = this,
            createMediaSourceFactory = ::createMediaSourceFactory,
            createRenderersFactory = ::createRenderersFactory
        )

        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleLibrary = ::toggleLibrary
        }
        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            ).setBitmapLoader(CoilBitmapLoader(this, scope))
            .build()
        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

        combine(playerVolume, normalizeFactor) { playerVolume, normalizeFactor ->
            playerVolume * normalizeFactor
        }.collectLatest(scope) {
            player.volume = it
        }

        playerVolume.debounce(1000).collect(scope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
            if (song != null) {
                discordRpc?.updateSong(song)
            } else {
                discordRpc?.closeRPC()
            }
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database.lyrics(mediaMetadata.id).first() == null) {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(LyricsEntity(id = mediaMetadata.id, lyrics = lyrics))
                }
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                player.skipSilenceEnabled = it
            }

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
        ) { format, normalizeAudio ->
            format to normalizeAudio
        }.collectLatest(scope) { (format, normalizeAudio) ->
            normalizeFactor.value =
                if (normalizeAudio && format?.loudnessDb != null) {
                    min(10f.pow(-format.loudnessDb.toFloat() / 20), 1f)
                } else {
                    1f
                }
        }

        // Monitor Crossfade settings
        combine(
            dataStore.data.map { it[CrossfadeEnabledKey] ?: false },
            dataStore.data.map { it[CrossfadeDurationKey] ?: 3 }
        ) { enabled, duration ->
            crossfadeManager.updateSettings(enabled, duration)
        }.collect(scope) { }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { (key, enabled) ->
                if (discordRpc?.isRpcRunning() == true) {
                    discordRpc?.closeRPC()
                }
                discordRpc = null
                if (key != null && enabled) {
                    discordRpc = DiscordRPC(this, key)
                    currentSong.value?.let {
                        discordRpc?.updateSong(it)
                    }
                }
            }

        if (dataStore.get(PersistentQueueKey, true)) {
            runCatching {
                filesDir.resolve(PERSISTENT_QUEUE_FILE).inputStream().use { fis ->
                    ObjectInputStream(fis).use { oos ->
                        oos.readObject() as PersistQueue
                    }
                }
            }.onSuccess { queue ->
                playQueue(
                    queue = ListQueue(
                        title = queue.title,
                        items = queue.items.map { it.toMediaItem() },
                        startIndex = queue.mediaItemIndex,
                        position = queue.position,
                    ),
                    playWhenReady = false,
                )
            }
            runCatching {
                filesDir.resolve(PERSISTENT_AUTOMIX_FILE).inputStream().use { fis ->
                    ObjectInputStream(fis).use { oos ->
                        oos.readObject() as PersistQueue
                    }
                }
            }.onSuccess { queue ->
                automixItems.value = queue.items.map { it.toMediaItem() }
            }
        }

        // Start crossfade monitoring
        startCrossfadeMonitoring()

        // Save queue periodically to prevent queue loss from crash or force kill
        scope.launch {
            while (isActive) {
                delay(30.seconds)
                if (dataStore.get(PersistentQueueKey, true)) {
                    saveQueueToDisk()
                }
            }
        }
    }

    override fun onDestroy() {
        crossfadeManager.release()
        crossfadeCheckJob?.cancel()
        player.release()
        discordRpc?.closeRPC()
        scope.cancel()
        super.onDestroy()
    }

    private fun startCrossfadeMonitoring() {
        crossfadeCheckJob = scope.launch {
            while (isActive) {
                if (player.isPlaying && player.hasNextMediaItem()) {
                    val currentPosition = player.currentPosition
                    val duration = player.duration

                    if (duration > 0 && crossfadeManager.shouldStartCrossfade(currentPosition, duration)) {
                        val nextMediaItem = player.getMediaItemAt(player.currentMediaItemIndex + 1)
                        crossfadeManager.startCrossfade(nextMediaItem)
                    }
                }
                delay(500) // Check every half second
            }
        }
    }

    // ... (rest of your MusicService code remains unchanged, including playQueue, toggleLike, etc.)
    // For brevity, only the changed and improved sections are shown above.
    // Copy your remaining methods as they are unless you want further refactoring.

    // Helper functions for creating factories, toggling like, etc., remain as in your original code.
}
