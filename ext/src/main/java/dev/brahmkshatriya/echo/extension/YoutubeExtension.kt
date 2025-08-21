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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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

    private val json = Json { ignoreUnknownKeys = true }

    val api = YoutubeiApi(
        data_language = ENGLISH
    )

    // Ensure visitor ID is initialized before any API calls
    private suspend fun ensureVisitorId() {
        try {
            if (api.visitor_id == null) {
                var visitorError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        api.visitor_id = visitorEndpoint.getVisitorId()
                        return
                    } catch (e: Exception) {
                        visitorError = e
                        if (attempt < 3) {
                            kotlinx.coroutines.delay(500L * attempt)
                        }
                    }
                }
                throw visitorError ?: Exception("Failed to get visitor ID after 3 attempts")
            }
        } catch (_: Exception) {
            // Continue without visitor id if needed
        }
    }

    private val thumbnailQuality
        get() = if (settings.getBoolean("high_quality") == true) HIGH else LOW

    private val preferVideos
        get() = settings.getBoolean("prefer_videos") == true

    private val showVideos
        get() = settings.getBoolean("show_videos") != false

    private val preferOpus
        get() = settings.getBoolean("prefer_opus") != false

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

        // YouTube Audio Format IDs (itag codes)
        // MP4 AAC Formats
        const val AUDIO_AAC_HE_48KBPS = 139    // AAC (HE v1) 48 Kbps
        const val AUDIO_AAC_LC_128KBPS = 140   // AAC (LC) 128 Kbps
        const val AUDIO_AAC_LC_256KBPS = 141   // AAC (LC) 256 Kbps (rare)

        // WebM Opus Formats
        const val AUDIO_OPUS_50KBPS = 249      // Opus ~50 Kbps
        const val AUDIO_OPUS_70KBPS = 250      // Opus ~70 Kbps
        const val AUDIO_OPUS_128KBPS = 251     // Opus ~128 Kbps

        // Other known/rare
        const val AUDIO_OPUS_256KBPS = 774     // Opus ~256 Kbps (rare)
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

    // Map app-provided extras (or defaults) into target video height
    private fun targetHeightFrom(streamable: Streamable?): Int? {
        if (!showVideos) return null
        val extras = streamable?.extras ?: emptyMap()
        val q = (extras["videoQuality"] ?: extras["quality"] ?: extras["streamQuality"])
            ?.toString()?.lowercase()
        return when (q) {
            "lowest", "low", "144p" -> 144
            "360p" -> 360
            "medium", "480p" -> 480
            "high", "highest", "720p" -> 720
            "1080p" -> 1080
            "auto", "automatic" -> 480
            else -> 480
        }
    }

    // Internal lightweight format wrapper
    private data class Fmt(
        val itag: Int?,
        val url: String?,
        val mimeType: String,
        val bitrate: Long?,
        val height: Int? // null for audio
    )

    private fun toFmt(format: Any): Fmt {
        fun getIntFieldOrNull(name: String): Int? = try {
            val f = format.javaClass.getDeclaredField(name)
            f.isAccessible = true
            (f.get(format) as? Number)?.toInt()
        } catch (_: Exception) { null }

        @Suppress("UNCHECKED_CAST")
        fun getStringFieldOrNull(name: String): String? = try {
            val f = format.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.get(format) as? String
        } catch (_: Exception) { null }

        fun getLongFieldOrNull(name: String): Long? = try {
            val f = format.javaClass.getDeclaredField(name)
            f.isAccessible = true
            (f.get(format) as? Number)?.toLong()
        } catch (_: Exception) { null }

        // Try direct properties first using Kotlin reflection-like access via exceptions
        val url = try { format.javaClass.getDeclaredField("url").let { it.isAccessible = true; it.get(format) as? String } } catch (_: Exception) { null }
        val mime = try { format.javaClass.getDeclaredField("mimeType").let { it.isAccessible = true; it.get(format)?.toString() ?: "" } } catch (_: Exception) { "" }
        val bitrate = try { format.javaClass.getDeclaredField("bitrate").let { it.isAccessible = true; (it.get(format) as? Number)?.toLong() } } catch (_: Exception) { null }
        val itag = getIntFieldOrNull("itag") // many models expose it
        val height = getIntFieldOrNull("height") // video only

        return Fmt(
            itag = itag,
            url = url,
            mimeType = mime.lowercase(),
            bitrate = bitrate,
            height = height
        )
    }

    private fun chooseBestAudio(formats: List<Fmt>, preferOpus: Boolean): Fmt? {
        val audio = formats.filter { it.mimeType.startsWith("audio/") && it.url != null }
        if (audio.isEmpty()) return null

        val priority = if (preferOpus)
            listOf(AUDIO_OPUS_128KBPS, AUDIO_OPUS_70KBPS, AUDIO_OPUS_50KBPS, AUDIO_AAC_LC_128KBPS, AUDIO_AAC_HE_48KBPS)
        else
            listOf(AUDIO_AAC_LC_128KBPS, AUDIO_OPUS_128KBPS, AUDIO_OPUS_70KBPS, AUDIO_OPUS_50KBPS, AUDIO_AAC_HE_48KBPS)

        for (pid in priority) {
            audio.firstOrNull { it.itag == pid }?.let { return it }
        }
        // fallback: highest bitrate audio
        return audio.maxByOrNull { it.bitrate ?: 0L }
    }

    private fun chooseBestVideo(formats: List<Fmt>, targetHeight: Int?): Fmt? {
        val video = formats.filter { it.mimeType.startsWith("video/") && it.url != null }
        if (video.isEmpty()) return null
        if (targetHeight == null) return video.maxByOrNull { it.bitrate ?: 0L }

        val exact = video.filter { it.height == targetHeight }
        if (exact.isNotEmpty()) return exact.maxByOrNull { it.bitrate ?: 0L }

        val below = video.filter { (it.height ?: Int.MAX_VALUE) <= targetHeight }
        if (below.isNotEmpty()) return below.maxByOrNull { it.height ?: 0 }

        // last-resort: pick the smallest height above target
        return video.minByOrNull { it.height ?: Int.MAX_VALUE }
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean
    ): Streamable.Media {
        return when (streamable.type) {
            Streamable.MediaType.Server -> when (streamable.id) {
                "AUDIO_MP3", "AUDIO_MP4", "AUDIO_WEBM", "AUDIO" -> {
                    // Audio-only playback (use exact URLs; do not mutate)
                    ensureVisitorId()
                    val videoId = streamable.extras["videoId"]
                        ?: throw IllegalArgumentException("Missing videoId in streamable extras")

                    val (video, _) = videoEndpoint.getVideo(true, videoId)

                    val fmts = video.streamingData.adaptiveFormats.map { toFmt(it) }
                    val audioFmt = chooseBestAudio(fmts, preferOpus)
                    val videoFmt = if (preferVideos && showVideos) chooseBestVideo(fmts, targetHeightFrom(streamable)) else null

                    val sources = mutableListOf<Streamable.Source.Http>()

                    if (audioFmt?.url != null) {
                        sources += Streamable.Source.Http(
                            request = audioFmt.url.toRequest(), // use URL as-is
                            quality = (audioFmt.bitrate ?: 0L).toInt()
                        )
                    } else {
                        throw Exception("No valid audio sources found")
                    }

                    if (preferVideos && showVideos && videoFmt?.url != null) {
                        sources += Streamable.Source.Http(
                            request = videoFmt.url.toRequest(),
                            quality = (videoFmt.bitrate ?: 0L).toInt()
                        )
                        Streamable.Media.Server(sources = sources, merged = true)
                    } else {
                        Streamable.Media.Server(sources = listOf(sources.first()), merged = false)
                    }
                }

                "VIDEO_MP4", "VIDEO_WEBM", "VIDEO" -> {
                    if (!showVideos) throw Exception("Video streaming is disabled in settings")

                    ensureVisitorId()
                    val videoId = streamable.extras["videoId"]
                        ?: throw IllegalArgumentException("Missing videoId in streamable extras")

                    val (video, _) = videoEndpoint.getVideo(true, videoId)

                    val fmts = video.streamingData.adaptiveFormats.map { toFmt(it) }
                    val target = targetHeightFrom(streamable)
                    val videoFmt = chooseBestVideo(fmts, target)
                    val audioFmt = chooseBestAudio(fmts, preferOpus)

                    if (videoFmt?.url != null && audioFmt?.url != null) {
                        val sources = listOf(
                            Streamable.Source.Http(
                                request = audioFmt.url.toRequest(),
                                quality = (audioFmt.bitrate ?: 0L).toInt()
                            ),
                            Streamable.Source.Http(
                                request = videoFmt.url.toRequest(),
                                quality = (videoFmt.bitrate ?: 0L).toInt()
                            )
                        )
                        Streamable.Media.Server(sources = sources, merged = true)
                    } else if (videoFmt?.url != null) {
                        Streamable.Media.Server(
                            sources = listOf(
                                Streamable.Source.Http(
                                    request = videoFmt.url.toRequest(),
                                    quality = (videoFmt.bitrate ?: 0L).toInt()
                                )
                            ),
                            merged = false
                        )
                    } else {
                        throw Exception("No valid video sources found")
                    }
                }

                else -> throw IllegalArgumentException("Unknown server streamable ID: ${streamable.id}")
            }

            Streamable.MediaType.Background -> throw IllegalArgumentException("Background media type not supported")
            Streamable.MediaType.Subtitle -> throw IllegalArgumentException("Subtitle media type not supported")
        }
    }

    override suspend fun loadTrack(track: Track) = coroutineScope {
        ensureVisitorId()
        val deferredInfo = async { songEndPoint.loadSong(track.id).getOrThrow() }
        val (video, type) = videoEndpoint.getVideo(true, track.id)

        val newTrack = deferredInfo.await()
        val resultTrack = newTrack.copy(
            description = video.videoDetails.shortDescription,
            artists = newTrack.artists.ifEmpty {
                video.videoDetails.run { listOf(Artist(channelId, author)) }
            },
            streamables = listOf(
                Streamable.server(
                    "AUDIO_MP3",
                    0,
                    "Audio Stream",
                    mutableMapOf<String, String>().apply { put("videoId", track.id) }
                )
            ),
            plays = video.videoDetails.viewCount?.toLongOrNull()
        )
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

    private val trackMap = ConcurrentHashMap<String, PagedData<Track>>()

    override suspend fun loadAlbum(album: Album): Album {
        val (ytmPlaylist, _, data) = playlistEndPoint.loadFromPlaylist(
            album.id, null, thumbnailQuality
        )
        trackMap[ytmPlaylist.id] = data
        return ytmPlaylist.toAlbum(false, HIGH)
    }

    override fun loadTracks(album: Album): PagedData<Track> = trackMap[album.id]
        ?: throw IllegalStateException("Album tracks not loaded for ${album.id}")

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

    override fun loadTracks(playlist: Playlist): PagedData<Track> = trackMap[playlist.id]
        ?: throw IllegalStateException("Playlist tracks not loaded for ${playlist.id}")

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
        // Tab("FEmusic_listening_review", "Review"),
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
