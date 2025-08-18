package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ArtistFollowClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackLikeClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.clients.UserClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Request
import dev.brahmkshatriya.echo.common.models.Request.Companion.toRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.endpoints.EchoArtistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoArtistMoreEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoEditPlaylistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoLibraryEndPoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoLyricsEndPoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoPlaylistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSearchEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSearchSuggestionsEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongEndPoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongFeedEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongRelatedEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVideoEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState
import dev.toastbits.ytmkt.model.external.PlaylistEditor
import dev.toastbits.ytmkt.model.external.SongLikedStatus
import dev.toastbits.ytmkt.model.external.ThumbnailProvider.Quality.HIGH
import dev.toastbits.ytmkt.model.external.ThumbnailProvider.Quality.LOW
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.http.headers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

class YoutubeExtension : ExtensionClient, HomeFeedClient, TrackClient, SearchFeedClient,
    RadioClient, AlbumClient, ArtistClient, UserClient, PlaylistClient, LoginClient.WebView,
    TrackerClient, LibraryFeedClient, ShareClient, LyricsClient, ArtistFollowClient,
    TrackLikeClient, PlaylistEditClient {
    override val settingItems: List<Setting> = listOf(
        SettingSwitch(
            "Prefer Videos",
            "prefer_videos",
            "Prefer videos over audio when available",
            false
        ),
        SettingSwitch(
            "Show Videos",
            "show_videos",
            "Allows videos to be available when playing stuff. Instead of disabling videos, change the streaming quality as Medium in the app settings to select audio only by default.",
            true
        ),
        SettingSwitch(
            "Resolve to Music for Videos",
            "resolve_music_for_videos",
            "Resolve actual music metadata for music videos, does slow down loading music videos.",
            true
        ),
        SettingSwitch(
            "High Thumbnail Quality",
            "high_quality",
            "Use high quality thumbnails, will cause more data usage.",
            false
        ),
    )
    private lateinit var settings: Settings
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }
    val api = YoutubeiApi(
        data_language = ENGLISH
    )
    // Ensure visitor ID is initialized before any API calls
    private suspend fun ensureVisitorId() {
        try {
            println("DEBUG: Checking visitor ID, current: ${api.visitor_id}")
            if (api.visitor_id == null) {
                println("DEBUG: Getting new visitor ID")
                api.visitor_id = visitorEndpoint.getVisitorId()
                println("DEBUG: Got visitor ID: ${api.visitor_id}")
            } else {
                println("DEBUG: Visitor ID already exists: ${api.visitor_id}")
            }
        } catch (e: Exception) {
            println("DEBUG: Failed to initialize visitor ID: ${e.message}")
            // If visitor ID initialization fails, try to continue without it
            // Some endpoints might work without visitor ID
        }
    }
    private val thumbnailQuality
        get() = if (settings.getBoolean("high_quality") == true) HIGH else LOW
    private val resolveMusicForVideos
        get() = settings.getBoolean("resolve_music_for_videos") ?: true
    private val showVideos
        get() = settings.getBoolean("show_videos") ?: true
    private val preferVideos
        get() = settings.getBoolean("prefer_videos") ?: false
    private val language = ENGLISH
    private val visitorEndpoint = EchoVisitorEndpoint(api)
    private val songFeedEndPoint = EchoSongFeedEndpoint(api)
    private val artistEndPoint = EchoArtistEndpoint(api)
    private val artistMoreEndpoint = EchoArtistMoreEndpoint(api)
    private val libraryEndPoint = EchoLibraryEndPoint(api)
    private val songEndPoint = EchoSongEndPoint(api)
    private val songRelatedEndpoint = EchoSongRelatedEndpoint(api)
    private val videoEndpoint = EchoVideoEndpoint(api)
    private val playlistEndPoint = EchoPlaylistEndpoint(api)
    private val lyricsEndPoint = EchoLyricsEndPoint(api)
    private val searchSuggestionsEndpoint = EchoSearchSuggestionsEndpoint(api)
    private val searchEndpoint = EchoSearchEndpoint(api)
    private val editorEndpoint = EchoEditPlaylistEndpoint(api)
    companion object {
        const val ENGLISH = "en-GB"
        const val SINGLES = "Singles"
        const val SONGS = "songs"
    }
    override suspend fun getHomeTabs() = listOf<Tab>()
    override fun getHomeFeed(tab: Tab?) = PagedData.Continuous {
        val continuation = it
        val result = songFeedEndPoint.getSongFeed(
            params = null, continuation = continuation
        ).getOrThrow()
        val data = result.layouts.map { itemLayout ->
            itemLayout.toShelf(api, SINGLES, thumbnailQuality)
        }
        Page(data, result.ctoken)
    }.toFeed()
    private suspend fun searchSongForVideo(title: String, artists: String): Track? {
        val result = searchEndpoint.search(
            "$title $artists",
            "EgWKAQIIAWoSEAMQBBAJEA4QChAFEBEQEBAV",
            false
        ).getOrThrow().categories.firstOrNull()?.first?.items?.firstOrNull() ?: return null
        val mediaItem =
            result.toEchoMediaItem(false, thumbnailQuality) as EchoMediaItem.TrackItem
        if (mediaItem.title != title) return null
        val newTrack = songEndPoint.loadSong(mediaItem.id).getOrThrow()
        return newTrack
    }
    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean
    ): Streamable.Media {
        return when (streamable.type) {
            Streamable.MediaType.Server -> when (streamable.id) {
                "DUAL_STREAM" -> {
                    // Improved Dual-stream system
                    println("DEBUG: Loading improved dual-stream for videoId: ${streamable.extras["videoId"]}")
                    ensureVisitorId()

                    val videoId = streamable.extras["videoId"]
                        ?: throw IllegalArgumentException("Missing videoId for DUAL_STREAM")

                    var lastException: Exception? = null
                    val maxMainAttempts = 3 // Reduced main attempts, more granular retries
                    // Note: maxSubAttempts variable was declared but not used in the previous version, removing it.

                    for (mainAttempt in 1..maxMainAttempts) {
                        try {
                            println("DEBUG: Dual-stream main attempt $mainAttempt/$maxMainAttempts")

                            // Potentially reset visitor ID periodically or on specific failures
                            if (mainAttempt > 1) {
                                println("DEBUG: Resetting visitor ID before main attempt $mainAttempt")
                                api.visitor_id = null
                                ensureVisitorId()
                            }

                            // --- Attempt to fetch video data ---
                            val videoData: Pair<dev.toastbits.ytmkt.model.internal.Video, String>? = try {
                                withTimeout(10_000) { // 10 second timeout for initial fetch
                                    videoEndpoint.getVideo(useHLS = true, videoId = videoId) // Get data once
                                }
                            } catch (e: Exception) {
                                println("DEBUG: Failed to fetch video data on main attempt $mainAttempt: ${e.message}")
                                lastException = e
                                // Don't return yet, try the next main attempt
                                continue
                            }
                            val (video, type) = videoData ?: continue // If null, retry main loop

                            val allSources = mutableListOf<Streamable.Source.Http>()
                            var hlsSuccess = false
                            var mp3Success = false

                            // --- Fetch HLS Stream ---
                            try {
                                val hlsUrl = video.streamingData.hlsManifestUrl
                                if (!hlsUrl.isNullOrEmpty()) {
                                    val freshHlsUrl = addCacheBustingParams(hlsUrl, "hls_main_$mainAttempt")
                                    println("DEBUG: Adding primary HLS stream: $freshHlsUrl")
                                    allSources.add(
                                        Streamable.Source.Http(
                                            freshHlsUrl.toRequest(),
                                            quality = 1080 // Assign a standard quality value for HLS
                                        )
                                    )
                                    hlsSuccess = true
                                } else {
                                    println("DEBUG: No HLS URL found in video data for attempt $mainAttempt")
                                }
                            } catch (e: Exception) {
                                println("DEBUG: Error processing HLS stream on main attempt $mainAttempt: ${e.message}")
                                // Continue trying MP3 even if HLS fails initially
                            }

                            // --- Fetch MP3 Streams ---
                            try {
                                val audioFormats = video.streamingData.adaptiveFormats.filter {
                                    it.mimeType.contains("audio")
                                }
                                if (audioFormats.isNotEmpty()) {
                                    for ((index, format) in audioFormats.withIndex()) {
                                        // --- FIX: Correctly access 'url' and 'audioSampleRate' from 'format' ---
                                        format.url?.let { originalUrl ->
                                            val freshMp3Url = addCacheBustingParams(originalUrl, "mp3_${mainAttempt}_${index}")
                                            // --- FIX: Correctly access 'audioSampleRate' from 'format' ---
                                            val quality = format.audioSampleRate.toIntOrNull() ?: 128000 // Default if parsing fails
                                            println("DEBUG: Adding MP3 stream (quality: $quality): $freshMp3Url")
                                            allSources.add(
                                                Streamable.Source.Http(
                                                    freshMp3Url.toRequest(),
                                                    quality = quality
                                                )
                                            )
                                        }
                                    }
                                    // --- FIX/CLARIFICATION: Check if any non-HLS sources (MP3) were added ---
                                    // The previous check 'it.quality != 1080' was slightly off logic.
                                    // Let's just check if we added any MP3 sources based on the loop.
                                    // A simple way is to check if the size increased beyond potential HLS source.
                                    // Or, set a flag inside the loop (mp3Success).
                                    // For now, let's assume if the audioFormats list wasn't empty and the loop ran,
                                    // we intended to add MP3 sources (even if some failed). The flag is simpler.
                                    if (audioFormats.any { it.url != null }) { // Check if any format had a URL to add
                                         mp3Success = true
                                    }
                                } else {
                                    println("DEBUG: No MP3 formats found in video data for attempt $mainAttempt")
                                }
                            } catch (e: Exception) {
                                println("DEBUG: Error processing MP3 streams on main attempt $mainAttempt: ${e.message}")
                                // Don't let MP3 processing failure stop the whole attempt if HLS succeeded
                            }

                            // --- Evaluate Success ---
                            if (allSources.isNotEmpty()) {
                                val isLive = type.contains("live", ignoreCase = true) // Heuristic
                                println("DEBUG: Dual-stream main attempt $mainAttempt succeeded. Sources: ${allSources.size}, HLS: $hlsSuccess, MP3: $mp3Success, Live: $isLive")
                                return Streamable.Media.Server(allSources, isLive)
                            } else {
                                println("DEBUG: Dual-stream main attempt $mainAttempt failed - no valid sources found.")
                                lastException = Exception("No valid HLS or MP3 sources found")
                            }

                        } catch (e: Exception) {
                            println("DEBUG: Dual-stream main attempt $mainAttempt encountered an exception: ${e::class.simpleName}: ${e.message}")
                            lastException = e
                            // Small delay before next main attempt, unless it's a clear network issue
                            if (mainAttempt < maxMainAttempts) {
                               val isNetworkError = e is SocketTimeoutException ||
                                                    e is UnknownHostException ||
                                                    e is ConnectTimeoutException ||
                                                    e is SSLException ||
                                                    (e is ClientRequestException && e.response.status.value in 400..599)
                               if (!isNetworkError) {
                                   kotlinx.coroutines.delay(500) // Short delay for non-network issues
                               }
                            }
                        }
                    }

                    // --- All Main Attempts Failed ---
                    println("DEBUG: All dual-stream main attempts failed. Throwing last exception.")
                    throw lastException ?: Exception("Failed to load DUAL_STREAM after $maxMainAttempts attempts.")

                }
                "VIDEO_M3U8" -> {
                    // Legacy HLS support - kept for compatibility
                    // Ensure visitor ID is initialized
                    ensureVisitorId()
                    println("DEBUG: Refreshing HLS URL for videoId: ${streamable.extras["videoId"]}")
                    // Enhanced retry mechanism with more attempts
                    var lastError: Exception? = null
                    for (attempt in 1..8) { // Try up to 8 times
                        try {
                            println("DEBUG: HLS Attempt $attempt of 8")
                            // Vary parameters based on attempt number
                            val useDifferentParams = attempt % 2 == 0
                            val resetVisitor = attempt > 4 // Reset visitor ID after 4 attempts
                            if (resetVisitor) {
                                println("DEBUG: Resetting visitor ID on attempt $attempt")
                                api.visitor_id = null
                                ensureVisitorId()
                            }
                            val (video, _) = videoEndpoint.getVideo(useDifferentParams, streamable.extras["videoId"]!!)
                            val hlsManifestUrl = video.streamingData.hlsManifestUrl!!
                            println("DEBUG: Got HLS URL on attempt $attempt: $hlsManifestUrl")
                            // Add future-dated cache-busting parameters for longer lifespan
                            val baseTimestamp = System.currentTimeMillis()
                            val futureTimestamp = baseTimestamp + (4 * 60 * 60 * 1000) // +4 hours to extend lifespan
                            val random = java.util.Random().nextInt(1000000) + attempt
                            val sessionId = "session_${System.currentTimeMillis()}_${attempt}"
                            val freshUrl = if (hlsManifestUrl.contains("?")) {
                                "$hlsManifestUrl&cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=$attempt"
                            } else {
                                "$hlsManifestUrl?cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=$attempt"
                            }
                            println("DEBUG: Final URL on attempt $attempt: $freshUrl")
                            // Create multiple backup URLs with different timestamps for mid-stream failure recovery
                            val backupUrls = mutableListOf<Streamable.Source.Http>()
                            // Primary URL (current timestamp)
                            backupUrls.add(
                                Streamable.Source.Http(
                                    freshUrl.toRequest(),
                                    quality = 0
                                )
                            )
                            // Backup URL 1 (+1 hour)
                            val backup1Timestamp = baseTimestamp + (1 * 60 * 60 * 1000)
                            val backup1Url = if (hlsManifestUrl.contains("?")) {
                                "$hlsManifestUrl&cachebuster=$backup1Timestamp&future=$futureTimestamp&rand=${random + 1}&session=$sessionId&attempt=${attempt}_backup1"
                            } else {
                                "$hlsManifestUrl?cachebuster=$backup1Timestamp&future=$futureTimestamp&rand=${random + 1}&session=$sessionId&attempt=${attempt}_backup1"
                            }
                            backupUrls.add(
                                Streamable.Source.Http(
                                    backup1Url.toRequest(),
                                    quality = 1
                                )
                            )
                            // Backup URL 2 (+2 hours)
                            val backup2Timestamp = baseTimestamp + (2 * 60 * 60 * 1000)
                            val backup2Url = if (hlsManifestUrl.contains("?")) {
                                "$hlsManifestUrl&cachebuster=$backup2Timestamp&future=$futureTimestamp&rand=${random + 2}&session=$sessionId&attempt=${attempt}_backup2"
                            } else {
                                "$hlsManifestUrl?cachebuster=$backup2Timestamp&future=$futureTimestamp&rand=${random + 2}&session=$sessionId&attempt=${attempt}_backup2"
                            }
                            backupUrls.add(
                                Streamable.Source.Http(
                                    backup2Url.toRequest(),
                                    quality = 2
                                )
                            )
                            // Use toServerMedia converter with multiple sources
                            return Streamable.Media.Server(backupUrls, true)
                        } catch (e: Exception) {
                            lastError = e
                            println("DEBUG: HLS Attempt $attempt failed: ${e.message}")
                            // Small randomized delay between attempts to avoid rate limiting
                            if (attempt < 8) {
                                val delayTime = 200L + java.util.Random().nextInt(100) // 200-300ms random delay
                                kotlinx.coroutines.delay(delayTime)
                            }
                        }
                    }
                    // All attempts failed, throw the last error
                    throw lastError ?: Exception("All HLS attempts failed")
                }
                "AUDIO_MP3" -> {
                    // Ensure visitor ID is initialized
                    ensureVisitorId()
                    println("DEBUG: Refreshing audio URLs for videoId: ${streamable.extras["videoId"]}")
                    // Enhanced retry mechanism with more attempts
                    var lastError: Exception? = null
                    for (attempt in 1..8) { // Try up to 8 times
                        try {
                            println("DEBUG: Audio Attempt $attempt of 8")
                            // Vary parameters based on attempt number
                            val useDifferentParams = attempt % 2 == 0
                            val resetVisitor = attempt > 4 // Reset visitor ID after 4 attempts
                            if (resetVisitor) {
                                println("DEBUG: Resetting visitor ID on attempt $attempt")
                                api.visitor_id = null
                                ensureVisitorId()
                            }
                            val (video, _) = videoEndpoint.getVideo(useDifferentParams, streamable.extras["videoId"]!!)
                            val audioFiles = video.streamingData.adaptiveFormats.mapNotNull {
                                if (!it.mimeType.contains("audio")) return@mapNotNull null
                                val originalUrl = it.url!!
                                // Add future-dated cache-busting parameters for longer lifespan
                                val baseTimestamp = System.currentTimeMillis()
                                val futureTimestamp = baseTimestamp + (4 * 60 * 60 * 1000) // +4 hours to extend lifespan
                                val random = java.util.Random().nextInt(1000000) + attempt
                                val sessionId = "session_${System.currentTimeMillis()}_${attempt}"
                                val freshUrl = if (originalUrl.contains("?")) {
                                    "$originalUrl&cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=$attempt"
                                } else {
                                    "$originalUrl?cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=$attempt"
                                }
                                println("DEBUG: Audio URL ${it.audioSampleRate}Hz on attempt $attempt: $freshUrl")
                                it.audioSampleRate.toString() to freshUrl
                            }.toMap()
                            println("DEBUG: Audio attempt $attempt total formats: ${audioFiles.size}")
                            if (audioFiles.isNotEmpty()) {
                                // Create enhanced audio sources with backup URLs for each quality
                                val enhancedAudioSources = mutableListOf<Streamable.Source.Http>()
                                audioFiles.forEach { (quality, primaryUrl) ->
                                    // Primary URL
                                    enhancedAudioSources.add(
                                        Streamable.Source.Http(
                                            primaryUrl.toRequest(),
                                            quality = quality.toIntOrNull() ?: 0
                                        )
                                    )
                                    // Create backup URLs with different timestamps for this quality
                                    val baseTimestamp = System.currentTimeMillis()
                                    val futureTimestamp = baseTimestamp + (4 * 60 * 60 * 1000)
                                    val random = java.util.Random().nextInt(1000000) + attempt
                                    val sessionId = "session_${System.currentTimeMillis()}_${attempt}"
                                    // Extract original URL without parameters
                                    val originalUrl = audioFiles.entries.firstOrNull { it.value == primaryUrl }?.key?.let { sampleRate ->
                                        video.streamingData.adaptiveFormats.find { it.audioSampleRate.toString() == sampleRate }?.url
                                    } ?: primaryUrl.split("?")[0]
                                    // Backup URL 1 (+1 hour)
                                    val backup1Timestamp = baseTimestamp + (1 * 60 * 60 * 1000)
                                    val backup1Url = if (originalUrl.contains("?")) {
                                        "$originalUrl&cachebuster=$backup1Timestamp&future=$futureTimestamp&rand=${random + 1}&session=$sessionId&attempt=${attempt}_backup1"
                                    } else {
                                        "$originalUrl?cachebuster=$backup1Timestamp&future=$futureTimestamp&rand=${random + 1}&session=$sessionId&attempt=${attempt}_backup1"
                                    }
                                    enhancedAudioSources.add(
                                        Streamable.Source.Http(
                                            backup1Url.toRequest(),
                                            quality = quality.toIntOrNull()?.plus(1000) ?: 1000 // Higher quality number for backup
                                        )
                                    )
                                    // Backup URL 2 (+2 hours)
                                    val backup2Timestamp = baseTimestamp + (2 * 60 * 60 * 1000)
                                    val backup2Url = if (originalUrl.contains("?")) {
                                        "$originalUrl&cachebuster=$backup2Timestamp&future=$futureTimestamp&rand=${random + 2}&session=$sessionId&attempt=${attempt}_backup2"
                                    } else {
                                        "$originalUrl?cachebuster=$backup2Timestamp&future=$futureTimestamp&rand=${random + 2}&session=$sessionId&attempt=${attempt}_backup2"
                                    }
                                    enhancedAudioSources.add(
                                        Streamable.Source.Http(
                                            backup2Url.toRequest(),
                                            quality = quality.toIntOrNull()?.plus(2000) ?: 2000 // Even higher for second backup
                                        )
                                    )
                                }
                                println("DEBUG: Created ${enhancedAudioSources.size} audio sources (primary + backups)")
                                return Streamable.Media.Server(enhancedAudioSources, false)
                            } else {
                                throw Exception("No audio formats found on attempt $attempt")
                            }
                        } catch (e: Exception) {
                            lastError = e
                            println("DEBUG: Audio Attempt $attempt failed: ${e.message}")
                            // Small randomized delay between attempts to avoid rate limiting
                            if (attempt < 8) {
                                val delayTime = 200L + java.util.Random().nextInt(100) // 200-300ms random delay
                                kotlinx.coroutines.delay(delayTime)
                            }
                        }
                    }
                    // All attempts failed, throw the last error
                    throw lastError ?: Exception("All audio attempts failed")
                }
                else -> throw IllegalArgumentException("Unknown server streamable ID: ${streamable.id}")
            }
            // Add other MediaType cases to make when exhaustive
            Streamable.MediaType.Background -> throw IllegalArgumentException("Background media type not supported")
            Streamable.MediaType.Subtitle -> throw IllegalArgumentException("Subtitle media type not supported")
        }
    }

    // Helper function to add cache-busting parameters
    private fun addCacheBustingParams(originalUrl: String, suffix: String): String {
        val baseTimestamp = System.currentTimeMillis()
        val futureTimestamp = baseTimestamp + (4 * 60 * 60 * 1000) // +4 hours
        val random = java.util.Random().nextInt(1000000)
        val sessionId = "session_${System.currentTimeMillis()}_$suffix"
        return if (originalUrl.contains("?")) {
            "$originalUrl&cb_ts=$baseTimestamp&cb_future=$futureTimestamp&cb_rand=$random&cb_session=$sessionId&cb_suffix=$suffix"
        } else {
            "$originalUrl?cb_ts=$baseTimestamp&cb_future=$futureTimestamp&cb_rand=$random&cb_session=$sessionId&cb_suffix=$suffix"
        }
    }

    override suspend fun loadTrack(track: Track) = coroutineScope {
        // Ensure visitor ID is initialized
        ensureVisitorId()
        println("DEBUG: Loading track: ${track.title} (${track.id})")
        val deferred = async { songEndPoint.loadSong(track.id).getOrThrow() }
        val (video, type) = videoEndpoint.getVideo(true, track.id)
        val isMusic = type == "MUSIC_VIDEO_TYPE_ATV"
        println("DEBUG: Video type: $type, isMusic: $isMusic")
        val resolvedTrack = if (resolveMusicForVideos && !isMusic) {
            searchSongForVideo(video.videoDetails.title!!, video.videoDetails.author)
        } else null
        val hlsUrl = video.streamingData.hlsManifestUrl!!
        val audioFiles = video.streamingData.adaptiveFormats.mapNotNull {
            if (!it.mimeType.contains("audio")) return@mapNotNull null
            it.audioSampleRate.toString() to it.url!!
        }.toMap()
        println("DEBUG: Audio formats found: ${audioFiles.keys}")
        println("DEBUG: HLS URL available: ${hlsUrl.isNotEmpty()}")
        val newTrack = resolvedTrack ?: deferred.await()
        val resultTrack = newTrack.copy(
            description = video.videoDetails.shortDescription,
            artists = newTrack.artists.ifEmpty {
                video.videoDetails.run { listOf(Artist(channelId, author)) }
            },
            streamables = listOfNotNull(
                Streamable.server(
                    "DUAL_STREAM",
                    0,
                    "Dual Stream (HLS + MP3)",
                    mapOf("videoId" to track.id)
                ).takeIf { !isMusic && (showVideos || audioFiles.isNotEmpty()) },
                Streamable.server(
                    "VIDEO_M3U8",
                    0,
                    "Video M3U8",
                    mapOf("videoId" to track.id)
                ).takeIf { !isMusic && showVideos && audioFiles.isEmpty() }, // Fallback if no audio files
                Streamable.server(
                    "AUDIO_MP3",
                    0,
                    "Audio MP3",
                    mutableMapOf<String, String>().apply { put("videoId", track.id) }
                ).takeIf { audioFiles.isNotEmpty() && (!showVideos || isMusic) }, // Fallback if videos disabled
            ).let { if (preferVideos) it else it.reversed() },
            plays = video.videoDetails.viewCount?.toLongOrNull()
        )
        println("DEBUG: Streamables created: ${resultTrack.streamables.size}")
        resultTrack.streamables.forEach { streamable ->
            println("DEBUG: Streamable: ${streamable.id} with extras: ${streamable.extras.keys}")
        }
        resultTrack
    }
    private suspend fun loadRelated(track: Track) = track.run {
        val relatedId = extras["relatedId"] ?: throw Exception("No related id found.")
        songFeedEndPoint.getSongFeed(browseId = relatedId).getOrThrow().layouts.map {
            it.toShelf(api, SINGLES, thumbnailQuality)
        }
    }
    override fun getShelves(track: Track) = PagedData.Single { loadRelated(track) }
    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        searchSuggestionsEndpoint.delete(item as QuickSearchItem.Query)
    }
    override suspend fun quickSearch(query: String) = query.takeIf { it.isNotBlank() }?.run {
        try {
            api.SearchSuggestions.getSearchSuggestions(this).getOrThrow()
                .map { QuickSearchItem.Query(it.text, it.is_from_history) }
        } catch (e: NullPointerException) {
            null
        } catch (e: ConnectTimeoutException) {
            null
        }
    } ?: listOf()
    private var oldSearch: Pair<String, List<Shelf>>? = null
    override fun searchFeed(query: String, tab: Tab?) = if (query.isNotBlank()) PagedData.Single {
        val old = oldSearch?.takeIf {
            it.first == query && (tab == null || tab.id == "All")
        }?.second
        if (old != null) return@Single old
        val search = api.Search.search(query, tab?.id).getOrThrow()
        search.categories.map { (itemLayout, _) ->
            itemLayout.items.mapNotNull { item ->
                item.toEchoMediaItem(false, thumbnailQuality)?.toShelf()
            }
        }.flatten()
    }.toFeed() else if (tab != null) PagedData.Continuous {
        val params = tab.id
        val continuation = it
        val result = songFeedEndPoint.getSongFeed(
            params = params, continuation = continuation
        ).getOrThrow()
        val data = result.layouts.map { itemLayout ->
            itemLayout.toShelf(api, SINGLES, thumbnailQuality)
        }
        Page(data, result.ctoken)
    }.toFeed() else PagedData.Single<Shelf> { listOf() }.toFeed()
    override suspend fun searchTabs(query: String): List<Tab> {
        if (query.isNotBlank()) {
            val search = api.Search.search(query, null).getOrThrow()
            oldSearch = query to search.categories.map { (itemLayout, _) ->
                itemLayout.toShelf(api, SINGLES, thumbnailQuality)
            }
            val tabs = search.categories.mapNotNull { (item, filter) ->
                filter?.let {
                    Tab(
                        it.params, item.title?.getString(language) ?: "???"
                    )
                }
            }
            return listOf(Tab("All", "All")) + tabs
        } else {
            val result = songFeedEndPoint.getSongFeed().getOrThrow()
            return result.filter_chips?.map {
                Tab(it.params, it.text.getString(language))
            } ?: emptyList()
        }
    }
    override fun loadTracks(radio: Radio) =
        PagedData.Single { json.decodeFromString<List<Track>>(radio.extras["tracks"]!!) }
    override suspend fun radio(album: Album): Radio {
        val track = api.LoadPlaylist.loadPlaylist(album.id).getOrThrow().items
            ?.lastOrNull()?.toTrack(HIGH)
            ?: throw Exception("No tracks found")
        return radio(track, null)
    }
    override suspend fun radio(artist: Artist): Radio {
        val id = "radio_${artist.id}"
        val result = api.ArtistRadio.getArtistRadio(artist.id, null).getOrThrow()
        val tracks = result.items.map { song -> song.toTrack(thumbnailQuality) }
        return Radio(
            id = id,
            title = "${artist.name} Radio",
            extras = mutableMapOf<String, String>().apply {
                put("tracks", json.encodeToString(tracks))
            }
        )
    }
    override suspend fun radio(track: Track, context: EchoMediaItem?): Radio {
        val id = "radio_${track.id}"
        val cont = (context as? EchoMediaItem.Lists.RadioItem)?.radio?.extras?.get("cont")
        val result = api.SongRadio.getSongRadio(track.id, cont).getOrThrow()
        val tracks = result.items.map { song -> song.toTrack(thumbnailQuality) }
        return Radio(
            id = id,
            title = "${track.title} Radio",
            extras = mutableMapOf<String, String>().apply {
                put("tracks", json.encodeToString(tracks))
                result.continuation?.let { put("cont", it) }
            }
        )
    }
    override suspend fun radio(user: User) = radio(user.toArtist())
    override suspend fun radio(playlist: Playlist): Radio {
        val track = loadTracks(playlist).loadAll().lastOrNull()
            ?: throw Exception("No tracks found")
        return radio(track, null)
    }
    override fun getShelves(album: Album): PagedData<Shelf> = PagedData.Single {
        loadTracks(album).loadAll().lastOrNull()?.let { loadRelated(loadTrack(it)) }
            ?: emptyList()
    }
    private val trackMap = mutableMapOf<String, PagedData<Track>>()
    override suspend fun loadAlbum(album: Album): Album {
        val (ytmPlaylist, _, data) = playlistEndPoint.loadFromPlaylist(
            album.id, null, thumbnailQuality
        )
        trackMap[ytmPlaylist.id] = data
        return ytmPlaylist.toAlbum(false, HIGH)
    }
    override fun loadTracks(album: Album): PagedData<Track> = trackMap[album.id]!!
    private suspend fun getArtistMediaItems(artist: Artist): List<Shelf> {
        val result =
            loadedArtist.takeIf { artist.id == it?.id } ?: api.LoadArtist.loadArtist(artist.id)
                .getOrThrow()
        return result.layouts?.map {
            val title = it.title?.getString(ENGLISH)
            val single = title == SINGLES
            Shelf.Lists.Items(
                title = it.title?.getString(language) ?: "Unknown",
                subtitle = it.subtitle?.getString(language),
                list = it.items?.mapNotNull { item ->
                    item.toEchoMediaItem(single, thumbnailQuality)
                } ?: emptyList(),
                more = it.view_more?.getBrowseParamsData()?.let { param ->
                    PagedData.Single {
                        val data = artistMoreEndpoint.load(param)
                        data.map { row ->
                            row.items.mapNotNull { item ->
                                item.toEchoMediaItem(single, thumbnailQuality)
                            }
                        }.flatten()
                    }
                })
        } ?: emptyList()
    }
    override fun getShelves(artist: Artist) = PagedData.Single {
        getArtistMediaItems(artist)
    }
    override fun getShelves(user: User) = getShelves(user.toArtist())
    override suspend fun loadUser(user: User): User {
        loadArtist(user.toArtist())
        return loadedArtist!!.toUser(HIGH)
    }
    override suspend fun followArtist(artist: Artist, follow: Boolean) {
        val subId = artist.extras["subId"] ?: throw Exception("No subId found")
        withUserAuth { it.SetSubscribedToArtist.setSubscribedToArtist(artist.id, follow, subId) }
    }
    private var loadedArtist: YtmArtist? = null
    override suspend fun loadArtist(artist: Artist): Artist {
        val result = artistEndPoint.loadArtist(artist.id)
        loadedArtist = result
        return result.toArtist(HIGH)
    }
    override fun getShelves(playlist: Playlist) = PagedData.Single {
        val cont = playlist.extras["relatedId"] ?: throw Exception("No related id found.")
        if (cont.startsWith("id://")) {
            val id = cont.substring(5)
            getShelves(loadTrack(Track(id, ""))).loadList(null).data
                .filterIsInstance<Shelf.Category>()
        } else {
            val continuation = songRelatedEndpoint.loadFromPlaylist(cont).getOrThrow()
            continuation.map { it.toShelf(api, language, thumbnailQuality) }
        }
    }
    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val (ytmPlaylist, related, data) = playlistEndPoint.loadFromPlaylist(
            playlist.id,
            null,
            thumbnailQuality
        )
        trackMap[ytmPlaylist.id] = data
        return ytmPlaylist.toPlaylist(HIGH, related)
    }
    override fun loadTracks(playlist: Playlist): PagedData<Track> = trackMap[playlist.id]!!
    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val initialUrl =
            "https://accounts.google.com/v3/signin/identifier?dsh=S1527412391%3A1678373417598386&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den-GB%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F%253Fcbrd%253D1%26feature%3D__FEATURE__&hl=en-GB&ifkv=AWnogHfK4OXI8X1zVlVjzzjybvICXS4ojnbvzpE4Gn_Pfddw7fs3ERdfk-q3tRimJuoXjfofz6wuzg&ltmpl=music&passive=true&service=youtube&uilel=3&flowName=GlifWebSignIn&flowEntry=ServiceLogin".toRequest()
        override val stopUrlRegex = "https://music\\.youtube\\.com/.*".toRegex()
        override suspend fun onStop(url: Request, cookie: String): List<User> {
            if (!cookie.contains("SAPISID")) throw Exception("Login Failed, could not load SAPISID")
            val auth = run {
                val currentTime = System.currentTimeMillis() / 1000
                val id = cookie.split("SAPISID=")[1].split(";")[0]
                val str = "$currentTime $id https://music.youtube.com"
                val idHash = MessageDigest.getInstance("SHA-1").digest(str.toByteArray())
                    .joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
                "SAPISIDHASH ${currentTime}_${idHash}"
            }
            val headersMap = mutableMapOf("cookie" to cookie, "authorization" to auth)
            val headers = headers { headersMap.forEach { (t, u) -> append(t, u) } }
            return api.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
                headers {
                    append("referer", "https://music.youtube.com/")
                    appendAll(headers)
                }
            }.getUsers(cookie, auth)
        }
    }
    override suspend fun onSetLoginUser(user: User?) {
        if (user == null) {
            api.user_auth_state = null
        } else {
            val cookie = user.extras["cookie"] ?: throw Exception("No cookie")
            val auth = user.extras["auth"] ?: throw Exception("No auth")
            val headers = headers {
                append("cookie", cookie)
                append("authorization", auth)
            }
            val authenticationState =
                YoutubeiAuthenticationState(api, headers, user.id.ifEmpty { null })
            api.user_auth_state = authenticationState
        }
        api.visitor_id = visitorEndpoint.getVisitorId()
    }
    override suspend fun getCurrentUser(): User? {
        val headers = api.user_auth_state?.headers ?: return null
        return api.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
            headers {
                append("referer", "https://music.youtube.com/")
                appendAll(headers)
            }
        }.getUsers("", "").firstOrNull()
    }
    override val markAsPlayedDuration = 30000L
    override suspend fun onMarkAsPlayed(details: TrackDetails) {
        api.user_auth_state?.MarkSongAsWatched?.markSongAsWatched(details.track.id)?.getOrThrow()
    }
    override suspend fun getLibraryTabs() = listOf(
        Tab("FEmusic_library_landing", "All"),
        Tab("FEmusic_history", "History"),
        Tab("FEmusic_liked_playlists", "Playlists"),
//        Tab("FEmusic_listening_review", "Review"),
        Tab("FEmusic_liked_videos", "Songs"),
        Tab("FEmusic_library_corpus_track_artists", "Artists")
    )
    private suspend fun <T> withUserAuth(
        block: suspend (auth: YoutubeiAuthenticationState) -> T
    ): T {
        val state = api.user_auth_state
            ?: throw ClientException.LoginRequired()
        return runCatching { block(state) }.getOrElse {
            if (it is ClientRequestException) {
                if (it.response.status.value == 401) {
                    val user = state.own_channel_id
                        ?: throw ClientException.LoginRequired()
                    throw ClientException.Unauthorized(user)
                }
            }
            throw it
        }
    }
    override fun getLibraryFeed(tab: Tab?) = PagedData.Continuous<Shelf> { cont ->
        val browseId = tab?.id ?: "FEmusic_library_landing"
        val (result, ctoken) = withUserAuth { libraryEndPoint.loadLibraryFeed(browseId, cont) }
        val data = result.mapNotNull { playlist ->
            playlist.toEchoMediaItem(false, thumbnailQuality)?.toShelf()
        }
        Page(data, ctoken)
    }.toFeed()
    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        val playlistId = withUserAuth {
            it.CreateAccountPlaylist
                .createAccountPlaylist(title, description ?: "")
                .getOrThrow()
        }
        return loadPlaylist(Playlist(playlistId, "", true))
    }
    override suspend fun deletePlaylist(playlist: Playlist) = withUserAuth {
        it.DeleteAccountPlaylist.deleteAccountPlaylist(playlist.id).getOrThrow()
    }
    override suspend fun likeTrack(track: Track, isLiked: Boolean) {
        val likeStatus = if (isLiked) SongLikedStatus.LIKED else SongLikedStatus.NEUTRAL
        withUserAuth { it.SetSongLiked.setSongLiked(track.id, likeStatus).getOrThrow() }
    }
    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> =
        withUserAuth { auth ->
            auth.AccountPlaylists.getAccountPlaylists().getOrThrow().mapNotNull {
                if (it.id != "VLSE") it.toPlaylist(thumbnailQuality) to false
                else null
            }
        }
    override suspend fun editPlaylistMetadata(
        playlist: Playlist, title: String, description: String?
    ) {
        withUserAuth { auth ->
            val editor = auth.AccountPlaylistEditor.getEditor(playlist.id, listOf(), listOf())
            editor.performAndCommitActions(
                listOfNotNull(
                    PlaylistEditor.Action.SetTitle(title),
                    description?.let { PlaylistEditor.Action.SetDescription(it) }
                )
            )
        }
    }
    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist, tracks: List<Track>, indexes: List<Int>
    ) {
        val actions = indexes.map {
            val track = tracks[it]
            EchoEditPlaylistEndpoint.Action.Remove(track.id, track.extras["setId"]!!)
        }
        editorEndpoint.editPlaylist(playlist.id, actions)
    }
    override suspend fun addTracksToPlaylist(
        playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>
    ) {
        val actions = new.map { EchoEditPlaylistEndpoint.Action.Add(it.id) }
        val setIds = editorEndpoint.editPlaylist(playlist.id, actions).playlistEditResults!!.map {
            it.playlistEditVideoAddedResultData.setVideoId
        }
        val addBeforeTrack = tracks.getOrNull(index)?.extras?.get("setId") ?: return
        val moveActions = setIds.map { setId ->
            EchoEditPlaylistEndpoint.Action.Move(setId, addBeforeTrack)
        }
        editorEndpoint.editPlaylist(playlist.id, moveActions)
    }
    override suspend fun moveTrackInPlaylist(
        playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int
    ) {
        val setId = tracks[fromIndex].extras["setId"]!!
        val before = if (fromIndex - toIndex > 0) 0 else 1
        val addBeforeTrack = tracks.getOrNull(toIndex + before)?.extras?.get("setId")
            ?: return
        editorEndpoint.editPlaylist(
            playlist.id, listOf(
                EchoEditPlaylistEndpoint.Action.Move(setId, addBeforeTrack)
            )
        )
    }
    override fun searchTrackLyrics(clientId: String, track: Track) = PagedData.Single {
        val lyricsId = track.extras["lyricsId"] ?: return@Single listOf()
        val data = lyricsEndPoint.getLyrics(lyricsId) ?: return@Single listOf()
        val lyrics = data.first.map {
            it.cueRange.run {
                Lyrics.Item(
                    it.lyricLine,
                    startTimeMilliseconds.toLong(),
                    endTimeMilliseconds.toLong()
                )
            }
        }
        listOf(Lyrics(lyricsId, track.title, data.second, Lyrics.Timed(lyrics)))
    }
    override suspend fun loadLyrics(lyrics: Lyrics) = lyrics
    override suspend fun onShare(item: EchoMediaItem) = when (item) {
        is EchoMediaItem.Lists.AlbumItem -> "https://music.youtube.com/browse/${item.id}"
        is EchoMediaItem.Lists.PlaylistItem -> "https://music.youtube.com/playlist?list=${item.id}"
        is EchoMediaItem.Lists.RadioItem -> "https://music.youtube.com/playlist?list=${item.id}"
        is EchoMediaItem.Profile.ArtistItem -> "https://music.youtube.com/channel/${item.id}"
        is EchoMediaItem.Profile.UserItem -> "https://music.youtube.com/channel/${item.id}"
        is EchoMediaItem.TrackItem -> "https://music.youtube.com/watch?v=${item.id}"
    }
}