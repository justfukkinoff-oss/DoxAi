package io.github.artcraftandroid.wordcraft.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** The foreground service that hosts [DoxAiMcpServer] while the setting is on. */
class McpService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        DoxAiMcpServer.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        DoxAiMcpServer.stop()
        super.onDestroy()
    }

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Agent server", NotificationManager.IMPORTANCE_LOW),
            )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("DoxAi agent server")
            .setContentText("MCP endpoint http://127.0.0.1:${DoxAiMcpServer.PORT}/mcp")
            .setOngoing(true)
            .build()
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val CHANNEL = "mcp"
        private const val ID = 42

        fun setRunning(context: Context, on: Boolean) {
            val intent = Intent(context, McpService::class.java)
            if (on) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
