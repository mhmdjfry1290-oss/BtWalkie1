package com.example.btwalkie

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        val APP_UUID: UUID = UUID.fromString("8f3c2a10-5b7e-4d1a-9c63-2e4f7a1b9d05")
        const val RATE = 16000
    }

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null
    @Volatile private var connected = false
    @Volatile private var talking = false

    private lateinit var status: TextView
    private lateinit var hostBtn: Button
    private lateinit var joinBtn: Button
    private lateinit var talkBtn: Button
    private lateinit var stopBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        requestPerms()
    }

    // ---------------- UI ----------------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 80, 48, 48)
        }
        status = TextView(this).apply {
            text = "ابتدا دو گوشی را در تنظیمات بلوتوث Pair کنید.\nیک گوشی «منتظر اتصال» و دیگری «اتصال به گوشی» را بزند."
            textSize = 16f
            gravity = Gravity.CENTER
        }
        hostBtn = Button(this).apply {
            text = "منتظر اتصال (گوشی اول)"
            setOnClickListener { startHost() }
        }
        joinBtn = Button(this).apply {
            text = "اتصال به گوشی (گوشی دوم)"
            setOnClickListener { pickDevice() }
        }
        stopBtn = Button(this).apply {
            text = "قطع اتصال"
            setOnClickListener { disconnect("قطع شد") }
        }
        talkBtn = Button(this).apply {
            text = "نگه دارید و صحبت کنید"
            textSize = 22f
            isEnabled = false
            setBackgroundColor(Color.parseColor("#B0BEC5"))
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(Color.parseColor("#E53935")); startTalking(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        setBackgroundColor(Color.parseColor("#43A047")); stopTalking(); v.performClick(); true
                    }
                    else -> false
                }
            }
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 }
        root.addView(status, lp)
        root.addView(hostBtn, lp)
        root.addView(joinBtn, lp)
        root.addView(stopBtn, lp)
        root.addView(talkBtn, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 32 })
        setContentView(root)
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun setTalkEnabled(on: Boolean) = runOnUiThread {
        talkBtn.isEnabled = on
        talkBtn.setBackgroundColor(Color.parseColor(if (on) "#43A047" else "#B0BEC5"))
    }

    // ---------------- Permissions ----------------
    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        val missing = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun hasPerms(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    private fun btReady(): Boolean {
        if (!hasPerms()) { requestPerms(); toast("دسترسی‌ها را تایید کنید"); return false }
        if (adapter == null) { toast("این گوشی بلوتوث ندارد"); return false }
        if (adapter?.isEnabled != true) { toast("بلوتوث را روشن کنید"); return false }
        return true
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    // ---------------- Connection ----------------
    private fun startHost() {
        if (!btReady() || connected) return
        thread {
            try {
                setStatus("منتظر اتصال گوشی دیگر...")
                server = adapter!!.listenUsingRfcommWithServiceRecord("BtWalkie", APP_UUID)
                val s = server!!.accept()
                server?.close()
                onConnected(s)
            } catch (e: IOException) {
                setStatus("خطا در انتظار اتصال: ${e.message}")
            } catch (e: SecurityException) {
                setStatus("دسترسی بلوتوث داده نشده")
            }
        }
    }

    private fun pickDevice() {
        if (!btReady() || connected) return
        val devices = try { adapter!!.bondedDevices.toList() } catch (e: SecurityException) { emptyList() }
        if (devices.isEmpty()) { toast("هیچ گوشی Pair شده‌ای پیدا نشد"); return }
        val names = devices.map { "${it.name} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("انتخاب گوشی")
            .setItems(names) { _, i -> connectTo(devices[i]) }
            .show()
    }

    private fun connectTo(d: BluetoothDevice) {
        thread {
            try {
                setStatus("در حال اتصال...")
                adapter!!.cancelDiscovery()
                val s = d.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                onConnected(s)
            } catch (e: IOException) {
                setStatus("اتصال ناموفق بود. مطمئن شوید گوشی دیگر روی «منتظر اتصال» است.")
            } catch (e: SecurityException) {
                setStatus("دسترسی بلوتوث داده نشده")
            }
        }
    }

    private fun onConnected(s: BluetoothSocket) {
        socket = s
        connected = true
        val name = try { s.remoteDevice.name } catch (e: SecurityException) { "" }
        setStatus("وصل شد به $name\nدکمه را نگه دارید و صحبت کنید")
        setTalkEnabled(true)
        startPlayback(s)
    }

    private fun disconnect(msg: String) {
        talking = false
        connected = false
        try { socket?.close() } catch (_: IOException) {}
        try { server?.close() } catch (_: IOException) {}
        socket = null
        setTalkEnabled(false)
        setStatus(msg)
    }

    // ---------------- Audio: receive ----------------
    private fun startPlayback(s: BluetoothSocket) = thread {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buf = ByteArray(min)
        try {
            track.play()
            val input = s.inputStream
            while (connected) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) track.write(buf, 0, n)
            }
        } catch (_: IOException) {
        } finally {
            track.release()
            if (connected) disconnect("ارتباط قطع شد")
        }
    }

    // ---------------- Audio: send (push-to-talk) ----------------
    private fun startTalking() {
        if (!connected || talking || !hasPerms()) return
        talking = true
        thread {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2
                )
                rec.startRecording()
                val out = socket!!.outputStream
                val buf = ByteArray(min)
                while (talking && connected) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) out.write(buf, 0, n)
                }
                out.flush()
            } catch (e: IOException) {
                disconnect("ارتباط قطع شد")
            } catch (e: Exception) {
                setStatus("خطا در میکروفون: ${e.message}")
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                talking = false
            }
        }
    }

    private fun stopTalking() { talking = false }

    override fun onDestroy() {
        super.onDestroy()
        disconnect("")
    }
}
