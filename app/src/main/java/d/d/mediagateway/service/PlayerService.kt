package d.d.mediagateway.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.ktx.messaging
import com.google.firebase.messaging.ktx.remoteMessage
import d.d.mediagateway.BuildConfig
import d.d.mediagateway.R
import java.util.*


class PlayerService : Service() {
    val NOTIFICATION_CHANNEL_ID = "player"

    private val TAG = "PlayerService"
    private val playbackStateBuilder = PlaybackState.Builder()
    private var mediaSession: MediaSession? = null
    var notification: Notification? = null

    val ACTION_PLAY = "action_play"
    val ACTION_PAUSE = "action_pause"
    val ACTION_REWIND = "action_rewind"
    val ACTION_FAST_FORWARD = "action_fast_foward"
    val ACTION_NEXT = "action_next"
    val ACTION_PREVIOUS = "action_previous"
    val ACTION_STOP = "action_stop"

    val TIMEOUT_SELF_STOP: Long = 25 * 60 * 1000

    var handler: Handler? = null

    val stopRunnable = Runnable {
        Log.d(TAG, "stopping service: ")
        stopSelf()
        mediaSession?.release()
    }

    var isPlaying = false

    val keyReceiver = object: BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            Log.d(TAG, "onReceive: key")
            val event = p1?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if(event == null){
                Log.d(TAG, "onReceive: keyEvent empty")
                return
            }
            if(event.action != KeyEvent.ACTION_DOWN){
                return
            }
            Log.d(TAG, "onReceive: $event")
            handleKeyCode(event.keyCode)
        }
    }

    private fun handleKeyCode(keyCode: Int){
        when(keyCode){
            KeyEvent.KEYCODE_MEDIA_PLAY -> play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playPrevious()
            KeyEvent.KEYCODE_MEDIA_NEXT -> playNext()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if(isPlaying) pause()
                else play()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        TODO("Return the communication channel to the service.")
    }

    private fun generateAction(
        icon: Int,
        title: String,
        intentAction: String
    ): Notification.Action? {
        val intent = Intent(applicationContext, PlayerService::class.java)
        intent.action = intentAction
        val pendingIntent = PendingIntent.getService(applicationContext, 0, intent, 0)
        return Notification.Action.Builder(icon, title, pendingIntent).build()
    }

    private fun buildNotification(is_playing: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val playPauseAction = when (is_playing) {
            true -> generateAction(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE)
            false -> generateAction(android.R.drawable.ic_media_play, "Play", ACTION_PLAY)
        }

        builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(
                generateAction(
                    android.R.drawable.ic_media_previous,
                    "Previous",
                    ACTION_PREVIOUS
                )
            )
            .addAction(playPauseAction)
            .addAction(generateAction(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))


        builder.style = Notification.MediaStyle()
            .setMediaSession(mediaSession!!.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return builder.build()
    }

    private fun restartSelfTimer(){
        handler?.removeCallbacks(stopRunnable)
        handler?.postDelayed(stopRunnable, TIMEOUT_SELF_STOP)
    }

    private fun playDummySound(){
        Log.d(TAG, "playDummySound: playing dummy sound")
        val bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_8BIT)
        Log.d(TAG, "playDummySound: $bufferSize")
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_8BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.write(ByteArray(bufferSize), 0, 1)
        track.play()
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate: ")

        mediaSession = MediaSession(this, "player")
        mediaSession!!.isActive = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            mediaSession!!.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
        mediaSession!!.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        playbackStateBuilder
            .setState(PlaybackState.STATE_CONNECTING, 0, 1.0f)
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
        mediaSession!!.setPlaybackState(playbackStateBuilder.build())
        mediaSession!!.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                Log.d(TAG, "onMediaButtonEvent: ")
                val event =
                    mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                handleKeyCode(event.keyCode)
                return true
            }
        })
        val metadata = MediaMetadata.Builder()
            .putText(MediaMetadata.METADATA_KEY_TITLE, "-")
            .putText(MediaMetadata.METADATA_KEY_ARTIST, "-")
            .build()
        mediaSession!!.setMetadata(metadata)

        registerNotificationChannel()

        notification = buildNotification(false)
        startForeground(1, notification)

        handler = Handler()
        restartSelfTimer()
        playDummySound()

        registerReceiver(keyReceiver, IntentFilter(Intent.ACTION_MEDIA_BUTTON))
    }

    companion object {
        private val ALLOWED_CHARACTERS = "0123456789qwertyuiopasdfghjklzxcvbnm"
    }

    private fun getRandomString(sizeOfRandomString: Int): String {
        val random = Random()
        val sb = StringBuilder(sizeOfRandomString)
        for (i in 0 until sizeOfRandomString)
            sb.append(ALLOWED_CHARACTERS[random.nextInt(ALLOWED_CHARACTERS.length)])
        return sb.toString()
    }

    private fun sendUpstreamCommand(command: String){
        Log.d(TAG, "sendUpstreamCommand: sending message $command")
        val message = remoteMessage("${BuildConfig.SENDER_ID}@fcm.googleapis.com"){
            setMessageId(getRandomString(20))
            setTtl(20)
            setData(mapOf(Pair("media_command", command)))
        }
        Firebase.messaging.send(message)
    }

    private fun playPrevious() {
        Log.d(TAG, "playPrevious: ")
        sendUpstreamCommand("PREVIOUS")
    }

    private fun playNext() {
        Log.d(TAG, "playNext: ")
        sendUpstreamCommand("NEXT")
    }

    private fun setPlayingState(is_playing: Boolean){
        isPlaying = is_playing
        startForeground(1, buildNotification(is_playing))
    }

    private fun play() {
        Log.d(TAG, "play: ")
        sendUpstreamCommand("PLAY")
    }

    private fun pause() {
        Log.d(TAG, "pause: ")
        sendUpstreamCommand("PAUSE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent!!.action}")
        when (intent.action) {
            MessageService.MESSAGE_TYPE_PLAYBACK_CHANGED -> {
                if (intent.hasExtra("playbackState")) {
                    val playbackState = intent.getStringExtra("playbackState")
                    val newState = when (playbackState!!.lowercase()) {
                        "playing" -> PlaybackState.STATE_PLAYING
                        "paused" -> PlaybackState.STATE_PAUSED
                        "stopped" -> PlaybackState.STATE_STOPPED
                        else -> PlaybackState.STATE_ERROR
                    }
                    Log.d(TAG, "onStartCommand: new state: $newState")
                    mediaSession!!.setPlaybackState(
                        playbackStateBuilder
                            .setState(newState, 0, 1.0f)
                            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                            .build()
                    )
                    setPlayingState(
                        newState == PlaybackState.STATE_PLAYING
                    )
                    Log.d(TAG, "onStartCommand: $playbackState")
                }
                if(intent.hasExtra("artist") && intent.hasExtra("title")){
                    val metadata = MediaMetadata.Builder()
                        .putText(MediaMetadata.METADATA_KEY_TITLE, intent.getStringExtra("title")!!)
                        .putText(MediaMetadata.METADATA_KEY_ARTIST, intent.getStringExtra("artist")!!)
                    mediaSession!!.setMetadata(metadata.build())
                }
            }
            ACTION_PREVIOUS -> playPrevious()
            ACTION_NEXT -> playNext()
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            MessageService.MESSAGE_TYPE_PING -> handlePing()
        }
        // playDummySound()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: ")
        unregisterReceiver(keyReceiver)
    }

    private fun handlePing(){
        Log.d(TAG, "handlePing: received ping")
        restartSelfTimer()
    }

    private fun registerNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Player notifications",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }
}