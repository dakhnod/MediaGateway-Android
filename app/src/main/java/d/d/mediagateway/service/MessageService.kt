package d.d.mediagateway.service

import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import d.d.mediagateway.R
import java.lang.Exception

class MessageService : FirebaseMessagingService() {
    private val TAG = "MessageService"

    companion object{
        const val MESSAGE_TYPE_PLAYBACK_CHANGED = "d.d.MediaGateway.PLAYBACK_CHANGED"
        const val MESSAGE_TYPE_PING             = "d.d.MediaGateway.PING"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: ")

    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: ")
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val msg = getString(R.string.msg_token_fmt, token)
        Log.d(TAG, "onNewToken: $msg")
    }

    override fun onMessageSent(msgId: String) {
        super.onMessageSent(msgId)
        Log.d(TAG, "onMessageSent: $msgId")
    }

    override fun onSendError(msgId: String, exception: Exception) {
        super.onSendError(msgId, exception)
        exception.printStackTrace()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "onMessageReceived: priority: ${message.messageId}")
        val data = message.data
        val type = data["type"]
        val intent = Intent(this, PlayerService::class.java)

        intent.action = when(type){
            "player_state" -> MESSAGE_TYPE_PLAYBACK_CHANGED
            "ping" -> MESSAGE_TYPE_PING
            else -> return
        }
        for (key in data.keys) {
            intent.putExtra(key, data[key])
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "onMessageReceived: starting service")
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}