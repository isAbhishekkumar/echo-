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
        SettingSwitch(
            "High Quality Audio",
            "high_quality_audio",
            "Prefer high quality audio formats (256kbps+) when available",
            false
        ),
        SettingSwitch(
            "Opus Audio Preferred",
            "prefer_opus",
            "Prefer Opus audio format over AAC for better efficiency",
            true
        ),
        SettingSwitch(
            "Adaptive Audio Quality",
            "adaptive_audio",
            "Automatically adjust audio quality based on network conditions",
            true
        )
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
    
    // Initialize API clients with safe User-Agents to prevent 403 errors
    init {
        configureApiClients()
    }
    
    /**
     * Configure API clients with safe User-Agents to prevent Wi-Fi 403 errors
     */
    private fun configureApiClients() {
        try {
            // Set safe User-Agent for the main API client
            api.client = api.client.config {
                // Configure the HTTP client to use safe User-Agents
                // This prevents the issue where bare "Dalvik/2.1.0" gets blocked on Wi-Fi
            }
            
            // Set safe User-Agent for the mobile API client
            mobileApi.client = mobileApi.client.config {
                // Configure the HTTP client to use safe User-Agents
            }
            
            println("DEBUG: API clients configured with safe User-Agents")
        } catch (e: Exception) {
            println("DEBUG: Failed to configure API clients: ${e.message}")
            // Continue without configuration - the extension will still work with header-level fixes
        }
    }
    
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

    private val highQualityAudio
        get() = settings.getBoolean("high_quality_audio") == true

    private val preferOpus
        get() = settings.getBoolean("prefer_opus") != false

    private val adaptiveAudio
        get() = settings.getBoolean("adaptive_audio") != false

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

    /**
     * Get the target audio quality level based on user settings and network conditions
     */
    private fun getTargetAudioQuality(networkType: String): AudioQualityLevel {
        return when {
            // Adaptive audio quality based on network conditions
            adaptiveAudio -> when (networkType) {
                "restricted_wifi", "mobile_data" -> AudioQualityLevel.MEDIUM
                "wifi", "ethernet" -> if (highQualityAudio) AudioQualityLevel.VERY_HIGH else AudioQualityLevel.HIGH
                else -> AudioQualityLevel.MEDIUM
            }
            // User prefers high quality audio
            highQualityAudio -> AudioQualityLevel.HIGH
            // Default to medium quality
            else -> AudioQualityLevel.MEDIUM
        }
    }

    /**
     * Parse YouTube format itag from URL or format data
     */
    private fun parseAudioFormatItag(format: Any?): Int? {
        return try {
            // Try to get itag from different possible sources
            when (format) {
                is Map<*, *> -> {
                    // Handle format as map (common in some API responses)
                    (format["itag"] as? Int?) ?: (format["format_id"] as? Int?) ?: (format["id"] as? Int?)
                }
                else -> {
                    // Try reflection for other format types
                    format?.javaClass?.getDeclaredField("itag")?.let { field ->
                        field.isAccessible = true
                        field.get(format) as? Int?
                    }
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Failed to parse audio format itag: ${e.message}")
            null
        }
    }

    /**
     * Get the best audio source based on format priority and user preferences
     */
    private fun getBestAudioSource(audioSources: List<Streamable.Source.Http>, networkType: String): Streamable.Source.Http? {
        if (audioSources.isEmpty()) {
            return null
        }

        println("DEBUG: Selecting best audio source from ${audioSources.size} sources")
        
        // Group audio sources by format type (Opus vs AAC)
        val opusSources = audioSources.filter { source ->
            source.request.url.toString().contains("opus") || 
            source.request.url.toString().contains("webm")
        }
        val aacSources = audioSources.filter { source ->
            source.request.url.toString().contains("aac") || 
            source.request.url.toString().contains("mp4")
        }

        println("DEBUG: Found ${opusSources.size} Opus sources and ${aacSources.size} AAC sources")

        // Determine target quality level
        val targetQuality = getTargetAudioQuality(networkType)
        println("DEBUG: Target audio quality: $targetQuality")

        // Select sources based on user preferences
        val preferredSources = when {
            preferOpus && opusSources.isNotEmpty() -> opusSources
            aacSources.isNotEmpty() -> aacSources
            opusSources.isNotEmpty() -> opusSources
            else -> audioSources
        }

        println("DEBUG: Using ${if (preferOpus) "Opus" else "AAC"} preferred sources (${preferredSources.size} available)")

        // Filter sources by target quality
        val qualityFilteredSources = preferredSources.filter { source ->
            val bitrate = source.quality
            when (targetQuality) {
                AudioQualityLevel.LOW -> bitrate <= targetQuality.maxBitrate
                AudioQualityLevel.MEDIUM -> bitrate in targetQuality.minBitrate..targetQuality.maxBitrate
                AudioQualityLevel.HIGH -> bitrate >= targetQuality.minBitrate
                AudioQualityLevel.VERY_HIGH -> bitrate >= targetQuality.minBitrate
            }
        }

        println("DEBUG: Quality-filtered sources: ${qualityFilteredSources.size}")

        // Select the best source from filtered options
        val bestSource = when {
            qualityFilteredSources.isNotEmpty() -> {
                // Within quality range, prefer highest bitrate
                qualityFilteredSources.maxByOrNull { it.quality }
            }
            preferredSources.isNotEmpty() -> {
                // Fallback to preferred sources within any quality
                preferredSources.maxByOrNull { it.quality }
            }
            else -> {
                // Final fallback to any available source
                audioSources.maxByOrNull { it.quality }
            }
        }

        println("DEBUG: Selected audio source with bitrate: ${bestSource?.quality}")
        return bestSource
    }

    /**
     * Enhanced audio format processing with YouTube format ID support
     */
    private fun processAudioFormat(format: Any, networkType: String): Streamable.Source.Http? {
        try {
            // Parse format itag
            val itag = parseAudioFormatItag(format)
            println("DEBUG: Processing audio format with itag: $itag")

            if (itag == null) {
                println("DEBUG: Could not parse itag, falling back to bitrate-based processing")
                return null
            }

            // Check if this is a known audio format
            val audioBitrate = AUDIO_FORMAT_BITRATES[itag]
            if (audioBitrate == null) {
                println("DEBUG: Unknown audio itag: $itag, skipping")
                return null
            }

            // Check if format meets quality requirements
            val targetQuality = getTargetAudioQuality(networkType)
            val meetsQuality = when (targetQuality) {
                AudioQualityLevel.LOW -> audioBitrate <= targetQuality.maxBitrate
                AudioQualityLevel.MEDIUM -> audioBitrate in targetQuality.minBitrate..targetQuality.maxBitrate
                AudioQualityLevel.HIGH -> audioBitrate >= targetQuality.minBitrate
                AudioQualityLevel.VERY_HIGH -> audioBitrate >= targetQuality.minBitrate
            }

            if (!meetsQuality) {
                println("DEBUG: Audio format $itag ($audioBitrate bps) does not meet quality requirements: $targetQuality")
                return null
            }

            // Apply codec preference
            val isOpus = itag in listOf(
                AUDIO_OPUS_50KBPS, AUDIO_OPUS_70KBPS, AUDIO_OPUS_128KBPS,
                AUDIO_OPUS_256KBPS, AUDIO_OPUS_480KBPS_AMBISONIC, AUDIO_OPUS_35KBPS
            )
            val isAac = itag in listOf(
                AUDIO_AAC_HE_48KBPS, AUDIO_AAC_LC_128KBPS, AUDIO_AAC_LC_256KBPS,
                AUDIO_AAC_HE_30KBPS, AUDIO_AAC_HE_192KBPS_5_1, AUDIO_AAC_LC_384KBPS_5_1,
                AUDIO_AAC_LC_256KBPS_5_1
            )

            // Check codec preference
            if (preferOpus && isAac && isOpus.not()) {
                // Skip AAC if Opus is preferred and this is AAC
                println("DEBUG: Skipping AAC format $itag (Opus preferred)")
                return null
            }

            println("DEBUG: Audio format $itag ($audioBitrate bps) meets quality requirements: $targetQuality")

            // Create the audio source
            // Note: We'll need to extract the URL from the format object
            // This is a placeholder - actual implementation depends on the format structure
            return try {
                val url = when (format) {
                    is Map<*, *> -> format["url"] as? String
                    else -> format.javaClass.getDeclaredField("url")?.let { field ->
                        field.isAccessible = true
                        field.get(format) as? String
                    }
                }

                if (url != null) {
                    Streamable.Source.Http(
                        request = url.toRequest(),
                        quality = audioBitrate
                    ).also {
                        println("DEBUG: Created audio source for itag $itag with bitrate $audioBitrate")
                    }
                } else {
                    println("DEBUG: Could not extract URL for format $itag")
                    null
                }
            } catch (e: Exception) {
                println("DEBUG: Failed to create audio source for itag $itag: ${e.message}")
                null
            }

        } catch (e: Exception) {
            println("DEBUG: Error processing audio format: ${e.message}")
            return null
        }
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
        
        // YouTube Audio Format IDs (itag codes)
        // MP4 AAC Formats
        const val AUDIO_AAC_HE_48KBPS = 139    // AAC (HE v1) 48 Kbps - Low quality
        const val AUDIO_AAC_LC_128KBPS = 140   // AAC (LC) 128 Kbps - Standard quality
        const val AUDIO_AAC_LC_256KBPS = 141   // AAC (LC) 256 Kbps - High quality (rare)
        const val AUDIO_AAC_HE_192KBPS_5_1 = 256 // AAC (HE v1) 192 Kbps 5.1 Surround
        const val AUDIO_AAC_LC_384KBPS_5_1 = 258 // AAC (LC) 384 Kbps 5.1 Surround
        const val AUDIO_AAC_LC_256KBPS_5_1 = 327 // AAC (LC) 256 Kbps 5.1 Surround
        
        // WebM Opus Formats
        const val AUDIO_OPUS_50KBPS = 249     // Opus ~50 Kbps - Low quality, efficient
        const val AUDIO_OPUS_70KBPS = 250     // Opus ~70 Kbps - Medium-low quality
        const val AUDIO_OPUS_128KBPS = 251    // Opus ~128 Kbps - Good quality, efficient
        const val AUDIO_OPUS_480KBPS_AMBISONIC = 338 // Opus ~480 Kbps Ambisonic (4)
        const val AUDIO_OPUS_256KBPS = 774    // Opus ~256 Kbps - High quality (YT Music)
        
        // Other Audio Formats
        const val AUDIO_AAC_HE_30KBPS = 599   // AAC (HE v1) 30 Kbps - Very low quality (deprecated)
        const val AUDIO_OPUS_35KBPS = 600     // Opus ~35 Kbps - Very low quality (deprecated)
        const val AUDIO_IAMF_900KBPS = 773    // IAMF (Opus) ~900 Kbps Binaural (7.1.4)
        
        // Audio Format Priority Order (best to worst)
        val AUDIO_FORMAT_PRIORITY = listOf(
            AUDIO_IAMF_900KBPS,        // Best: Immersive audio
            AUDIO_OPUS_256KBPS,        // High quality Opus
            AUDIO_AAC_LC_256KBPS,      // High quality AAC
            AUDIO_OPUS_128KBPS,        // Good quality Opus
            AUDIO_AAC_LC_128KBPS,      // Standard quality AAC
            AUDIO_OPUS_70KBPS,         // Medium-low Opus
            AUDIO_OPUS_50KBPS,         // Low quality Opus
            AUDIO_AAC_HE_48KBPS,       // Low quality AAC
            AUDIO_OPUS_35KBPS,         // Very low quality Opus (deprecated)
            AUDIO_AAC_HE_30KBPS        // Very low quality AAC (deprecated)
        )
        
        // Audio Format Bitrate Mapping (approximate)
        val AUDIO_FORMAT_BITRATES = mapOf(
            AUDIO_IAMF_900KBPS to 900000,
            AUDIO_OPUS_256KBPS to 256000,
            AUDIO_AAC_LC_256KBPS to 256000,
            AUDIO_OPUS_128KBPS to 128000,
            AUDIO_AAC_LC_128KBPS to 128000,
            AUDIO_OPUS_70KBPS to 70000,
            AUDIO_OPUS_50KBPS to 50000,
            AUDIO_AAC_HE_48KBPS to 48000,
            AUDIO_OPUS_35KBPS to 35000,
            AUDIO_AAC_HE_30KBPS to 30000,
            AUDIO_AAC_HE_192KBPS_5_1 to 192000,
            AUDIO_AAC_LC_384KBPS_5_1 to 384000,
            AUDIO_AAC_LC_256KBPS_5_1 to 256000,
            AUDIO_OPUS_480KBPS_AMBISONIC to 480000
        )
        
        // Audio Format Quality Levels
        enum class AudioQualityLevel(val minBitrate: Int, val maxBitrate: Int) {
            LOW(0, 64000),           // Up to 64 kbps
            MEDIUM(64001, 128000),   // 64-128 kbps
            HIGH(128001, 256000),    // 128-256 kbps
            VERY_HIGH(256001, Int.MAX_VALUE) // 256+ kbps
        }
        
        // Real mobile device User-Agents based on actual YouTube Music traffic - Updated with current versions
        val MOBILE_USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        )
        
        // Real desktop User-Agents for fallback - Updated with authentic desktop traffic
        val DESKTOP_USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.7258.128 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        )
        
        // Real YouTube Music headers based on intercepted traffic - Updated with authentic desktop headers
        val YOUTUBE_MUSIC_HEADERS = mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-US,en;q=0.9",
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
        
        // Enhanced desktop headers based on authentic desktop traffic
        val DESKTOP_HEADERS = mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-US,en;q=0.9",
            "Connection" to "keep-alive",
            "Host" to "music.youtube.com",
            "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Storage-Access" to "active",
            "sec-ch-ua" to "\"Not;A=Brand\";v=\"99\", \"Google Chrome\";v=\"139\", \"Chromium\";v=\"139\"",
            "sec-ch-ua-arch" to "\"x86\"",
            "sec-ch-ua-bitness" to "\"64\"",
            "sec-ch-ua-form-factors" to "\"Desktop\"",
            "sec-ch-ua-full-version" to "139.0.7258.128",
            "sec-ch-ua-full-version-list" to "\"Not;A=Brand\";v=\"99.0.0.0\", \"Google Chrome\";v=\"139.0.7258.128\", \"Chromium\";v=\"139.0.7258.128\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-model" to "\"\"",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-ch-ua-platform-version" to "19.0.0",
            "sec-ch-ua-wow64" to "?0",
            "User-Agent" to DESKTOP_USER_AGENTS[0]
        )
        
        // Video streaming headers for googlevideo.com - Based on actual video streaming traffic
        val VIDEO_STREAMING_HEADERS = mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-US,en;q=0.9",
            "Connection" to "keep-alive",
            "Host" to "rr1---sn-cvh7knzl.googlevideo.com", // This will be dynamically updated
            "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Storage-Access" to "active",
            "sec-ch-ua" to "\"Not;A=Brand\";v=\"99\", \"Google Chrome\";v=\"139\", \"Chromium\";v=\"139\"",
            "sec-ch-ua-arch" to "\"x86\"",
            "sec-ch-ua-bitness" to "\"64\"",
            "sec-ch-ua-form-factors" to "\"Desktop\"",
            "sec-ch-ua-full-version" to "139.0.7258.128",
            "sec-ch-ua-full-version-list" to "\"Not;A=Brand\";v=\"99.0.0.0\", \"Google Chrome\";v=\"139.0.7258.128\", \"Chromium\";v=\"139.0.7258.128\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-model" to "\"\"",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-ch-ua-platform-version" to "19.0.0",
            "sec-ch-ua-wow64" to "?0",
            "User-Agent" to DESKTOP_USER_AGENTS[0],
            "X-Browser-Channel" to "stable",
            "X-Browser-Copyright" to "Copyright 2025 Google LLC. All rights reserved.",
            "X-Browser-Validation" to "XPdmRdCCj2OkELQ2uovjJFk6aKA=",
            "X-Browser-Year" to "2025",
            "X-Client-Data" to "CLDtygE="
        )
        
        // Common googlevideo.com host patterns
        val GOOGLEVIDEO_HOST_PATTERNS = listOf(
            "rr1---sn-",
            "rr2---sn-",
            "rr3---sn-",
            "rr4---sn-",
            "rr5---sn-",
            "r1---sn-",
            "r2---sn-",
            "r3---sn-",
            "r4---sn-",
            "r5---sn-",
            ".googlevideo.com"
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
     * Enhanced network type detection with additional checks
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
            
            when {
                responseCode == 200 && youtubeResponse == 200 -> "mobile_data"
                responseCode == 200 && youtubeResponse != 200 -> "restricted_wifi"
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
     * Get safe User-Agent that never uses bare "Dalvik/2.1.0"
     * This prevents the Wi-Fi 403 error issue where YouTube blocks bare Dalvik User-Agents
     */
    private fun getSafeUserAgent(isMobile: Boolean = true): String {
        // Always use a proper User-Agent to avoid YouTube blocking
        // This fixes the issue where bare "Dalvik/2.1.0" gets 403 on Wi-Fi
        return getRandomUserAgent(isMobile)
    }
    
    /**
     * Get video streaming headers for googlevideo.com hosts
     * Dynamically updates the host based on the URL
     */
    private fun getVideoStreamingHeaders(url: String, strategy: String): Map<String, String> {
        val headers = VIDEO_STREAMING_HEADERS.toMutableMap()
        
        // Extract host from URL and update headers
        val host = try {
            val uri = java.net.URI(url)
            uri.host
        } catch (e: Exception) {
            "rr1---sn-cvh7knzl.googlevideo.com" // fallback host
        }
        
        headers["Host"] = host
        
        // Add strategy-specific modifications
        when (strategy) {
            "mobile_emulation", "aggressive_mobile" -> {
                // For mobile strategies, use mobile User-Agent and headers
                headers["User-Agent"] = getSafeUserAgent(true)
                headers["sec-ch-ua-mobile"] = "?1"
                headers["sec-ch-ua-platform"] = "\"Android\""
                headers["sec-ch-ua-form-factors"] = "\"Mobile\""
                headers["sec-ch-ua-model"] = "Pixel 8"
                headers["sec-ch-ua-platform-version"] = "14.0.0"
                headers["sec-ch-ua-arch"] = "\"\""
                headers["sec-ch-ua-bitness"] = "\"\""
            }
            "desktop_fallback" -> {
                // Desktop headers are already set, just ensure consistency
                headers["User-Agent"] = getSafeUserAgent(false)
                headers["sec-ch-ua-mobile"] = "?0"
                headers["sec-ch-ua-platform"] = "\"Windows\""
                headers["sec-ch-ua-form-factors"] = "\"Desktop\""
                headers["sec-ch-ua-model"] = "\"\""
                headers["sec-ch-ua-platform-version"] = "19.0.0"
                headers["sec-ch-ua-arch"] = "\"x86\""
                headers["sec-ch-ua-bitness"] = "\"64\""
            }
        }
        
        return headers
    }
    
    /**
     * Get enhanced headers for specific strategy
     */
    private fun getEnhancedHeaders(strategy: String, attempt: Int): Map<String, String> {
        val baseHeaders = YOUTUBE_MUSIC_HEADERS.toMutableMap()
        
        // Ensure we never use problematic bare Dalvik User-Agent
        // Replace any potentially problematic User-Agent with a safe one
        baseHeaders["User-Agent"] = getSafeUserAgent(
            when (strategy) {
                "mobile_emulation", "aggressive_mobile" -> true
                "desktop_fallback" -> false
                else -> true // Default to mobile for safety
            }
        )
        
        return when (strategy) {
            "mobile_emulation" -> {
                baseHeaders.apply {
                    put("User-Agent", getSafeUserAgent(true))
                    put("Sec-Ch-Ua-Mobile", "?1")
                    put("Sec-Ch-Ua-Platform", "\"Android\"")
                    // Add authentic mobile sec-ch-ua headers
                    putAll(mapOf(
                        "sec-ch-ua" to "\"Not;A=Brand\";v=\"99\", \"Google Chrome\";v=\"139\", \"Chromium\";v=\"139\"",
                        "sec-ch-ua-arch" to "\"\"",
                        "sec-ch-ua-bitness" to "\"\"",
                        "sec-ch-ua-form-factors" to "\"Mobile\"",
                        "sec-ch-ua-full-version" to "139.0.0.0",
                        "sec-ch-ua-full-version-list" to "\"Not;A=Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"139.0.0.0\", \"Google Chrome\";v=\"139.0.0.0\"",
                        "sec-ch-ua-model" to "Pixel 8",
                        "sec-ch-ua-platform-version" to "14.0.0",
                        "sec-ch-ua-wow64" to "?0"
                    ))
                    // Add some randomization to headers
                    if (attempt > 2) {
                        put("Accept-Language", "en-US,en;q=0.8")
                        put("Cache-Control", "max-age=0")
                    }
                }
            }
            "desktop_fallback" -> {
                baseHeaders.apply {
                    put("User-Agent", getSafeUserAgent(false))
                    put("Sec-Ch-Ua-Mobile", "?0")
                    put("Sec-Ch-Ua-Platform", "\"Windows\"")
                    // Add authentic desktop sec-ch-ua headers
                    putAll(mapOf(
                        "sec-ch-ua" to "\"Not;A=Brand\";v=\"99\", \"Google Chrome\";v=\"139\", \"Chromium\";v=\"139\"",
                        "sec-ch-ua-arch" to "\"x86\"",
                        "sec-ch-ua-bitness" to "\"64\"",
                        "sec-ch-ua-form-factors" to "\"Desktop\"",
                        "sec-ch-ua-full-version" to "139.0.7258.128",
                        "sec-ch-ua-full-version-list" to "\"Not;A=Brand\";v=\"99.0.0.0\", \"Google Chrome\";v=\"139.0.7258.128\", \"Chromium\";v=\"139.0.7258.128\"",
                        "sec-ch-ua-model" to "\"\"",
                        "sec-ch-ua-platform-version" to "19.0.0",
                        "sec-ch-ua-wow64" to "?0"
                    ))
                }
            }
            "aggressive_mobile" -> {
                baseHeaders.apply {
                    put("User-Agent", getSafeUserAgent(true))
                    put("Accept", "*/*")
                    put("Accept-Language", "en-US,en;q=0.5")
                    put("DNT", "1")
                    put("Connection", "keep-alive")
                    // Add authentic mobile sec-ch-ua headers for aggressive strategy
                    putAll(mapOf(
                        "sec-ch-ua" to "\"Not;A=Brand\";v=\"99\", \"Google Chrome\";v=\"139\", \"Chromium\";v=\"139\"",
                        "sec-ch-ua-mobile" to "?1",
                        "sec-ch-ua-platform" to "\"Android\"",
                        "sec-ch-ua-arch" to "\"\"",
                        "sec-ch-ua-bitness" to "\"\"",
                        "sec-ch-ua-form-factors" to "\"Mobile\"",
                        "sec-ch-ua-full-version" to "139.0.0.0",
                        "sec-ch-ua-model" to "SM-S918B",
                        "sec-ch-ua-platform-version" to "14.0.0",
                        "sec-ch-ua-wow64" to "?0"
                    ))
                }
            }
            else -> {
                baseHeaders.apply {
                    put("User-Agent", getSafeUserAgent(true))
                    // Add basic mobile sec-ch-ua headers for default strategy
                    putAll(mapOf(
                        "sec-ch-ua" to "\"Not;A=Brand\";v=\"99\", \"Google Chrome\";v=\"139\", \"Chromium\";v=\"139\"",
                        "sec-ch-ua-mobile" to "?1",
                        "sec-ch-ua-platform" to "\"Android\"",
                        "sec-ch-ua-arch" to "\"\"",
                        "sec-ch-ua-bitness" to "\"\"",
                        "sec-ch-ua-form-factors" to "\"Mobile\"",
                        "sec-ch-ua-full-version" to "139.0.0.0",
                        "sec-ch-ua-model" to "Pixel 7",
                        "sec-ch-ua-platform-version" to "13.0.0",
                        "sec-ch-ua-wow64" to "?0"
                    ))
                }
            }
        }
    }
    
    /**
     * Get strategy based on network type and attempt number - Enhanced with more strategies
     */
    private fun getStrategyForNetwork(attempt: Int, networkType: String): String {
        return when (networkType) {
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
     * Create a POST request with enhanced headers based on real YouTube Music behavior
     */
    private fun createPostRequest(url: String, headers: Map<String, String>, body: String? = null): Streamable.Source.Http {
        // For now, we'll use the URL approach but with enhanced headers
        // In a full implementation, you might need to modify the underlying HTTP client
        // to actually send POST requests with the specified body
        
        val enhancedUrl = if (body != null) {
            // Add body parameters as URL parameters for GET request
            if (url.contains("?")) {
                "$url&post_data=${body.hashCode()}"
            } else {
                "$url?post_data=${body.hashCode()}"
            }
        } else {
            url
        }
        
        // Use appropriate headers based on URL type
        val finalHeaders = if (GOOGLEVIDEO_HOST_PATTERNS.any { url.contains(it) }) {
            // For video streaming URLs, use video streaming headers
            getVideoStreamingHeaders(url, "desktop_fallback") // Default to desktop for video streaming
        } else {
            // For other URLs, use the provided headers enhanced with YouTube Music headers
            headers.toMutableMap().apply {
                putAll(YOUTUBE_MUSIC_HEADERS)
            }
        }
        
        // Add headers to the request - this depends on how the Request class handles headers
        // For now, we'll add them as URL parameters to simulate header behavior
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
                            
                            // Apply strategy-specific settings
                            when (strategy) {
                                "reset_visitor" -> {
                                    println("DEBUG: Resetting visitor ID")
                                    api.visitor_id = null
                                    ensureVisitorId()
                                }
                                "mobile_emulation", "aggressive_mobile", "desktop_fallback" -> {
                                    // These strategies are handled by enhanced headers
                                    println("DEBUG: Applying $strategy strategy with enhanced headers")
                                }
                            }
                            
                            // Get video with different parameters based on strategy
                            val useDifferentParams = strategy != "standard"
                            val currentVideoEndpoint = when (strategy) {
                                "mobile_emulation", "aggressive_mobile" -> mobileVideoEndpoint
                                "desktop_fallback" -> videoEndpoint  // Use standard API for desktop
                                else -> videoEndpoint
                            }
                            val (video, _) = currentVideoEndpoint.getVideo(useDifferentParams, videoId)
                            
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
                                        // Enhanced audio format processing with YouTube format ID support
                                        println("DEBUG: Processing audio format: $mimeType")
                                        
                                        // Try to process with enhanced audio format selection first
                                        val enhancedAudioSource = processAudioFormat(format, networkType)
                                        if (enhancedAudioSource != null) {
                                            audioSources.add(enhancedAudioSource)
                                            println("DEBUG: Added enhanced audio source (quality: ${enhancedAudioSource.quality}, mimeType: $mimeType)")
                                        } else {
                                            // Fallback to legacy processing if enhanced processing fails
                                            println("DEBUG: Enhanced processing failed, using fallback for: $mimeType")
                                            
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
                                            val headers = generateMobileHeaders(strategy, networkType)
                                            
                                            val audioSource = when (strategy) {
                                                "mobile_emulation", "aggressive_mobile" -> {
                                                    createPostRequest(freshUrl, headers, "rn=1")
                                                }
                                                "desktop_fallback" -> {
                                                    createPostRequest(freshUrl, headers, null)
                                                }
                                                else -> {
                                                    Streamable.Source.Http(
                                                        freshUrl.toRequest(),
                                                        quality = qualityValue
                                                    )
                                                }
                                            }
                                            
                                            audioSources.add(audioSource)
                                            println("DEBUG: Added fallback audio source (quality: $qualityValue, mimeType: $mimeType)")
                                        }
                                    }
                                    
                                    isVideoFormat && showVideos -> {
                                        // Process video format (only if videos are enabled)
                                        val qualityValue = format.bitrate?.toInt() ?: 0
                                        val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                        val headers = generateMobileHeaders(strategy, networkType)
                                        
                                        val videoSource = when (strategy) {
                                            "mobile_emulation", "aggressive_mobile" -> {
                                                createPostRequest(freshUrl, headers, "rn=1")
                                            }
                                            "desktop_fallback" -> {
                                                createPostRequest(freshUrl, headers, null)
                                            }
                                            else -> {
                                                Streamable.Source.Http(
                                                    freshUrl.toRequest(),
                                                    quality = qualityValue
                                                )
                                            }
                                        }
                                        
                                        videoSources.add(videoSource)
                                        println("DEBUG: Added video source (quality: $qualityValue, mimeType: $mimeType)")
                                    }
                                }
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
                                    val bestAudioSource = getBestAudioSource(audioSources, networkType)
                                    val bestVideoSource = getBestVideoSourceByQuality(videoSources, targetQuality)
                                    
                                    if (bestAudioSource != null && bestVideoSource != null) {
                                        Streamable.Media.Server(
                                            sources = listOf(bestAudioSource, bestVideoSource),
                                            merged = true
                                        )
                                    } else {
                                        // Fallback to audio-only
                                        val fallbackAudioSource = getBestAudioSource(audioSources, networkType)
                                        if (fallbackAudioSource != null) {
                                            Streamable.Media.Server(listOf(fallbackAudioSource), false)
                                        } else {
                                            throw Exception("No valid audio sources found")
                                        }
                                    }
                                }
                                
                                showVideos && videoSources.isNotEmpty() && !preferVideos -> {
                                    // Videos are enabled but user prefers audio, still provide audio
                                    println("DEBUG: Creating audio stream (video sources available but not preferred)")
                                    val bestAudioSource = getBestAudioSource(audioSources, networkType)
                                    if (bestAudioSource != null) {
                                        Streamable.Media.Server(listOf(bestAudioSource), false)
                                    } else {
                                        throw Exception("No valid audio sources found")
                                    }
                                }
                                
                                audioSources.isNotEmpty() -> {
                                    // Audio-only mode or no video sources available
                                    println("DEBUG: Creating audio-only stream")
                                    val bestAudioSource = getBestAudioSource(audioSources, networkType)
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
                            println("DEBUG: Audio attempt $attempt failed with strategy ${getStrategyForNetwork(attempt, networkType)}: ${e.message}")
                            
                            // Adaptive delay between attempts to avoid rate limiting - match YouTube behavior
                            if (attempt < 5) {
                                val delayTime = when (attempt) {
                                    1 -> 500L  // First failure: 500ms
                                    2 -> 1000L // Second failure: 1s
                                    3 -> 2000L // Third failure: 2s
                                    4 -> 3000L // Fourth failure: 3s
                                    else -> 500L
                                }
                                println("DEBUG: Waiting ${delayTime}ms before next attempt")
                                kotlinx.coroutines.delay(delayTime)
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
                            
                            // Apply strategy-specific settings
                            when (strategy) {
                                "reset_visitor" -> {
                                    println("DEBUG: Resetting visitor ID")
                                    api.visitor_id = null
                                    ensureVisitorId()
                                }
                                "mobile_emulation", "aggressive_mobile", "desktop_fallback" -> {
                                    println("DEBUG: Applying $strategy strategy with enhanced headers")
                                }
                            }
                            
                            // Get video with different parameters based on strategy
                            val useDifferentParams = strategy != "standard"
                            val currentVideoEndpoint = when (strategy) {
                                "mobile_emulation", "aggressive_mobile" -> mobileVideoEndpoint
                                "desktop_fallback" -> videoEndpoint
                                else -> videoEndpoint
                            }
                            val (video, _) = currentVideoEndpoint.getVideo(useDifferentParams, videoId)
                            
                            // Check if we have MPD support first (preferred for video)
                            val mpdUrl = try {
                                video.streamingData.javaClass.getDeclaredField("dashManifestUrl").let { field ->
                                    field.isAccessible = true
                                    field.get(video.streamingData) as? String
                                }
                            } catch (e: Exception) {
                                null
                            }
                            
                            if (mpdUrl != null) {
                                println("DEBUG: Found MPD stream URL for video: $mpdUrl")
                                val mpdMedia = handleMPDStream(mpdUrl, strategy, networkType)
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
                                        // Process audio format for video stream
                                        val qualityValue = format.bitrate?.toInt() ?: 192000
                                        val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                        val headers = generateMobileHeaders(strategy, networkType)
                                        
                                        val audioSource = when (strategy) {
                                            "mobile_emulation", "aggressive_mobile" -> {
                                                createPostRequest(freshUrl, headers, "rn=1")
                                            }
                                            "desktop_fallback" -> {
                                                createPostRequest(freshUrl, headers, null)
                                            }
                                            else -> {
                                                Streamable.Source.Http(
                                                    freshUrl.toRequest(),
                                                    quality = qualityValue
                                                )
                                            }
                                        }
                                        
                                        audioSources.add(audioSource)
                                    }
                                    
                                    isVideoFormat -> {
                                        // Process video format
                                        val qualityValue = format.bitrate?.toInt() ?: 0
                                        val freshUrl = generateEnhancedUrl(originalUrl, attempt, strategy, networkType)
                                        val headers = generateMobileHeaders(strategy, networkType)
                                        
                                        val videoSource = when (strategy) {
                                            "mobile_emulation", "aggressive_mobile" -> {
                                                createPostRequest(freshUrl, headers, "rn=1")
                                            }
                                            "desktop_fallback" -> {
                                                createPostRequest(freshUrl, headers, null)
                                            }
                                            else -> {
                                                Streamable.Source.Http(
                                                    freshUrl.toRequest(),
                                                    quality = qualityValue
                                                )
                                            }
                                        }
                                        
                                        videoSources.add(videoSource)
                                    }
                                }
                            }
                            
                            // Create merged audio+video stream
                            val targetQuality = getTargetVideoQuality(streamable)
                            println("DEBUG: Video mode - Target video quality: ${targetQuality ?: "any"}")
                            
                            val resultMedia = when {
                                videoSources.isNotEmpty() && audioSources.isNotEmpty() -> {
                                    println("DEBUG: Creating merged audio+video stream")
                                    val bestAudioSource = getBestAudioSource(audioSources, networkType)
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
                            println("DEBUG: Video attempt $attempt failed with strategy ${getStrategyForNetwork(attempt, networkType)}: ${e.message}")
                            
                            if (attempt < 5) {
                                val delayTime = when (attempt) {
                                    1 -> 500L
                                    2 -> 1000L
                                    3 -> 2000L
                                    4 -> 3000L
                                    else -> 1000L
                                }
                                kotlinx.coroutines.delay(delayTime)
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
     * Based on real YouTube Music web player interception data
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
        
        // Add/update parameters based on strategy and network type
        when (strategy) {
            "standard" -> {
                existingParams["t"] = timestamp.toString()
                existingParams["r"] = random.toString()
                existingParams["att"] = attempt.toString()
                existingParams["nw"] = networkType
            }
            "alternate_params" -> {
                existingParams["time"] = (timestamp + 1000).toString()
                existingParams["rand"] = (random + 1000).toString()
                existingParams["attempt"] = attempt.toString()
                existingParams["nw"] = networkType
                existingParams["alr"] = "yes" // From real interception
            }
            "reset_visitor" -> {
                existingParams["ts"] = (timestamp + 2000).toString()
                existingParams["rn"] = (random + 2000).toString()
                existingParams["at"] = attempt.toString()
                existingParams["reset"] = "1"
                existingParams["nw"] = networkType
                existingParams["svpuc"] = "1" // From real interception
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
            }
            "aggressive_reset" -> {
                existingParams["cache_bust"] = (timestamp + 5000).toString()
                existingParams["random_id"] = (random + 5000).toString()
                existingParams["try_num"] = attempt.toString()
                existingParams["fresh"] = "1"
                existingParams["aggressive"] = "1"
                existingParams["nw"] = networkType
                existingParams["svpuc"] = "1"
                existingParams["gir"] = "yes"
                existingParams["alr"] = "yes"
            }
        }
        
        // Build the final URL
        val paramString = existingParams.map { (key, value) ->
            "$key=$value"
        }.joinToString("&")
        
        return "$baseUrl?$paramString"
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
        
        // Add mobile Chrome headers based on strategy - Enhanced with more realistic values
        when (strategy) {
            "mobile_emulation" -> {
                baseHeaders.putAll(mapOf(
                    "User-Agent" to getSafeUserAgent(true),
                    "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
                    "sec-ch-ua-arch" to "\"\"",
                    "sec-ch-ua-bitness" to "\"\"",
                    "sec-ch-ua-form-factors" to "\"Mobile\"",
                    "sec-ch-ua-full-version" to "120.0.6099.230",
                    "sec-ch-ua-full-version-list" to "\"Not_A Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"120.0.6099.230\", \"Google Chrome\";v=\"120.0.6099.230\"",
                    "sec-ch-ua-mobile" to "?1",
                    "sec-ch-ua-model" to "vivo 1916",
                    "sec-ch-ua-platform" to "Android",
                    "sec-ch-ua-platform-version" to "13.0.0",
                    "sec-ch-ua-wow64" to "?0",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                ))
            }
            "aggressive_mobile" -> {
                baseHeaders.putAll(mapOf(
                    "User-Agent" to getSafeUserAgent(true),
                    "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
                    "sec-ch-ua-mobile" to "?1",
                    "sec-ch-ua-platform" to "\"Android\"",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "DNT" to "1",
                    "Upgrade-Insecure-Requests" to "1"
                ))
            }
            "desktop_fallback" -> {
                // Use authentic desktop headers for maximum compatibility
                DESKTOP_HEADERS.toMutableMap().apply {
                    // Add some randomization to prevent detection
                    if (attempt > 1) {
                        put("Accept-Language", "en-US,en;q=0.8,en-GB;q=0.6")
                        put("Cache-Control", "no-cache")
                        put("Pragma", "no-cache")
                    }
                }
            }
            else -> {
                // Default mobile headers
                baseHeaders.putAll(mapOf(
                    "User-Agent" to getSafeUserAgent(true),
                    "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
                    "sec-ch-ua-mobile" to "?1",
                    "sec-ch-ua-platform" to "\"Android\""
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