package studio.cluvex.aethery

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var connectionControl: ConnectionControl
    private lateinit var connectionTitle: TextView
    private lateinit var connectionDetail: TextView
    private lateinit var modeSelector: LinearLayout
    private lateinit var modeValue: TextView
    private lateinit var locationSelector: LinearLayout
    private lateinit var locationValue: TextView
    private lateinit var logSelector: LinearLayout
    private lateinit var scannerSelector: LinearLayout
    private lateinit var scanValue: TextView
    private lateinit var mainRoot: FrameLayout
    private lateinit var pageHost: FrameLayout
    private var selectedProtocol = Protocol.MASQUE
    private var pendingConfig: String? = null
    private var visualState = ConnectionControl.State.DISCONNECTED
    private var receiverRegistered = false
    private var showingSettings = false
    private var settingsPage: View? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(AetherVpnService.EXTRA_STATUS)) {
                AetherVpnService.STATUS_CONNECTING -> showConnecting()
                AetherVpnService.STATUS_CONNECTED -> showConnected()
                AetherVpnService.STATUS_FAILED -> showFailure(intent.getStringExtra(AetherVpnService.EXTRA_DETAIL))
                AetherVpnService.STATUS_DISCONNECTED -> showDisconnected()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()

        connectionControl = ConnectionControl(this).apply { setOnClickListener { toggleTunnel() } }
        connectionTitle = label(textSize = 20f, color = INK, style = TypefaceStyle.MEDIUM).apply {
            gravity = Gravity.CENTER
        }
        connectionDetail = label(textSize = 14f, color = MUTED).apply { gravity = Gravity.CENTER }
        selectedProtocol = defaultProtocol()
        modeValue = label(selectedProtocol.label, 16f, INK, TypefaceStyle.MEDIUM)
        modeSelector = createModeSelector()
        locationSelector = createLocationSelector()
        logSelector = createLogSelector()
        scanValue = label(defaultScan().label, 14f, INK, TypefaceStyle.MEDIUM)
        scannerSelector = createScannerSelector()

        mainRoot = FrameLayout(this).apply { setBackgroundColor(CANVAS) }
        val header = createHeader()
        mainRoot.addView(header, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
            topMargin = dp(16)
        })
        mainRoot.setOnApplyWindowInsetsListener { _, insets ->
            (header.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = insets.systemWindowInsetTop + dp(16)
                header.layoutParams = this
            }
            insets
        }
        mainRoot.addView(createConnectionConsole(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
        })
        mainRoot.addView(label("AETHER CORE", 12f, MUTED).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply { bottomMargin = dp(24) })
        pageHost = FrameLayout(this).apply {
            setBackgroundColor(CANVAS)
            addView(mainRoot, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        setContentView(pageHost)
        handleDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AetherVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            pendingConfig?.let(::connect)
        } else if (requestCode == VPN_REQUEST) {
            showDisconnected("VPN permission required")
        }
        pendingConfig = null
    }

    private fun createHeader(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Aethery", 22f, INK, TypefaceStyle.MEDIUM))
            addView(label("Private connection", 14f, MUTED), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) })
        }
        addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label("⚙", 28f, INK).apply {
            gravity = Gravity.CENTER
            contentDescription = "Settings"
            isClickable = true
            isFocusable = true
            setOnClickListener { openSettingsScreen() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun createConnectionConsole(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(connectionControl)
        addView(connectionTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(24) })
        addView(connectionDetail, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        addView(modeSelector, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64),
        ).apply { topMargin = dp(32) })
        addView(locationSelector, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64),
        ).apply { topMargin = dp(12) })
        addView(logSelector, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56),
        ).apply { topMargin = dp(12) })
        addView(scannerSelector, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56),
        ).apply { topMargin = dp(12) })

        val checkProtocolsBtn = createSettingsButton("🔍 Test protocols on current network") {
            testNetworkProtocols()
        }
        addView(checkProtocolsBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply { topMargin = dp(12) })
    }

    private fun createModeSelector(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), 0, dp(18), 0)
        background = roundedBackground(SURFACE_VARIANT, 20, DIVIDER)
        contentDescription = "Connection mode, ${selectedProtocol.label}"
        isClickable = true
        isFocusable = true
        setOnClickListener { showModeSheet() }

        addView(label("MODE", 12f, MUTED).apply { letterSpacing = 0.1f })
        addView(modeValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(16)
        })
        addView(ChevronView(this@MainActivity, MUTED), LinearLayout.LayoutParams(dp(24), dp(24)))
    }

    private fun createLogSelector(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), 0, dp(18), 0)
        background = roundedBackground(SURFACE_VARIANT, 18, DIVIDER)
        contentDescription = "View connection log"
        isClickable = true
        isFocusable = true
        setOnClickListener { showLogSheet() }

        addView(label("LOG", 12f, MUTED).apply { letterSpacing = 0.1f })
        addView(label("Events", 14f, INK, TypefaceStyle.MEDIUM), LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { leftMargin = dp(16) })
        addView(ChevronView(this@MainActivity, MUTED), LinearLayout.LayoutParams(dp(24), dp(24)))
    }

    private fun createScannerSelector(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), 0, dp(14), 0)
        background = roundedBackground(SURFACE_VARIANT, 18, DIVIDER)
        contentDescription = "Scanner options, ${defaultScan().label}"
        isClickable = true
        isFocusable = true
        setOnClickListener { showScannerSheet() }

        addView(label("SCANNER", 12f, MUTED).apply { letterSpacing = 0.08f })
        addView(scanValue, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { leftMargin = dp(10) })
        addView(ChevronView(this@MainActivity, MUTED), LinearLayout.LayoutParams(dp(20), dp(20)))
    }

    private fun showLogSheet() {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = roundedBackground(SURFACE, 28, SURFACE)
        }
        sheet.addView(label("Connection log", 22f, INK, TypefaceStyle.MEDIUM))
        sheet.addView(label("Latest tunnel events", 14f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4); bottomMargin = dp(16) })

        val events = label(textSize = 13f, color = INK).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(events) }
        sheet.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(300),
        ))

        val container = FrameLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(16))
            addView(sheet, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        dialog.setContentView(container)
        val refreshHandler = Handler(Looper.getMainLooper())
        val refresh = object : Runnable {
            override fun run() {
                val newText = connectionLogText()
                if (events.text.toString() != newText) {
                    events.text = newText
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                if (dialog.isShowing) refreshHandler.postDelayed(this, LOG_REFRESH_MS)
            }
        }
        dialog.setOnShowListener { refresh.run() }
        dialog.setOnDismissListener { refreshHandler.removeCallbacks(refresh) }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.62f)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }

    private fun connectionLogText(): String {
        val events = ConnectionLog.snapshot() + NativeCore.lastLog().lineSequence().filter(String::isNotBlank)
        return events.joinToString("\n").ifBlank { "No connection events yet" }
    }

    private fun showScannerSheet() {
        if (visualState == ConnectionControl.State.CONNECTING ||
            visualState == ConnectionControl.State.CONNECTED ||
            NativeCore.isRunning()
        ) return

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = roundedBackground(SURFACE, 28, SURFACE)
        }
        sheet.addView(label("Scanner options", 22f, INK, TypefaceStyle.MEDIUM))
        sheet.addView(label("Choose address families for endpoint discovery", 14f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4); bottomMargin = dp(20) })
        ScanTarget.entries.forEachIndexed { index, target ->
            sheet.addView(createScannerOption(target, dialog), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(68),
            ).apply { if (index > 0) topMargin = dp(10) })
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(16))
            addView(sheet, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        dialog.setContentView(container)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.62f)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }

    private fun createScannerOption(target: ScanTarget, dialog: Dialog): LinearLayout {
        val selected = target == defaultScan()
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), 0, dp(18), 0)
            background = roundedBackground(
                if (selected) PRIMARY_CONTAINER else SURFACE_VARIANT,
                18,
                if (selected) PRIMARY else SURFACE_VARIANT,
            )
            contentDescription = "Scan ${target.label} endpoints"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putString(DEFAULT_SCAN, target.coreName).apply()
                scanValue.text = target.label
                scannerSelector.contentDescription = "Scanner options, ${target.label}"
                dialog.dismiss()
            }
            val labels = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(label(target.label, 16f, INK, TypefaceStyle.MEDIUM))
            labels.addView(label(target.description, 13f, MUTED), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) })
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) addView(label("SELECTED", 11f, PRIMARY, TypefaceStyle.MEDIUM).apply {
                letterSpacing = 0.08f
            })
        }
    }

    private fun showModeSheet() {
        if (visualState == ConnectionControl.State.CONNECTING ||
            visualState == ConnectionControl.State.CONNECTED ||
            NativeCore.isRunning()
        ) return

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = roundedBackground(SURFACE, 28, SURFACE)
        }
        sheet.addView(label("Connection mode", 22f, INK, TypefaceStyle.MEDIUM))
        sheet.addView(label("Choose how Aethery connects", 14f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4); bottomMargin = dp(20) })
        Protocol.entries.forEachIndexed { index, protocol ->
            sheet.addView(createModeOption(protocol, dialog), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(76),
            ).apply { if (index > 0) topMargin = dp(10) })
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(16))
            addView(sheet, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        dialog.setContentView(container)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.62f)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }

    private fun showSettingsPage() {
        showingSettings = true
        val page = FrameLayout(this).apply { setBackgroundColor(CANVAS) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(24))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("‹", 40f, INK).apply {
                gravity = Gravity.CENTER
                contentDescription = "Back"
                isClickable = true
                isFocusable = true
                setOnClickListener { showMainPage() }
            }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(label("Settings", 22f, INK, TypefaceStyle.MEDIUM), LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ))
        }
        content.addView(header)
        content.addView(label("Version ${appVersion()}", 14f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(48); topMargin = dp(-8); bottomMargin = dp(36) })
        content.addView(label("DEFAULT PROTOCOL", 12f, MUTED).apply { letterSpacing = 0.1f })
        Protocol.entries.forEachIndexed { index, protocol ->
            content.addView(createDefaultProtocolOption(protocol), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(76),
            ).apply { topMargin = if (index == 0) dp(16) else dp(12) })
        }
        content.addView(label("CORE SOCKS PORT", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        val portField = EditText(this).apply {
            setText(socksPort().toString())
            setTextColor(INK)
            setHintTextColor(MUTED)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setSelectAllOnFocus(false)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
            contentDescription = "Core SOCKS port"
        }
        val portRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(portField, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(createSettingsButton("Apply") { applySocksPort(portField) }, LinearLayout.LayoutParams(
                dp(92),
                dp(52),
            ).apply { leftMargin = dp(10) })
        }
        content.addView(portRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        content.addView(label("Used by Aether's local SOCKS listener; Android VPN/TUN routes do not use this port.", 12f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        page.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
            topMargin = dp(16)
        })
        page.setOnApplyWindowInsetsListener { _, insets ->
            (content.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = insets.systemWindowInsetTop + dp(16)
                content.layoutParams = this
            }
            insets
        }
        setContentView(page)
    }

    private fun showMainPage() {
        showingSettings = false
        setContentView(mainRoot)
    }

    private fun mtu(): String = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString("pref_mtu_str", "auto") ?: "auto"
    private fun dns(): String = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString("pref_dns_str", "auto") ?: "auto"
    private fun bypassApps(): String = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString("pref_bypass_apps", "") ?: ""
    private fun scanMode(): String = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString("pref_scan_mode", "balanced") ?: "balanced"
    private fun obfProfile(): String = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString("pref_obf_profile", "firewall") ?: "firewall"
    private fun forcedPeer(): String = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString("pref_forced_peer", "") ?: ""

    private fun applyScanMode(field: EditText) {
        val mode = field.text.toString().trim().lowercase()
        if (mode !in listOf("turbo", "balanced", "thorough", "stealth", "ironclad")) {
            field.error = "Enter turbo, balanced, thorough, stealth, or ironclad"
            return
        }
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putString("pref_scan_mode", mode).apply()
        field.error = null
        field.clearFocus()
    }

    private fun applyObfProfile(field: EditText) {
        val obf = field.text.toString().trim().lowercase()
        if (obf !in listOf("firewall", "gfw", "off")) {
            field.error = "Enter firewall, gfw, or off"
            return
        }
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putString("pref_obf_profile", obf).apply()
        field.error = null
        field.clearFocus()
    }

    private fun applyForcedPeer(field: EditText) {
        val peer = field.text.toString().trim()
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putString("pref_forced_peer", peer).apply()
        field.error = null
        field.clearFocus()
    }

    private fun applyMtu(field: EditText) {
        val text = field.text.toString().trim()
        if (text.lowercase() != "auto") {
            val valMtu = text.toIntOrNull()
            if (valMtu == null || valMtu !in 1200..1500) {
                field.error = "Enter auto or MTU between 1200 and 1500"
                return
            }
        }
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putString("pref_mtu_str", text).apply()
        field.error = null
        field.clearFocus()
    }

    private fun applyDns(field: EditText) {
        val valDns = field.text.toString().trim()
        if (valDns.isEmpty()) {
            field.error = "Enter valid DNS address"
            return
        }
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putString("pref_dns_str", valDns).apply()
        field.error = null
        field.clearFocus()
    }

    private fun applyBypassApps(field: EditText) {
        val valApps = field.text.toString().trim()
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putString("pref_bypass_apps", valApps).apply()
        field.error = null
        field.clearFocus()
    }

    private fun openSettingsScreen(animate: Boolean = true) {
        showingSettings = true
        settingsPage?.let(pageHost::removeView)

        val page = FrameLayout(this).apply { setBackgroundColor(CANVAS) }
        val scrollView = ScrollView(this).apply { 
            isVerticalScrollBarEnabled = false 
        }
        val content = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL 
            setPadding(dp(24), dp(16), dp(24), dp(100)) // extra padding at the bottom for scrolling
        }
        
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("‹", 40f, INK).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                contentDescription = "Back"
                isClickable = true
                isFocusable = true
                setOnClickListener { closeSettingsScreen() }
            }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(label("Settings", 22f, INK, TypefaceStyle.MEDIUM))
        }
        content.addView(header)
        
        // --- 1. DEFAULT PROTOCOL ---
        content.addView(label("DEFAULT PROTOCOL", 12f, MUTED).apply { letterSpacing = 0.1f })
        Protocol.entries.forEachIndexed { index, protocol ->
            content.addView(createDefaultProtocolOption(protocol), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(76),
            ).apply { topMargin = if (index == 0) dp(16) else dp(12) })
        }
        
        // --- 2. SOCKS PORT ---
        content.addView(label("CORE SOCKS PORT", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        val portField = EditText(this).apply {
            setText(socksPort().toString())
            setTextColor(INK)
            setHintTextColor(MUTED)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(portField, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(createSettingsButton("Apply") { applySocksPort(portField) }, LinearLayout.LayoutParams(
                dp(92),
                dp(52),
            ).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        
        // --- 3. CUSTOM MTU TUNER ---
        content.addView(label("CUSTOM MTU (TUNNEL SIZE)", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        val mtuField = EditText(this).apply {
            setText(mtu().toString())
            setTextColor(INK)
            setHintTextColor(MUTED)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(mtuField, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(createSettingsButton("Apply") { applyMtu(mtuField) }, LinearLayout.LayoutParams(
                dp(92),
                dp(52),
            ).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        content.addView(label("Lower values (e.g. 1280-1360) bypass UDP throttling on MCI and Irancell.", 12f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        // --- 4. CUSTOM DNS OVER HTTPS PRESSETS ---
        content.addView(label("TUNNEL DNS SERVERS", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        val dnsField = EditText(this).apply {
            setText(dns())
            setTextColor(INK)
            setHintTextColor(MUTED)
            textSize = 16f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dnsField, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(createSettingsButton("Apply") { applyDns(dnsField) }, LinearLayout.LayoutParams(
                dp(92),
                dp(52),
            ).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        content.addView(label("Use comma-separated addresses. Presets: 1.1.1.1 (Cloudflare), 178.22.122.100 (Shecan bypass), 10.202.10.10 (403.online).", 12f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        // --- 5. SPLIT TUNNELING (APP BYPASS) ---
        content.addView(label("BYPASS APPLICATION PACKAGES", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        val bypassField = EditText(this).apply {
            setText(bypassApps())
            setTextColor(INK)
            hint = "com.example.app, ir.snapp.passenger"
            textSize = 14f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(bypassField, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(createSettingsButton("Apply") { applyBypassApps(bypassField) }, LinearLayout.LayoutParams(
                dp(92),
                dp(52),
            ).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        content.addView(label("Comma-separated package names of applications to bypass the VPN tunnel (e.g. Iranian banking apps, Snapp, Divar).", 12f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        // --- 6. SCAN MODE SELECTOR ---
        content.addView(label("TUNNEL SCAN MODE", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        val scanModeField = EditText(this).apply {
            setText(scanMode())
            setTextColor(INK)
            hint = "turbo, balanced, thorough, stealth, ironclad"
            textSize = 16f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(scanModeField, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(createSettingsButton("Apply") { applyScanMode(scanModeField) }, LinearLayout.LayoutParams(
                dp(92),
                dp(52),
            ).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        content.addView(label("Options: balanced (default), turbo, thorough, stealth, or ironclad.", 12f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        // --- 7. OBFUSCATION PROFILE ---
        content.addView(label("OBFUSCATION PROFILE", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        val obfField = EditText(this).apply {
            setText(obfProfile())
            setTextColor(INK)
            hint = "firewall, gfw, off"
            textSize = 16f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(obfField, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(createSettingsButton("Apply") { applyObfProfile(obfField) }, LinearLayout.LayoutParams(
                dp(92),
                dp(52),
            ).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        content.addView(label("Configure padding and packet size obfuscation (firewall, gfw, or off).", 12f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        // --- 8. MULTI-LOCATION / FORCED ANYCAST ENDPOINT ---
        content.addView(label("FORCED ANYCAST ENDPOINT (IP:PORT)", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        val peerField = EditText(this).apply {
            setText(forcedPeer())
            setTextColor(INK)
            hint = "e.g. 162.159.192.1:2408"
            textSize = 16f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(peerField, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(createSettingsButton("Apply") { applyForcedPeer(peerField) }, LinearLayout.LayoutParams(
                dp(92),
                dp(52),
            ).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        content.addView(label("Forces the connection to a specific Cloudflare IP:Port, bypassing Anycast scanning (e.g. 162.159.193.10:2408). Leave blank for auto-scanning.", 12f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        // --- 9. IMPORT / PASTE CUSTOM CONFIG ---
        content.addView(label("IMPORT CONFIG (JSON OR AETHER:// LINK)", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        val importField = EditText(this).apply {
            hint = "Paste custom JSON config or aether:// URL here"
            setHintTextColor(MUTED)
            setTextColor(INK)
            textSize = 14f
            background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        content.addView(importField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        content.addView(createSettingsButton("Import and Apply") {
            val text = importField.text.toString().trim()
            if (importConfig(text)) {
                importField.setText("")
                importField.clearFocus()
                Toast.makeText(this, "Config imported successfully!", Toast.LENGTH_SHORT).show()
                openSettingsScreen(animate = false) // refresh settings page
            } else {
                importField.error = "Invalid JSON or aether:// link"
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply { topMargin = dp(10) })

        scrollView.addView(content)
        page.addView(scrollView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CANVAS) // Solid background for footer so text doesn't overlap on scroll
            setPadding(dp(24), dp(12), dp(24), dp(24))
            addView(label("Version ${appVersion()}", 14f, MUTED))
        }
        page.addView(footer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        
        page.setOnApplyWindowInsetsListener { _, insets ->
            scrollView.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom + dp(80))
            (footer.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = insets.systemWindowInsetBottom
                footer.layoutParams = this
            }
            insets
        }

        settingsPage = page
        pageHost.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        page.requestApplyInsets()
        if (animate) {
            page.alpha = 0f
            page.translationX = dp(32).toFloat()
            mainRoot.animate().alpha(0.65f).translationX(-dp(12).toFloat())
                .setDuration(PAGE_ANIMATION_MS).setInterpolator(DecelerateInterpolator()).start()
            page.animate().alpha(1f).translationX(0f)
                .setDuration(PAGE_ANIMATION_MS).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun closeSettingsScreen() {
        showingSettings = false
        val page = settingsPage ?: return
        page.animate().alpha(0f).translationX(dp(32).toFloat())
            .setDuration(PAGE_ANIMATION_MS).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                pageHost.removeView(page)
                settingsPage = null
            }.start()
        mainRoot.animate().alpha(1f).translationX(0f)
            .setDuration(PAGE_ANIMATION_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    override fun onBackPressed() {
        if (showingSettings) closeSettingsScreen() else super.onBackPressed()
    }

    private fun createDefaultProtocolOption(protocol: Protocol): LinearLayout {
        val selected = protocol == defaultProtocol()
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), 0, dp(18), 0)
            background = roundedBackground(
                if (selected) PRIMARY_CONTAINER else SURFACE_VARIANT,
                18,
                if (selected) PRIMARY else SURFACE_VARIANT,
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Set ${protocol.label} as default"
            setOnClickListener {
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putString(DEFAULT_PROTOCOL, protocol.coreName).apply()
                selectedProtocol = protocol
                modeValue.text = protocol.label
                modeSelector.contentDescription = "Connection mode, ${protocol.label}"
                openSettingsScreen(animate = false)
            }
            addView(label(protocol.label, 16f, INK, TypefaceStyle.MEDIUM), LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ))
            if (selected) addView(label("DEFAULT", 11f, PRIMARY, TypefaceStyle.MEDIUM).apply {
                letterSpacing = 0.08f
            })
        }
    }

    private fun createModeOption(protocol: Protocol, dialog: Dialog): LinearLayout {
        val selected = protocol == selectedProtocol
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), 0, dp(18), 0)
            background = roundedBackground(
                if (selected) PRIMARY_CONTAINER else SURFACE_VARIANT,
                18,
                if (selected) PRIMARY else SURFACE_VARIANT,
            )
            contentDescription = "Use ${protocol.label} mode"
            isClickable = protocol.androidAvailable
            isFocusable = protocol.androidAvailable
            alpha = if (protocol.androidAvailable) 1f else DISABLED_ALPHA
            setOnClickListener {
                if (!protocol.androidAvailable) return@setOnClickListener
                selectedProtocol = protocol
                modeValue.text = protocol.label
                modeSelector.contentDescription = "Connection mode, ${protocol.label}"
                dialog.dismiss()
            }
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(protocol.label, 16f, INK, TypefaceStyle.MEDIUM))
            texts.addView(label(protocol.description, 13f, MUTED), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) addView(label("CURRENT", 11f, PRIMARY, TypefaceStyle.MEDIUM).apply {
                letterSpacing = 0.08f
            }) else if (!protocol.androidAvailable) addView(label("DESKTOP ONLY", 11f, MUTED, TypefaceStyle.MEDIUM).apply {
                letterSpacing = 0.08f
            })
        }
    }

    private fun toggleTunnel() {
        if (NativeCore.isRunning()) {
            startService(Intent(this, AetherVpnService::class.java).setAction(AetherVpnService.ACTION_DISCONNECT))
            showDisconnected("Disconnecting")
            return
        }

        val config = configJson()
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) connect(config) else {
            pendingConfig = config
            startActivityForResult(permissionIntent, VPN_REQUEST)
        }
    }

    private fun connect(config: String) {
        showConnecting()
        startForegroundService(Intent(this, AetherVpnService::class.java)
            .setAction(AetherVpnService.ACTION_CONNECT)
            .putExtra(AetherVpnService.EXTRA_CONFIG, config))
    }

    private fun configJson(): String = org.json.JSONObject().apply {
        val prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE)
        put("config_path", File(filesDir, "aether.toml").absolutePath)
        put("protocol", selectedProtocol.coreName)
        put("listen", "127.0.0.1:${socksPort()}")
        put("scan_mode", prefs.getString("pref_scan_mode", "balanced") ?: "balanced")
        put("ip_scan", defaultScan().coreName)
        
        val forcedPeer = prefs.getString("pref_forced_peer", "") ?: ""
        if (forcedPeer.isNotEmpty()) {
            put("forced_peer", forcedPeer)
        }
        
        val obfProfile = prefs.getString("pref_obf_profile", "firewall") ?: "firewall"
        if (obfProfile != "off") {
            put("obfuscation_profile", obfProfile)
        }
    }.toString()

    private fun renderStatus() {
        if (!NativeCore.isRunning() && visualState == ConnectionControl.State.CONNECTED) showDisconnected()
    }

    private fun showConnecting() {
        visualState = ConnectionControl.State.CONNECTING
        connectionControl.state = visualState
        connectionTitle.setTextColor(PRIMARY)
        connectionTitle.text = "Connecting"
        connectionDetail.text = "Starting ${selectedProtocol.label} tunnel"
        setModeEnabled(false)
    }

    private fun showConnected() {
        visualState = ConnectionControl.State.CONNECTED
        connectionControl.state = visualState
        connectionTitle.setTextColor(CONNECTED)
        connectionTitle.text = "Connected"
        connectionDetail.text = "${selectedProtocol.label} tunnel is active"
        setModeEnabled(false)
    }

    private fun showFailure(detail: String? = null) {
        visualState = ConnectionControl.State.FAILED
        connectionControl.state = visualState
        connectionTitle.setTextColor(ERROR)
        connectionTitle.text = "Connection failed"
        connectionDetail.text = detail ?: "Check the server and try again"
        setModeEnabled(true)
    }

    private fun showDisconnected(detail: String = "Tap the circle to connect") {
        visualState = ConnectionControl.State.DISCONNECTED
        connectionControl.state = visualState
        connectionTitle.setTextColor(INK)
        connectionTitle.text = "Not connected"
        connectionDetail.text = detail
        setModeEnabled(true)
    }

    private fun setModeEnabled(enabled: Boolean) {
        modeSelector.isEnabled = enabled
        modeSelector.alpha = if (enabled) 1f else DISABLED_ALPHA
        scannerSelector.isEnabled = enabled
        scannerSelector.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun configureSystemBars() {
        window.statusBarColor = CANVAS
        window.navigationBarColor = CANVAS
        window.decorView.systemUiVisibility = 0
    }

    private fun label(
        text: String = "",
        textSize: Float,
        color: Int,
        style: TypefaceStyle = TypefaceStyle.REGULAR,
    ): TextView = TextView(this).apply {
        this.text = text
        this.textSize = textSize
        setTextColor(color)
        typeface = when (style) {
            TypefaceStyle.REGULAR -> android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
            TypefaceStyle.MEDIUM -> android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
    }

    private fun roundedBackground(fill: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun appVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"

    private fun createSettingsButton(
        text: String,
        icon: Int? = null,
        onClick: () -> Unit,
    ): TextView = label(text, 15f, INK, TypefaceStyle.MEDIUM).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
        isClickable = true
        isFocusable = true
        contentDescription = text
        icon?.let {
            setCompoundDrawablesRelativeWithIntrinsicBounds(it, 0, 0, 0)
            compoundDrawablePadding = dp(12)
            compoundDrawablesRelative[0]?.setTint(PRIMARY)
        }
        setOnClickListener { onClick() }
    }

    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun defaultProtocol(): Protocol {
        val name = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString(DEFAULT_PROTOCOL, Protocol.MASQUE.coreName)
        return Protocol.entries.firstOrNull { it.coreName == name } ?: Protocol.MASQUE
    }

    private fun defaultScan(): ScanTarget {
        val name = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString(DEFAULT_SCAN, ScanTarget.IPV4.coreName)
        return ScanTarget.entries.firstOrNull { it.coreName == name } ?: ScanTarget.IPV4
    }

    private fun importConfig(rawConfig: String): Boolean {
        val trimmed = rawConfig.trim()
        if (trimmed.isEmpty()) return false
        val prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE)
        val editor = prefs.edit()
        try {
            if (trimmed.startsWith("{")) {
                val json = org.json.JSONObject(trimmed)
                if (json.has("protocol")) {
                    val proto = json.getString("protocol")
                    editor.putString(DEFAULT_PROTOCOL, proto)
                    Protocol.entries.firstOrNull { it.coreName == proto }?.let { selectedProtocol = it }
                }
                if (json.has("mtu")) editor.putString("pref_mtu_str", json.getString("mtu"))
                if (json.has("dns")) editor.putString("pref_dns_str", json.getString("dns"))
                if (json.has("scan_mode")) editor.putString("pref_scan_mode", json.getString("scan_mode"))
                if (json.has("obfuscation_profile")) editor.putString("pref_obf_profile", json.getString("obfuscation_profile"))
                if (json.has("forced_peer")) editor.putString("pref_forced_peer", json.getString("forced_peer"))
                if (json.has("bypass_apps")) editor.putString("pref_bypass_apps", json.getString("bypass_apps"))
                editor.apply()
                
                // Update UI values immediately if they exist
                modeValue.text = selectedProtocol.label
                locationValue.text = locationLabel()
                scanValue.text = defaultScan().label
                return true
            } else if (trimmed.startsWith("aether://") || trimmed.contains("protocol=")) {
                val cleanUrl = if (!trimmed.startsWith("aether://")) "aether://connect?" + trimmed else trimmed
                val uri = android.net.Uri.parse(cleanUrl)
                val configBase64 = uri.getQueryParameter("config")
                if (configBase64 != null) {
                    val decoded = String(android.util.Base64.decode(configBase64, android.util.Base64.DEFAULT))
                    val json = org.json.JSONObject(decoded)
                    if (json.has("protocol")) {
                        val proto = json.getString("protocol")
                        editor.putString(DEFAULT_PROTOCOL, proto)
                        Protocol.entries.firstOrNull { it.coreName == proto }?.let { selectedProtocol = it }
                    }
                    if (json.has("mtu")) editor.putString("pref_mtu_str", json.getString("mtu"))
                    if (json.has("dns")) editor.putString("pref_dns_str", json.getString("dns"))
                    if (json.has("scan_mode")) editor.putString("pref_scan_mode", json.getString("scan_mode"))
                    if (json.has("obf")) editor.putString("pref_obf_profile", json.getString("obf"))
                    if (json.has("forced_peer")) editor.putString("pref_forced_peer", json.getString("forced_peer"))
                } else {
                    uri.getQueryParameter("protocol")?.let { proto ->
                        editor.putString(DEFAULT_PROTOCOL, proto)
                        Protocol.entries.firstOrNull { it.coreName == proto }?.let { selectedProtocol = it }
                    }
                    uri.getQueryParameter("mtu")?.let { editor.putString("pref_mtu_str", it) }
                    uri.getQueryParameter("dns")?.let { editor.putString("pref_dns_str", it) }
                    uri.getQueryParameter("scan")?.let { editor.putString("pref_scan_mode", it) }
                    uri.getQueryParameter("obf")?.let { editor.putString("pref_obf_profile", it) }
                    uri.getQueryParameter("forced_peer")?.let { editor.putString("pref_forced_peer", it) }
                }
                editor.apply()
                
                // Update UI values immediately
                modeValue.text = selectedProtocol.label
                locationValue.text = locationLabel()
                scanValue.text = defaultScan().label
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("Aethery", "Failed to parse imported config", e)
        }
        return false
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "aether") {
            if (importConfig(uri.toString())) {
                ConnectionLog.record("Imported config via deep link: aether://")
                showDisconnected("Config imported! Click connect")
            } else {
                ConnectionLog.record("Failed to parse config link: " + uri.toString())
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun createLocationSelector(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), 0, dp(18), 0)
        background = roundedBackground(SURFACE_VARIANT, 20, DIVIDER)
        contentDescription = "Select Location, ${locationLabel()}"
        isClickable = true
        isFocusable = true
        setOnClickListener { showLocationSheet() }

        addView(label("LOCATION", 12f, MUTED).apply { letterSpacing = 0.1f })
        locationValue = label(locationLabel(), 16f, INK, TypefaceStyle.MEDIUM)
        addView(locationValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(16)
        })
        addView(ChevronView(this@MainActivity, MUTED), LinearLayout.LayoutParams(dp(24), dp(24)))
    }

    private fun locationLabel(): String {
        val peer = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString("pref_forced_peer", "") ?: ""
        return when {
            peer.contains("193.1") -> "Germany 🇩🇪"
            peer.contains("194.1") -> "Netherlands 🇳🇱"
            peer.contains("197.1") -> "France 🇫🇷"
            peer.contains("192.1") -> "United Kingdom 🇬🇧"
            peer.contains("198.1") -> "Switzerland 🇨🇭"
            peer.contains("195.1") -> "United States 🇺🇸"
            peer.contains("199.1") -> "United States (West) 🇺🇸"
            peer.contains("196.1") -> "Singapore 🇸🇬"
            peer.contains("200.1") -> "Japan 🇯🇵"
            peer.contains("201.1") -> "Hong Kong 🇭🇰"
            peer.contains("202.1") -> "Australia 🇦🇺"
            peer.contains("203.1") -> "Canada 🇨🇦"
            peer.contains("204.1") -> "Turkey 🇹🇷"
            peer.contains("205.1") -> "UAE 🇦🇪"
            peer.contains("206.1") -> "Brazil 🇧🇷"
            peer.isEmpty() -> "Auto-Scan 🌐"
            else -> "Custom 📍"
        }
    }

    private fun showLocationSheet() {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = roundedBackground(SURFACE, 28, SURFACE)
        }
        sheet.addView(label("Select Location", 22f, INK, TypefaceStyle.MEDIUM))
        sheet.addView(label("Lock connection to specific regional Anycast gateway", 14f, MUTED).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val options = listOf(
            Triple("Auto-Scan 🌐", "", "Auto-select fastest Cloudflare node"),
            Triple("Germany 🇩🇪", "162.159.193.1:2408", "Frankfurt — best for EU"),
            Triple("Netherlands 🇳🇱", "162.159.194.1:2408", "Amsterdam — major hub"),
            Triple("France 🇫🇷", "162.159.197.1:2408", "Paris — low latency EU"),
            Triple("United Kingdom 🇬🇧", "162.159.192.1:2408", "London — premium routing"),
            Triple("Switzerland 🇨🇭", "162.159.198.1:2408", "Zurich — privacy friendly"),
            Triple("United States (East) 🇺🇸", "162.159.195.1:2408", "US East Coast"),
            Triple("United States (West) 🇺🇸", "162.159.199.1:2408", "US West Coast"),
            Triple("Singapore 🇸🇬", "162.159.196.1:2408", "SG — Asia gateway"),
            Triple("Japan 🇯🇵", "162.159.200.1:2408", "Tokyo — low latency Asia"),
            Triple("Hong Kong 🇭🇰", "162.159.201.1:2408", "HK — fast CN routing"),
            Triple("Australia 🇦🇺", "162.159.202.1:2408", "Sydney — Oceania"),
            Triple("Canada 🇨🇦", "162.159.203.1:2408", "Toronto — North America"),
            Triple("Turkey 🇹🇷", "162.159.204.1:2408", "Istanbul — MENA hub"),
            Triple("UAE 🇦🇪", "162.159.205.1:2408", "Dubai — Middle East"),
            Triple("Brazil 🇧🇷", "162.159.206.1:2408", "São Paulo — South America")
        )

        val optionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        options.forEach { (name, peerIp, desc) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                isFocusable = true
                background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
                setOnClickListener {
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putString("pref_forced_peer", peerIp).apply()
                    locationValue.text = name
                    locationSelector.contentDescription = "Select Location, $name"
                    dialog.dismiss()
                }
            }
            row.addView(label(name, 16f, INK, TypefaceStyle.MEDIUM))
            row.addView(label(desc, 12f, MUTED).apply { setPadding(0, dp(2), 0, 0) })
            optionsLayout.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        val optionsScrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(optionsLayout)
        }
        sheet.addView(optionsScrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(400) // limit height to 400dp to ensure title remains visible and scroll works cleanly
        ))

        dialog.setContentView(sheet)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun socksPort(): Int = getSharedPreferences(SETTINGS, MODE_PRIVATE)
        .getInt(DEFAULT_SOCKS_PORT, DEFAULT_SOCKS_PORT_VALUE)

    private fun applySocksPort(field: EditText) {
        val port = field.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            field.error = "Enter a port from 1 to 65535"
            return
        }
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putInt(DEFAULT_SOCKS_PORT, port).apply()
        field.error = null
        field.clearFocus()
    }

    private enum class Protocol(
        val label: String,
        val coreName: String,
        val description: String,
        val androidAvailable: Boolean = true,
    ) {
        MASQUE("MASQUE", "masque", "HTTP/3 tunnel"),
        WIREGUARD("WireGuard", "wireguard", "WireGuard tunnel"),
        WARP_IN_WARP("WARP-on-WARP", "gool", "Double-layer tunnel"),
    }

    private enum class ScanTarget(
        val label: String,
        val coreName: String,
        val description: String,
    ) {
        IPV4("IPv4", "v4", "Scan IPv4 endpoints only"),
        IPV6("IPv6", "v6", "Scan IPv6 endpoints only"),
        BOTH("Both", "both", "Scan IPv4 and IPv6 endpoints"),
    }

    private enum class TypefaceStyle { REGULAR, MEDIUM }

    private companion object {
        const val VPN_REQUEST = 100
        const val LOG_REFRESH_MS = 750L
        const val PAGE_ANIMATION_MS = 220L
        const val SETTINGS = "settings"
        const val DEFAULT_PROTOCOL = "default_protocol"
        const val DEFAULT_SCAN = "default_scan"
        const val DEFAULT_SOCKS_PORT = "default_socks_port"
        const val DEFAULT_SOCKS_PORT_VALUE = 1819
        const val CANVAS = 0xFF101411.toInt()
        const val SURFACE = 0xFF171C18.toInt()
        const val SURFACE_VARIANT = 0xFF222A24.toInt()
        const val INK = 0xFFE8F1EA.toInt()
        const val MUTED = 0xFFB9C6BB.toInt()
        const val DIVIDER = 0xFF3B473E.toInt()
        const val PRIMARY = 0xFFA4D8BB.toInt()
        const val PRIMARY_CONTAINER = 0xFF1F4030.toInt()
        const val CONNECTED = 0xFF67D89C.toInt()
        const val ERROR = 0xFFFFB4AB.toInt()
        const val DISABLED_ALPHA = 0.48f
    }

    private fun testNetworkProtocols() {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = roundedBackground(SURFACE, 28, SURFACE)
        }
        layout.addView(label("Network Protocol Prober", 22f, INK, TypefaceStyle.MEDIUM))
        layout.addView(label("Testing Cloudflare Anycast endpoints...", 14f, MUTED).apply {
            setPadding(0, dp(4), 0, dp(20))
        })

        val resultsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(resultsLayout)
        
        dialog.setContentView(layout)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        Thread {
            val m3Status = probeUdp("162.159.193.1", 443)
            val m2Status = probeTcp("162.159.193.1", 443)
            val wgStatus = probeUdp("162.159.193.1", 2408)

            Handler(Looper.getMainLooper()).post {
                resultsLayout.removeAllViews()
                resultsLayout.addView(createProbeRow("MASQUE (HTTP/3 - UDP 443)", m3Status))
                resultsLayout.addView(createProbeRow("MASQUE (HTTP/2 - TCP 443)", m2Status))
                resultsLayout.addView(createProbeRow("WireGuard / Gool (UDP 2408)", wgStatus))
            }
        }.start()
    }

    private fun createProbeRow(title: String, status: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(label(title, 14f, MUTED))
            addView(label(status, 16f, INK, TypefaceStyle.MEDIUM).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun probeTcp(host: String, port: Int): String {
        val start = System.currentTimeMillis()
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 2000)
            socket.close()
            val delay = System.currentTimeMillis() - start
            "Connected 🟢 (${delay}ms)"
        } catch (e: Exception) {
            "Blocked 🔴 (TCP Reset / Timeout)"
        }
    }

    private fun probeUdp(host: String, port: Int): String {
        val start = System.currentTimeMillis()
        return try {
            val socket = java.net.DatagramSocket()
            socket.soTimeout = 2000
            val address = java.net.InetAddress.getByName(host)
            val data = ByteArray(4)
            val packet = java.net.DatagramPacket(data, data.size, address, port)
            socket.send(packet)
            socket.close()
            val delay = System.currentTimeMillis() - start
            "Open 🟢 (${delay}ms)"
        } catch (e: Exception) {
            "Blocked 🔴"
        }
    }
}

private class ChevronView(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 1.8f
        this.color = color
    }

    override fun onDraw(canvas: Canvas) {
        val middleX = width / 2f
        val middleY = height / 2f - resources.displayMetrics.density
        val arm = resources.displayMetrics.density * 4f
        canvas.drawLine(middleX - arm, middleY - arm / 2, middleX, middleY + arm / 2, paint)
        canvas.drawLine(middleX, middleY + arm / 2, middleX + arm, middleY - arm / 2, paint)
    }
}

private class ConnectionControl(context: Context) : View(context) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private var containerColor: Int = PRIMARY_CONTAINER
    private var accentColor: Int = PRIMARY
    private var glowScale: Float = 0.3f
    private var rotationAngle: Float = 0f
    private var strokeAngle: Float = 0f
    
    private var colorAnimator: ValueAnimator? = null
    private var glowAnimator: ValueAnimator? = null
    private var rotateAnimator: ValueAnimator? = null

    var state: State = State.DISCONNECTED
        set(value) {
            if (field == value) return
            field = value
            contentDescription = when (value) {
                State.DISCONNECTED, State.FAILED -> "Connect"
                State.CONNECTING -> "Connecting"
                State.CONNECTED -> "Disconnect"
            }
            animateStateTransition(value)
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBounds = RectF()
    private val density = resources.displayMetrics.density

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Connect"
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        startGlowAnimation()
    }

    private fun animateStateTransition(targetState: State) {
        val targetPalette = when (targetState) {
            State.DISCONNECTED -> Palette(PRIMARY_CONTAINER, PRIMARY)
            State.CONNECTING -> Palette(PRIMARY_CONTAINER, PRIMARY)
            State.CONNECTED -> Palette(CONNECTED_CONTAINER, CONNECTED)
            State.FAILED -> Palette(ERROR_CONTAINER, ERROR)
        }

        colorAnimator?.cancel()
        val evaluator = android.animation.ArgbEvaluator()
        
        val startContainer = containerColor
        val startAccent = accentColor
        
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            addUpdateListener {
                val fraction = it.animatedValue as Float
                containerColor = evaluator.evaluate(fraction, startContainer, targetPalette.container) as Int
                accentColor = evaluator.evaluate(fraction, startAccent, targetPalette.accent) as Int
                invalidate()
            }
            start()
        }

        if (targetState == State.CONNECTING) {
            startRotationAnimation()
        } else {
            stopRotationAnimation()
        }
    }

    private fun startGlowAnimation() {
        glowAnimator = ValueAnimator.ofFloat(0.3f, 1.0f).apply {
            duration = 1800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                glowScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startRotationAnimation() {
        rotateAnimator?.cancel()
        rotateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                strokeAngle = (rotationAngle * 1.5f) % 360f
                invalidate()
            }
            start()
        }
    }

    private fun stopRotationAnimation() {
        rotateAnimator?.cancel()
        rotateAnimator = null
        rotationAngle = 0f
        strokeAngle = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(176)
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (min(width, height) / 2f) - dp(16)

        // 1. Draw Outer Neon Glow (Incy Style)
        if (state == State.CONNECTED || state == State.CONNECTING) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(4).toFloat()
            paint.color = accentColor
            val alphaVal = (50 * (1f - (glowScale - 0.3f) / 0.7f)).toInt()
            paint.alpha = if (alphaVal < 10) 10 else if (alphaVal > 120) 120 else alphaVal
            val outerRadius = radius + dp(12) * glowScale
            canvas.drawCircle(centerX, centerY, outerRadius, paint)
        }

        // 2. Draw Main Solid Container
        paint.style = Paint.Style.FILL
        paint.color = containerColor
        paint.alpha = 255
        paint.setShadowLayer(dp(18).toFloat(), 0f, dp(6).toFloat(), (accentColor and 0x22FFFFFF))
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.clearShadowLayer()

        // 3. Draw Accent Border Ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = accentColor
        canvas.drawCircle(centerX, centerY, radius, paint)

        // 4. Draw Center Stylized Power Key (Power icon in the center)
        val iconRadius = dp(24).toFloat()
        arcBounds.set(centerX - iconRadius, centerY - iconRadius, centerX + iconRadius, centerY + iconRadius)
        paint.strokeWidth = dp(3.5f).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        
        canvas.save()
        if (state == State.CONNECTING) {
            canvas.rotate(rotationAngle, centerX, centerY)
        }
        
        canvas.drawArc(arcBounds, 45f, 270f, false, paint)
        canvas.drawLine(centerX, centerY - dp(30), centerX, centerY - dp(4), paint)
        canvas.restore()
        
        paint.strokeCap = Paint.Cap.BUTT

        // 5. Draw Orbiting Loading Arc when connecting
        if (state == State.CONNECTING) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3.5f).toFloat()
            paint.color = accentColor
            paint.alpha = 255
            val loadingBounds = RectF(
                centerX - radius - dp(8),
                centerY - radius - dp(8),
                centerX + radius + dp(8),
                centerY + radius + dp(8)
            )
            canvas.drawArc(loadingBounds, strokeAngle, 90f, false, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
            true
        }
        MotionEvent.ACTION_UP -> {
            animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            performClick()
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            true
        }
        else -> super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        stopRotationAnimation()
        glowAnimator?.cancel()
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()
    private fun dp(value: Int): Int = (value * density).roundToInt()

    private data class Palette(val container: Int, val accent: Int)

    private companion object {
        const val PRIMARY = 0xFFA4D8BB.toInt()
        const val PRIMARY_CONTAINER = 0xFF1F4030.toInt()
        const val CONNECTED = 0xFF67D89C.toInt()
        const val CONNECTED_CONTAINER = 0xFF123B27.toInt()
        const val ERROR = 0xFFFFB4AB.toInt()
        const val ERROR_CONTAINER = 0xFF4A1E1C.toInt()
    }
}
