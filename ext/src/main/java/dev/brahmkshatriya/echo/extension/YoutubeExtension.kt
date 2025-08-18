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
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

class YoutubeExtension : ExtensionClient, HomeFeedClient, TrackClient, SearchFeedClient,
    RadioClient, AlbumClient, ArtistClient, UserClient, PlaylistClient, LoginClient.WebView,
    TrackerClient, LibraryFeedClient, ShareClient, LyricsClient, ArtistFollowClient,
    TrackLikeClient, PlaylistEditClient {

    // Error handling state
    private var retryCount = 0
    private var currentAudioSourceIndex = 0
    private var lastErrorTime = 0L
    private var currentVideoId = ""
    
    // Server types for retry logic
    private val AUDIO_SOURCE_TYPES = listOf(
        "Audio MP3", "Audio MP4", "Audio WebM", "Combined MP4", "Combined WebM", "HLS Stream"
    )
    
    // Error handling constants
    private val MAX_RETRIES = 6
    private val RETRY_DELAY_MS = 1000L // 1 second between retries

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
    
    // Error handling and toast simulation functions
    private fun showToast(title: String, message: String, isLong: Boolean = false) {
        // This would interface with the Android app's toast system
        // For now, we'll just log it
        println("TOAST[$title]: $message")
        
        // In a real implementation, this would call:
        // android.widget.Toast.makeText(context, message, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
    
    private fun resetRetryState() {
        retryCount = 0
        currentAudioSourceIndex = 0
        lastErrorTime = 0L
    }
    
    private fun isRetryableError(error: Exception): Boolean {
        val errorMessage = error.message?.lowercase() ?: ""
        return errorMessage.contains("403") || 
               errorMessage.contains("forbidden") ||
               errorMessage.contains("skipping atom with length") ||
               errorMessage.contains("network error") ||
               error is ClientRequestException ||
               error is ConnectTimeoutException
    }
    
    private fun getNextAudioSource(): String {
        val nextSource = AUDIO_SOURCE_TYPES[currentAudioSourceIndex]
        currentAudioSourceIndex = (currentAudioSourceIndex + 1) % AUDIO_SOURCE_TYPES.size
        return nextSource
    }
    
    private suspend fun handleStreamError(error: Exception, currentSource: String, videoId: String): Streamable.Media {
        val currentTime = System.currentTimeMillis()
        
        if (isRetryableError(error) && retryCount < MAX_RETRIES) {
            val nextSource = getNextAudioSource()
            
            // Show retry toast with "Other server" message
            showToast(
                "Stream Error", 
                "Retrying with Other server (${retryCount + 1}/$MAX_RETRIES)",
                isLong = true
            )
            
            println("DEBUG: Error: ${error.message}")
            println("DEBUG: Retrying with $nextSource (attempt ${retryCount + 1}/$MAX_RETRIES)")
            
            // Update retry state
            retryCount++
            lastErrorTime = currentTime
            currentVideoId = videoId
            
            // Wait before retry
            kotlinx.coroutines.delay(RETRY_DELAY_MS)
            
            // Try the next audio source type
            return tryAudioSource(nextSource, videoId)
        } else {
            // Max retries reached or non-retryable error
            showToast(
                "Stream Error", 
                "Failed to load stream after $retryCount attempts. Please try again later.",
                isLong = true
            )
            
            println("DEBUG: Max retries reached or non-retryable error: ${error.message}")
            
            // Reset retry state
            resetRetryState()
            throw error
        }
    }
    
    private suspend fun tryAudioSource(sourceType: String, videoId: String): Streamable.Media {
        println("DEBUG: Trying audio source: $sourceType for videoId: $videoId")
        
        return when (sourceType) {
            "Audio MP3" -> loadAudioOnlyStream(videoId, "mp3")
            "Audio MP4" -> loadAudioOnlyStream(videoId, "mp4")
            "Audio WebM" -> loadAudioOnlyStream(videoId, "webm")
            "Combined MP4" -> loadCombinedStream(videoId, "mp4")
            "Combined WebM" -> loadCombinedStream(videoId, "webm")
            "HLS Stream" -> loadHlsStream(videoId)
            else -> throw IllegalArgumentException("Unknown audio source type: $sourceType")
        }
    }
    
    private suspend fun loadAudioOnlyStream(videoId: String, format: String): Streamable.Media {
        println("DEBUG: Loading audio-only stream: $format for videoId: $videoId")
        
        // Ensure visitor ID is initialized
        ensureVisitorId()
        
        var lastError: Exception? = null
        for (attempt in 1..3) { // Try 3 times for this specific format
            try {
                println("DEBUG: Audio-only $format attempt $attempt of 3")
                
                val useDifferentParams = attempt % 2 == 0
                val resetVisitor = attempt > 1
                
                if (resetVisitor) {
                    println("DEBUG: Resetting visitor ID for audio-only $format attempt $attempt")
                    api.visitor_id = null
                    ensureVisitorId()
                }
                
                val (video, _) = videoEndpoint.getVideo(useDifferentParams, videoId)
                val baseTimestamp = System.currentTimeMillis()
                val futureTimestamp = baseTimestamp + (4 * 60 * 60 * 1000)
                val random = java.util.Random().nextInt(1000000) + attempt
                val sessionId = "session_${System.currentTimeMillis()}_${attempt}"
                
                val audioSources = mutableListOf<Streamable.Source.Http>()
                
                video.streamingData.adaptiveFormats.forEach { format ->
                    val mimeType = format.mimeType.lowercase()
                    val originalUrl = format.url ?: return@forEach
                    
                    // Filter for audio-only formats of the requested type
                    val isTargetFormat = when (format) {
                        "mp3" -> mimeType.contains("audio/mp3") || mimeType.contains("audio/mpeg")
                        "mp4" -> mimeType.contains("audio/mp4")
                        "webm" -> mimeType.contains("audio/webm")
                        else -> false
                    }
                    
                    if (isTargetFormat && !mimeType.contains("video")) {
                        val freshUrl = if (originalUrl.contains("?")) {
                            "$originalUrl&cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=${attempt}_audio"
                        } else {
                            "$originalUrl?cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=${attempt}_audio"
                        }
                        
                        val quality = format.audioSampleRate?.toInt() ?: 0
                        audioSources.add(
                            Streamable.Source.Http(
                                freshUrl.toRequest(),
                                quality = quality
                            )
                        )
                    }
                }
                
                if (audioSources.isNotEmpty()) {
                    println("DEBUG: Found ${audioSources.size} audio-only $format sources")
                    return Streamable.Media.Server(audioSources, false)
                } else {
                    throw Exception("No audio-only $format streams found")
                }
                
            } catch (e: Exception) {
                lastError = e
                println("DEBUG: Audio-only $format attempt $attempt failed: ${e.message}")
                
                if (attempt < 3) {
                    val delayTime = 200L + java.util.Random().nextInt(100)
                    kotlinx.coroutines.delay(delayTime)
                }
            }
        }
        
        throw lastError ?: Exception("All audio-only $format attempts failed")
    }
    
    private suspend fun loadCombinedStream(videoId: String, format: String): Streamable.Media {
        println("DEBUG: Loading combined stream: $format for videoId: $videoId")
        
        // Ensure visitor ID is initialized
        ensureVisitorId()
        
        var lastError: Exception? = null
        for (attempt in 1..3) { // Try 3 times for this specific format
            try {
                println("DEBUG: Combined $format attempt $attempt of 3")
                
                val useDifferentParams = attempt % 2 == 0
                val resetVisitor = attempt > 1
                
                if (resetVisitor) {
                    println("DEBUG: Resetting visitor ID for combined $format attempt $attempt")
                    api.visitor_id = null
                    ensureVisitorId()
                }
                
                val (video, _) = videoEndpoint.getVideo(useDifferentParams, videoId)
                val baseTimestamp = System.currentTimeMillis()
                val futureTimestamp = baseTimestamp + (4 * 60 * 60 * 1000)
                val random = java.util.Random().nextInt(1000000) + attempt
                val sessionId = "session_${System.currentTimeMillis()}_${attempt}"
                
                val combinedSources = mutableListOf<Streamable.Source.Http>()
                
                video.streamingData.adaptiveFormats.forEach { format ->
                    val mimeType = format.mimeType.lowercase()
                    val originalUrl = format.url ?: return@forEach
                    
                    // Filter for combined audio+video formats of the requested type
                    val isTargetFormat = when (format) {
                        "mp4" -> mimeType.contains("video/mp4") && mimeType.contains("audio")
                        "webm" -> mimeType.contains("video/webm") && mimeType.contains("audio")
                        else -> false
                    }
                    
                    if (isTargetFormat) {
                        val freshUrl = if (originalUrl.contains("?")) {
                            "$originalUrl&cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=${attempt}_combined"
                        } else {
                            "$originalUrl?cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=${attempt}_combined"
                        }
                        
                        val quality = (format.height ?: 0) * 1000 + (format.bitrate / 1000).toInt()
                        combinedSources.add(
                            Streamable.Source.Http(
                                freshUrl.toRequest(),
                                quality = quality
                            )
                        )
                    }
                }
                
                if (combinedSources.isNotEmpty()) {
                    println("DEBUG: Found ${combinedSources.size} combined $format sources")
                    return Streamable.Media.Server(combinedSources, false)
                } else {
                    throw Exception("No combined $format streams found")
                }
                
            } catch (e: Exception) {
                lastError = e
                println("DEBUG: Combined $format attempt $attempt failed: ${e.message}")
                
                if (attempt < 3) {
                    val delayTime = 200L + java.util.Random().nextInt(100)
                    kotlinx.coroutines.delay(delayTime)
                }
            }
        }
        
        throw lastError ?: Exception("All combined $format attempts failed")
    }
    
    private suspend fun loadHlsStream(videoId: String): Streamable.Media {
        println("DEBUG: Loading HLS stream for videoId: $videoId")
        
        // Ensure visitor ID is initialized
        ensureVisitorId()
        
        var lastError: Exception? = null
        for (attempt in 1..3) { // Try 3 times for HLS
            try {
                println("DEBUG: HLS attempt $attempt of 3")
                
                val useDifferentParams = attempt % 2 == 0
                val resetVisitor = attempt > 1
                
                if (resetVisitor) {
                    println("DEBUG: Resetting visitor ID for HLS attempt $attempt")
                    api.visitor_id = null
                    ensureVisitorId()
                }
                
                val (video, _) = videoEndpoint.getVideo(useDifferentParams, videoId)
                val hlsManifestUrl = video.streamingData.hlsManifestUrl
                
                if (hlsManifestUrl != null) {
                    val baseTimestamp = System.currentTimeMillis()
                    val futureTimestamp = baseTimestamp + (4 * 60 * 60 * 1000)
                    val random = java.util.Random().nextInt(1000000) + attempt
                    val sessionId = "session_${System.currentTimeMillis()}_${attempt}"
                    
                    val freshUrl = if (hlsManifestUrl.contains("?")) {
                        "$hlsManifestUrl&cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=${attempt}_hls"
                    } else {
                        "$hlsManifestUrl?cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&attempt=${attempt}_hls"
                    }
                    
                    val hlsSources = listOf(
                        Streamable.Source.Http(freshUrl.toRequest(), quality = 0)
                    )
                    
                    println("DEBUG: HLS stream loaded successfully")
                    return Streamable.Media.Server(hlsSources, true)
                } else {
                    throw Exception("No HLS manifest URL found")
                }
                
            } catch (e: Exception) {
                lastError = e
                println("DEBUG: HLS attempt $attempt failed: ${e.message}")
                
                if (attempt < 3) {
                    val delayTime = 200L + java.util.Random().nextInt(100)
                    kotlinx.coroutines.delay(delayTime)
                }
            }
        }
        
        throw lastError ?: Exception("All HLS attempts failed")
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
                    // Enhanced multi-format system with comprehensive error handling
                    println("DEBUG: Loading multi-format stream for videoId: ${streamable.extras["videoId"]}")
                    
                    // Reset retry state for new stream request
                    resetRetryState()
                    
                    val videoId = streamable.extras["videoId"]!!
                    
                    try {
                        // Try the first audio source type
                        val firstSource = getNextAudioSource()
                        return tryAudioSource(firstSource, videoId)
                    } catch (e: Exception) {
                        // Handle the error with retry logic
                        return handleStreamError(e, "Multi-Format Stream", videoId)
                    }
                }
                
                "VIDEO_M3U8" -> {
                    // Legacy HLS support - kept for compatibility with error handling
                    println("DEBUG: Loading HLS stream for videoId: ${streamable.extras["videoId"]}")
                    
                    // Reset retry state for new stream request
                    resetRetryState()
                    
                    val videoId = streamable.extras["videoId"]!!
                    
                    try {
                        return loadHlsStream(videoId)
                    } catch (e: Exception) {
                        // Handle the error with retry logic
                        return handleStreamError(e, "HLS Stream", videoId)
                    }
                }
                
                "AUDIO_MP3" -> {
                    // Audio MP3 support with enhanced error handling
                    println("DEBUG: Loading audio MP3 stream for videoId: ${streamable.extras["videoId"]}")
                    
                    // Reset retry state for new stream request
                    resetRetryState()
                    
                    val videoId = streamable.extras["videoId"]!!
                    
                    try {
                        return loadAudioOnlyStream(videoId, "mp3")
                    } catch (e: Exception) {
                        // Handle the error with retry logic
                        return handleStreamError(e, "Audio MP3", videoId)
                    }
                }
                
                else -> throw IllegalArgumentException("Unknown server streamable ID: ${streamable.id}")
            }
            
            // Add other MediaType cases to make when exhaustive
            Streamable.MediaType.Background -> throw IllegalArgumentException("Background media type not supported")
            Streamable.MediaType.Subtitle -> throw IllegalArgumentException("Subtitle media type not supported")
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
                    "Audio & Combined Stream (HLS + MP3 + MP4+Audio + WebM+Audio)",
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