package com.ensemblereads.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.ensemblereads.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 前台朗读播放服务（Media3）。currentSegment 供 UI 高亮跟读。 */
class AudioPlaybackService : Service() {
    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1

        /** 当前播放段索引；-1 表示未播放。 */
        val currentSegment: MutableStateFlow<Int> = MutableStateFlow(-1)

        /** 前台服务实例（onCreate 时设置，onDestroy 清空）。 */
        @Volatile var instance: AudioPlaybackService? = null

        @Volatile private var speed = 1f
        fun currentSpeed(): Float = speed
    }

    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        player = ExoPlayer.Builder(this).build()
        // 监听段切换 → currentSegment（用于高亮）
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentSegment.value = player.currentMediaItemIndex
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY && currentSegment.value < 0) {
                    currentSegment.value = player.currentMediaItemIndex
                }
            }
        })
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("准备播放"))
    }

    /** 按段文件列表播放，从 startIndex 段开始。 */
    fun play(files: List<File>, startIndex: Int = 0) {
        val items = files.map { MediaItem.fromUri(Uri.fromFile(it)) }
        player.setMediaItems(items, startIndex, 0L)
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        player.play()
        currentSegment.value = startIndex
    }

    fun setSpeed(s: Float) {
        speed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    fun pause() { player.pause() }
    fun resume() { player.play() }
    fun next() { player.seekToNextMediaItem() }
    fun prev() { player.seekToPreviousMediaItem() }

    /** 睡眠定时：minutes 后暂停播放。0 或负表示取消。 */
    fun sleepTimer(minutes: Int) {
        scope.launch {
            if (minutes <= 0) return@launch
            delay(minutes * 60_000L)
            player.pause()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        player.release()
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "朗读播放", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("多角色朗读")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
