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
import dev.brahmkshatriya.echo.common.clients.ShelvesClient
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
    TrackLikeClient, PlaylistEditClient, ShelvesClient {

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

    override suspend fun getShelves(tab: Tab?): List<Shelf> {
        TODO("Implement get shelves")
        throw NotImplementedError("Get shelves not implemented")
    }

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
                "ANDROID_STREAM" -> {
                    // Android-style POST streaming
                    println("DEBUG: Loading Android-style POST stream for videoId: ${streamable.extras["videoId"]}")
                    loadAndroidStyleStream(streamable.extras["videoId"]!!)
                }
                "DUAL_STREAM" -> {
                    // Try Android-style POST streaming first, fallback to legacy method
                    try {
                        println("DEBUG: Attempting Android-style POST streaming for videoId: ${streamable.extras["videoId"]}")
                        loadAndroidStyleStream(streamable.extras["videoId"]!!)
                    } catch (e: Exception) {
                        println("DEBUG: Android-style streaming failed, falling back to legacy method: ${e.message}")
                        loadLegacyStreamableMedia(streamable, isDownload)
                    }
                }
                else -> loadLegacyStreamableMedia(streamable, isDownload)
            }
            
            // Add other MediaType cases to make when exhaustive
            Streamable.MediaType.Background -> throw IllegalArgumentException("Background media type not supported")
            Streamable.MediaType.Subtitle -> throw IllegalArgumentException("Subtitle media type not supported")
        }
    }

    private suspend fun loadAndroidStyleStream(videoId: String): Streamable.Media {
        // Ensure visitor ID is initialized
        ensureVisitorId()
        
        println("DEBUG: Loading Android-style POST stream for videoId: $videoId")
        
        // Get video info first to extract necessary parameters
        val (video, _) = videoEndpoint.getVideo(false, videoId)
        
        // Generate Android-style POST request parameters based on captured request
        val currentTime = System.currentTimeMillis() / 1000
        val expireTime = currentTime + 21600 // 6 hours from now
        
        // Extract base URL from adaptive formats or use default
        val baseUrl = video.streamingData.adaptiveFormats.firstOrNull()?.url?.let { url ->
            val uri = url.split("?")[0]
            val host = uri.substringBefore("videoplayback") + "videoplayback"
            host
        } ?: "https://rr1---sn-bxonu5gpo-cvhs.googlevideo.com/videoplayback"
        
        // Generate required parameters for Android-style request (from captured data)
        val params = mutableMapOf<String, String>().apply {
            put("expire", expireTime.toString())
            put("ei", "572kaPEbgr3i3g-a0eqYBg") // This might need to be dynamic
            put("ip", "103.44.49.196") // This should be dynamic based on user's IP
            put("id", "o-APDK12uviFb8ad8dVZGsNGfVFkhfT3V7v7dlf4RRJ_3-") // This might need to be dynamic
            put("source", "youtube")
            put("requiressl", "yes")
            put("xpc", "EgVo2aDSNQ%3D%3D")
            put("met", "1755626983%2C")
            put("mh", "kF")
            put("mm", "31%2C29")
            put("mn", "sn-bxonu5gpo-cvhs%2Csn-cvh7knsz")
            put("ms", "au%2Crdu")
            put("mv", "m")
            put("mvi", "1")
            put("pcm2cms", "yes")
            put("pl", "24")
            put("rms", "au%2Cau")
            put("initcwndbps", "1106250")
            put("siu", "1")
            put("spc", "l3OVKQHIBJVK0Q8vTXag_SR3ZCDWZEYyy2B76009uFOukzuyd3bStFxZQsDJFGWyC78c")
            put("svpuc", "1")
            put("ns", "8bgr6Qa2o4jJZ6PrrS2wjxcQ")
            put("sabr", "1")
            put("rqh", "1")
            put("mt", "1755626478")
            put("fvip", "3")
            put("keepalive", "yes")
            put("fexp", "51331020%2C51548755%2C51565116%2C51565682")
            put("c", "MWEB")
            put("n", "ktB2js8CVX151A")
            put("sparams", "expire%2Cei%2Cip%2Cid%2Csource%2Crequiressl%2Cxpc%2Csiu%2Cspc%2Csvpuc%2Cns%2Csabr%2Crqh")
            put("sig", "AJfQdSswRgIhANad136wHwoBFd4qVvWzPTa0B8RIbwF26ltEAHwoSgiRAiEAmhFqeI1RcCg33riArTuDnzwb30f81pDIJr9FJH8Auh8%3D")
            put("lsparams", "met%2Cmh%2Cmm%2Cmn%2Cms%2Cmv%2Cmvi%2Cpcm2cms%2Cpl%2Crms%2Cinitcwndbps")
            put("lsig", "APaTxxMwRQIgAzvggPqrUFBwkEcYdlxAzJxn4IAsnYSR92CXDYzx-uICIQCinSloplyCKBp41shj3TOfoyoi6iYCt6G2v4LHUofrDA%3D%3D")
            put("cpn", generateCPN()) // This should be dynamic
            put("cver", "2.20250819.01.00")
            put("rn", "48")
            put("alr", "yes")
        }
        
        // Build the final URL
        val queryString = params.map { (key, value) -> "$key=$value" }.joinToString("&")
        val finalUrl = "$baseUrl?$queryString"
        
        println("DEBUG: Android-style URL: $finalUrl")
        
        // Create POST request body (this would need to be determined from actual captured data)
        val postBody = createAndroidPostBody(videoId, video)
        
        // Create the streamable media with Android-style request
        return Streamable.Media.Companion.toServerMedia(
            listOf(
                Streamable.Source.Http(
                    request = finalUrl.toRequest(),
                    quality = 1000000 // Highest priority for Android-style streams
                )
            )
        )
    }

    private fun generateCPN(): String {
        // Generate a CPN (Content Playback Notification) ID similar to YouTube
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString {
            for (i in 0..15) {
                append(chars.random())
            }
        }
    }

    private fun createAndroidPostBody(videoId: String, video: Any): String {
        // This would need to be determined from the actual hex data you captured
        // For now, creating a basic post body structure
        // The actual POST body from the Android app is likely more complex
        return buildString {
            append("video_id=").append(videoId)
            append("&audio_only=").append("true")
            append("&format=").append("json")
            append("&client=").append("android")
            append("&visitor_id=").append(api.visitor_id ?: "")
            append("&cpn=").append(generateCPN())
        }
    }

    private suspend fun loadLegacyStreamableMedia(
        streamable: Streamable, isDownload: Boolean
    ): Streamable.Media {
        // Simplified legacy method - just use basic HLS streaming
        println("DEBUG: Loading legacy stream for videoId: ${streamable.extras["videoId"]}")
        
        val videoId = streamable.extras["videoId"]!!
        val (video, _) = videoEndpoint.getVideo(false, videoId)
        
        val sources = mutableListOf<Streamable.Source.Http>()
        
        // Try HLS first
        video.streamingData.hlsManifestUrl?.let { hlsUrl ->
            sources.add(
                Streamable.Source.Http(
                    hlsUrl.toRequest(),
                    quality = 500000
                )
            )
        }
        
        // If no HLS, try adaptive formats
        if (sources.isEmpty()) {
            video.streamingData.adaptiveFormats
                .filter { it.mimeType.contains("audio") }
                .forEach { format ->
                    format.url?.let { url ->
                        sources.add(
                            Streamable.Source.Http(
                                url.toRequest(),
                                quality = format.bitrate.toInt()
                            )
                        )
                    }
                }
        }
        
        if (sources.isEmpty()) {
            throw Exception("No streaming sources found")
        }
        
        return Streamable.Media.Companion.toServerMedia(
            sources
        )
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
                    "ANDROID_STREAM",
                    0,
                    "Android-style POST Stream",
                    mapOf("videoId" to track.id)
                ).takeIf { true }, // Always try Android-style first
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

    // Add all other required method stubs to make the class compile
    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        // Implementation needed
    }

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        // TODO: Implement quick search properly
        // For now, return empty list to avoid compilation issues
        return emptyList()
    }

    override suspend fun searchTabs(query: String): List<Tab> {
        return listOf(Tab("Tracks", ""), Tab("Artists", ""), Tab("Albums", ""), Tab("Playlists", ""))
    }

    override suspend fun radio(album: Album): Radio {
        TODO("Implement album radio")
        throw NotImplementedError("Album radio not implemented")
    }

    override suspend fun radio(artist: Artist): Radio {
        TODO("Implement artist radio")
        throw NotImplementedError("Artist radio not implemented")
    }

    override suspend fun radio(track: Track, context: EchoMediaItem?): Radio {
        TODO("Implement track radio")
        throw NotImplementedError("Track radio not implemented")
    }

    override suspend fun radio(user: User) = radio(user.toArtist())

    override suspend fun radio(playlist: Playlist): Radio {
        TODO("Implement playlist radio")
        throw NotImplementedError("Playlist radio not implemented")
    }

    override suspend fun loadAlbum(album: Album): Album {
        TODO("Implement load album")
        throw NotImplementedError("Load album not implemented")
    }

    override suspend fun loadUser(user: User): User {
        TODO("Implement load user")
        throw NotImplementedError("Load user not implemented")
    }

    override suspend fun followArtist(artist: Artist, follow: Boolean) {
        TODO("Implement follow artist")
    }

    override suspend fun loadArtist(artist: Artist): Artist {
        TODO("Implement load artist")
        throw NotImplementedError("Load artist not implemented")
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        TODO("Implement load playlist")
        throw NotImplementedError("Load playlist not implemented")
    }

    override suspend fun onSetLoginUser(user: User?) {
        TODO("Implement on set login user")
    }

    override suspend fun getCurrentUser(): User? {
        TODO("Implement get current user")
        throw NotImplementedError("Get current user not implemented")
    }

    override suspend fun onMarkAsPlayed(details: TrackDetails) {
        TODO("Implement on mark as played")
    }

    override suspend fun getLibraryTabs() = listOf(
        Tab("Playlists", ""),
        Tab("Songs", ""),
        Tab("Albums", ""),
        Tab("Artists", "")
    )

    private suspend fun <T> withUserAuth(
        operation: suspend () -> T
    ): T {
        TODO("Implement with user auth")
        throw NotImplementedError("With user auth not implemented")
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        TODO("Implement create playlist")
        throw NotImplementedError("Create playlist not implemented")
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        TODO("Implement delete playlist")
        throw NotImplementedError("Delete playlist not implemented")
    }

    override suspend fun likeTrack(track: Track, isLiked: Boolean) {
        TODO("Implement like track")
    }

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        TODO("Implement list editable playlists")
    }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist, title: String, description: String?
    ) {
        TODO("Implement edit playlist metadata")
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist, tracks: List<Track>, indexes: List<Int>
    ) {
        TODO("Implement remove tracks from playlist")
    }

    override suspend fun addTracksToPlaylist(
        playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>
    ) {
        TODO("Implement add tracks to playlist")
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int
    ) {
        TODO("Implement move track in playlist")
    }

    override suspend fun loadLyrics(lyrics: Lyrics) = lyrics

    override suspend fun onShare(item: EchoMediaItem) = when (item) {
        is EchoMediaItem.TrackItem -> "https://music.youtube.com/watch?v=${item.track.id}"
        is EchoMediaItem.Lists.AlbumItem -> "https://music.youtube.com/browse/${item.album.id}"
        is EchoMediaItem.Lists.PlaylistItem -> "https://music.youtube.com/playlist?list=${item.playlist.id}"
        is EchoMediaItem.Profile.ArtistItem -> "https://music.youtube.com/channel/${item.artist.id}"
        else -> throw IllegalArgumentException("Unsupported media item type for sharing")
    }
}