package com.onlinemsg.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.onlinemsg.client.MainActivity
import com.onlinemsg.client.ui.ChatSessionManager
import com.onlinemsg.client.ui.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ChatForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ChatSessionManager.initialize(application)
        ensureForegroundChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ChatSessionManager.onForegroundServiceStopped()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    buildForegroundNotification(ChatSessionManager.uiState.value.status, ChatSessionManager.uiState.value.statusHint)
                )
                observeStatusAndRefreshNotification()
                return START_STICKY
            }

            else -> return START_STICKY
        }
    }

    override fun onDestroy() {
        statusJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeStatusAndRefreshNotification() {
        if (statusJob != null) return
        statusJob = serviceScope.launch {
            ChatSessionManager.uiState
                .map { it.status to it.statusHint }
                .distinctUntilChanged()
                .collect { (status, hint) ->
                    NotificationManagerCompat.from(this@ChatForegroundService).notify(
                        FOREGROUND_NOTIFICATION_ID,
                        buildForegroundNotification(status, hint)
                    )
                    if (status == ConnectionStatus.IDLE && !ChatSessionManager.shouldForegroundServiceRun()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
        }
    }

    private fun buildForegroundNotification(status: ConnectionStatus, hint: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ChatForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (status) {
            ConnectionStatus.READY -> "OnlineMsg 已保持连接"
            ConnectionStatus.CONNECTING,
            ConnectionStatus.HANDSHAKING,
            ConnectionStatus.AUTHENTICATING -> "OnlineMsg 正在连接"
            ConnectionStatus.ERROR -> "OnlineMsg 连接异常"
            ConnectionStatus.IDLE -> "OnlineMsg 后台服务"
        }

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(hint.ifBlank { "后台保持连接中" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "断开", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "OnlineMsg 后台连接",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 WebSocket 后台长连接"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START = "com.onlinemsg.client.action.START_FOREGROUND_CHAT"
        private const val ACTION_STOP = "com.onlinemsg.client.action.STOP_FOREGROUND_CHAT"
        private const val FOREGROUND_CHANNEL_ID = "onlinemsg_foreground"
        private const val FOREGROUND_NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ChatForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ChatForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
