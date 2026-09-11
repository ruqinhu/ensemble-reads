package com.ensemblereads.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ensemblereads.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        const val ACTION_PLAY = "com.ensemblereads.app.player.PLAY"
        const val ACTION_PAUSE = "com.ensemblereads.app.player.PAUSE"

        /** 当前播放段的 segIndex；-1 表示未播放。 */
        val currentSegment: MutableStateFlow<Int> = MutableStateFlow(-1)

        /** 前台服务实例（onCreate 时设置，onDestroy 清空）。 */
        @Volatile var instance: AudioPlaybackService? = null

        /** 整章是否已合成完成：为 false 时播放列表临时耗尽不能停服务（边合成边播）。 */
        @Volatile var synthesisDone = false

        @Volatile private var speed = 1f
        fun currentSpeed(): Float = speed
    }

    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** media item 位置 → segIndex 映射，保证高亮按真实段号对齐。 */
    private val segIndexByMedia = mutableListOf<Int>()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
                    // 仅在整章合成完成后才停止：边合成边播时列表临时耗尽不能杀服务
                    if (synthesisDone) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        })
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("准备播放"))
    }

    /** 整章合成完成：若此刻已播完则停止服务；否则等 ENDED 后再停。 */
    fun notifySynthesisDone() {
        synthesisDone = true
        if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 从头播放一组段文件；startIndex 为 files/segIndexes 内的起始位置。 */
    fun play(files: List<File>, segIndexes: List<Int>, startIndex: Int = 0) {
        synthesisDone = false
        requestAudioResources()
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
                requestAudioResources()
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

    /** 播放前申请音频焦点 + 部分唤醒锁（息屏/Doze 下后台朗读不被中断）。 */
    private fun requestAudioResources() {
        val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build().also { audioFocusRequest = it }
        audioManager.requestAudioFocus(req)
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnsembleReads:playback")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(30 * 60 * 1000L) // 最长 30 分钟，防异常场景泄漏
    }

    private fun releaseAudioResources() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    fun setSpeed(s: Float) {
        speed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    fun pause() { player.pause() }
    fun resume() { player.play() }
    fun next() { player.seekToNextMediaItem() }
    fun prev() { player.seekToPreviousMediaItem() }

    private var sleepJob: Job? = null

    /** 睡眠定时：minutes 后暂停播放。重复调用会先取消旧的。0 或负表示取消。 */
    fun sleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) return
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            player.pause()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PLAY -> player.play()
            ACTION_PAUSE -> player.pause()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        releaseAudioResources()
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
            .addAction(R.drawable.ic_play, "播放", actionPendingIntent(ACTION_PLAY))
            .addAction(R.drawable.ic_play, "暂停", actionPendingIntent(ACTION_PAUSE))
            .addAction(R.drawable.ic_play, "停止", actionPendingIntent(ACTION_STOP))
            .build()

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
