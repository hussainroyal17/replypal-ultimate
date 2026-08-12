package com.replypal.ultimate.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.replypal.ultimate.MainActivity
import com.replypal.ultimate.R
import com.replypal.ultimate.data.SettingsManager
import com.replypal.ultimate.data.UserPreferences
import com.replypal.ultimate.domain.ConversationProfile
import com.replypal.ultimate.domain.GenerationRequest
import com.replypal.ultimate.domain.LocalReplyProvider
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FloatingAssistantService : Service() {
    private val scope = MainScope()
    private lateinit var windowManager: WindowManager
    private lateinit var settingsManager: SettingsManager
    private var bubble: View? = null
    private var panel: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(71, notification())
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        scope.launch { showBubble(settingsManager.preferences.first()) }
    }

    private fun showBubble(preferences: UserPreferences) {
        val size = preferences.bubbleSizeDp.dp
        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.bubbleX
            y = preferences.bubbleY
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_replypal)
            contentDescription = "Open ReplyPal quick assistant"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(2.dp, Color.WHITE)
            }
            elevation = 16.dp.toFloat()
        }
        var downX = 0
        var downY = 0
        var startX = 0
        var startY = 0
        icon.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX.toInt(); downY = event.rawY.toInt()
                    startX = bubbleParams?.x ?: 0; startY = bubbleParams?.y ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    bubbleParams?.let { params ->
                        params.x = startX + (event.rawX.toInt() - downX)
                        params.y = startY + (event.rawY.toInt() - downY)
                        windowManager.updateViewLayout(icon, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX.toInt() - downX) + kotlin.math.abs(event.rawY.toInt() - downY) > 12
                    bubbleParams?.let { scope.launch { settingsManager.setBubblePosition(it.x, it.y) } }
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }
        bubble = icon
        windowManager.addView(icon, bubbleParams)
    }

    private fun togglePanel() {
        if (panel != null) { removePanel(); return }
        val textInput = EditText(this).apply {
            hint = "Paste the latest message…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(205, 198, 255))
            setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            background = rounded(Color.rgb(42, 28, 78), 14.dp)
            minLines = 2
            maxLines = 4
        }
        val response = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            text = "Paste a message, then tap Suggest."
        }
        val suggest = Button(this).apply { text = "Suggest" }
        val close = Button(this).apply { text = "Minimize" }
        val openApp = Button(this).apply { text = "Open app" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            background = rounded(Color.rgb(29, 18, 56), 24.dp)
            elevation = 20.dp.toFloat()
            addView(TextView(this@FloatingAssistantService).apply {
                text = "ReplyPal  •  Quick reply"
                setTextColor(Color.WHITE); textSize = 17f; setPadding(4.dp, 0, 4.dp, 12.dp)
            })
            addView(textInput, LinearLayout.LayoutParams(280.dp, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(suggest, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
            addView(response, LinearLayout.LayoutParams(280.dp, LinearLayout.LayoutParams.WRAP_CONTENT))
            val footer = LinearLayout(this@FloatingAssistantService).apply { orientation = LinearLayout.HORIZONTAL; addView(openApp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); addView(close, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            addView(footer)
        }
        suggest.setOnClickListener {
            val message = textInput.text.toString().trim()
            if (message.isBlank()) { response.text = "Paste a message first."; return@setOnClickListener }
            response.text = "Thinking…"
            scope.launch {
                val pref = settingsManager.preferences.first()
                val result = LocalReplyProvider().generate(GenerationRequest(0L, "Quick reply", emptyList(), message, ConversationProfile("Quick reply only; choose a saved chat in the main app for long-term memory.", "", pref.language, "Auto", "Low", "Auto", pref.emojiLevel, "Getting to know each other"), pref))
                val reply = result.suggestions.values.firstOrNull()?.firstOrNull().orEmpty()
                response.text = "$reply\n\nTap here to copy"
                response.setOnClickListener { copy(reply); response.text = "Copied — paste it in your chat." }
            }
        }
        close.setOnClickListener { removePanel() }
        openApp.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            removePanel()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24.dp; y = 120.dp }
        panel = box
        windowManager.addView(box, params)
    }

    private fun removePanel() { panel?.let { windowManager.removeView(it) }; panel = null }
    private fun copy(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ReplyPal suggestion", value))
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("replypal_overlay", "Floating Assistant", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
    private fun notification() = NotificationCompat.Builder(this, "replypal_overlay")
        .setSmallIcon(R.drawable.ic_replypal)
        .setContentTitle("ReplyPal floating assistant")
        .setContentText("Tap the bubble for quick reply ideas. You can turn it off in Settings.")
        .setOngoing(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { removePanel(); bubble?.let { windowManager.removeView(it) }; scope.cancel(); super.onDestroy() }
}
