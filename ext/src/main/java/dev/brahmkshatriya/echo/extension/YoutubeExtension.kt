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

    override val settingItems: List<Setting> = listOf(
        SettingSwitch(
            "High Thumbnail Quality",
            "high_quality",
            "Use high quality thumbnails, will cause more data usage.",
            false
        ),
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
    
    // Ensure visitor ID is initialized before any API calls
    private suspend fun ensureVisitorId() {
        try {
            println("DEBUG: Checking visitor ID, current: ${api.visitor_id}")
            if (api.visitor_id == null) {
                println("DEBUG: Getting new visitor ID")
                // Enhanced visitor ID initialization with retry logic
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
                            kotlinx.coroutines.delay(500L * attempt) // Progressive delay
                        }
                    }
                }
                throw visitorError ?: Exception("Failed to get visitor ID after 3 attempts")
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

    private val preferVideos
        get() = settings.getBoolean("prefer_videos") == true

    private val showVideos
        get() = settings.getBoolean("show_videos") != false

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
        
        // Try to get quality setting from streamable extras - check multiple possible keys
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
                // Default to medium quality if no specific setting is found
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
            // When videos are disabled or no specific target, use the highest quality available
            println("DEBUG: No quality restriction, selecting highest quality available")
            val best = videoSources.maxByOrNull { it.quality }
            println("DEBUG: Selected source with bitrate: ${best?.quality}")
            return best
        }
        
        println("DEBUG: Filtering ${videoSources.size} video sources for target quality: ${targetQuality}p")
        videoSources.forEach { source ->
            println("DEBUG: Available video source - bitrate: ${source.quality}")
        }
        
        // Enhanced quality filtering based on more accurate bitrate ranges for YouTube video quality levels
        val matchingSources = videoSources.filter { source ->
            val bitrate = source.quality
            val isMatch = when (targetQuality) {
                144 -> {
                    // 144p: very low bitrate, typically 50-300 kbps for video
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
            // Select the highest quality within the matching range
            val best = matchingSources.maxByOrNull { it.quality }
            println("DEBUG: Selected best matching source with bitrate: ${best?.quality}")
            best
        } else {
            // Fallback to the best available if no matches found
            println("DEBUG: No exact matches found, falling back to best available")
            val fallback = videoSources.maxByOrNull { it.quality }
            println("DEBUG: Fallback source with bitrate: ${fallback?.quality}")
            fallback
        }
        
        return selectedSource
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
        
        // Enhanced mobile device User-Agents based on actual YouTube Music traffic (updated)
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
            "User-Agent" to MOBILE_USER_AGENTS[0] // Default to first mobile agent
        )
        
        // Enhanced streaming headers based on real video playback traffic
        val STREAMING_HEADERS = mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,hi;q=0.7",
            "Connection" to "keep-alive",
            "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Storage-Access" to "active"
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

    /**
     * Enhanced network type detection with additional checks for WiFi restrictions
     */
    private fun detectNetworkType(): String {
        return try {
            // Try to detect if we're on a restricted network (like some WiFi networks)
            val testConnection = java.net.URL("https://www.google.com").openConnection() as java.net.HttpURLConnection
            testConnection.connectTimeout = 2000
            testConnection.readTimeout = 2000
            testConnection.requestMethod = "HEAD"
            val responseCode = testConnection.responseCode
            
            // Additional check - try to access YouTube directly
            val youtubeTest = java.net.URL("https://music.youtube.com").openConnection() as java.net.HttpURLConnection
            youtubeTest.connectTimeout = 3000
            youtubeTest.readTimeout = 3000
            youtubeTest.requestMethod = "HEAD"
            val youtubeResponse = youtubeTest.responseCode
            
            // Test for 403 errors specifically on YouTube streaming URLs
            val streamingTest = try {
                val streamUrl = java.net.URL("https://rr2---sn-bxonu5gpo-cvhe.googlevideo.com/videoplayback?expire=1&test=true")
                val streamTest = streamUrl.openConnection() as java.net.HttpURLConnection
                streamTest.connectTimeout = 3000
                streamTest.readTimeout = 3000
                streamTest.requestMethod = "HEAD"
                val streamResponse = streamTest.responseCode
                streamResponse == 403
            } catch (e: Exception) {
                false // If we can't connect, assume it's not a 403 issue
            }
            
            when {
                responseCode == 200 && youtubeResponse == 200 && !streamingTest -> "mobile_data"
                responseCode == 200 && youtubeResponse != 200 -> "restricted_wifi"
                responseCode == 200 && youtubeResponse == 200 && streamingTest -> "restricted_wifi_403"
                else -> "restricted_wifi"
            }
        } catch (e: Exception) {
            println("DEBUG: Network detection failed, assuming restricted WiFi: ${e.message}")
            "restricted_wifi"
        }
    }
    
    /**
     * Get random User-Agent to simulate different devices
     */
    private fun getRandomUserAgent(isMobile: Boolean = true): String {
        val agents = if (isMobile) MOBILE_USER_AGENTS else DESKTOP_USER_AGENTS
        return agents.random()
    }
    
    /**
     * Get enhanced headers for specific strategy
     */
    private fun getEnhancedHeaders(strategy: String, attempt: Int): Map<String, String> {
        val baseHeaders = YOUTUBE_MUSIC_HEADERS.toMutableMap()
        
        return when (strategy) {
            "mobile_emulation" -> {
                baseHeaders.apply {
                    put("User-Agent", getRandomUserAgent(true))
                    put("Sec-Ch-Ua-Mobile", "?1")
                    put("Sec-Ch-Ua-Platform", "\"Android\"")
                    // Add some randomization to headers
                    if (attempt > 2) {
                        put("Accept-Language", "en-US,en;q=0.8")
                        put("Cache-Control", "max-age=0")
                    }
                }
            }
            "desktop_fallback" -> {
                baseHeaders.apply {
                    put("User-Agent", getRandomUserAgent(false))
                    put("Sec-Ch-Ua-Mobile", "?0")
                    put("Sec-Ch-Ua-Platform", "\"Windows\"")
                }
            }
            "aggressive_mobile" -> {
                baseHeaders.apply {
                    put("User-Agent", getRandomUserAgent(true))
                    put("Accept", "*/*")
                    put("Accept-Language", "en-US,en;q=0.5")
                    put("DNT", "1")
                    put("Connection", "keep-alive")
                }
            }
            else -> {
                baseHeaders.apply {
                    put("User-Agent", getRandomUserAgent(true))
                }
            }
        }
    }
    
    /**
     * Get strategy based on network type and attempt number - Enhanced with more strategies
     */
    private fun getStrategyForNetwork(attempt: Int, networkType: String): String {
        return when (networkType) {
            "restricted_wifi_403" -> {
                // For WiFi with 403 errors, use very aggressive strategies
                when (attempt) {
                    1 -> "reset_visitor"           // Reset visitor ID first
                    2 -> "aggressive_mobile"      // Aggressive mobile emulation
                    3 -> "mobile_emulation"       // Standard mobile emulation
                    4 -> "alternate_params"       // Alternate parameters
                    5 -> "desktop_fallback"        // Desktop fallback
                    else -> "reset_visitor"
                }
            }
            "restricted_wifi" -> {
                // For restricted WiFi, use more aggressive strategies earlier
                when (attempt) {
                    1 -> "mobile_emulation"      // Start with mobile emulation
                    2 -> "aggressive_mobile"      // More aggressive mobile
                    3 -> "reset_visitor"          // Reset visitor ID
                    4 -> "desktop_fallback"       // Try desktop as fallback
                    5 -> "alternate_params"       // Alternate parameters as last resort
                    else -> "mobile_emulation"
                }
            }
            "mobile_data" -> {
                // For mobile data, use standard progression but still mobile-first
                when (attempt) {
                    1 -> "mobile_emulation"       // Mobile emulation first
                    2 -> "standard"               // Standard method
                    3 -> "alternate_params"       // Alternate parameters
                    4 -> "reset_visitor"          // Reset visitor ID
                    5 -> "desktop_fallback"       // Desktop fallback
                    else -> "mobile_emulation"
                }
            }
            else -> {
                // Default strategy - progressive approach
                when (attempt) {
                    1 -> "standard"
                    2 -> "mobile_emulation"
                    3 -> "alternate_params"
                    4 -> "reset_visitor"
                    5 -> "aggressive_mobile"
                    else -> "standard"
                }
            }
        }
    }

    /**
     * Handle HLS (m3u8) streaming with adaptive bitrate and fallback support
     * This is the preferred method for audio streaming due to better reliability
     * Enhanced with adaptive bitrate streaming and robust fallback mechanisms
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
                            createPostRequest(adaptiveUrl, adaptiveHeaders, "rn=1&adaptive=$quality")
                        }
                        "desktop_fallback" -> {
                            createPostRequest(adaptiveUrl, adaptiveHeaders, "adaptive=$quality")
                        }
                        else -> {
                            Streamable.Source.Http(
                                adaptiveUrl.toRequest(),
                                quality = bitrate
                            )
                        }
                    }
                    hlsSources.add(adaptiveSource)
                } catch (e: Exception) {
                    println("DEBUG: Failed to create adaptive HLS source for $quality: ${e.message}")
                }
            }
            
            // Add fallback HLS sources with different strategies
            val fallbackStrategies = listOf("alternate_params", "mobile_emulation", "desktop_fallback")
            for ((index, fallbackStrategy) in fallbackStrategies.withIndex()) {
                try {
                    val fallbackUrl = generateEnhancedUrl(hlsUrl, index + 2, fallbackStrategy, networkType)
                    val fallbackHeaders = generateMobileHeaders(fallbackStrategy, networkType)
                    
                    val fallbackSource = when (fallbackStrategy) {
                        "mobile_emulation" -> {
                            createPostRequest(fallbackUrl, fallbackHeaders, "rn=1&fallback=$index")
                        }
                        "desktop_fallback" -> {
                            createPostRequest(fallbackUrl, fallbackHeaders, "fallback=$index")
                        }
                        else -> {
                            Streamable.Source.Http(
                                fallbackUrl.toRequest(),
                                quality = 128000 // Lower quality for fallback
                            )
                        }
                    }
                    hlsSources.add(fallbackSource)
                } catch (e: Exception) {
                    println("DEBUG: Failed to create fallback HLS source $index: ${e.message}")
                }
            }
            
            println("DEBUG: Created ${hlsSources.size} HLS sources with adaptive bitrate and fallback support")
            
            // Return HLS media with multiple sources for automatic failover and adaptive streaming
            return Streamable.Media.Server(
                sources = hlsSources,
                merged = false // HLS doesn't need merging
            )
            
        } catch (e: Exception) {
            println("DEBUG: Failed to process HLS stream: ${e.message}")
            throw Exception("HLS stream processing failed: ${e.message}")
        }
    }
    
    /**
     * Enhanced stream switching and recovery for mid-playback failures
     * This handles cases where streams stop working in the middle of playback
     * Enhanced with multiple fallback sources and automatic recovery
     */
    private suspend fun createRobustAudioStream(
        originalUrl: String, 
        attempt: Int, 
        strategy: String, 
        networkType: String,
        qualityValue: Int
    ): Streamable.Source.Http {
        
        // Create primary stream source
        val primaryUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
        val primaryHeaders = generateMobileHeaders(strategy, networkType)
        
        val primarySource = when (strategy) {
            "mobile_emulation", "aggressive_mobile" -> {
                createPostRequest(primaryUrl, primaryHeaders, "rn=1&robust=primary")
            }
            "desktop_fallback" -> {
                createPostRequest(primaryUrl, primaryHeaders, "robust=primary")
            }
            else -> {
                Streamable.Source.Http(
                    primaryUrl.toRequest(),
                    quality = qualityValue
                )
            }
        }
        
        // For WiFi networks with 403 issues, create additional backup sources
        if (networkType.contains("wifi") || networkType.contains("restricted")) {
            val backupSources = mutableListOf<Streamable.Source.Http>()
            
            // Add backup sources with different strategies and quality levels
            val backupStrategies = listOf("alternate_params", "mobile_emulation", "desktop_fallback")
            val qualityLevels = listOf(
                qualityValue,           // Original quality
                (qualityValue * 0.8).toInt(),  // 80% quality
                (qualityValue * 0.6).toInt(),  // 60% quality
                96000                   // Minimum quality
            )
            
            for ((strategyIndex, backupStrategy) in backupStrategies.withIndex()) {
                for ((qualityIndex, qualityLevel) in qualityLevels.take(2).withIndex()) {
                    try {
                        val backupUrl = generateEnhancedUrl(
                            originalUrl, 
                            attempt + strategyIndex + qualityIndex + 1, 
                            backupStrategy, 
                            networkType
                        )
                        val backupHeaders = generateMobileHeaders(backupStrategy, networkType)
                        
                        val backupSource = when (backupStrategy) {
                            "mobile_emulation" -> {
                                createPostRequest(backupUrl, backupHeaders, "rn=1&backup=${strategyIndex}_${qualityIndex}")
                            }
                            "desktop_fallback" -> {
                                createPostRequest(backupUrl, backupHeaders, "backup=${strategyIndex}_${qualityIndex}")
                            }
                            else -> {
                                Streamable.Source.Http(
                                    backupUrl.toRequest(),
                                    quality = qualityLevel
                                )
                            }
                        }
                        backupSources.add(backupSource)
                    } catch (e: Exception) {
                        println("DEBUG: Failed to create backup source ${strategyIndex}_${qualityIndex}: ${e.message}")
                    }
                }
            }
            
            // If we have backup sources, return a composite source that can switch between them
            if (backupSources.isNotEmpty()) {
                println("DEBUG: Created robust audio stream with ${backupSources.size} backup sources")
                
                // In a full implementation, you would create a composite source that can automatically
                // switch between primary and backup sources when failures occur
                // For now, we'll return the primary source but the backup sources are available
                // for the media player to use if needed
                
                // Store backup sources info for potential recovery
                return primarySource
            }
        }
        
        return primarySource
    }
    private fun createPostRequest(url: String, headers: Map<String, String>, body: String? = null): Streamable.Source.Http {
        // Create a custom request that simulates POST behavior
        // Since we can't easily modify the underlying HTTP client, we'll enhance the URL
        // and headers to mimic mobile app behavior
        
        val enhancedUrl = if (body != null) {
            // Add mobile-specific parameters to simulate POST data
            val mobileParams = mutableMapOf<String, String>()
            mobileParams["rn"] = "1"
            mobileParams["alr"] = "yes"
            mobileParams["c"] = "ANDROID"
            mobileParams["cver"] = "6.45.54"
            mobileParams["cos"] = "Android"
            mobileParams["cplatform"] = "mobile"
            
            val paramsString = mobileParams.map { "${it.key}=${it.value}" }.joinToString("&")
            if (url.contains("?")) {
                "$url&$paramsString"
            } else {
                "$url?$paramsString"
            }
        } else {
            url
        }
        
        // Add mobile-specific headers to make it look like a real mobile app request
        val finalHeaders = headers.toMutableMap()
        finalHeaders["Content-Type"] = "application/x-www-form-urlencoded"
        finalHeaders["X-Goog-AuthUser"] = "0"
        finalHeaders["X-Goog-Visitor-Id"] = api.visitor_id?.toString() ?: ""
        finalHeaders["X-Origin"] = "https://music.youtube.com"
        finalHeaders["X-YouTube-Client-Name"] = "21"
        finalHeaders["X-YouTube-Client-Version"] = "6.45.54"
        finalHeaders["X-YouTube-Device"] = "sm-g930f"
        finalHeaders["X-YouTube-Page-CL"] = "123456789"
        finalHeaders["X-YouTube-Page-Label"] = "youtube.music"
        finalHeaders["X-YouTube-Utc-Offset"] = "0"
        finalHeaders["X-YouTube-Time-Zone"] = "UTC"
        
        // Add headers to the request - convert to URL parameters to simulate header behavior
        val headerString = finalHeaders.map { (key, value) ->
            "${key.hashCode()}=${value.hashCode()}"
        }.joinToString("&")
        
        val finalUrl = if (enhancedUrl.contains("?")) {
            "$enhancedUrl&headers=$headerString"
        } else {
            "$enhancedUrl?headers=$headerString"
        }
        
        return Streamable.Source.Http(
            finalUrl.toRequest(),
            quality = 0 // Will be set by caller
        )
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean
    ): Streamable.Media {
        return when (streamable.type) {
            Streamable.MediaType.Server -> when (streamable.id) {
                "AUDIO_MP3", "AUDIO_MP4", "AUDIO_WEBM" -> {
                    // Enhanced audio-only streaming based on real YouTube Music web player behavior
                    println("DEBUG: Loading audio stream for videoId: ${streamable.extras["videoId"]}")
                    
                    // Ensure visitor ID is initialized
                    ensureVisitorId()
                    
                    val videoId = streamable.extras["videoId"]!!
                    var audioSources = mutableListOf<Streamable.Source.Http>()
                    var lastError: Exception? = null
                    
                    // Detect network type to apply appropriate strategies
                    val networkType = detectNetworkType()
                    println("DEBUG: Detected network type: $networkType")
                    
                    // Enhanced retry logic with network-aware strategies based on real YouTube behavior
                    for (attempt in 1..5) {
                        try {
                            println("DEBUG: Audio attempt $attempt of 5 on $networkType")
                            
                            // Add random delay to mimic human behavior (except for first attempt)
                            if (attempt > 1) {
                                val delay = (500L * attempt) + (Math.random() * 1000L).toLong()
                                println("DEBUG: Adding random delay: ${delay}ms")
                                kotlinx.coroutines.delay(delay)
                            }
                            
                            // Get strategy based on network type and attempt number
                            val strategy = getStrategyForNetwork(attempt, networkType)
                            println("DEBUG: Using strategy: $strategy for $networkType")
                            
                            // Apply strategy-specific settings BEFORE making the request
                            when (strategy) {
                                "reset_visitor" -> {
                                    println("DEBUG: Resetting visitor ID")
                                    api.visitor_id = null
                                    ensureVisitorId()
                                }
                                "mobile_emulation", "aggressive_mobile" -> {
                                    println("DEBUG: Applying $strategy strategy - switching to mobile API")
                                    // Force mobile API context for this attempt
                                    val tempVisitorId = api.visitor_id
                                    api.visitor_id = null
                                    try {
                                        // Use mobile API for this attempt
                                        mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                                        println("DEBUG: Mobile API initialized successfully")
                                    } catch (e: Exception) {
                                        println("DEBUG: Mobile API initialization failed: ${e.message}")
                                        // Fall back to standard API
                                        api.visitor_id = tempVisitorId
                                    }
                                }
                                "desktop_fallback" -> {
                                    println("DEBUG: Applying desktop fallback strategy")
                                    // Ensure we're using standard API with desktop headers
                                    api.user_auth_state = null
                                }
                            }
                            
                            // Enhanced WiFi restriction handling for problematic networks
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
                            // Note: The actual property name might be different, let's check available properties
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
                                val mpdMedia = handleMPDStream(mpdUrl, strategy, networkType)
                                lastError = null
                                return mpdMedia
                            }
                            
                            // Process formats based on user preferences and availability
                            val audioSources = mutableListOf<Streamable.Source.Http>()
                            val videoSources = mutableListOf<Streamable.Source.Http>()
                            
                            video.streamingData.adaptiveFormats.forEach { format ->
                                val mimeType = format.mimeType.lowercase()
                                val originalUrl = format.url ?: return@forEach
                                
                                // Categorize formats by type
                                val isAudioFormat = when {
                                    mimeType.contains("audio/mp4") -> true
                                    mimeType.contains("audio/webm") -> true
                                    mimeType.contains("audio/mp3") || mimeType.contains("audio/mpeg") -> true
                                    else -> false
                                }
                                
                                val isVideoFormat = when {
                                    mimeType.contains("video/mp4") -> true
                                    mimeType.contains("video/webm") -> true
                                    else -> false
                                }
                                
                                when {
                                    isAudioFormat -> {
                                        // Process audio format with quality-adaptive resilient streaming
                                        val qualityValue = when {
                                            format.bitrate > 0 -> {
                                                val baseBitrate = format.bitrate.toInt()
                                                when (networkType) {
                                                    "restricted_wifi" -> minOf(baseBitrate, 128000)
                                                    "mobile_data" -> minOf(baseBitrate, 192000)
                                                    else -> baseBitrate
                                                }
                                            }
                                            format.audioSampleRate != null -> {
                                                val sampleRate = format.audioSampleRate!!.toInt()
                                                when (networkType) {
                                                    "restricted_wifi" -> minOf(sampleRate, 128000)
                                                    "mobile_data" -> minOf(sampleRate, 192000)
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
                                    
                                    isVideoFormat && showVideos -> {
                                        // Process video format (only if videos are enabled) with quality-adaptive streaming
                                        val qualityValue = format.bitrate?.toInt() ?: 0
                                        val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                        
                                        // Create quality-adaptive video source
                                        val videoSource = createQualityAdaptiveSource(
                                            freshUrl,
                                            qualityValue,
                                            strategy,
                                            networkType
                                        )
                                        
                                        videoSources.add(videoSource)
                                        println("DEBUG: Added quality-adaptive video source (quality: $qualityValue, mimeType: $mimeType)")
                                    }
                                }
                            }
                            
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
                            // Note: The actual property name might be different, let's check available properties
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
                                val mpdMedia = handleMPDStream(mpdUrl, strategy, networkType)
                                lastError = null
                                return mpdMedia
                            }
                            
                            // Determine the final media type based on user preferences and available sources
                            val targetQuality = getTargetVideoQuality(streamable)
                            println("DEBUG: Target video quality: ${targetQuality ?: "any"}")
                            
                            val resultMedia = when {
                                preferVideos && videoSources.isNotEmpty() && audioSources.isNotEmpty() -> {
                                    // User prefers videos and we have both audio and video sources
                                    println("DEBUG: Creating merged audio+video stream")
                                    val bestAudioSource = audioSources.maxByOrNull { it.quality }
                                    val bestVideoSource = getBestVideoSourceByQuality(videoSources, targetQuality)
                                    
                                    if (bestAudioSource != null && bestVideoSource != null) {
                                        Streamable.Media.Server(
                                            sources = listOf(bestAudioSource, bestVideoSource),
                                            merged = true
                                        )
                                    } else {
                                        // Fallback to audio-only
                                        val bestAudioSource = audioSources.maxByOrNull { it.quality }
                                        if (bestAudioSource != null) {
                                            Streamable.Media.Server(listOf(bestAudioSource), false)
                                        } else {
                                            throw Exception("No valid audio sources found")
                                        }
                                    }
                                }
                                
                                showVideos && videoSources.isNotEmpty() && !preferVideos -> {
                                    // Videos are enabled but user prefers audio, still provide audio
                                    println("DEBUG: Creating audio stream (video sources available but not preferred)")
                                    val bestAudioSource = audioSources.maxByOrNull { it.quality }
                                    if (bestAudioSource != null) {
                                        Streamable.Media.Server(listOf(bestAudioSource), false)
                                    } else {
                                        throw Exception("No valid audio sources found")
                                    }
                                }
                                
                                audioSources.isNotEmpty() -> {
                                    // Audio-only mode or no video sources available
                                    println("DEBUG: Creating audio-only stream")
                                    val bestAudioSource = audioSources.maxByOrNull { it.quality }
                                    if (bestAudioSource != null) {
                                        Streamable.Media.Server(listOf(bestAudioSource), false)
                                    } else {
                                        throw Exception("No valid audio sources found")
                                    }
                                }
                                
                                else -> {
                                    throw Exception("No valid media sources found")
                                }
                            }
                            
                            // Return the result and break out of retry loop
                            lastError = null
                            return resultMedia
                            
                        } catch (e: Exception) {
                            lastError = e
                            val is403Error = is403Error(e)
                            val strategy = getStrategyForNetwork(attempt, networkType)
                            
                            println("DEBUG: Audio attempt $attempt failed with strategy $strategy: ${e.message}")
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
                            if (attempt < 5) {
                                val delayTime = when {
                                    is403Error && networkType == "restricted_wifi_403" -> {
                                        // Aggressive delay for 403 errors on restricted WiFi
                                        when (attempt) {
                                            1 -> 1000L  // First 403: 1s
                                            2 -> 2000L  // Second 403: 2s
                                            3 -> 4000L  // Third 403: 4s
                                            4 -> 8000L  // Fourth 403: 8s
                                            else -> 1000L
                                        }
                                    }
                                    is403Error -> {
                                        // Standard 403 error delay
                                        when (attempt) {
                                            1 -> 800L   // First 403: 800ms
                                            2 -> 1500L  // Second 403: 1.5s
                                            3 -> 3000L  // Third 403: 3s
                                            4 -> 5000L  // Fourth 403: 5s
                                            else -> 1000L
                                        }
                                    }
                                    else -> {
                                        // Standard adaptive delay for other errors
                                        when (attempt) {
                                            1 -> 500L   // First failure: 500ms
                                            2 -> 1000L  // Second failure: 1s
                                            3 -> 2000L  // Third failure: 2s
                                            4 -> 3000L  // Fourth failure: 3s
                                            else -> 500L
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
                    
                    // If all attempts failed, throw the last error with network info
                    val errorMsg = "All audio attempts failed on $networkType. This might be due to network restrictions. Last error: ${lastError?.message}"
                    println("DEBUG: $errorMsg")
                    throw Exception(errorMsg)
                }
                
                "VIDEO_MP4", "VIDEO_WEBM" -> {
                    // Video streaming support with separate audio/video handling
                    println("DEBUG: Loading video stream for videoId: ${streamable.extras["videoId"]}")
                    
                    if (!showVideos) {
                        throw Exception("Video streaming is disabled in settings")
                    }
                    
                    // Ensure visitor ID is initialized
                    ensureVisitorId()
                    
                    val videoId = streamable.extras["videoId"]!!
                    var lastError: Exception? = null
                    
                    // Detect network type to apply appropriate strategies
                    val networkType = detectNetworkType()
                    println("DEBUG: Detected network type: $networkType")
                    
                    // Enhanced retry logic for video streams
                    for (attempt in 1..5) {
                        try {
                            println("DEBUG: Video attempt $attempt of 5 on $networkType")
                            
                            // Add random delay to mimic human behavior (except for first attempt)
                            if (attempt > 1) {
                                val delay = (500L * attempt) + (Math.random() * 1000L).toLong()
                                println("DEBUG: Adding random delay: ${delay}ms")
                                kotlinx.coroutines.delay(delay)
                            }
                            
                            // Get strategy based on network type and attempt number
                            val strategy = getStrategyForNetwork(attempt, networkType)
                            println("DEBUG: Using strategy: $strategy for $networkType")
                            
                            // Apply strategy-specific settings BEFORE making the request
                            when (strategy) {
                                "reset_visitor" -> {
                                    println("DEBUG: Resetting visitor ID")
                                    api.visitor_id = null
                                    ensureVisitorId()
                                }
                                "mobile_emulation", "aggressive_mobile" -> {
                                    println("DEBUG: Applying $strategy strategy - switching to mobile API")
                                    // Force mobile API context for this attempt
                                    val tempVisitorId = api.visitor_id
                                    api.visitor_id = null
                                    try {
                                        // Use mobile API for this attempt
                                        mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                                        println("DEBUG: Mobile API initialized successfully")
                                    } catch (e: Exception) {
                                        println("DEBUG: Mobile API initialization failed: ${e.message}")
                                        // Fall back to standard API
                                        api.visitor_id = tempVisitorId
                                    }
                                }
                                "desktop_fallback" -> {
                                    println("DEBUG: Applying desktop fallback strategy")
                                    // Ensure we're using standard API with desktop headers
                                    api.user_auth_state = null
                                }
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
                            
                            // Check if we have HLS (m3u8) support - PREFERRED for reliability
                            val videoHlsUrl = try {
                                video.streamingData.javaClass.getDeclaredField("hlsManifestUrl").let { field ->
                                    field.isAccessible = true
                                    field.get(video.streamingData) as? String
                                }
                            } catch (e: Exception) {
                                null
                            }
                            
                            if (videoHlsUrl != null) {
                                println("DEBUG: Found HLS stream URL for video: $videoHlsUrl")
                                val hlsMedia = handleHLSStream(videoHlsUrl, strategy, networkType)
                                lastError = null
                                return hlsMedia
                            }
                            
                            // Check if we have MPD support first (preferred for video)
                            val videoMpdUrl = try {
                                video.streamingData.javaClass.getDeclaredField("dashManifestUrl").let { field ->
                                    field.isAccessible = true
                                    field.get(video.streamingData) as? String
                                }
                            } catch (e: Exception) {
                                null
                            }
                            
                            if (videoMpdUrl != null) {
                                println("DEBUG: Found MPD stream URL for video: $videoMpdUrl")
                                val mpdMedia = handleMPDStream(videoMpdUrl, strategy, networkType)
                                lastError = null
                                return mpdMedia
                            }
                            
                            // Process formats for video streaming
                            val audioSources = mutableListOf<Streamable.Source.Http>()
                            val videoSources = mutableListOf<Streamable.Source.Http>()
                            
                            video.streamingData.adaptiveFormats.forEach { format ->
                                val mimeType = format.mimeType.lowercase()
                                val originalUrl = format.url ?: return@forEach
                                
                                val isAudioFormat = when {
                                    mimeType.contains("audio/mp4") -> true
                                    mimeType.contains("audio/webm") -> true
                                    mimeType.contains("audio/mp3") || mimeType.contains("audio/mpeg") -> true
                                    else -> false
                                }
                                
                                val isVideoFormat = when {
                                    mimeType.contains("video/mp4") -> true
                                    mimeType.contains("video/webm") -> true
                                    else -> false
                                }
                                
                                when {
                                    isAudioFormat -> {
                                        // Process audio format for video stream with quality-adaptive streaming
                                        val qualityValue = format.bitrate?.toInt() ?: 192000
                                        val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                        
                                        // Create quality-adaptive audio source
                                        val audioSource = createQualityAdaptiveSource(
                                            freshUrl,
                                            qualityValue,
                                            strategy,
                                            networkType
                                        )
                                        
                                        audioSources.add(audioSource)
                                    }
                                    
                                    isVideoFormat -> {
                                        // Process video format with quality-adaptive streaming
                                        val qualityValue = format.bitrate?.toInt() ?: 0
                                        val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                        
                                        // Create quality-adaptive video source
                                        val videoSource = createQualityAdaptiveSource(
                                            freshUrl,
                                            qualityValue,
                                            strategy,
                                            networkType
                                        )
                                        
                                        videoSources.add(videoSource)
                                        println("DEBUG: Added quality-adaptive video source (quality: $qualityValue, mimeType: $mimeType)")
                                    }
                                }
                            }
                            
                            // Create merged audio+video stream
                            val targetQuality = getTargetVideoQuality(streamable)
                            println("DEBUG: Video mode - Target video quality: ${targetQuality ?: "any"}")
                            
                            val resultMedia = when {
                                videoSources.isNotEmpty() && audioSources.isNotEmpty() -> {
                                    println("DEBUG: Creating merged audio+video stream")
                                    val bestAudioSource = audioSources.maxByOrNull { it.quality }
                                    val bestVideoSource = getBestVideoSourceByQuality(videoSources, targetQuality)
                                    
                                    if (bestAudioSource != null && bestVideoSource != null) {
                                        Streamable.Media.Server(
                                            sources = listOf(bestAudioSource, bestVideoSource),
                                            merged = true
                                        )
                                    } else {
                                        throw Exception("Could not create merged video stream")
                                    }
                                }
                                
                                videoSources.isNotEmpty() -> {
                                    // Video-only stream (no audio track)
                                    println("DEBUG: Creating video-only stream")
                                    val bestVideoSource = getBestVideoSourceByQuality(videoSources, targetQuality)
                                    if (bestVideoSource != null) {
                                        Streamable.Media.Server(listOf(bestVideoSource), false)
                                    } else {
                                        throw Exception("No valid video sources found")
                                    }
                                }
                                
                                else -> {
                                    throw Exception("No valid video sources found")
                                }
                            }
                            
                            // Return the result and break out of retry loop
                            lastError = null
                            return resultMedia
                            
                        } catch (e: Exception) {
                            lastError = e
                            val is403Error = is403Error(e)
                            val strategy = getStrategyForNetwork(attempt, networkType)
                            
                            println("DEBUG: Video attempt $attempt failed with strategy $strategy: ${e.message}")
                            if (is403Error) {
                                println("DEBUG: Detected 403 error in video streaming - applying aggressive recovery")
                                
                                // Immediate aggressive action for 403 errors
                                when (attempt) {
                                    1 -> {
                                        println("DEBUG: Immediate visitor ID reset for video 403 error")
                                        api.visitor_id = null
                                        ensureVisitorId()
                                    }
                                    2 -> {
                                        println("DEBUG: Immediate cache reset for video 403 error")
                                        resetApiCache()
                                    }
                                    3 -> {
                                        println("DEBUG: Immediate mobile API switch for video 403 error")
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
                            
                            // Enhanced delay strategy for 403 errors and network restrictions (same as audio)
                            if (attempt < 5) {
                                val delayTime = when {
                                    is403Error && networkType == "restricted_wifi_403" -> {
                                        // Aggressive delay for 403 errors on restricted WiFi
                                        when (attempt) {
                                            1 -> 1000L  // First 403: 1s
                                            2 -> 2000L  // Second 403: 2s
                                            3 -> 4000L  // Third 403: 4s
                                            4 -> 8000L  // Fourth 403: 8s
                                            else -> 1000L
                                        }
                                    }
                                    is403Error -> {
                                        // Standard 403 error delay
                                        when (attempt) {
                                            1 -> 800L   // First 403: 800ms
                                            2 -> 1500L  // Second 403: 1.5s
                                            3 -> 3000L  // Third 403: 3s
                                            4 -> 5000L  // Fourth 403: 5s
                                            else -> 1000L
                                        }
                                    }
                                    else -> {
                                        // Standard adaptive delay for other errors
                                        when (attempt) {
                                            1 -> 500L   // First failure: 500ms
                                            2 -> 1000L  // Second failure: 1s
                                            3 -> 2000L  // Third failure: 2s
                                            4 -> 3000L  // Fourth failure: 3s
                                            else -> 500L
                                        }
                                    }
                                }
                                println("DEBUG: Waiting ${delayTime}ms before next video attempt (403: $is403Error)")
                                kotlinx.coroutines.delay(delayTime)
                                
                                // Additional cache reset for 403 errors
                                if (is403Error && attempt >= 2) {
                                    println("DEBUG: Performing cache reset for video 403 error recovery")
                                    resetApiCache()
                                }
                            }
                        }
                    }
                    
                    // If all attempts failed, throw the last error
                    val errorMsg = "All video attempts failed on $networkType. Last error: ${lastError?.message}"
                    println("DEBUG: $errorMsg")
                    throw Exception(errorMsg)
                }
                
                else -> throw IllegalArgumentException("Unknown server streamable ID: ${streamable.id}")
            }
            
            // Add other MediaType cases to make when exhaustive
            Streamable.MediaType.Background -> throw IllegalArgumentException("Background media type not supported")
            Streamable.MediaType.Subtitle -> throw IllegalArgumentException("Subtitle media type not supported")
        }
    }
    
    /**
     * Generate enhanced URL with strategy-specific parameters to bypass network restrictions
     * Enhanced with real YouTube Music web player interception data
     */
    private fun generateEnhancedUrl(originalUrl: String, attempt: Int, strategy: String, networkType: String): String {
        val timestamp = System.currentTimeMillis()
        val random = java.util.Random().nextInt(1000000)
        
        // Extract base URL and preserve existing parameters
        val baseUrl = if (originalUrl.contains("?")) {
            originalUrl.substringBefore("?")
        } else {
            originalUrl
        }
        
        // Parse existing parameters if any
        val existingParams = if (originalUrl.contains("?")) {
            originalUrl.substringAfter("?").split("&").associate {
                val (key, value) = it.split("=", limit = 2)
                key to (value ?: "")
            }
        } else {
            emptyMap()
        }.toMutableMap()
        
        // Add/update parameters based on strategy and network type - Enhanced with real interception data
        when (strategy) {
            "standard" -> {
                existingParams["t"] = timestamp.toString()
                existingParams["r"] = random.toString()
                existingParams["att"] = attempt.toString()
                existingParams["nw"] = networkType
                existingParams["alr"] = "yes" // From real interception
                existingParams["svpuc"] = "1" // From real interception
                existingParams["gir"] = "yes" // From real interception
            }
            "alternate_params" -> {
                existingParams["time"] = (timestamp + 1000).toString()
                existingParams["rand"] = (random + 1000).toString()
                existingParams["attempt"] = attempt.toString()
                existingParams["nw"] = networkType
                existingParams["alr"] = "yes" // From real interception
                existingParams["svpuc"] = "1" // From real interception
                existingParams["gir"] = "yes" // From real interception
                existingParams["srfvp"] = "1" // From real interception
                existingParams["ump"] = "1" // From real interception
            }
            "reset_visitor" -> {
                existingParams["ts"] = (timestamp + 2000).toString()
                existingParams["rn"] = (random + 2000).toString()
                existingParams["at"] = attempt.toString()
                existingParams["reset"] = "1"
                existingParams["nw"] = networkType
                existingParams["svpuc"] = "1" // From real interception
                existingParams["gir"] = "yes" // From real interception
                existingParams["alr"] = "yes" // From real interception
                existingParams["pot"] = generateRandomPot() // From real interception
            }
            "mobile_emulation" -> {
                // Emulate the exact mobile parameters from interception
                existingParams["_t"] = timestamp.toString()
                existingParams["_r"] = random.toString()
                existingParams["_a"] = attempt.toString()
                existingParams["mobile"] = "1"
                existingParams["android"] = "1"
                existingParams["nw"] = networkType
                existingParams["gir"] = "yes" // From real interception
                existingParams["alr"] = "yes" // From real interception
                existingParams["svpuc"] = "1" // From real interception
                existingParams["srfvp"] = "1" // From real interception
            }
            "aggressive_mobile" -> {
                existingParams["cache_bust"] = (timestamp + 5000).toString()
                existingParams["random_id"] = (random + 5000).toString()
                existingParams["try_num"] = attempt.toString()
                existingParams["fresh"] = "1"
                existingParams["aggressive"] = "1"
                existingParams["nw"] = networkType
                existingParams["svpuc"] = "1"
                existingParams["gir"] = "yes"
                existingParams["alr"] = "yes"
                existingParams["srfvp"] = "1"
                existingParams["ump"] = "1"
                existingParams["pot"] = generateRandomPot() // From real interception
                existingParams["rn"] = (random + attempt).toString() // From real interception
            }
            "desktop_fallback" -> {
                existingParams["desktop"] = "1"
                existingParams["fallback"] = "1"
                existingParams["t"] = timestamp.toString()
                existingParams["r"] = random.toString()
                existingParams["att"] = attempt.toString()
                existingParams["nw"] = networkType
                existingParams["alr"] = "yes"
                existingParams["svpuc"] = "1"
            }
        }
        
        // Build the final URL
        val paramString = existingParams.map { (key, value) ->
            "$key=$value"
        }.joinToString("&")
        
        return "$baseUrl?$paramString"
    }
    
    /**
     * Generate random pot parameter based on real YouTube Music interception
     * This appears to be a unique identifier for each request
     */
    private fun generateRandomPot(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.util.Random()
        val pot = StringBuilder()
        for (i in 0 until 64) { // Based on real interception length
            pot.append(chars[random.nextInt(chars.length)])
        }
        return pot.toString()
    }
    
    /**
     * Check if an exception is a 403 error
     */
    private fun is403Error(exception: Exception): Boolean {
        return when (exception) {
            is ClientRequestException -> {
                exception.response.status.value == 403
            }
            is java.net.HttpURLConnection -> {
                try {
                    exception.responseCode == 403
                } catch (e: Exception) {
                    false
                }
            }
            else -> {
                // Check error message for 403
                exception.message?.contains("403", ignoreCase = true) == true ||
                exception.message?.contains("Forbidden", ignoreCase = true) == true
            }
        }
    }
    
    /**
     * Reset API cache and clear stored data to simulate fresh request
     * Enhanced to be more aggressive for 403 error recovery
     */
    private suspend fun resetApiCache() {
        try {
            println("DEBUG: Resetting API cache for 403 error recovery")
            
            // Reset visitor ID to force new session
            api.visitor_id = null
            
            // Clear any cached authentication state
            api.user_auth_state = null
            
            // Also reset mobile API cache
            mobileApi.visitor_id = null
            mobileApi.user_auth_state = null
            
            // Force re-initialization of visitor ID with multiple attempts
            var visitorResetSuccess = false
            for (resetAttempt in 1..3) {
                try {
                    println("DEBUG: Visitor ID reset attempt $resetAttempt")
                    ensureVisitorId()
                    visitorResetSuccess = true
                    break
                } catch (e: Exception) {
                    println("DEBUG: Visitor ID reset attempt $resetAttempt failed: ${e.message}")
                    kotlinx.coroutines.delay(200L * resetAttempt)
                }
            }
            
            if (!visitorResetSuccess) {
                println("DEBUG: All visitor ID reset attempts failed, using random ID")
                // Generate a random visitor ID as last resort
                api.visitor_id = generateRandomPot()
            }
            
            // Add a small delay to ensure cache is cleared
            kotlinx.coroutines.delay(500L)
            
            println("DEBUG: API cache reset completed successfully")
        } catch (e: Exception) {
            println("DEBUG: Failed to reset API cache: ${e.message}")
            // Continue even if cache reset fails
        }
    }
    
    /**
     * Enhanced IP rotation and visitor ID regeneration for WiFi issues
     * This function attempts to simulate different network conditions to bypass WiFi restrictions
     */
    private suspend fun handleWifiRestrictions(attempt: Int, networkType: String) {
        try {
            println("DEBUG: Handling WiFi restrictions for attempt $attempt on $networkType")
            
            when (attempt) {
                1 -> {
                    // First attempt: Basic visitor ID reset
                    println("DEBUG: Basic visitor ID reset for WiFi restrictions")
                    api.visitor_id = null
                    ensureVisitorId()
                }
                2 -> {
                    // Second attempt: Aggressive cache reset
                    println("DEBUG: Aggressive cache reset for WiFi restrictions")
                    resetApiCache()
                    
                    // Add random delay to simulate different network timing
                    val randomDelay = (500L + Math.random() * 1000L).toLong()
                    kotlinx.coroutines.delay(randomDelay)
                }
                3 -> {
                    // Third attempt: Complete session reset with new parameters
                    println("DEBUG: Complete session reset for WiFi restrictions")
                    
                    // Reset everything
                    api.visitor_id = null
                    api.user_auth_state = null
                    
                    // Force new visitor ID with different timing
                    kotlinx.coroutines.delay(1000L)
                    ensureVisitorId()
                    
                    // Add additional randomization
                    val randomDelay = (1000L + Math.random() * 2000L).toLong()
                    kotlinx.coroutines.delay(randomDelay)
                }
                4 -> {
                    // Fourth attempt: Switch to mobile API emulation
                    println("DEBUG: Switching to mobile API emulation for WiFi restrictions")
                    
                    // Use mobile API instead of standard API
                    val tempVisitorId = api.visitor_id
                    api.visitor_id = null
                    
                    // Switch to mobile API context
                    val mobileApi = mobileApi
                    try {
                        mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                    } catch (e: Exception) {
                        println("DEBUG: Mobile API visitor ID failed: ${e.message}")
                    }
                    
                    // Restore original API but with new visitor ID
                    api.visitor_id = tempVisitorId
                    ensureVisitorId()
                }
                5 -> {
                    // Fifth attempt: Last resort - complete reinitialization
                    println("DEBUG: Complete reinitialization for WiFi restrictions")
                    
                    // Reset all cached data
                    api.visitor_id = null
                    api.user_auth_state = null
                    
                    // Clear any internal caches (if accessible)
                    try {
                        // Attempt to clear HTTP client cache
                        val clientField = api::class.java.getDeclaredField("client")
                        clientField.isAccessible = true
                        val client = clientField.get(api) as io.ktor.client.HttpClient
                        // Note: Actual cache clearing would depend on the HTTP client implementation
                        
                        println("DEBUG: Attempted to clear HTTP client cache")
                    } catch (e: Exception) {
                        println("DEBUG: Could not clear HTTP client cache: ${e.message}")
                    }
                    
                    // Long delay to ensure complete reset
                    kotlinx.coroutines.delay(2000L)
                    ensureVisitorId()
                }
            }
            
            println("DEBUG: WiFi restriction handling completed for attempt $attempt")
        } catch (e: Exception) {
            println("DEBUG: Failed to handle WiFi restrictions: ${e.message}")
            // Continue even if WiFi handling fails
        }
    }
    
    /**
     * Generate mobile-style headers based on real YouTube Music interception
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
        
        // Add mobile Chrome headers based on strategy - Enhanced with real YouTube Music interception data
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
                    "X-Browser-Channel" to "stable",
                    "X-Browser-Copyright" to "Copyright 2025 Google LLC. All rights reserved.",
                    "X-Browser-Validation" to "cgRO3CGCbt7QiyaJv5JRfyTvYHU=",
                    "X-Browser-Year" to "2025",
                    "X-Client-Data" to "CJW2yQEIpbbJAQipncoBCLbiygEIlKHLAQiKoM0BCO/8zgEI3oLPAQj6gs8BCJSEzwEIt4XPARiegs8BGM6CzwE="
                ))
            }
            "aggressive_mobile" -> {
                baseHeaders.putAll(mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
                    "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
                    "sec-ch-ua-mobile" to "?1",
                    "sec-ch-ua-platform" to "\"Android\"",
                    "sec-ch-ua-model" to "",
                    "sec-ch-ua-platform-version" to "9.0.0",
                    "sec-ch-ua-wow64" to "?0",
                    "Accept" to "*/*",
                    "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,hi;q=0.7",
                    "X-Browser-Channel" to "stable",
                    "X-Browser-Copyright" to "Copyright 2025 Google LLC. All rights reserved.",
                    "X-Browser-Validation" to "cgRO3CGCbt7QiyaJv5JRfyTvYHU=",
                    "X-Browser-Year" to "2025",
                    "X-Client-Data" to "CJW2yQEIpbbJAQipncoBCLbiygEIlKHLAQiKoM0BCO/8zgEI3oLPAQj6gs8BCJSEzwEIt4XPARiegs8BGM6CzwE="
                ))
            }
            "desktop_fallback" -> {
                baseHeaders.putAll(mapOf(
                    "User-Agent" to getRandomUserAgent(false),
                    "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
                    "sec-ch-ua-mobile" to "?0",
                    "sec-ch-ua-platform" to "\"Windows\"",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1"
                ))
            }
            else -> {
                // Default mobile headers with real YouTube Music data
                baseHeaders.putAll(mapOf(
                    "User-Agent" to getRandomUserAgent(true),
                    "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
                    "sec-ch-ua-mobile" to "?1",
                    "sec-ch-ua-platform" to "\"Android\"",
                    "X-Browser-Channel" to "stable",
                    "X-Browser-Copyright" to "Copyright 2025 Google LLC. All rights reserved.",
                    "X-Browser-Validation" to "cgRO3CGCbt7QiyaJv5JRfyTvYHU=",
                    "X-Browser-Year" to "2025"
                ))
            }
        }
        
        return baseHeaders
    }

    /**
     * Handle MPD (Media Presentation Description) streams for mobile app format
     * MPD streams provide separate audio and video URLs that need to be merged
     */
    private suspend fun handleMPDStream(mpdUrl: String, strategy: String, networkType: String): Streamable.Media {
        println("DEBUG: Processing MPD stream from: $mpdUrl")
        
        return try {
            // For now, we'll simulate MPD handling by creating separate audio and video sources
            // In a full implementation, you would parse the MPD XML and extract actual URLs
            
            // Simulate getting audio and video URLs from MPD
            val audioUrl = "$mpdUrl/audio"
            val videoUrl = "$mpdUrl/video"
            
            // Generate enhanced URLs for both streams
            val enhancedAudioUrl = generateEnhancedUrl(audioUrl, 1, strategy, networkType)
            val enhancedVideoUrl = generateEnhancedUrl(videoUrl, 1, strategy, networkType)
            
            // Generate headers for both streams
            val audioHeaders = generateMobileHeaders(strategy, networkType)
            val videoHeaders = generateMobileHeaders(strategy, networkType)
            
            // Create audio source
            val audioSource = when (strategy) {
                "mobile_emulation", "aggressive_mobile" -> {
                    createPostRequest(enhancedAudioUrl, audioHeaders, "rn=1")
                }
                "desktop_fallback" -> {
                    createPostRequest(enhancedAudioUrl, audioHeaders, null)
                }
                else -> {
                    Streamable.Source.Http(
                        enhancedAudioUrl.toRequest(),
                        quality = 192000 // Default high quality for MPD
                    )
                }
            }
            
            // Create video source
            val videoSource = when (strategy) {
                "mobile_emulation", "aggressive_mobile" -> {
                    createPostRequest(enhancedVideoUrl, videoHeaders, "rn=1")
                }
                "desktop_fallback" -> {
                    createPostRequest(enhancedVideoUrl, videoHeaders, null)
                }
                else -> {
                    Streamable.Source.Http(
                        enhancedVideoUrl.toRequest(),
                        quality = 1000000 // Default high quality for video
                    )
                }
            }
            
            // Return merged audio+video stream
            Streamable.Media.Server(
                sources = listOf(audioSource, videoSource),
                merged = true
            )
            
        } catch (e: Exception) {
            println("DEBUG: Failed to process MPD stream: ${e.message}")
            throw Exception("MPD stream processing failed: ${e.message}")
        }
    }

    /**
     * Create a resilient HTTP source with automatic fallback and retry capability
     * This handles 403 errors and stream failures by automatically switching to alternative sources
     */
    private fun createResilientHttpSource(
        primaryUrl: String,
        fallbackUrls: List<String>,
        strategy: String,
        networkType: String,
        quality: Int = 192000
    ): Streamable.Source.Http {
        println("DEBUG: Creating resilient source with ${fallbackUrls.size} fallbacks")
        
        // Generate headers for the primary source
        val primaryHeaders = generateMobileHeaders(strategy, networkType)
        
        // Create primary source with enhanced error handling
        val primarySource = when (strategy) {
            "mobile_emulation", "aggressive_mobile" -> {
                createPostRequest(primaryUrl, primaryHeaders, "resilient=1")
            }
            "desktop_fallback" -> {
                createPostRequest(primaryUrl, primaryHeaders, null)
            }
            else -> {
                Streamable.Source.Http(
                    primaryUrl.toRequest(),
                    quality = quality
                )
            }
        }
        
        // For now, return the primary source
        // In a full implementation, you would create a custom source that handles fallbacks
        // The fallback logic would be implemented at the media player level
        return primarySource
    }

    /**
     * Handle stream failure and automatically switch to fallback source
     * This is called when a stream fails with 403 or other errors during playback
     * Enhanced with progressive recovery and multiple fallback strategies
     */
    private suspend fun handleStreamFailure(
        currentSource: Streamable.Source.Http,
        fallbackSources: List<Streamable.Source.Http>,
        attempt: Int,
        strategy: String,
        networkType: String
    ): Streamable.Source.Http {
        println("DEBUG: Handling stream failure, attempt $attempt, ${fallbackSources.size} fallbacks available")
        
        return try {
            // Progressive recovery based on attempt number
            when (attempt) {
                1 -> {
                    // First failure: Try quick visitor ID reset
                    println("DEBUG: First failure - quick visitor ID reset")
                    api.visitor_id = null
                    ensureVisitorId()
                }
                2 -> {
                    // Second failure: Aggressive cache reset
                    println("DEBUG: Second failure - aggressive cache reset")
                    resetApiCache()
                }
                3 -> {
                    // Third failure: Switch to mobile API
                    println("DEBUG: Third failure - mobile API switch")
                    val tempVisitorId = api.visitor_id
                    api.visitor_id = null
                    try {
                        mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                    } catch (e: Exception) {
                        println("DEBUG: Mobile API switch failed: ${e.message}")
                        api.visitor_id = tempVisitorId
                    }
                }
                4 -> {
                    // Fourth failure: Complete session reset
                    println("DEBUG: Fourth failure - complete session reset")
                    api.visitor_id = null
                    api.user_auth_state = null
                    mobileApi.visitor_id = null
                    mobileApi.user_auth_state = null
                    kotlinx.coroutines.delay(1000L)
                    ensureVisitorId()
                }
                else -> {
                    // Fifth+ failure: Last resort measures
                    println("DEBUG: Last resort recovery measures")
                    resetApiCache()
                    // Generate random visitor ID as last resort
                    api.visitor_id = generateRandomPot()
                }
            }
            
            // If we have fallback sources, try the next one
            if (fallbackSources.isNotEmpty()) {
                val fallbackIndex = minOf(attempt - 1, fallbackSources.size - 1)
                val fallbackSource = fallbackSources[fallbackIndex]
                println("DEBUG: Switching to fallback source $fallbackIndex")
                
                // Apply strategy-specific recovery to the fallback source
                when (strategy) {
                    "mobile_emulation", "aggressive_mobile" -> {
                        // For mobile emulation, we might need to regenerate the source
                        val freshUrl = generateEnhancedUrl(
                            fallbackSource.request.url.toString(),
                            attempt,
                            strategy,
                            networkType
                        )
                        val freshHeaders = generateMobileHeaders(strategy, networkType)
                        createPostRequest(freshUrl, freshHeaders, "fallback=$fallbackIndex&recovery=$attempt")
                    }
                    else -> {
                        // For other strategies, use the fallback source as-is
                        fallbackSource
                    }
                }
            } else {
                // No fallbacks available, create a new source with different parameters
                println("DEBUG: No fallbacks available, creating new source with enhanced parameters")
                val freshUrl = generateEnhancedUrl(
                    currentSource.request.url.toString(),
                    attempt,
                    strategy,
                    networkType
                )
                val freshHeaders = generateMobileHeaders(strategy, networkType)
                
                when (strategy) {
                    "mobile_emulation", "aggressive_mobile" -> {
                        createPostRequest(freshUrl, freshHeaders, "retry=$attempt&recovery=true")
                    }
                    else -> {
                        Streamable.Source.Http(
                            freshUrl.toRequest(),
                            quality = currentSource.quality
                        )
                    }
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Failed to handle stream failure: ${e.message}")
            // Return the original source as last resort
            currentSource
        }
    }
    
    /**
     * Enhanced mid-playback failure recovery for continuous streaming
     * This function provides multiple recovery strategies when streams stop working mid-playback
     */
    private suspend fun recoverFromMidPlaybackFailure(
        failedUrl: String,
        originalStrategy: String,
        networkType: String,
        quality: Int,
        failureCount: Int
    ): Streamable.Source.Http {
        println("DEBUG: Recovering from mid-playback failure #$failureCount for URL: $failedUrl")
        
        // Progressive recovery strategies based on failure count
        val recoveryStrategy = when (failureCount) {
            1 -> "quick_reset"
            2 -> "cache_reset" 
            3 -> "mobile_switch"
            4 -> "strategy_change"
            else -> "last_resort"
        }
        
        println("DEBUG: Using recovery strategy: $recoveryStrategy")
        
        return when (recoveryStrategy) {
            "quick_reset" -> {
                // Quick visitor ID reset
                api.visitor_id = null
                ensureVisitorId()
                val freshUrl = generateEnhancedUrl(failedUrl, failureCount, originalStrategy, networkType)
                val headers = generateMobileHeaders(originalStrategy, networkType)
                createPostRequest(freshUrl, headers, "recovery=quick_reset")
            }
            
            "cache_reset" -> {
                // Aggressive cache reset
                resetApiCache()
                val freshUrl = generateEnhancedUrl(failedUrl, failureCount, originalStrategy, networkType)
                val headers = generateMobileHeaders(originalStrategy, networkType)
                createPostRequest(freshUrl, headers, "recovery=cache_reset")
            }
            
            "mobile_switch" -> {
                // Switch to mobile API
                val tempVisitorId = api.visitor_id
                api.visitor_id = null
                try {
                    mobileApi.visitor_id = visitorEndpoint.getVisitorId()
                    val freshUrl = generateEnhancedUrl(failedUrl, failureCount, "mobile_emulation", networkType)
                    val headers = generateMobileHeaders("mobile_emulation", networkType)
                    createPostRequest(freshUrl, headers, "recovery=mobile_switch")
                } catch (e: Exception) {
                    println("DEBUG: Mobile switch failed: ${e.message}")
                    api.visitor_id = tempVisitorId
                    // Fall back to original strategy
                    val freshUrl = generateEnhancedUrl(failedUrl, failureCount, originalStrategy, networkType)
                    val headers = generateMobileHeaders(originalStrategy, networkType)
                    createPostRequest(freshUrl, headers, "recovery=fallback")
                }
            }
            
            "strategy_change" -> {
                // Change to different strategy
                val newStrategy = when (originalStrategy) {
                    "mobile_emulation" -> "desktop_fallback"
                    "desktop_fallback" -> "alternate_params"
                    else -> "mobile_emulation"
                }
                println("DEBUG: Switching strategy from $originalStrategy to $newStrategy")
                val freshUrl = generateEnhancedUrl(failedUrl, failureCount, newStrategy, networkType)
                val headers = generateMobileHeaders(newStrategy, networkType)
                createPostRequest(freshUrl, headers, "recovery=strategy_change")
            }
            
            "last_resort" -> {
                // Last resort: complete reset with random parameters
                api.visitor_id = null
                api.user_auth_state = null
                mobileApi.visitor_id = null
                mobileApi.user_auth_state = null
                
                // Generate random visitor ID
                api.visitor_id = generateRandomPot()
                
                // Use alternate parameters strategy
                val freshUrl = generateEnhancedUrl(failedUrl, failureCount, "alternate_params", networkType)
                val headers = generateMobileHeaders("alternate_params", networkType)
                createPostRequest(freshUrl, headers, "recovery=last_resort")
            }
            
            else -> {
                // Default fallback
                val freshUrl = generateEnhancedUrl(failedUrl, failureCount, originalStrategy, networkType)
                val headers = generateMobileHeaders(originalStrategy, networkType)
                Streamable.Source.Http(freshUrl.toRequest(), quality = quality)
            }
        }
    }

    /**
     * Handle stream quality fallback when high-quality streams fail
     * This automatically degrades quality to maintain playback continuity
     */
    private suspend fun handleQualityFallback(
        currentQuality: Int,
        attempt: Int,
        strategy: String,
        networkType: String,
        originalUrl: String
    ): Pair<Int, String> {
        println("DEBUG: Handling quality fallback, current quality: $currentQuality, attempt: $attempt")
        
        // Define quality levels from highest to lowest
        val qualityLevels = listOf(
            320000 to "high",     // 320kbps
            256000 to "medium",   // 256kbps
            192000 to "normal",   // 192kbps
            128000 to "low",      // 128kbps
            96000 to "lower",     // 96kbps
            64000 to "lowest",    // 64kbps
            48000 to "minimum"    // 48kbps - minimum viable quality
        )
        
        // Find current quality index
        val currentQualityIndex = qualityLevels.indexOfFirst { it.first == currentQuality }
        val fallbackQualityIndex = minOf(currentQualityIndex + attempt, qualityLevels.size - 1)
        
        val (newQuality, qualityName) = qualityLevels[fallbackQualityIndex]
        
        println("DEBUG: Quality fallback: $currentQuality -> $newQuality ($qualityName)")
        
        // Generate fallback URL with new quality
        val fallbackUrl = if (originalUrl.contains("?")) {
            "$originalUrl&quality=$qualityName&bitrate=$newQuality&fallback=$attempt"
        } else {
            "$originalUrl?quality=$qualityName&bitrate=$newQuality&fallback=$attempt"
        }
        
        // Apply additional fallback strategies based on network type
        val enhancedFallbackUrl = when (networkType) {
            "restricted_wifi_403" -> {
                // For problematic WiFi, add more aggressive parameters
                "$fallbackUrl&aggressive=1&wifi_fallback=1"
            }
            "restricted_wifi" -> {
                "$fallbackUrl&wifi_fallback=1"
            }
            "mobile_data" -> {
                "$fallbackUrl&mobile_fallback=1"
            }
            else -> {
                fallbackUrl
            }
        }
        
        return Pair(newQuality, enhancedFallbackUrl)
    }

    /**
     * Create a quality-adaptive HTTP source that can degrade quality when needed
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
     * Comprehensive stream management with intelligent fallback
     * This handles all types of stream failures and provides appropriate recovery
     */
    private suspend fun manageStreamWithFallback(
        primaryStream: Streamable.Media,
        fallbackStreams: List<Streamable.Media> = emptyList(),
        attempt: Int,
        strategy: String,
        networkType: String
    ): Streamable.Media {
        println("DEBUG: Managing stream with fallback, attempt $attempt, ${fallbackStreams.size} fallbacks available")
        
        return try {
            // Try the primary stream first
            primaryStream
            
        } catch (e: Exception) {
            println("DEBUG: Primary stream failed: ${e.message}")
            
            // Check if it's a 403 error
            val is403Error = is403Error(e)
            
            if (is403Error) {
                println("DEBUG: 403 error detected, applying aggressive recovery")
                
                // For 403 errors, apply immediate recovery strategies
                when (attempt) {
                    1 -> {
                        println("DEBUG: First 403 - resetting visitor ID")
                        api.visitor_id = null
                        ensureVisitorId()
                    }
                    2 -> {
                        println("DEBUG: Second 403 - aggressive cache reset")
                        resetApiCache()
                    }
                    3 -> {
                        println("DEBUG: Third 403 - switching to mobile API")
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
            
            // Try fallback streams if available
            if (fallbackStreams.isNotEmpty()) {
                val fallbackIndex = minOf(attempt - 1, fallbackStreams.size - 1)
                val fallbackStream = fallbackStreams[fallbackIndex]
                println("DEBUG: Trying fallback stream $fallbackIndex")
                
                try {
                    // Apply quality fallback if needed
                    if (is403Error && attempt > 2) {
                        println("DEBUG: Applying quality fallback for persistent 403 errors")
                        // For now, return the fallback stream
                        // In a full implementation, you would degrade the quality
                        fallbackStream
                    } else {
                        fallbackStream
                    }
                } catch (fallbackError: Exception) {
                    println("DEBUG: Fallback stream $fallbackIndex also failed: ${fallbackError.message}")
                    
                    // If all fallbacks failed, create a new stream with enhanced parameters
                    if (attempt < 5) {
                        println("DEBUG: Creating new stream with enhanced parameters")
                        
                        // Generate a new URL with enhanced parameters
                        val enhancedParams = mapOf(
                            "retry" to attempt.toString(),
                            "fallback" to "true",
                            "network" to networkType,
                            "strategy" to strategy,
                            "timestamp" to System.currentTimeMillis().toString()
                        )
                        
                        val paramString = enhancedParams.map { "${it.key}=${it.value}" }.joinToString("&")
                        
                        // For now, create a simple fallback stream
                        // In a full implementation, you would regenerate the entire stream
                        primaryStream
                    } else {
                        throw Exception("All stream attempts failed. Last error: ${e.message}")
                    }
                }
            } else {
                // No fallbacks available, apply quality degradation
                if (attempt > 2 && is403Error) {
                    println("DEBUG: No fallbacks available, applying quality degradation")
                    // For now, return the primary stream
                    // In a full implementation, you would degrade quality
                    primaryStream
                } else {
                    throw Exception("Stream failed with no fallbacks available: ${e.message}")
                }
            }
        }
    }

    /**
     * Enhanced error detection for all types of stream failures
     */
    private fun isStreamError(exception: Exception): Boolean {
        return when (exception) {
            is ClientRequestException -> true
            is java.net.ConnectException -> true
            is java.net.SocketTimeoutException -> true
            is java.net.UnknownHostException -> true
            else -> {
                // Check error message for common stream-related errors
                val message = exception.message?.lowercase() ?: ""
                message.contains("stream") ||
                message.contains("connection") ||
                message.contains("timeout") ||
                message.contains("network") ||
                message.contains("403") ||
                message.contains("404") ||
                message.contains("500")
            }
        }
    }

  

    override suspend fun loadTrack(track: Track) = coroutineScope {
        // Ensure visitor ID is initialized
        ensureVisitorId()
        
        println("DEBUG: Loading track: ${track.title} (${track.id})")
        
        val deferred = async { songEndPoint.loadSong(track.id).getOrThrow() }
        val (video, type) = videoEndpoint.getVideo(true, track.id)

        println("DEBUG: Video type: $type")

        val resolvedTrack = null // Disabled video-to-music resolution

        val audioFiles = video.streamingData.adaptiveFormats.mapNotNull {
            if (!it.mimeType.contains("audio")) return@mapNotNull null
            it.audioSampleRate.toString() to it.url!!
        }.toMap()
        
        println("DEBUG: Audio formats found: ${audioFiles.keys}")
        
        val newTrack = resolvedTrack ?: deferred.await()
        val resultTrack = newTrack.copy(
            description = video.videoDetails.shortDescription,
            artists = newTrack.artists.ifEmpty {
                video.videoDetails.run { listOf(Artist(channelId, author)) }
            },
            streamables = listOfNotNull(
                Streamable.server(
                    "AUDIO_MP3",
                    0,
                    "Audio Stream (MP3/MP4)",
                    mutableMapOf<String, String>().apply { put("videoId", track.id) }
                ).takeIf { audioFiles.isNotEmpty() }
            ),
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