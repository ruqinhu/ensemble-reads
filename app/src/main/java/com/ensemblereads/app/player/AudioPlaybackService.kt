package com.ensemblereads.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
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

        /** 睡眠定时剩余毫秒；0 表示未设/已到点。供 UI 倒计时。 */
        val remainingMs: MutableStateFlow<Long> = MutableStateFlow(0L)

        /** 前台服务实例（onCreate 时设置，onDestroy 清空）。 */
        @Volatile var instance: AudioPlaybackService? = null

        /** 整章是否已合成完成：为 false 时播放列表临时耗尽不能停服务（边合成边播）。 */
        @Volatile var synthesisDone = false

        /** 睡眠定时「播完本章停止」：ENDED 时暂停而非停止服务。 */
        @Volatile var sleepAfterChapter = false

        @Volatile private var speed = 1f
        fun currentSpeed(): Float = speed
    }

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var audioManager: AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** media item 位置 → segIndex 映射，保证高亮按真实段号对齐。 */
    private val segIndexByMedia = mutableListOf<Int>()

    /** media item 位置 → 段音频实测时长(ms)，由调用方合成时传入；未知为 0。供章内累计位置。 */
    private val durationByMedia = mutableListOf<Long>()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 因焦点丢失而暂停：恢复焦点(Gain)时才自动续播，用户手动暂停不受影响。 */
    private var resumeOnFocusGain = false

    /** 音频焦点三态处理：来电/导航抢占时暂停，短暂丢失可自动恢复，不因外部抢占一直外放。 */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { state ->
        when (state) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                player.volume = 1f
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    player.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = true
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.volume = 0.2f
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                player.pause()
                releaseAudioResources()
            }
        }
    }

    /** 拔耳机（AUDIO_BECOMING_NOISY）自动暂停，避免漏音外放。 */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause()
        }
    }

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
                    // 「播完本章停止」：到章尾暂停但保留服务
                    if (sleepAfterChapter) {
                        sleepAfterChapter = false
                        player.pause()
                        return
                    }
                    // 仅在整章合成完成后才停止：边合成边播时列表临时耗尽不能杀服务
                    if (synthesisDone) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                updateNotification()
            }
        })
        // MediaSession：锁屏卡片 / 蓝牙耳机媒体键 / 系统媒体控制走这里（MediaStyle 通知关联）
        mediaSession = MediaSession.Builder(this, player).build()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // 拔耳机自动暂停（听书场景高频诉求：蓝牙耳机断开不应继续外放）
        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
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

    /** 从头播放一组段文件；durations 与 files 对齐（每段实测时长，未知传 0）；startIndex 为起始位置。 */
    fun play(files: List<File>, segIndexes: List<Int>, durations: List<Long> = emptyList(), startIndex: Int = 0) {
        synthesisDone = false
        requestAudioResources()
        segIndexByMedia.clear()
        segIndexByMedia.addAll(segIndexes)
        durationByMedia.clear()
        durationByMedia.addAll(durations)
        val items = files.map { MediaItem.fromUri(Uri.fromFile(it)) }
        player.setMediaItems(items, startIndex, 0L)
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        player.play()
        currentSegment.value = segIndexes.getOrElse(startIndex) { -1 }
    }

    /** 边合成边追加段文件；durations 与 files 对齐。若当前未在播放则立即开始。 */
    fun appendMedia(files: List<File>, segIndexes: List<Int>, durations: List<Long> = emptyList()) {
        if (files.isEmpty() || files.size != segIndexes.size) return
        val items = files.map { MediaItem.fromUri(Uri.fromFile(it)) }
        when (player.playbackState) {
            Player.STATE_IDLE -> {
                requestAudioResources()
                segIndexByMedia.clear()
                segIndexByMedia.addAll(segIndexes)
                durationByMedia.clear()
                durationByMedia.addAll(durations)
                player.setMediaItems(items, 0, 0L)
                player.prepare()
                player.play()
                currentSegment.value = segIndexes[0]
            }
            Player.STATE_ENDED -> {
                val startMedia = segIndexByMedia.size
                segIndexByMedia.addAll(segIndexes)
                durationByMedia.addAll(durations)
                player.addMediaItems(items)
                player.seekTo(startMedia, 0L)
                player.play()
            }
            else -> {
                val startMedia = segIndexByMedia.size
                segIndexByMedia.addAll(segIndexes)
                durationByMedia.addAll(durations)
                player.addMediaItems(startMedia, items)
            }
        }
    }

    /** 切书/切章前重置播放状态：暂停、清空播放列表与段映射，防止旧章残留拼接进新章。 */
    fun resetForChapter() {
        player.pause()
        player.clearMediaItems()
        segIndexByMedia.clear()
        durationByMedia.clear()
        currentSegment.value = -1
        synthesisDone = false
    }

    /** 当前章内累计播放位置(ms)：已播完各段时长(合成时传入) + 当前段内偏移。供进度条。 */
    fun chapterPositionMs(): Long {
        val idx = player.currentMediaItemIndex
        if (idx < 0 || idx >= segIndexByMedia.size) return 0
        var acc = 0L
        for (i in 0 until idx) acc += durationByMedia.getOrElse(i) { 0L }.coerceAtLeast(0)
        return acc + player.currentPosition.coerceAtLeast(0)
    }

    /** 在当前播放列表内按段号 seek：返回是否命中（目标段未合成/不在列表时由调用方重开）。 */
    fun seekToSeg(segIndex: Int, offsetMs: Long): Boolean {
        val media = segIndexByMedia.indexOf(segIndex)
        if (media < 0) return false
        player.seekTo(media, offsetMs.coerceAtLeast(0))
        currentSegment.value = segIndex
        return true
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
            .setOnAudioFocusChangeListener(focusListener)
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
    /** 当前是否正在播放（供阅读统计累计收听时长）。 */
    fun isPlaying(): Boolean = player.isPlaying

    private var sleepJob: Job? = null

    /** 睡眠定时：minutes 后暂停播放，剩余时间实时写入 remainingMs 供 UI 倒计时。重复调用先取消旧的；0 取消。 */
    fun sleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepAfterChapter = false
        remainingMs.value = 0
        if (minutes <= 0) return
        remainingMs.value = minutes * 60_000L
        sleepJob = scope.launch {
            while (remainingMs.value > 0) {
                delay(1000)
                remainingMs.value = (remainingMs.value - 1000).coerceAtLeast(0)
                if (remainingMs.value <= 0) player.pause()
            }
        }
    }

    /** 睡眠定时「播完本章停止」：到章尾暂停（见 ENDED 处理），不清播放列表。 */
    fun sleepAfterChapterMode() {
        sleepJob?.cancel()
        sleepAfterChapter = true
        remainingMs.value = -1L // -1 表示「本章结束」模式
    }

    /** 取消睡眠定时。 */
    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepAfterChapter = false
        remainingMs.value = 0
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
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
        scope.cancel()
        releaseAudioResources()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "朗读播放", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** 当前播放的书名 · 章节名（写入媒体通知标题）。 */
    private var mediaTitle: String? = null

    /** 媒体通知：MediaStyle + MediaSession → 锁屏卡片/媒体键/车载控制生效。 */
    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(mediaTitle ?: "多角色朗读")
            .setContentText(text)
            .setOngoing(true)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .addAction(R.drawable.ic_play, "播放", actionPendingIntent(ACTION_PLAY))
            .addAction(R.drawable.ic_play, "暂停", actionPendingIntent(ACTION_PAUSE))
            .addAction(R.drawable.ic_play, "停止", actionPendingIntent(ACTION_STOP))
            .build()

    /** 播放状态变化时刷新通知（标题/正文随播放暂停切换）。 */
    private fun updateNotification() {
        val playing = player.playbackState == Player.STATE_READY && player.playWhenReady
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(if (playing) "正在朗读" else "已暂停"))
        }
    }

    /** 写入锁屏/媒体通知标题（书名 · 章节名）。MediaStyle 锁屏媒体卡直接显示通知标题。 */
    fun updateMediaMetadata(bookTitle: String, chapterTitle: String) {
        mediaTitle = "$bookTitle · $chapterTitle"
        updateNotification()
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
