package com.ariesr91.videoloop

import android.net.Uri
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.graphics.Matrix
import android.graphics.RectF
import android.view.Surface
import android.widget.PopupWindow
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var videoPlayer: ExoPlayer
    private lateinit var musicPlayer: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var menuButton: TextView
    private lateinit var videoStatus: TextView
    private lateinit var musicStatus: TextView
    private lateinit var musicVolume: SeekBar
    private lateinit var removeMusicButton: Button
    private lateinit var folderVideosRecycler: RecyclerView
    private var folderVideos: List<GalleryVideo> = emptyList()
    // Null means the gallery scope is "Semua"; a name means a specifically selected album.
    private var activeGalleryAlbum: String? = null
    // When launched from an external file manager, constrain the strip to that file's parent folder.
    private var externalOpenBucketId: String? = null
    private var externalOpenFolderPath: String? = null
    private var externalOpenDisplayName: String? = null
    private var externalOpenFileSize: Long? = null

    private var selectedVideo: Uri? = null
    private var selectedMusic: Uri? = null
    private var musicVolumeValue = 0.8f
    private var drawerOpen = false
    private var zoomMode = false
    private var landscapeVideo = false
    private var trimStartMs = 0L
    private var trimEndMs = 0L
    private var trimEnabled = false
    private var trimSourceDurationMs = 0L
    private var stopMusicWhenVideoPaused = false
    private var panLastX = 0f
    private var panLastY = 0f
    private var panOffsetX = 0f
    private var panOffsetY = 0f
    private var audioChoice = 0 // 0=automatic, 1=video, 2=music, 3=mute
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            loadVideo(uri)
        }
    }

    private val requestVideoPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasVideoPermission()) openVideoGallery()
        else android.widget.Toast.makeText(this, "Izin akses video diperlukan untuk menampilkan galeri.", android.widget.Toast.LENGTH_LONG).show()
    }

    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            loadMusic(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        videoPlayer = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    playerView.post {
                        applyVideoOrientation()
                        applySourceFrameRate()
                    }
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    playerView.post { applySourceFrameRate() }
                }
                override fun onRenderedFirstFrame() {
                    playerView.post { applySourceFrameRate() }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Video tidak dapat diputar: " + (error.localizedMessage ?: "format tidak didukung"),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
        musicPlayer = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = musicVolumeValue
        }
        stopMusicWhenVideoPaused = getSharedPreferences("sr_video_loop", MODE_PRIVATE)
            .getBoolean("stop_music_when_video_paused", false)
        videoPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!stopMusicWhenVideoPaused || selectedMusic == null) return
                if (playWhenReady) musicPlayer.play() else musicPlayer.pause()
            }
        })
        buildInterface()
        handleIncomingVideoIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingVideoIntent(intent)
    }

    private fun handleIncomingVideoIntent(incoming: android.content.Intent?) {
        if (incoming == null) return
        val uri = when (incoming.action) {
            android.content.Intent.ACTION_VIEW -> incoming.data
            android.content.Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) incoming.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (incoming.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? Uri)
            }
            else -> incoming.data
        } ?: return
        try { persistReadPermission(uri) } catch (_: Exception) { }
        val externalInfo = resolveExternalVideo(uri)
        externalOpenBucketId = externalInfo.bucketId
        externalOpenFolderPath = externalInfo.folderPath
        externalOpenDisplayName = externalInfo.displayName
        externalOpenFileSize = externalInfo.fileSize
        activeGalleryAlbum = null
        playerView.post { loadVideo(uri) }
    }

    private data class ExternalVideoInfo(
        val bucketId: String?,
        val folderPath: String?,
        val displayName: String?,
        val fileSize: Long?
    )

    private fun resolveExternalVideo(uri: Uri): ExternalVideoInfo {
        var name: String? = null
        var size: Long? = null
        var path: String? = null

        if (uri.scheme == "file") path = uri.path
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE, MediaStore.MediaColumns.DATA, MediaStore.Video.Media.BUCKET_ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    val pi = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    name = if (ni >= 0) c.getString(ni) else null
                    size = if (si >= 0 && !c.isNull(si)) c.getLong(si) else null
                    if (pi >= 0 && !c.isNull(pi)) path = c.getString(pi)
                    val bi = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                    if (bi >= 0 && !c.isNull(bi)) {
                        val bucket = c.getString(bi)
                        if (!bucket.isNullOrBlank()) return ExternalVideoInfo(bucket, path?.substringBeforeLast('/', ""), name, size)
                    }
                }
            }
        } catch (_: Exception) { }

        // Some file managers expose a provider URI rather than a MediaStore URI.
        // Try Android's MediaStore bridge, then resolve against indexed video paths.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val bridged = MediaStore.getMediaUri(this, uri)
                if (bridged != null && bridged != uri) {
                    contentResolver.query(bridged, arrayOf(MediaStore.Video.Media.BUCKET_ID, MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val bi = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                            val pi = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                            val bucket = if (bi >= 0) c.getString(bi) else null
                            if (path.isNullOrBlank() && pi >= 0) path = c.getString(pi)
                            if (!bucket.isNullOrBlank()) return ExternalVideoInfo(bucket, path?.substringBeforeLast('/', ""), name, size)
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        return ExternalVideoInfo(null, path?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }, name, size)
    }

    @Suppress("DEPRECATION")
    private fun buildInterface() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        playerView = layoutInflater.inflate(R.layout.player_view, root, false) as PlayerView
        playerView.apply {
            player = videoPlayer
            useController = true
            // Keep the complete source frame visible by default (e.g. 9:16 stays 9:16).
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(0xFF000000.toInt())
            setPadding(0)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        folderVideosRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.GONE
            clipToPadding = false
            setPadding(dp(10), dp(3), dp(10), dp(3))
            adapter = FolderVideoAdapter()
        }
        root.addView(folderVideosRecycler, FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM).apply {
            // Place the sibling-video strip above the playback time and progress meter.
            bottomMargin = dp(76)
        })
        // Reuse the single built-in white gear; it opens the combined settings menu.
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
            showPlayerSettings(it)
        }
        // Overlay actions follow the visibility of ExoPlayer's playback controls.
        menuButton = TextView(this).apply {
            text = "☰"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = null
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setOnClickListener { toggleDrawer(true) }
            contentDescription = "Buka menu"
        }
        root.addView(menuButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(16)
            marginStart = dp(12)
        })

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            val showActions = visibility == View.VISIBLE && !drawerOpen
            menuButton.visibility = if (showActions) View.VISIBLE else View.GONE
            folderVideosRecycler.visibility = if (showActions && folderVideos.isNotEmpty()) View.VISIBLE else View.GONE
        })

        scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { toggleDrawer(false) }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18))
            setBackgroundColor(0xFF17191F.toInt())
            elevation = dp(18).toFloat()
        }
        val drawerWidth = minOf(dp(320), (resources.displayMetrics.widthPixels * 0.84f).toInt())
        root.addView(drawer, FrameLayout.LayoutParams(drawerWidth, -1, Gravity.START).apply {
            topMargin = 0
        })

        buildDrawerContents()
        drawer.translationX = -drawerWidth.toFloat()
        setContentView(root)
        // Apply immersive mode only after the activity content is attached.
        hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun toggleZoomMode() {
        zoomMode = !zoomMode
        applyVideoOrientation()
    }

    private fun applyVideoOrientation() {
        // Rotate controls Activity orientation only; video surface stays untouched.
        playerView.resizeMode = if (zoomMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.videoSurfaceView?.apply {
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
        }
        panOffsetX = 0f
        panOffsetY = 0f
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        playerView.post {
            playerView.requestLayout()
            applyVideoOrientation()
        }
        val spanCount = if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 6 else 3
        (galleryRecycler?.layoutManager as? GridLayoutManager)?.spanCount = spanCount
        galleryRecycler?.requestLayout()
    }

    @android.annotation.TargetApi(30)
    private fun applySourceFrameRate() {
        if (Build.VERSION.SDK_INT < 30) return
        val texture = playerView.videoSurfaceView as? TextureView ?: return
        val textureSurface = texture.surfaceTexture ?: return
        val fps = videoPlayer.videoFormat?.frameRate ?: return
        if (fps <= 0f || !fps.isFinite()) return
        try {
            // Hint Android's display compositor to use the source frame cadence (e.g. 60 fps).
            val surface = Surface(textureSurface)
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            surface.release()
        } catch (_: Exception) { }
    }

    private var settingsPopup: PopupWindow? = null

    private fun makeBottomPopup(content: View, width: Int = dp(236)) {
        settingsPopup?.dismiss()
        settingsPopup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            showAtLocation(playerView, Gravity.BOTTOM or Gravity.END, dp(10), dp(68))
        }
    }

    private fun popupPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xD9101010.toInt())
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0x33777777)
        }
        elevation = dp(12).toFloat()
    }

    private fun addPopupOption(panel: LinearLayout, icon: String, title: String, subtitle: String, action: () -> Unit) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(7), dp(4), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        line.addView(TextView(this).apply {
            text = icon; textSize = 23f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        labels.addView(TextView(this).apply { text = title; textSize = 14f; setTextColor(0xFFF5F5F5.toInt()) })
        labels.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(0xFFB8B8B8.toInt()) })
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(line, LinearLayout.LayoutParams(-1, dp(58)))
    }

    private fun showPlayerSettings(anchor: View) {
        val panel = popupPanel()
        val speed = videoPlayer.playbackParameters.speed
        val speedLabel = if (speed == 1f) "Normal" else "${speed}x"
        val audioLabel = when (audioChoice) {
            1 -> "Audio video"
            2 -> "Musik tambahan"
            3 -> "Bisukan"
            else -> if (selectedMusic != null) "Musik tambahan" else "Audio video"
        }
        addPopupOption(panel, "◴", "Kecepatan", speedLabel) { showSpeedOptions() }
        addPopupOption(panel, "♫", "Audio", audioLabel) { showAudioOptions() }
        addPopupOption(panel, "⛶", "Zoom", if (zoomMode) "Aktif" else "Nonaktif") {
            toggleZoomMode()
            showPlayerSettings(anchor)
        }
        addPopupOption(panel, "↻", "Rotate", if (landscapeVideo) "Landscape" else "Potret") {
            landscapeVideo = !landscapeVideo
            requestedOrientation = if (landscapeVideo) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            showPlayerSettings(anchor)
        }
        makeBottomPopup(panel)
    }

    private fun showBottomChoices(title: String, choices: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit) {
        val panel = popupPanel()
        panel.addView(TextView(this).apply {
            text = title; textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(8), dp(6), dp(8), dp(8))
        })
        choices.forEachIndexed { index, choice ->
            addPopupOption(panel, if (index == selected) "●" else "○", choice.first, choice.second) {
                settingsPopup?.dismiss()
                onSelect(index)
            }
        }
        makeBottomPopup(panel)
    }

    private fun showSpeedOptions() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val current = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - videoPlayer.playbackParameters.speed) } ?: 2
        showBottomChoices("Kecepatan", speeds.map { (if (it == 1f) "Normal" else "${it}x") to "" }, current) {
            videoPlayer.setPlaybackSpeed(speeds[it])
            showPlayerSettings(playerView.findViewById(androidx.media3.ui.R.id.exo_settings) ?: playerView)
        }
    }

    private fun showAudioOptions() {
        val labels = listOf("Otomatis", "Audio video", "Musik tambahan", "Bisukan")
        showBottomChoices("Audio", labels.map { it to "" }, audioChoice) { which ->
            if (which == 2 && selectedMusic == null) {
                android.widget.Toast.makeText(this, "Pilih musik tambahan dari menu", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                audioChoice = which
                applyAudioRouting()
            }
            showPlayerSettings(playerView.findViewById(androidx.media3.ui.R.id.exo_settings) ?: playerView)
        }
    }

    private fun buildDrawerContents() {
        val heading = TextView(this).apply {
            text = "SR VIDEO LOOP"
            textSize = 19f
            letterSpacing = 0.06f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        drawer.addView(heading, LinearLayout.LayoutParams(-1, dp(58)))

        val subtitle = TextView(this).apply {
            text = "PEMUTAR VIDEO"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(0xFF8F96A5.toInt())
        }
        drawer.addView(subtitle, LinearLayout.LayoutParams(-1, dp(28)))

        drawer.addView(makeButton("Pilih Video") {
            toggleDrawer(false)
            showVideoGallery()
        }, LinearLayout.LayoutParams(-1, dp(48)))
        videoStatus = makeStatus("Belum ada video")
        drawer.addView(videoStatus, LinearLayout.LayoutParams(-1, dp(38)))
        drawer.addView(makeButton("Potong Video") {
            toggleDrawer(false)
            showVideoTrimDialog()
        }, LinearLayout.LayoutParams(-1, dp(48)))

        addDrawerDivider()

        drawer.addView(makeButton("Pilih Musik") {
            toggleDrawer(false)
            pickMusic.launch(arrayOf("audio/*"))
        }, LinearLayout.LayoutParams(-1, dp(48)))
        musicStatus = makeStatus("Musik tambahan tidak aktif")
        drawer.addView(musicStatus, LinearLayout.LayoutParams(-1, dp(38)))

        val volumeLabel = TextView(this).apply {
            text = "Volume musik"
            textSize = 13f
            setTextColor(0xFFD7D8DE.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        drawer.addView(volumeLabel, LinearLayout.LayoutParams(-1, dp(32)))
        musicVolume = SeekBar(this).apply {
            max = 100
            progress = 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    musicVolumeValue = progress / 100f
                    musicPlayer.volume = musicVolumeValue
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        drawer.addView(musicVolume, LinearLayout.LayoutParams(-1, dp(44)))
        val pauseMusicRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val pauseMusicLabel = TextView(this).apply {
            text = "Hentikan musik saat video di jeda"
            textSize = 13f
            setTextColor(0xFFF5F5F5.toInt())
        }
        pauseMusicRow.addView(pauseMusicLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val pauseMusicCheck = android.widget.CheckBox(this).apply {
            isChecked = stopMusicWhenVideoPaused
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF26A69A.toInt())
            setOnCheckedChangeListener { _, checked ->
                stopMusicWhenVideoPaused = checked
                getSharedPreferences("sr_video_loop", MODE_PRIVATE).edit()
                    .putBoolean("stop_music_when_video_paused", checked).apply()
                if (selectedMusic != null) {
                    if (checked && !videoPlayer.playWhenReady) musicPlayer.pause()
                    else if (checked && videoPlayer.playWhenReady) musicPlayer.play()
                    else if (!checked) musicPlayer.play()
                }
            }
        }
        pauseMusicRow.addView(pauseMusicCheck)
        drawer.addView(pauseMusicRow, LinearLayout.LayoutParams(-1, dp(48)))

        removeMusicButton = makeButton("Hapus Musik") { removeMusic() }.apply {
            isEnabled = false
        }
        drawer.addView(removeMusicButton, LinearLayout.LayoutParams(-1, dp(48)))

        addDrawerDivider()
        val note = TextView(this).apply {
            text = "Video dan musik berulang otomatis. Saat musik tambahan dipilih, suara asli video akan dimatikan. Hapus musik untuk mengaktifkan kembali suara video."
            textSize = 12f
            setTextColor(0xFF969BA7.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        drawer.addView(note, LinearLayout.LayoutParams(-1, -2))
        drawer.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        drawer.addView(makeButton("Tutup Menu") { toggleDrawer(false) }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun addDrawerDivider() {
        drawer.addView(View(this).apply {
            setBackgroundColor(0xFF343740.toInt())
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })
    }

    private fun toggleDrawer(show: Boolean) {
        if (drawerOpen == show) return
        drawerOpen = show
        if (show) {
            menuButton.visibility = View.GONE
        } else {
            playerView.showController()
        }
        val width = drawer.layoutParams.width.toFloat()
        if (show) {
            scrim.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(180).start()
            drawer.animate().translationX(0f).setDuration(220).start()
        } else {
            scrim.animate().alpha(0f).setDuration(180).withEndAction {
                if (!drawerOpen) scrim.visibility = View.GONE
            }.start()
            drawer.animate().translationX(-width).setDuration(220).start()
        }
    }

    private data class GalleryVideo(
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val id: Long,
        val album: String,
        val bucketId: String,
        val filePath: String
    )

    private fun requiredVideoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasVideoPermission(): Boolean {
        return requiredVideoPermissions().any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showVideoGallery() {
        externalOpenBucketId = null
        if (!hasVideoPermission()) {
            requestVideoPermission.launch(requiredVideoPermissions())
            return
        }
        openVideoGallery()
    }

    private fun openVideoGallery(selectedAlbum: String? = null) {
        activeGalleryAlbum = selectedAlbum
        val allVideos = queryGalleryVideos()
        val videos = if (selectedAlbum == null) allVideos else allVideos.filter { it.album == selectedAlbum }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0)
            setBackgroundColor(0xFF101116.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val title = TextView(this).apply {
            text = selectedAlbum ?: "Semua"
            textSize = 21f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
            isClickable = true
            setOnClickListener {
                val albums = allVideos.map { it.album }.distinct().sorted()
                val labels = listOf("Semua") + albums
                AlertDialog.Builder(this@MainActivity)
                    .setItems(labels.toTypedArray()) { _, which ->
                        galleryDialog?.dismiss()
                        openVideoGallery(if (which == 0) null else albums[which - 1])
                    }.show()
            }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        val count = TextView(this).apply {
            text = videos.size.toString() + " video"
            textSize = 12f
            setTextColor(0xFF9BA3B4.toInt())
        }
        header.addView(count)
        content.addView(header)

        if (videos.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Tidak ada video yang ditemukan di galeri."
                textSize = 15f
                setTextColor(0xFFD5D7DE.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(20))
            }
            content.addView(empty, LinearLayout.LayoutParams(-1, dp(180)))
        } else {
            val spanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 6 else 3
            val list = RecyclerView(this).apply {
                layoutManager = GridLayoutManager(this@MainActivity, spanCount)
                setHasFixedSize(true)
                setPadding(0)
                clipToPadding = true
                itemAnimator = null
                adapter = GalleryAdapter(videos)
                setBackgroundColor(0xFF101116.toInt())
            }
            galleryRecycler = list
            content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        galleryDialog = AlertDialog.Builder(this)
            .setView(content)
            .create()
        galleryDialog?.setCancelable(true)
        galleryDialog?.setOnDismissListener { galleryRecycler = null }
        galleryDialog?.show()
        galleryDialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        galleryDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private var galleryDialog: AlertDialog? = null
    private var galleryRecycler: RecyclerView? = null

    private inner class SquareFrameLayout(context: android.content.Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
            val side = measuredWidth
            setMeasuredDimension(side, side)
        }
    }

    private inner class GalleryAdapter(private val items: List<GalleryVideo>) : RecyclerView.Adapter<GalleryAdapter.VideoHolder>() {
        inner class VideoHolder(val root: SquareFrameLayout, val image: ImageView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder {
            val cell = SquareFrameLayout(this@MainActivity).apply {
                setPadding(0)
                background = null
                isClickable = true
                isFocusable = true
            }
            val image = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF08090C.toInt())
            }
            cell.addView(image, FrameLayout.LayoutParams(-1, -1))
            return VideoHolder(cell, image)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VideoHolder, position: Int) {
            val video = items[position]
            holder.image.setImageDrawable(null)
            holder.image.tag = video.uri
            holder.root.setOnClickListener {
                galleryDialog?.dismiss()
                loadVideo(video.uri)
            }
            thumbnailExecutor.execute {
                try {
                    val bitmap = loadVideoThumbnail(video.uri, video.id, dp(320), dp(200))
                    runOnUiThread {
                        if (!isFinishing && holder.image.tag == video.uri && bitmap != null) holder.image.setImageBitmap(bitmap)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun updateFolderVideoStrip(uri: Uri) {
        folderVideos = try {
            val all = queryGalleryVideos()
            val currentId = uri.lastPathSegment?.toLongOrNull()
            val current = all.firstOrNull { it.uri == uri }
                ?: all.firstOrNull { currentId != null && it.id == currentId }
            val externalBucket = externalOpenBucketId
            val externalFolder = externalOpenFolderPath
            val externalName = externalOpenDisplayName
            val externalSize = externalOpenFileSize
            val albumScope = activeGalleryAlbum
            if (!externalBucket.isNullOrBlank() || !externalFolder.isNullOrBlank() || !externalName.isNullOrBlank()) {
                // Resolve external file-manager URI to the exact source folder.
                val byBucket = if (!externalBucket.isNullOrBlank()) all.filter { it.bucketId == externalBucket } else emptyList()
                val byPath = if (!externalFolder.isNullOrBlank()) all.filter { it.filePath.substringBeforeLast('/', "") == externalFolder } else emptyList()
                val byFile = if (!externalName.isNullOrBlank()) all.filter { it.name == externalName && (externalSize == null || it.filePath.isNotBlank() && runCatching { java.io.File(it.filePath).length() == externalSize }.getOrDefault(false)) } else emptyList()
                when {
                    byBucket.isNotEmpty() -> byBucket
                    byPath.isNotEmpty() -> byPath
                    byFile.isNotEmpty() -> all.filter { it.bucketId == byFile.first().bucketId }
                    else -> all
                }
            } else if (albumScope == null) {
                // "Semua": show every indexed video, preserving DATE_ADDED DESC order.
                all
            } else {
                // A chosen album: show only videos belonging to that album.
                val selectedBucket = current?.bucketId
                if (!selectedBucket.isNullOrBlank()) {
                    all.filter { it.bucketId == selectedBucket }
                } else {
                    all.filter { it.album == albumScope }
                }
            }
        } catch (_: Exception) { emptyList() }
        folderVideosRecycler.adapter?.notifyDataSetChanged()
        val selectedIndex = folderVideos.indexOfFirst { it.uri == uri || it.id == uri.lastPathSegment?.toLongOrNull() }
        if (selectedIndex >= 0) folderVideosRecycler.scrollToPosition(selectedIndex)
        folderVideosRecycler.visibility = if (folderVideos.isNotEmpty() && !drawerOpen) View.VISIBLE else View.GONE
    }

    private inner class FolderVideoAdapter : RecyclerView.Adapter<FolderVideoAdapter.Holder>() {
        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ImageView(this@MainActivity).apply {
                layoutParams = RecyclerView.LayoutParams(dp(72), dp(50)).apply { rightMargin = dp(7) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(7).toFloat())
                    }
                }
                clipToOutline = true
            }
            return Holder(image)
        }
        override fun getItemCount() = folderVideos.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val video = folderVideos[position]
            holder.image.setImageDrawable(null)
            holder.image.tag = video.uri
            val selected = video.uri == selectedVideo
            val border = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), if (selected) 0xFF2196F3.toInt() else 0x66888888)
            }
            holder.image.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            holder.image.foreground = border
            holder.image.clipToOutline = true
            holder.image.setOnClickListener {
                if (video.uri != selectedVideo) loadVideo(video.uri)
            }
            thumbnailExecutor.execute {
                try {
                    val bitmap = loadVideoThumbnail(video.uri, video.id, dp(180), dp(120))
                    runOnUiThread { if (!isFinishing && holder.image.tag == video.uri && bitmap != null) holder.image.setImageBitmap(bitmap) }
                } catch (_: Exception) { }
            }
        }
    }

    /** Load a thumbnail using MediaStore first, then decode a frame directly from the source.
     * Some devices/codecs do not provide MediaStore thumbnails for every indexed video.
     */
    private fun loadVideoThumbnail(uri: Uri, videoId: Long, width: Int, height: Int): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(uri, android.util.Size(width.coerceAtLeast(1), height.coerceAtLeast(1)), null)?.let { return it }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(contentResolver, videoId, MediaStore.Video.Thumbnails.MINI_KIND, null)?.let { return it }
            }
        } catch (_: Exception) { }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val frame = try {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) { null } ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun queryGalleryVideos(): List<GalleryVideo> {
        val result = mutableListOf<GalleryVideo>()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = if (Build.VERSION.SDK_INT >= 29) {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.MediaColumns.DATA
            )
        } else {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.MediaColumns.DATA
            )
        }
        val sort = MediaStore.Video.Media.DATE_ADDED + " DESC"
        try {
            contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val volumeColumn = if (Build.VERSION.SDK_INT >= 29) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                } else -1
                val albumColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val bucketColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                val pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Video"
                    val duration = cursor.getLong(durationColumn)
                    val album = if (albumColumn >= 0) cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: "Lainnya" else "Lainnya"
                    val itemUri = if (Build.VERSION.SDK_INT >= 29 && volumeColumn >= 0) {
                        val volumeName = cursor.getString(volumeColumn)
                        val volumeUri = MediaStore.Video.Media.getContentUri(volumeName)
                        android.content.ContentUris.withAppendedId(volumeUri, id)
                    } else {
                        android.content.ContentUris.withAppendedId(collection, id)
                    }
                    val bucketId = if (bucketColumn >= 0) cursor.getString(bucketColumn) ?: "" else ""
                    val filePath = if (pathColumn >= 0) cursor.getString(pathColumn) ?: "" else ""
                    result.add(GalleryVideo(itemUri, name, duration, id, album, bucketId, filePath))
                }
            }
        } catch (_: SecurityException) { }
        return result
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }



    private fun showVideoTrimDialog() {
        val uri = selectedVideo
        if (uri == null || !::videoPlayer.isInitialized || videoPlayer.mediaItemCount == 0) {
            android.widget.Toast.makeText(this, "Pilih video terlebih dahulu", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val duration = if (trimSourceDurationMs > 0L) trimSourceDurationMs else videoPlayer.duration
        if (duration <= 0L || duration == androidx.media3.common.C.TIME_UNSET) {
            android.widget.Toast.makeText(this, "Durasi video belum tersedia", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        trimSourceDurationMs = duration
        val initialStart = if (trimEnabled) trimStartMs.coerceIn(0L, duration - 1L) else 0L
        val initialEnd = if (trimEnabled) trimEndMs.coerceIn(initialStart + 1L, duration) else duration

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
            setBackgroundColor(0xFF171717.toInt())
        }
        content.addView(TextView(this).apply {
            text = "POTONG VIDEO"; textSize = 17f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(32)))

        val previewPlayer = ExoPlayer.Builder(this).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            seekTo(initialStart)
            playWhenReady = true
        }
        val preview = PlayerView(this).apply {
            player = previewPlayer
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(0xFF080808.toInt())
            setBackgroundColor(0xFF080808.toInt())
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(190)))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val startLabel = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 13f }
        val endLabel = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; gravity = Gravity.END }
        times.addView(startLabel, LinearLayout.LayoutParams(0, dp(30), 1f))
        times.addView(endLabel, LinearLayout.LayoutParams(0, dp(30), 1f))
        content.addView(times)

        val range = VideoRangeSlider(this, duration, initialStart, initialEnd).apply {
            onRangeChanged = { start, end ->
                startLabel.text = "Mulai  " + formatDuration(start)
                endLabel.text = "Akhir  " + formatDuration(end)
                if (isMovingStart) {
                    previewPlayer.seekTo(start)
                    previewPlayer.playWhenReady = true
                }
            }
        }
        startLabel.text = "Mulai  " + formatDuration(initialStart)
        endLabel.text = "Akhir  " + formatDuration(initialEnd)
        // Timeline and handles sit below the moving-frame preview, like a video editor.
        content.addView(range, LinearLayout.LayoutParams(-1, dp(58)))
        val dialog = AlertDialog.Builder(this).setView(content)
            .setPositiveButton("Terapkan", null).setNegativeButton("Batal", null)
            .setNeutralButton("Atur ulang", null).create()
        dialog.setOnDismissListener {
            previewPlayer.pause()
            preview.player = null
            previewPlayer.release()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                trimStartMs = range.startMs
                trimEndMs = range.endMs
                trimEnabled = trimStartMs > 0L || trimEndMs < duration
                if (trimEnabled) {
                    val mediaItem = MediaItem.fromUri(uri)
                    val source = DefaultMediaSourceFactory(this).createMediaSource(mediaItem)
                    val clipped = ClippingMediaSource(source, trimStartMs * 1000L, trimEndMs * 1000L)
                    videoPlayer.stop()
                    videoPlayer.clearMediaItems()
                    videoPlayer.setMediaSource(clipped)
                    videoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                    videoPlayer.prepare()
                    videoPlayer.seekTo(0L)
                    videoPlayer.playWhenReady = true
                    applyAudioRouting()
                } else {
                    videoPlayer.stop()
                    videoPlayer.clearMediaItems()
                    videoPlayer.setMediaItem(MediaItem.fromUri(uri))
                    videoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                    videoPlayer.prepare()
                    videoPlayer.playWhenReady = true
                    applyAudioRouting()
                }
                dialog.dismiss()
                android.widget.Toast.makeText(this,
                    if (trimEnabled) "Potongan diterapkan: " + formatDuration(trimStartMs) + " – " + formatDuration(trimEndMs) else "Potongan dinonaktifkan",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { range.setRange(0L, duration) }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun updateTrimPreview(uri: Uri, positionMs: Long, target: ImageView) {
        val tag = positionMs
        target.tag = tag
        thumbnailExecutor.execute {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val bitmap: Bitmap? = retriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                runOnUiThread {
                    if (target.tag == tag && bitmap != null) target.setImageBitmap(bitmap)
                }
            } catch (_: Exception) {
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }
        }
    }

    private inner class VideoRangeSlider(context: android.content.Context, private val maxDuration: Long, start: Long, end: Long) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt(); strokeWidth = dp(7).toFloat(); strokeCap = Paint.Cap.ROUND }
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF26A69A.toInt(); strokeWidth = dp(7).toFloat(); strokeCap = Paint.Cap.ROUND }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val thumbBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF26A69A.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        var startMs: Long = start; private set
        var endMs: Long = end; private set
        var onRangeChanged: ((Long, Long) -> Unit)? = null
        private val side = dp(18).toFloat()
        private fun xFor(value: Long): Float = side + (width - side * 2).coerceAtLeast(1f) * (value.toFloat() / maxDuration)
        private fun valueFor(x: Float): Long = (((x.coerceIn(side, width - side) - side) / (width - side * 2).coerceAtLeast(1f)) * maxDuration).toLong()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val y = height / 2f
            val left = xFor(startMs); val right = xFor(endMs)
            canvas.drawLine(side, y, width - side, y, trackPaint)
            canvas.drawLine(left, y, right, y, selectedPaint)
            canvas.drawCircle(left, y, dp(10).toFloat(), thumbPaint); canvas.drawCircle(left, y, dp(10).toFloat(), thumbBorder)
            canvas.drawCircle(right, y, dp(10).toFloat(), thumbPaint); canvas.drawCircle(right, y, dp(10).toFloat(), thumbBorder)
        }
        private var movingStart = true
        val isMovingStart: Boolean get() = movingStart
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    movingStart = kotlin.math.abs(event.x - xFor(startMs)) <= kotlin.math.abs(event.x - xFor(endMs))
                    parent?.requestDisallowInterceptTouchEvent(true); updateFromTouch(event.x); return true
                }
                MotionEvent.ACTION_MOVE -> { updateFromTouch(event.x); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    updateFromTouch(event.x); parent?.requestDisallowInterceptTouchEvent(false); performClick(); return true
                }
            }
            return true
        }
        private fun updateFromTouch(x: Float) {
            val value = valueFor(x)
            if (movingStart) startMs = value.coerceIn(0L, (endMs - 100L).coerceAtLeast(0L))
            else endMs = value.coerceIn((startMs + 100L).coerceAtMost(maxDuration), maxDuration)
            invalidate(); onRangeChanged?.invoke(startMs, endMs)
        }
        fun setRange(start: Long, end: Long) {
            startMs = start.coerceIn(0L, maxDuration - 1L)
            endMs = end.coerceIn(startMs + 1L, maxDuration)
            invalidate(); onRangeChanged?.invoke(startMs, endMs)
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    private fun loadVideo(uri: Uri) {
        try {
            // MediaStore URIs must be passed through unchanged; do not convert to file paths.
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
                ?: throw IllegalStateException("File video tidak dapat diakses.")
            selectedVideo = uri
            updateFolderVideoStrip(uri)
            trimEnabled = false
            trimSourceDurationMs = 0L
            trimStartMs = 0L
            trimEndMs = 0L
            videoPlayer.stop()
            videoPlayer.clearMediaItems()
            videoPlayer.setMediaItem(MediaItem.fromUri(uri))
            videoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            videoPlayer.prepare()
            videoPlayer.playWhenReady = true
            applyAudioRouting()
            videoStatus.text = displayName(uri)
            playerView.showController()
        } catch (error: Exception) {
            selectedVideo = null
            android.widget.Toast.makeText(
                this,
                "Gagal membuka video: " + (error.localizedMessage ?: "file tidak dapat diakses"),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun loadMusic(uri: Uri) {
        selectedMusic = uri
        musicPlayer.setMediaItem(MediaItem.fromUri(uri))
        musicPlayer.repeatMode = Player.REPEAT_MODE_ONE
        musicPlayer.volume = musicVolumeValue
        musicPlayer.prepare()
        if (stopMusicWhenVideoPaused && !videoPlayer.playWhenReady) musicPlayer.pause()
        else musicPlayer.play()
        applyAudioRouting()
        musicStatus.text = displayName(uri)
        removeMusicButton.isEnabled = true
    }

    private fun removeMusic() {
        selectedMusic = null
        if (audioChoice == 2) audioChoice = 0
        musicPlayer.stop()
        musicPlayer.clearMediaItems()
        applyAudioRouting()
        musicStatus.text = "Musik tambahan tidak aktif"
        removeMusicButton.isEnabled = false
    }

    private fun applyAudioRouting() {
        when (audioChoice) {
            1 -> { videoPlayer.volume = 1f; musicPlayer.volume = 0f }
            2 -> { videoPlayer.volume = 0f; musicPlayer.volume = musicVolumeValue }
            3 -> { videoPlayer.volume = 0f; musicPlayer.volume = 0f }
            else -> {
                videoPlayer.volume = if (selectedMusic != null) 0f else 1f
                musicPlayer.volume = if (selectedMusic != null) musicVolumeValue else 0f
            }
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun displayName(uri: Uri): String {
        val name = try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
        return name ?: (uri.lastPathSegment ?: "File dipilih")
    }

    private fun makeButton(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(0xFFF4F5F7.toInt())
            setOnClickListener { action() }
        }

    private fun makeStatus(value: String) =
        TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(0xFFBFC3CC.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun makeRoundedBackground(color: Int, radiusDp: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        super.onStop()
        videoPlayer.pause()
        musicPlayer.pause()
    }

    override fun onDestroy() {
        playerView.player = null
        videoPlayer.release()
        musicPlayer.release()
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }
}
