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
import kotlin.math.min

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
    
    // Mobile API instance for emulation
    val mobileApi = YoutubeiApi(
        data_language = "en"
    )

    // Enhanced visitor ID initialization with retry logic
    private suspend fun ensureVisitorId() {
        try {
            println("DEBUG: Checking visitor ID, current: ${api.visitor_id}")
            if (api.visitor_id == null) {
                println("DEBUG: Getting new visitor ID")
                var visitorError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        api.visitor_id = visitorEndpoint.getVisitorId()
                        println("DEBUG: Got visitor ID on attempt $attempt: ${api.visitor_id}")
                        return
                    } catch (e: Exception) {
                        visitorError = e
                        println("DEBUG: Visitor ID attempt $attempt failed: ${e.message}")
                        if (attempt < 3) {
                            kotlinx.coroutines.delay(500L * attempt)
                        }
                    }
                }
                throw visitorError ?: Exception("Failed to get visitor ID after 3 attempts")
            } else {
                println("DEBUG: Visitor ID already exists: ${api.visitor_id}")
            }
        } catch (e: Exception) {
            println("DEBUG: Failed to initialize visitor ID: ${e.message}")
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

    /**
     * Get the target video quality based on app settings
     * Returns the target height in pixels (144, 480, 720, or null for any quality)
     */
    private fun getTargetVideoQuality(streamable: Streamable? = null): Int? {
        // If videos are disabled, return null to use any available quality
        if (!showVideos) {
            println("DEBUG: Videos disabled, using any available quality")
            return null
        }
        
        // Try to get quality setting from streamable extras
        val extras = streamable?.extras ?: emptyMap()
        println("DEBUG: Available streamable extras: ${extras.keys}")
        
        val qualitySetting = when {
            extras.containsKey("quality") -> extras["quality"] as? String
            extras.containsKey("streamQuality") -> extras["streamQuality"] as? String
            extras.containsKey("videoQuality") -> extras["videoQuality"] as? String
            else -> null
        }
        
        println("DEBUG: Detected quality setting: $qualitySetting")
        
        val targetQuality = when (qualitySetting?.lowercase()) {
            "lowest", "low", "144p" -> {
                println("DEBUG: App quality setting: lowest (144p)")
                144
            }
            "medium", "480p" -> {
                println("DEBUG: App quality setting: medium (480p)")
                480
            }
            "highest", "high", "720p", "1080p" -> {
                println("DEBUG: App quality setting: highest (720p)")
                720
            }
            "auto", "automatic" -> {
                println("DEBUG: App quality setting: auto, using medium (480p)")
                480
            }
            else -> {
                println("DEBUG: No quality setting found, defaulting to medium (480p)")
                480
            }
        }
        
        return targetQuality
    }

    /**
     * Get the best video source based on target quality with enhanced filtering
     */
    private fun getBestVideoSourceByQuality(videoSources: List<Streamable.Source.Http>, targetQuality: Int?): Streamable.Source.Http? {
        if (videoSources.isEmpty()) {
            return null
        }
        
        if (targetQuality == null) {
            println("DEBUG: No quality restriction, selecting highest quality available")
            val best = videoSources.maxByOrNull { it.quality }
            println("DEBUG: Selected source with bitrate: ${best?.quality}")
            return best
        }
        
        println("DEBUG: Filtering ${videoSources.size} video sources for target quality: ${targetQuality}p")
        videoSources.forEach { source ->
            println("DEBUG: Available video source - bitrate: ${source.quality}")
        }
        
        // Enhanced quality filtering based on more accurate bitrate ranges
        val matchingSources = videoSources.filter { source ->
            val bitrate = source.quality
            val isMatch = when (targetQuality) {
                144 -> {
                    // 144p: very low bitrate, typically 50-300 kbps
                    bitrate in 50000..300000
                }
                480 -> {
                    // 480p: low to medium bitrate, typically 300-2000 kbps
                    bitrate in 300000..2000000
                }
                720 -> {
                    // 720p: medium to high bitrate, typically 1500-5000 kbps
                    bitrate in 1500000..5000000
                }
                else -> {
                    // For higher qualities or unknown targets, use the highest available
                    true
                }
            }
            
            if (isMatch) {
                println("DEBUG: ✓ Source matches quality criteria - bitrate: $bitrate for target ${targetQuality}p")
            } else {
                println("DEBUG: ✗ Source does not match quality criteria - bitrate: $bitrate for target ${targetQuality}p")
            }
            isMatch
        }
        
        val selectedSource = if (matchingSources.isNotEmpty()) {
            val best = matchingSources.maxByOrNull { it.quality }
            println("DEBUG: Selected best matching source with bitrate: ${best?.quality}")
            best
        } else {
            println("DEBUG: No exact matches found, falling back to best available")
            val fallback = videoSources.maxByOrNull { it.quality }
            println("DEBUG: Fallback source with bitrate: ${fallback?.quality}")
            fallback
        }
        
        return selectedSource
    }

    /**
     * Get the best audio source optimized for YouTube Music streaming
     * Prioritizes YouTube Music Premium formats and high-quality codecs
     */
    private fun getBestAudioSourceForMusic(audioSources: List<Streamable.Source.Http>): Streamable.Source.Http? {
        if (audioSources.isEmpty()) {
            return null
        }

        println("DEBUG: Selecting best audio source for music streaming from ${audioSources.size} sources")
        audioSources.forEach { source ->
            println("DEBUG: Available audio source - bitrate: ${source.quality}")
        }

        // Priority order for YouTube Music:
        // 1. YT Music Premium Opus 256k (774) - Best quality
        // 2. AAC LC 256k (141) - Premium high quality
        // 3. Opus 160k (251) - High quality Opus
        // 4. AAC LC 128k (140) - Most common, good quality
        // 5. Opus 70k (250) - Efficient quality
        // 6. AAC HEv1 48k (139) - Low bandwidth
        // 7. Opus 50k (249) - Lowest quality

        val bestSource = audioSources.maxByOrNull { source ->
            val quality = source.quality
            
            // Assign priority scores based on YouTube Music preferences
            val priority = when {
                // Highest priority: YT Music Premium Opus 256k
                quality >= 256000 -> 100
                // High priority: AAC LC 256k (Premium)
                quality >= 200000 -> 90
                // Good priority: Opus 160k
                quality >= 160000 -> 80
                // Standard priority: AAC LC 128k (most common)
                quality >= 128000 -> 70
                // Efficient priority: Opus 70k
                quality >= 70000 -> 60
                // Low bandwidth priority: AAC HEv1 48k
                quality >= 48000 -> 50
                // Lowest priority: Opus 50k and below
                else -> 40
            }
            
            println("DEBUG: Audio source bitrate $quality -> priority $priority")
            priority
        }

        println("DEBUG: Selected best audio source for music with bitrate: ${bestSource?.quality}")
        return bestSource
    }

    private val language = ENGLISH

    private val visitorEndpoint = EchoVisitorEndpoint(api)
    private val songFeedEndPoint = EchoSongFeedEndpoint(api)
    private val artistEndPoint = EchoArtistEndpoint(api)
    private val artistMoreEndpoint = EchoArtistMoreEndpoint(api)
    private val libraryEndPoint = EchoLibraryEndPoint(api)
    private val songEndPoint = EchoSongEndPoint(api)
    private val songRelatedEndpoint = EchoSongRelatedEndpoint(api)
    private val videoEndpoint = EchoVideoEndpoint(api)
    private val mobileVideoEndpoint = EchoVideoEndpoint(mobileApi)
    private val playlistEndPoint = EchoPlaylistEndpoint(api)
    private val lyricsEndPoint = EchoLyricsEndPoint(api)
    private val searchSuggestionsEndpoint = EchoSearchSuggestionsEndpoint(api)
    private val searchEndpoint = EchoSearchEndpoint(api)
    private val editorEndpoint = EchoEditPlaylistEndpoint(api)

    companion object {
        const val ENGLISH = "en-GB"
        const val SINGLES = "Singles"
        const val SONGS = "songs"
        
        // Enhanced mobile device User-Agents based on actual YouTube Music traffic
        val MOBILE_USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; vivo 1916) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.180 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.180 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.180 Mobile Safari/537.36"
        )
        
        // Real desktop User-Agents for fallback
        val DESKTOP_USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.180 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.180 Safari/537.36"
        )
        
        // Enhanced YouTube Music headers based on real intercepted traffic
        val YOUTUBE_MUSIC_HEADERS = mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,hi;q=0.7",
            "Connection" to "keep-alive",
            "Host" to "music.youtube.com",
            "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Storage-Access" to "active",
            "User-Agent" to MOBILE_USER_AGENTS[0]
        )
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

    /**
     * Generate enhanced URL with cache-busting and future timestamping
     */
    private fun generateEnhancedUrl(originalUrl: String, attempt: Int, strategy: String, networkType: String): String {
        val baseTimestamp = System.currentTimeMillis()
        val futureTimestamp = baseTimestamp + (4 * 60 * 60 * 1000) // 4 hours in future
        val random = java.util.Random().nextInt(1000000) + attempt
        val sessionId = "session_${System.currentTimeMillis()}_${attempt}"
        
        return if (originalUrl.contains("?")) {
            "$originalUrl&cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&strategy=$strategy&network=$networkType"
        } else {
            "$originalUrl?cachebuster=$baseTimestamp&future=$futureTimestamp&rand=$random&session=$sessionId&strategy=$strategy&network=$networkType"
        }
    }

    /**
     * Create POST request with enhanced headers for mobile emulation
     */
    private fun createPostRequest(url: String, headers: Map<String, String>, body: String): Streamable.Source.Http {
        // In a real implementation, this would create a POST request
        // For now, we'll enhance the URL with POST parameters
        val enhancedUrl = if (url.contains("?")) {
            "$url&$body"
        } else {
            "$url?$body"
        }
        
        return Streamable.Source.Http(
            enhancedUrl.toRequest(),
            quality = 0 // Will be set by caller
        )
    }

    /**
     * Get random user agent based on mobile preference
     */
    private fun getRandomUserAgent(preferMobile: Boolean = true): String {
        return if (preferMobile) {
            MOBILE_USER_AGENTS.random()
        } else {
            DESKTOP_USER_AGENTS.random()
        }
    }

    /**
     * Generate mobile headers with strategy-specific enhancements
     */
    private fun generateMobileHeaders(strategy: String, networkType: String): Map<String, String> {
        val baseHeaders = mutableMapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,hi;q=0.7",
            "Connection" to "keep-alive",
            "Host" to "music.youtube.com",
            "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Storage-Access" to "active"
        )
        
        // Add mobile Chrome headers based on strategy
        when (strategy) {
            "mobile_emulation" -> {
                baseHeaders.putAll(mapOf(
                    "User-Agent" to getRandomUserAgent(true),
                    "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
                    "sec-ch-ua-arch" to "\"\"",
                    "sec-ch-ua-bitness" to "\"\"",
                    "sec-ch-ua-form-factors" to "\"Mobile\"",
                    "sec-ch-ua-full-version" to "138.0.7204.180",
                    "sec-ch-ua-full-version-list" to "\"Not)A;Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"138.0.7204.180\", \"Google Chrome\";v=\"138.0.7204.180\"",
                    "sec-ch-ua-mobile" to "?1",
                    "sec-ch-ua-model" to "vivo 1916",
                    "sec-ch-ua-platform" to "\"Android\"",
                    "sec-ch-ua-platform-version" to "9.0.0",
                    "sec-ch-ua-wow64" to "?0",
                    "X-Browser-Year" to "2025"
                ))
            }
            "aggressive_mobile" -> {
                baseHeaders.putAll(mapOf(
                    "User-Agent" to getRandomUserAgent(true),
                    "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
                    "sec-ch-ua-mobile" to "?1",
                    "sec-ch-ua-platform" to "\"Android\"",
                    "X-Mobile-Request" to "true",
                    "X-Network-Type" to networkType,
                    "X-Strategy" to "aggressive_mobile"
                ))
            }
            "desktop_fallback" -> {
                baseHeaders.putAll(mapOf(
                    "User-Agent" to getRandomUserAgent(false),
                    "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
                    "sec-ch-ua-mobile" to "?0",
                    "sec-ch-ua-platform" to "\"Windows\"",
                    "X-Desktop-Fallback" to "true",
                    "X-Network-Type" to networkType
                ))
            }
        }
        
        return baseHeaders
    }

    /**
     * Detect network type for strategy selection
     */
    private fun detectNetworkType(): String {
        // In a real implementation, this would detect actual network conditions
        // For now, we'll use a simple heuristic based on previous errors
        return "standard" // Default to standard network
    }

    /**
     * Get strategy based on attempt number and network type
     */
    private fun getStrategyForNetwork(attempt: Int, networkType: String): String {
        return when (attempt) {
            1 -> "standard"
            2 -> "mobile_emulation"
            3 -> "reset_visitor"
            4 -> "aggressive_mobile"
            5 -> "desktop_fallback"
            else -> "last_resort"
        }
    }

    /**
     * Check if exception is a 403 error
     */
    private fun is403Error(e: Exception): Boolean {
        return e.message?.contains("403") == true || 
               e.message?.contains("Forbidden") == true ||
               (e is ClientRequestException && e.response.status.value == 403)
    }

    /**
     * Reset API cache for recovery
     */
    private fun resetApiCache() {
        try {
            api.visitor_id = null
            api.user_auth_state = null
            println("DEBUG: API cache reset completed")
        } catch (e: Exception) {
            println("DEBUG: Failed to reset API cache: ${e.message}")
        }
    }

    /**
     * Handle WiFi restrictions with enhanced recovery
     */
    private suspend fun handleWifiRestrictions(attempt: Int, networkType: String) {
        try {
            println("DEBUG: Handling WiFi restrictions for attempt $attempt on $networkType")
            
            when (attempt) {
                1 -> {
                    // Basic visitor ID reset
                    println("DEBUG: Basic visitor ID reset for WiFi restrictions")
                    api.visitor_id = null
                    ensureVisitorId()
                }
                2 -> {
                    // Aggressive cache reset
                    println("DEBUG: Aggressive cache reset for WiFi restrictions")
                    resetApiCache()
                    kotlinx.coroutines.delay(1000L)
                    ensureVisitorId()
                }
                3 -> {
                    // Mobile API switch
                    println("DEBUG: Mobile API switch for WiFi restrictions")
                    val tempVisitorId = api.visitor_id
                    api.visitor_id = null
                    try {
                        mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                        println("DEBUG: Mobile API initialized for WiFi restrictions")
                    } catch (e: Exception) {
                        println("DEBUG: Mobile API switch failed: ${e.message}")
                        api.visitor_id = tempVisitorId
                    }
                }
                4 -> {
                    // Complete reset with delay
                    println("DEBUG: Complete reset for WiFi restrictions")
                    resetApiCache()
                    kotlinx.coroutines.delay(2000L)
                    ensureVisitorId()
                }
            }
            
            println("DEBUG: WiFi restriction handling completed for attempt $attempt")
        } catch (e: Exception) {
            println("DEBUG: Failed to handle WiFi restrictions: ${e.message}")
        }
    }

    /**
     * Create quality-adaptive source with enhanced headers
     */
    private fun createQualityAdaptiveSource(
        url: String,
        initialQuality: Int,
        strategy: String,
        networkType: String
    ): Streamable.Source.Http {
        println("DEBUG: Creating quality-adaptive source with initial quality: $initialQuality")
        
        val headers = generateMobileHeaders(strategy, networkType).toMutableMap()
        
        // Add quality-adaptive headers
        headers.putAll(mapOf(
            "X-Quality-Adaptive" to "true",
            "X-Initial-Quality" to initialQuality.toString(),
            "X-Network-Type" to networkType
        ))
        
        return when (strategy) {
            "mobile_emulation", "aggressive_mobile" -> {
                createPostRequest(url, headers, "adaptive=1&quality=$initialQuality")
            }
            "desktop_fallback" -> {
                createPostRequest(url, headers, "quality=$initialQuality")
            }
            else -> {
                Streamable.Source.Http(
                    url.toRequest(),
                    quality = initialQuality
                )
            }
        }
    }

    /**
     * Handle HLS (m3u8) streaming with adaptive bitrate and fallback support
     * This is the preferred method for audio streaming due to better reliability
     */
    private suspend fun handleHLSStream(hlsUrl: String, strategy: String, networkType: String): Streamable.Media {
        try {
            println("DEBUG: Processing HLS stream: $hlsUrl")
            
            // Generate enhanced HLS URL with strategy-specific parameters
            val enhancedHlsUrl = generateEnhancedUrl(hlsUrl, 1, strategy, networkType)
            
            // Create multiple HLS sources with different bitrates for adaptive streaming
            val hlsSources = mutableListOf<Streamable.Source.Http>()
            
            // Primary HLS source with enhanced headers
            val primaryHeaders = generateMobileHeaders(strategy, networkType)
            val primarySource = when (strategy) {
                "mobile_emulation", "aggressive_mobile" -> {
                    createPostRequest(enhancedHlsUrl, primaryHeaders, "rn=1&hls=primary")
                }
                "desktop_fallback" -> {
                    createPostRequest(enhancedHlsUrl, primaryHeaders, "hls=primary")
                }
                else -> {
                    Streamable.Source.Http(
                        enhancedHlsUrl.toRequest(),
                        quality = 192000 // Default high quality
                    )
                }
            }
            hlsSources.add(primarySource)
            
            // Add adaptive bitrate HLS sources for different quality levels
            val bitrateLevels = listOf(
                320000 to "high",    // 320kbps - high quality
                192000 to "medium",  // 192kbps - medium quality  
                128000 to "low",     // 128kbps - low quality
                96000 to "lowest"   // 96kbps - lowest quality
            )
            
            for ((bitrate, quality) in bitrateLevels) {
                try {
                    // Create adaptive bitrate URL with quality parameter
                    val adaptiveUrl = if (enhancedHlsUrl.contains("?")) {
                        "$enhancedHlsUrl&bitrate=$bitrate&quality=$quality"
                    } else {
                        "$enhancedHlsUrl?bitrate=$bitrate&quality=$quality"
                    }
                    
                    val adaptiveHeaders = generateMobileHeaders(strategy, networkType)
                    
                    val adaptiveSource = when (strategy) {
                        "mobile_emulation", "aggressive_mobile" -> {
                            createPostRequest(adaptiveUrl, adaptiveHeaders, "hls=1&bitrate=$bitrate&quality=$quality")
                        }
                        "desktop_fallback" -> {
                            createPostRequest(adaptiveUrl, adaptiveHeaders, "bitrate=$bitrate&quality=$quality")
                        }
                        else -> {
                            Streamable.Source.Http(
                                adaptiveUrl.toRequest(),
                                quality = bitrate
                            )
                        }
                    }
                    hlsSources.add(adaptiveSource)
                    println("DEBUG: Added HLS source for $quality quality ($bitrate bps)")
                    
                } catch (e: Exception) {
                    println("DEBUG: Failed to create HLS source for $quality quality: ${e.message}")
                }
            }
            
            // Sort sources by quality (highest first)
            hlsSources.sortByDescending { it.quality }
            
            println("DEBUG: Created HLS stream with ${hlsSources.size} adaptive sources")
            println("DEBUG: HLS sources (top 3):")
            hlsSources.take(3).forEachIndexed { index, source ->
                println("DEBUG:   ${index + 1}. Quality: ${source.quality}")
            }
            
            return Streamable.Media.Server(
                sources = hlsSources,
                merged = false // HLS is typically a single adaptive stream
            )
            
        } catch (e: Exception) {
            println("DEBUG: Failed to process HLS stream: ${e.message}")
            throw Exception("HLS stream processing failed: ${e.message}")
        }
    }

    /**
     * Handle MPD (Media Presentation Description) streams for mobile app format
     * This extracts separate audio and video URLs from YouTube's adaptive formats
     */
    private suspend fun handleMPDStream(mpdUrl: String, strategy: String, networkType: String, videoId: String): Streamable.Media {
        println("DEBUG: Processing MPD stream for videoId: $videoId")
        
        return try {
            // Get the video data to access adaptive formats
            val currentVideoEndpoint = when (strategy) {
                "mobile_emulation", "aggressive_mobile" -> mobileVideoEndpoint
                "desktop_fallback" -> videoEndpoint
                else -> videoEndpoint
            }
            
            val (video, _) = currentVideoEndpoint.getVideo(false, videoId)
            val audioSources = mutableListOf<Streamable.Source.Http>()
            val videoSources = mutableListOf<Streamable.Source.Http>()
            
            // Extract separate audio and video formats from adaptive formats
            video.streamingData.adaptiveFormats.forEach { format ->
                val mimeType = format.mimeType.lowercase()
                val originalUrl = format.url ?: return@forEach
                val itag = format.itag ?: 0
                
                when (itag) {
                        // YouTube Music Core Audio Formats
                        139, 140, 141, 249, 250, 251, 774 -> {
                        val qualityValue = format.bitrate?.toInt() ?: when (itag) {
                            139 -> 48000.toInt()    // 48k AAC HEv1 (YT Music, DRC)
                            140 -> 128000.toInt()   // 128k AAC LC (YT Music, DRC) - Most common
                            141 -> 256000.toInt()   // 256k AAC LC (Premium only)
                            249 -> 50000.toInt()    // 50k Opus (DRC)
                            250 -> 70000.toInt()    // 70k Opus (DRC)
                            251 -> 160000.toInt()   // 160k Opus (DRC) - High quality
                            774 -> 256000.toInt()   // 256k Opus (YT Music Premium) - Best quality
                            else -> 128000.toInt()
                        }
                        val freshUrl = generateEnhancedUrl(originalUrl, 1, strategy, networkType)
                        
                        val audioSource = createQualityAdaptiveSource(
                            freshUrl,
                            qualityValue,
                            strategy,
                            networkType
                        )
                        audioSources.add(audioSource)
                        println("DEBUG: Added MPD audio source (itag: $itag, quality: $qualityValue)")
                    }
                    
                    // Fallback audio detection by mime type
                    mimeType.contains("audio") && !mimeType.contains("video") -> {
                        val qualityValue = format.bitrate?.toInt() ?: 192000
                        val freshUrl = generateEnhancedUrl(originalUrl, 1, strategy, networkType)
                        
                        val audioSource = createQualityAdaptiveSource(
                            freshUrl,
                            qualityValue,
                            strategy,
                            networkType
                        )
                        audioSources.add(audioSource)
                        println("DEBUG: Added MPD audio source by mime type (quality: $qualityValue, mimeType: $mimeType)")
                    }
                    
                    // Essential Music Video Formats (H.264)
                    133, 134, 135, 136, 160 -> {
                        val qualityValue = format.bitrate?.toInt() ?: when (itag) {
                            133, 160 -> 300000.toInt()    // 144p/240p H.264 (basic)
                            134 -> 500000.toInt()        // 360p H.264 (standard)
                            135 -> 1000000.toInt()       // 480p H.264 (good)
                            136 -> 2000000.toInt()       // 720p H.264 (HD)
                            else -> 500000.toInt()
                        }
                        val freshUrl = generateEnhancedUrl(originalUrl, 1, strategy, networkType)
                        
                        val videoSource = createQualityAdaptiveSource(
                            freshUrl,
                            qualityValue,
                            strategy,
                            networkType
                        )
                        videoSources.add(videoSource)
                        println("DEBUG: Added MPD video source (itag: $itag, quality: $qualityValue)")
                    }
                    
                    // Efficient VP9 Music Video Formats
                    243, 244, 247 -> {
                        val qualityValue = format.bitrate?.toInt() ?: when (itag) {
                            243 -> 500000.toInt()        // 360p VP9 (efficient)
                            244 -> 1000000.toInt()       // 480p VP9 (efficient)
                            247 -> 2000000.toInt()       // 720p VP9 (efficient HD)
                            else -> 1000000.toInt()
                        }
                        val freshUrl = generateEnhancedUrl(originalUrl, 1, strategy, networkType)
                        
                        val videoSource = createQualityAdaptiveSource(
                            freshUrl,
                            qualityValue,
                            strategy,
                            networkType
                        )
                        videoSources.add(videoSource)
                        println("DEBUG: Added MPD VP9 video source (itag: $itag, quality: $qualityValue)")
                    }
                    
                    // Fallback video detection by mime type
                    mimeType.contains("video") && !mimeType.contains("audio") -> {
                        val qualityValue = format.bitrate?.toInt() ?: 500000
                        val freshUrl = generateEnhancedUrl(originalUrl, 1, strategy, networkType)
                        
                        val videoSource = createQualityAdaptiveSource(
                            freshUrl,
                            qualityValue,
                            strategy,
                            networkType
                        )
                        videoSources.add(videoSource)
                        println("DEBUG: Added MPD video source by mime type (quality: $qualityValue, mimeType: $mimeType)")
                    }
                }
            }
            
            // Create the final media with separate audio and video sources
            return when {
                audioSources.isNotEmpty() && videoSources.isNotEmpty() -> {
                    println("DEBUG: Creating MPD stream with separate audio and video sources")
                    val bestAudioSource = getBestAudioSourceForMusic(audioSources)
                    val bestVideoSource = videoSources.maxByOrNull { it.quality }
                    
                    if (bestAudioSource != null && bestVideoSource != null) {
                        Streamable.Media.Server(
                            sources = listOf(bestAudioSource, bestVideoSource),
                            merged = true  // Player must merge separate audio and video streams
                        )
                    } else {
                        throw Exception("Failed to extract valid MPD audio and video sources")
                    }
                }
                
                audioSources.isNotEmpty() -> {
                    println("DEBUG: MPD fallback to audio-only stream")
                    val bestAudioSource = getBestAudioSourceForMusic(audioSources)
                    if (bestAudioSource != null) {
                        Streamable.Media.Server(listOf(bestAudioSource), merged = false)
                    } else {
                        throw Exception("Failed to extract valid MPD audio source")
                    }
                }
                
                else -> {
                    throw Exception("No valid MPD sources found")
                }
            }
            
        } catch (e: Exception) {
            println("DEBUG: Failed to process MPD stream: ${e.message}")
            throw Exception("MPD stream processing failed: ${e.message}")
        }
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean
    ): Streamable.Media {
        return when (streamable.type) {
            Streamable.MediaType.Server -> when (streamable.id) {
                "DUAL_STREAM" -> {
                    println("DEBUG: Loading multi-format stream for videoId: ${streamable.extras["videoId"]}")
                    
                    // Ensure visitor ID is initialized
                    ensureVisitorId()
                    
                    val videoId = streamable.extras["videoId"]!!
                    var allSources = mutableListOf<Streamable.Source.Http>()
                    var lastError: Exception? = null
                    var formatStats = mutableMapOf<String, Int>() 
                    
                    // Enhanced retry logic with network-aware strategies
                    for (attempt in 1..6) {
                        // Get strategy based on network type and attempt number
                        val networkType = detectNetworkType()
                        try {
                            println("DEBUG: Multi-format attempt $attempt of 6")
                            
                            // Add random delay to mimic human behavior (except for first attempt)
                            if (attempt > 1) {
                                val delay = (500L * attempt) + (Math.random() * 1000L).toLong()
                                println("DEBUG: Adding random delay: ${delay}ms")
                                kotlinx.coroutines.delay(delay)
                            }
                            
                            val strategy = getStrategyForNetwork(attempt, networkType)
                            println("DEBUG: Using strategy: $strategy for $networkType")
                            
                            // Apply strategy-specific settings
                            when (strategy) {
                                "reset_visitor" -> {
                                    println("DEBUG: Resetting visitor ID")
                                    api.visitor_id = null
                                    ensureVisitorId()
                                }
                                "mobile_emulation", "aggressive_mobile" -> {
                                    println("DEBUG: Applying $strategy strategy - switching to mobile API")
                                    val tempVisitorId = api.visitor_id
                                    api.visitor_id = null
                                    try {
                                        mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                                        println("DEBUG: Mobile API initialized successfully")
                                    } catch (e: Exception) {
                                        println("DEBUG: Mobile API initialization failed: ${e.message}")
                                        api.visitor_id = tempVisitorId
                                    }
                                }
                                "desktop_fallback" -> {
                                    println("DEBUG: Applying desktop fallback strategy")
                                    api.user_auth_state = null
                                }
                            }
                            
                            // Enhanced WiFi restriction handling
                            if (networkType.contains("wifi") || networkType.contains("restricted")) {
                                handleWifiRestrictions(attempt, networkType)
                            }
                            
                            // Get video with different parameters based on strategy
                            val useDifferentParams = strategy != "standard"
                            val currentVideoEndpoint = when (strategy) {
                                "mobile_emulation", "aggressive_mobile" -> {
                                    println("DEBUG: Using mobile API endpoint for video retrieval")
                                    mobileVideoEndpoint
                                }
                                "desktop_fallback" -> {
                                    println("DEBUG: Using standard API endpoint for desktop fallback")
                                    videoEndpoint
                                }
                                else -> {
                                    println("DEBUG: Using standard API endpoint")
                                    videoEndpoint
                                }
                            }
                            
                            println("DEBUG: Getting video with useDifferentParams=$useDifferentParams")
                            val (video, _) = currentVideoEndpoint.getVideo(useDifferentParams, videoId)
                            
                            // Check if we have HLS (m3u8) support - PREFERRED for audio reliability
                            val hlsUrl = try {
                                video.streamingData.javaClass.getDeclaredField("hlsManifestUrl").let { field ->
                                    field.isAccessible = true
                                    field.get(video.streamingData) as? String
                                }
                            } catch (e: Exception) {
                                null
                            }
                            
                            if (hlsUrl != null) {
                                println("DEBUG: Found HLS stream URL: $hlsUrl")
                                // Use HLS stream for better reliability and adaptive streaming
                                val hlsMedia = handleHLSStream(hlsUrl, strategy, networkType)
                                lastError = null
                                return hlsMedia
                            }
                            
                            // Check if we have MPD (Media Presentation Description) support
                            val mpdUrl = try {
                                video.streamingData.javaClass.getDeclaredField("dashManifestUrl").let { field ->
                                    field.isAccessible = true
                                    field.get(video.streamingData) as? String
                                }
                            } catch (e: Exception) {
                                null
                            }
                            
                            if (mpdUrl != null && showVideos) {
                                println("DEBUG: Found MPD stream URL: $mpdUrl")
                                // Use MPD stream for mobile app format
                                val mpdMedia = handleMPDStream(mpdUrl, strategy, networkType, videoId)
                                lastError = null
                                return mpdMedia
                            }
                            
                            // Process formats based on user preferences and availability
                            val audioSources = mutableListOf<Streamable.Source.Http>()
                            val videoSources = mutableListOf<Streamable.Source.Http>()
                            val combinedSources = mutableListOf<Streamable.Source.Http>()
                            
                            video.streamingData.adaptiveFormats.forEach { format ->
                                val mimeType = format.mimeType.lowercase()
                                val originalUrl = format.url ?: return@forEach
                                val itag = format.itag ?: 0
                                
                                // Categorize formats by type using optimized itag codes for music app
                                val isAudioFormat = when (itag) {
                                    // YouTube Music Core Audio Formats
                                    139, 140, 141, 249, 250, 251, 774 -> true
                                    // Also check mime type as fallback
                                    else -> when {
                                        mimeType.contains("audio/mp4") && !mimeType.contains("video") -> true
                                        mimeType.contains("audio/mp3") || mimeType.contains("audio/mpeg") -> true
                                        mimeType.contains("audio/webm") || mimeType.contains("audio/opus") -> true
                                        else -> false
                                    }
                                }
                                
                                val isVideoFormat = when (itag) {
                                    // Essential Music Video Formats (H.264)
                                    133, 134, 135, 136, 160 -> true
                                    // Efficient VP9 Music Video Formats
                                    243, 244, 247 -> true
                                    // Also check mime type as fallback
                                    else -> when {
                                        mimeType.contains("video/mp4") && !mimeType.contains("audio") -> true
                                        mimeType.contains("video/webm") && !mimeType.contains("audio") -> true
                                        else -> false
                                    }
                                }
                                
                                val isCombinedFormat = when (itag) {
                                    // Essential combined formats for music videos
                                    18, 22 -> true
                                    // Also check mime type as fallback
                                    else -> when {
                                        mimeType.contains("video/mp4") && mimeType.contains("audio") -> true
                                        mimeType.contains("video/webm") && mimeType.contains("audio") -> true
                                        else -> false
                                    }
                                }
                                
                                when {
                                    isAudioFormat -> {
                                        // Process audio-only format with quality-adaptive streaming
                                        val qualityValue: Int = when {
                                            format.bitrate != null && format.bitrate > 0 -> {
                                                val baseBitrate = format.bitrate.toInt()
                                                when (networkType) {
                                                    "restricted_wifi" -> min(baseBitrate, 128000)
                                                    "mobile_data" -> min(baseBitrate, 192000)
                                                    else -> baseBitrate
                                                }
                                            }
                                            format.audioSampleRate != null -> {
                                                val sampleRate = format.audioSampleRate!!.toInt()
                                                when (networkType) {
                                                    "restricted_wifi" -> min(sampleRate, 128000)
                                                    "mobile_data" -> min(sampleRate, 192000)
                                                    else -> sampleRate
                                                }
                                            }
                                            else -> {
                                                when (networkType) {
                                                    "restricted_wifi" -> 96000
                                                    "mobile_data" -> 128000
                                                    else -> 192000
                                                }
                                            }
                                        }
                                        
                                        val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                        
                                        // Create quality-adaptive audio source
                                        val audioSource = createQualityAdaptiveSource(
                                            freshUrl,
                                            qualityValue,
                                            strategy,
                                            networkType
                                        )
                                        
                                        audioSources.add(audioSource)
                                        println("DEBUG: Added audio-only source (quality: $qualityValue, mimeType: $mimeType)")
                                    }
                                    
                                    isVideoFormat -> {
                                        // Process video-only format (only if videos are enabled) with quality-adaptive streaming
                                        if (showVideos) {
                                            val qualityValue: Int = format.bitrate?.toInt() ?: 0
                                            val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                            
                                            // Create quality-adaptive video source
                                            val videoSource = createQualityAdaptiveSource(
                                                freshUrl,
                                                qualityValue,
                                                strategy,
                                                networkType
                                            )
                                            
                                            videoSources.add(videoSource)
                                            println("DEBUG: Added video-only source (quality: $qualityValue, mimeType: $mimeType)")
                                        }
                                    }
                                    
                                    isCombinedFormat -> {
                                        // Process combined format (audio + video in single stream)
                                        if (showVideos) {
                                            val qualityValue: Int = when {
                                                format.bitrate != null && format.bitrate > 0 -> format.bitrate.toInt()
                                                format.height != null -> ((format.height!! * 1000) + (format.bitrate?.toInt() ?: 0)).toInt()
                                                else -> 500000 // Default combined quality
                                            }
                                            
                                            val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                            
                                            // Create combined source (single stream with both audio and video)
                                            val combinedSource = createQualityAdaptiveSource(
                                                freshUrl,
                                                qualityValue,
                                                strategy,
                                                networkType
                                            )
                                            
                                            combinedSources.add(combinedSource)
                                            println("DEBUG: Added combined audio+video source (quality: $qualityValue, mimeType: $mimeType)")
                                        }
                                    }
                                }
                            }
                            
                            // Determine the final media type based on user preferences and available sources
                            // IMPORTANT: No separate video streams without audio are allowed
                            // Only combined streams (audio+video) or audio-only streams are supported
                            val targetQuality = getTargetVideoQuality(streamable)
                            println("DEBUG: Target video quality: ${targetQuality ?: "any"}")
                            println("DEBUG: Available sources - Audio: ${audioSources.size}, Video: ${videoSources.size}, Combined: ${combinedSources.size}")
                            
                            val resultMedia = when {
                                // Priority 1: Combined formats (single stream with audio + video) - most compatible
                                combinedSources.isNotEmpty() && showVideos -> {
                                    println("DEBUG: Creating combined audio+video stream (single source)")
                                    val bestCombinedSource = combinedSources.maxByOrNull { it.quality }
                                    if (bestCombinedSource != null) {
                                        Streamable.Media.Server(listOf(bestCombinedSource), merged = false)
                                    } else {
                                        throw Exception("No valid combined sources found")
                                    }
                                }
                                
                                // Priority 2: Separate audio + video sources (requires merging) - only if both available
                                preferVideos && videoSources.isNotEmpty() && audioSources.isNotEmpty() -> {
                                    println("DEBUG: Creating merged audio+video stream (separate sources)")
                                    val bestAudioSource = getBestAudioSourceForMusic(audioSources)
                                    val bestVideoSource = getBestVideoSourceByQuality(videoSources, targetQuality)
                                    
                                    if (bestAudioSource != null && bestVideoSource != null) {
                                        Streamable.Media.Server(
                                            sources = listOf(bestAudioSource, bestVideoSource),
                                            merged = true  // Player must merge separate audio and video streams
                                        )
                                    } else {
                                        // Fallback to audio-only if either audio or video is missing
                                        val bestAudioSource = getBestAudioSourceForMusic(audioSources)
                                        if (bestAudioSource != null) {
                                            Streamable.Media.Server(listOf(bestAudioSource), false)
                                        } else {
                                            throw Exception("No valid audio sources found for fallback")
                                        }
                                    }
                                }
                                
                                // Priority 3: Audio-only stream (fallback when no combined or separate audio+video available)
                                audioSources.isNotEmpty() -> {
                                    println("DEBUG: Creating audio-only stream (no video sources available or videos disabled)")
                                    val bestAudioSource = getBestAudioSourceForMusic(audioSources)
                                    if (bestAudioSource != null) {
                                        Streamable.Media.Server(listOf(bestAudioSource), false)
                                    } else {
                                        throw Exception("No valid audio sources found")
                                    }
                                }
                                
                                else -> {
                                    throw Exception("No valid media sources found - only audio with video or audio-only streams are supported")
                                }
                            }
                            
                            // Return the result and break out of retry loop
                            lastError = null
                            return resultMedia
                            
                        } catch (e: Exception) {
                            lastError = e
                            val is403Error = is403Error(e)
                            val strategy = getStrategyForNetwork(attempt, networkType)
                            
                            println("DEBUG: Multi-format attempt $attempt failed with strategy $strategy: ${e.message}")
                            if (is403Error) {
                                println("DEBUG: Detected 403 error - applying aggressive recovery")
                                
                                // Immediate aggressive action for 403 errors
                                when (attempt) {
                                    1 -> {
                                        println("DEBUG: Immediate visitor ID reset for 403 error")
                                        api.visitor_id = null
                                        ensureVisitorId()
                                    }
                                    2 -> {
                                        println("DEBUG: Immediate cache reset for 403 error")
                                        resetApiCache()
                                    }
                                    3 -> {
                                        println("DEBUG: Immediate mobile API switch for 403 error")
                                        val tempVisitorId = api.visitor_id
                                        api.visitor_id = null
                                        try {
                                            mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                                        } catch (e: Exception) {
                                            println("DEBUG: Mobile API switch failed: ${e.message}")
                                            api.visitor_id = tempVisitorId
                                        }
                                    }
                                }
                            }
                            
                            // Enhanced delay strategy for 403 errors and network restrictions
                            if (attempt < 6) {
                                val delayTime = when {
                                    is403Error && networkType == "restricted_wifi_403" -> {
                                        // Aggressive delay for 403 errors on restricted WiFi
                                        when (attempt) {
                                            1 -> 1000L   // 1s
                                            2 -> 2000L   // 2s
                                            3 -> 4000L   // 4s
                                            4 -> 8000L   // 8s
                                            else -> 500L  // 0.5s
                                        }
                                    }
                                    is403Error -> {
                                        // Standard delay for 403 errors
                                        when (attempt) {
                                            1 -> 500L    // 0.5s
                                            2 -> 1000L   // 1s
                                            3 -> 2000L   // 2s
                                            4 -> 3000L   // 3s
                                            else -> 500L  // 0.5s
                                        }
                                    }
                                    else -> {
                                        // Standard delay for other errors
                                        when (attempt) {
                                            1 -> 200L    // 0.2s
                                            2 -> 500L    // 0.5s
                                            3 -> 1000L   // 1s
                                            4 -> 1500L   // 1.5s
                                            else -> 500L  // 0.5s
                                        }
                                    }
                                }
                                println("DEBUG: Waiting ${delayTime}ms before next attempt (403: $is403Error)")
                                kotlinx.coroutines.delay(delayTime)
                                
                                // Additional cache reset for 403 errors
                                if (is403Error && attempt >= 2) {
                                    println("DEBUG: Performing cache reset for 403 error recovery")
                                    resetApiCache()
                                }
                            }
                        }
                    }
                    
                    throw lastError ?: Exception("All multi-format attempts failed")
                }
                
                "VIDEO_M3U8" -> {
                    ensureVisitorId()
                    
                    println("DEBUG: Refreshing HLS URL for videoId: ${streamable.extras["videoId"]}")
                    
                    var lastError: Exception? = null
                    val networkType = detectNetworkType()
                    
                    for (attempt in 1..8) { 
                        try {
                            println("DEBUG: HLS Attempt $attempt of 8")
                            
                            // Add random delay to mimic human behavior (except for first attempt)
                            if (attempt > 1) {
                                val delay = (200L * attempt) + (Math.random() * 500L).toLong()
                                println("DEBUG: Adding random delay: ${delay}ms")
                                kotlinx.coroutines.delay(delay)
                            }
                            
                            val strategy = getStrategyForNetwork(attempt, networkType)
                            println("DEBUG: Using strategy: $strategy for HLS")
                            
                            // Apply strategy-specific settings
                            when (strategy) {
                                "reset_visitor" -> {
                                    println("DEBUG: Resetting visitor ID for HLS")
                                    api.visitor_id = null
                                    ensureVisitorId()
                                }
                                "mobile_emulation", "aggressive_mobile" -> {
                                    println("DEBUG: Applying mobile strategy for HLS")
                                    val tempVisitorId = api.visitor_id
                                    api.visitor_id = null
                                    try {
                                        mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                                    } catch (e: Exception) {
                                        println("DEBUG: Mobile API initialization failed for HLS: ${e.message}")
                                        api.visitor_id = tempVisitorId
                                    }
                                }
                            }
                            
                            val useDifferentParams = attempt % 2 == 0
                            val currentVideoEndpoint = when (strategy) {
                                "mobile_emulation", "aggressive_mobile" -> mobileVideoEndpoint
                                else -> videoEndpoint
                            }
                            
                            val (video, _) = currentVideoEndpoint.getVideo(useDifferentParams, streamable.extras["videoId"]!!)
                            val hlsManifestUrl = video.streamingData.hlsManifestUrl!!
                            
                            println("DEBUG: Got HLS URL on attempt $attempt: $hlsManifestUrl")
                            
                            // Use enhanced HLS handling
                            val hlsMedia = handleHLSStream(hlsManifestUrl, strategy, networkType)
                            lastError = null
                            return hlsMedia
                            
                        } catch (e: Exception) {
                            lastError = e
                            val is403Error = is403Error(e)
                            println("DEBUG: HLS Attempt $attempt failed: ${e.message}")
                            
                            if (is403Error && attempt >= 2) {
                                println("DEBUG: Performing cache reset for HLS 403 error")
                                resetApiCache()
                            }
                            
                            if (attempt < 8) {
                                val delayTime = if (is403Error) 1000L else 200L
                                kotlinx.coroutines.delay(delayTime)
                            }
                        }
                    }                    
                    throw lastError ?: Exception("All HLS attempts failed")
                }
                
                "AUDIO_MP3" -> {
                    ensureVisitorId()
                    println("DEBUG: Refreshing audio URLs for videoId: ${streamable.extras["videoId"]}")                
                    
                    var lastError: Exception? = null
                    val networkType = detectNetworkType()
                    
                    for (attempt in 1..8) { 
                        try {
                            println("DEBUG: Audio Attempt $attempt of 8")
                            
                            // Add random delay to mimic human behavior (except for first attempt)
                            if (attempt > 1) {
                                val delay = (200L * attempt) + (Math.random() * 500L).toLong()
                                println("DEBUG: Adding random delay: ${delay}ms")
                                kotlinx.coroutines.delay(delay)
                            }
                            
                            val strategy = getStrategyForNetwork(attempt, networkType)
                            println("DEBUG: Using strategy: $strategy for audio")
                            
                            // Apply strategy-specific settings
                            when (strategy) {
                                "reset_visitor" -> {
                                    println("DEBUG: Resetting visitor ID for audio")
                                    api.visitor_id = null
                                    ensureVisitorId()
                                }
                                "mobile_emulation", "aggressive_mobile" -> {
                                    println("DEBUG: Applying mobile strategy for audio")
                                    val tempVisitorId = api.visitor_id
                                    api.visitor_id = null
                                    try {
                                        mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                                    } catch (e: Exception) {
                                        println("DEBUG: Mobile API initialization failed for audio: ${e.message}")
                                        api.visitor_id = tempVisitorId
                                    }
                                }
                            }
                            
                            val useDifferentParams = attempt % 2 == 0
                            val currentVideoEndpoint = when (strategy) {
                                "mobile_emulation", "aggressive_mobile" -> mobileVideoEndpoint
                                else -> videoEndpoint
                            }
                            
                            val (video, _) = currentVideoEndpoint.getVideo(useDifferentParams, streamable.extras["videoId"]!!)
                            
                            // Process audio formats with enhanced quality selection
                            val audioSources = mutableListOf<Streamable.Source.Http>()
                            
                            video.streamingData.adaptiveFormats.forEach { format ->
                                val mimeType = format.mimeType.lowercase()
                                val originalUrl = format.url ?: return@forEach
                                val itag = format.itag ?: 0
                                
                                if (!mimeType.contains("audio")) return@forEach
                                
                                val qualityValue: Int = when (itag) {
                                    // YouTube Music Core Audio Formats
                                    139, 140, 141, 249, 250, 251, 774 -> when (itag) {
                                        139 -> 48000.toInt()    // 48k AAC HEv1
                                        140 -> 128000.toInt()   // 128k AAC LC - Most common
                                        141 -> 256000.toInt()   // 256k AAC LC (Premium)
                                        249 -> 50000.toInt()    // 50k Opus
                                        250 -> 70000.toInt()    // 70k Opus
                                        251 -> 160000.toInt()   // 160k Opus - High quality
                                        774 -> 256000.toInt()   // 256k Opus (YT Music Premium) - Best quality
                                        else -> 128000.toInt()
                                    }
                                    format.bitrate != null && format.bitrate > 0 -> {
                                        val baseBitrate = format.bitrate.toInt()
                                        when (networkType) {
                                            "restricted_wifi" -> min(baseBitrate, 128000)
                                            "mobile_data" -> min(baseBitrate, 192000)
                                            else -> baseBitrate
                                        }
                                    }
                                    format.audioSampleRate != null -> {
                                        val sampleRate = format.audioSampleRate!!.toInt()
                                        when (networkType) {
                                            "restricted_wifi" -> min(sampleRate, 128000)
                                            "mobile_data" -> min(sampleRate, 192000)
                                            else -> sampleRate
                                        }
                                    }
                                    else -> {
                                        when (networkType) {
                                            "restricted_wifi" -> 96000
                                            "mobile_data" -> 128000
                                            else -> 192000
                                        }
                                    }
                                }
                                
                                val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                
                                // Create quality-adaptive audio source
                                val audioSource = createQualityAdaptiveSource(
                                    freshUrl,
                                    qualityValue,
                                    strategy,
                                    networkType
                                )
                                
                                audioSources.add(audioSource)
                                println("DEBUG: Added quality-adaptive audio source (quality: $qualityValue, mimeType: $mimeType)")
                            }
                            
                            if (audioSources.isNotEmpty()) {
                                // Sort by quality (highest first)
                                audioSources.sortByDescending { it.quality }
                                
                                println("DEBUG: Created ${audioSources.size} audio sources with quality-adaptive streaming")
                                println("DEBUG: Audio sources (top 3):")
                                audioSources.take(3).forEachIndexed { index, source ->
                                    println("DEBUG:   ${index + 1}. Quality: ${source.quality}")
                                }
                                
                                return Streamable.Media.Server(audioSources, false)
                            } else {
                                throw Exception("No audio formats found on attempt $attempt")
                            }
                            
                        } catch (e: Exception) {
                            lastError = e
                            val is403Error = is403Error(e)
                            println("DEBUG: Audio Attempt $attempt failed: ${e.message}")
                            
                            if (is403Error && attempt >= 2) {
                                println("DEBUG: Performing cache reset for audio 403 error")
                                resetApiCache()
                            }
                            
                            if (attempt < 8) {
                                val delayTime = if (is403Error) 1000L else 200L
                                kotlinx.coroutines.delay(delayTime)
                            }
                        }
                    }
                    throw lastError ?: Exception("All audio attempts failed")
                }
                
                else -> throw IllegalArgumentException("Unknown server streamable ID: ${streamable.id}")
            }
            Streamable.MediaType.Background -> throw IllegalArgumentException("Background media type not supported")
            Streamable.MediaType.Subtitle -> throw IllegalArgumentException("Subtitle media type not supported")
        }
    }

    override suspend fun loadTrack(track: Track) = coroutineScope {
        ensureVisitorId()
        
        println("DEBUG: Loading track: ${track.title} (${track.id})")
        
        val deferred = async { songEndPoint.loadSong(track.id).getOrThrow() }
        val (video, type) = videoEndpoint.getVideo(true, track.id)
        val isMusic = type == "MUSIC_VIDEO_TYPE_ATV"
        println("DEBUG: Video type: $type, isMusic: $isMusic")
        
        val resolvedTrack = if (resolveMusicForVideos && !isMusic) {
            searchSongForVideo(video.videoDetails.title!!, video.videoDetails.author)
        } else null
        
        val hlsUrl = video.streamingData.hlsManifestUrl
        val audioFiles = video.streamingData.adaptiveFormats.mapNotNull {
            if (!it.mimeType.contains("audio")) return@mapNotNull null
            it.audioSampleRate.toString() to it.url!!
        }.toMap()
        
        println("DEBUG: Audio formats found: ${audioFiles.keys}")
        println("DEBUG: HLS URL available: ${hlsUrl?.isNotEmpty()}")
        
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
                    "Audio & Combined Stream (HLS + MP3 + MP4+Audio) - Enhanced",
                    mapOf("videoId" to track.id)
                ).takeIf { !isMusic && (showVideos || audioFiles.isNotEmpty()) },
                Streamable.server(
                    "VIDEO_M3U8",
                    0,
                    "Video M3U8 - Enhanced Adaptive Streaming",
                    mapOf("videoId" to track.id)
                ).takeIf { !isMusic && showVideos && hlsUrl != null }, 
                Streamable.server(
                    "AUDIO_MP3",
                    0,
                    "Audio MP3 - Enhanced Quality Adaptive",
                    mutableMapOf<String, String>().apply { put("videoId", track.id) }
                ).takeIf { audioFiles.isNotEmpty() && (!showVideos || isMusic) }, 
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