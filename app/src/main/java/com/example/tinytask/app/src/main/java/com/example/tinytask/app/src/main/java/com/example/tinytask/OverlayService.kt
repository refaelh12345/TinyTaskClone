package com.example.tinytask

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var controlPanel: LinearLayout? = null
    private var captureView: View? = null

    private var isRecording = false
    private var recordingStartTime = 0L
    private var strokeStartTime = 0L
    private var currentPoints = mutableListOf<TouchPoint>()
    private var strokes = mutableListOf<RecordedStroke>()

    private val macroFile: File by lazy { File(filesDir, "macro.json") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        addControlPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeCaptureView()
        controlPanel?.let { runCatching { windowManager.removeView(it) } }
        controlPanel = null
    }

    private fun startForegroundNotification() {
        val channelId = "tinytask_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "TinyTask Overlay", NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, OverlayService::class.java)
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TinyTask Clone פעיל")
            .setContentText("הכפתורים הצפים פעילים")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingStop)
            .build()
        startForeground(1, notification)
    }

    // ---------- Floating control panel ----------

    private fun addControlPanel() {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.setBackgroundColor(Color.parseColor("#CC222222"))

        val recordBtn = Button(this).apply { text = "⏺" }
        val playBtn = Button(this).apply { text = "▶" }
        val saveBtn = Button(this).apply { text = "💾" }
        val loadBtn = Button(this).apply { text = "📂" }
        val closeBtn = Button(this).apply { text = "✕" }

        for (b in listOf(recordBtn, playBtn, saveBtn, loadBtn, closeBtn)) {
            panel.addView(b)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        // Make the panel draggable.
        panel.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchStartX = 0f
            var touchStartY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchStartX).toInt()
                        params.y = startY + (event.rawY - touchStartY).toInt()
                        windowManager.updateViewLayout(panel, params)
                        return true
                    }
                }
                return false
            }
        })

        recordBtn.setOnClickListener {
            if (!isRecording) startRecording(recordBtn) else stopRecording(recordBtn)
        }
        playBtn.setOnClickListener { playMacro() }
        saveBtn.setOnClickListener { saveMacro() }
        loadBtn.setOnClickListener { loadMacro() }
        closeBtn.setOnClickListener { stopSelf() }

        windowManager.addView(panel, params)
        controlPanel = panel
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    // ---------- Recording ----------

    private fun startRecording(recordBtn: Button) {
        strokes = mutableListOf()
        isRecording = true
        recordingStartTime = SystemClock.uptimeMillis()
        recordBtn.text = "⏹"
        addCaptureView()
        toast("מקליט... בצע את הפעולות")
    }

    private fun stopRecording(recordBtn: Button) {
        isRecording = false
        recordBtn.text = "⏺"
        removeCaptureView()
        toast("הקלטה הסתיימה: ${strokes.size} מגעים")
    }

    private fun addCaptureView() {
        val view = View(this)
        view.setBackgroundColor(Color.TRANSPARENT)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        view.setOnTouchListener { _, event -> handleCaptureTouch(event) }

        windowManager.addView(view, params)
        captureView = view
    }

    private fun removeCaptureView() {
        captureView?.let { runCatching { windowManager.removeView(it) } }
        captureView = null
    }

    private fun handleCaptureTouch(event: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeStartTime = now
                currentPoints = mutableListOf(TouchPoint(0, event.rawX, event.rawY))
            }
            MotionEvent.ACTION_MOVE -> {
                currentPoints.add(TouchPoint(now - strokeStartTime, event.rawX, event.rawY))
            }
            MotionEvent.ACTION_UP -> {
                currentPoints.add(TouchPoint(now - strokeStartTime, event.rawX, event.rawY))
                val strokeCopy = currentPoints.toList()
                strokes.add(RecordedStroke(strokeStartTime - recordingStartTime, strokeCopy))
                // Forward the touch to the real app underneath, since this overlay consumed it.
                RecorderAccessibilityService.instance?.dispatchImmediate(strokeCopy)
            }
        }
        return true
    }

    // ---------- Playback / persistence ----------

    private fun playMacro() {
        if (strokes.isEmpty()) {
            toast("אין הקלטה. הקלט פעולה קודם.")
            return
        }
        val service = RecorderAccessibilityService.instance
        if (service == null) {
            toast("שירות הנגישות לא פעיל")
            return
        }
        toast("מפעיל...")
        service.playMacro(strokes) {
            toast("סיום הפעלה")
        }
    }

    private fun saveMacro() {
        if (strokes.isEmpty()) {
            toast("אין הקלטה לשמור")
            return
        }
        runCatching {
            macroFile.writeText(MacroSerializer.toJson(strokes))
            toast("נשמר: ${macroFile.name}")
        }.onFailure {
            toast("שגיאה בשמירה: ${it.message}")
        }
    }

    private fun loadMacro() {
        if (!macroFile.exists()) {
            toast("אין קובץ שמור")
            return
        }
        runCatching {
            strokes = MacroSerializer.fromJson(macroFile.readText()).toMutableList()
            toast("נטען: ${strokes.size} מגעים")
        }.onFailure {
            toast("שגיאה בטעינה: ${it.message}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
