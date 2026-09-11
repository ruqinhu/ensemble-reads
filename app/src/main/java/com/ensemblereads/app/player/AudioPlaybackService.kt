package com.ensemblereads.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
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

/**
 * 前台朗读播放服务（Media3）。currentSegment 暴露当前段的 **segIndex**（而非播放列表位置），
 * 供 UI 按真实段号高亮跟读；支持边合成边追加段文件；播完自动停止。
 */
class AudioPlaybackService : Service() {
    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.ensemblereads.app.player.STOP"

        /** 当前播放段的 segIndex；-1 表示未播放。 */
        val currentSegment: MutableStateFlow<Int> = MutableStateFlow(-1)

        /** 前台服务实例（onCreate 时设置，onDestroy 清空）。 */
        @Volatile var instance: AudioPlaybackService? = null

        @Volatile private var speed = 1f
        fun currentSpeed(): Float = speed
    }

    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** media item 位置 → segIndex 映射，保证高亮按真实段号对齐。 */
    private val segIndexByMedia = mutableListOf<Int>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentSegment.value = segIndexByMedia.getOrElse(player.currentMediaItemIndex) { -1 }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && currentSegment.value < 0) {
                    currentSegment.value = segIndexByMedia.getOrElse(player.currentMediaItemIndex) { -1 }
                }
                if (state == Player.STATE_ENDED) {
                    currentSegment.value = -1
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        })
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("准备播放"))
    }

    /** 从头播放一组段文件；startIndex 为 files/segIndexes 内的起始位置。 */
    fun play(files: List<File>, segIndexes: List<Int>, startIndex: Int = 0) {
        segIndexByMedia.clear()
        segIndexByMedia.addAll(segIndexes)
        val items = files.map { MediaItem.fromUri(Uri.fromFile(it)) }
        player.setMediaItems(items, startIndex, 0L)
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        player.play()
        currentSegment.value = segIndexes.getOrElse(startIndex) { -1 }
    }

    /** 边合成边追加段文件；若当前未在播放则立即开始。 */
    fun appendMedia(files: List<File>, segIndexes: List<Int>) {
        if (files.isEmpty() || files.size != segIndexes.size) return
        val items = files.map { MediaItem.fromUri(Uri.fromFile(it)) }
        when (player.playbackState) {
            Player.STATE_IDLE -> {
                segIndexByMedia.clear()
                segIndexByMedia.addAll(segIndexes)
                player.setMediaItems(items, 0, 0L)
                player.prepare()
                player.play()
                currentSegment.value = segIndexes[0]
            }
            Player.STATE_ENDED -> {
                val startMedia = segIndexByMedia.size
                segIndexByMedia.addAll(segIndexes)
                player.addMediaItems(items)
                player.seekTo(startMedia, 0L)
                player.play()
            }
            else -> {
                val startMedia = segIndexByMedia.size
                segIndexByMedia.addAll(segIndexes)
                player.addMediaItems(startMedia, items)
            }
        }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

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

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, AudioPlaybackService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("多角色朗读")
            .setContentText(text)
            .setOngoing(true)
            .addAction(R.drawable.ic_play, "停止", stopPi)
            .build()
    }
}
