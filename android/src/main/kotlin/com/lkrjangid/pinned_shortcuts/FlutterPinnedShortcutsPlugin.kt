package com.lkrjangid.pinned_shortcuts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.NewIntentListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * FlutterPinnedShortcutsPlugin - A Flutter plugin for managing Android pinned shortcuts
 */
class FlutterPinnedShortcutsPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, NewIntentListener {

    companion object {
        private const val TAG = "PinnedShortcutsPlugin"
        private const val CHANNEL = "pinned_shortcuts"
        private const val SHORTCUT_CLICKED_ACTION = "com.lkrjangid.pinned_shortcuts.SHORTCUT_CLICKED"
        private const val EXTRA_SHORTCUT_ID = "shortcut_id"
        private const val EXTRA_SHORTCUT_DATA = "shortcut_data"

        // Legacy action constants for compatibility with older Android versions
        private const val INSTALL_SHORTCUT_ACTION = "com.android.launcher.action.INSTALL_SHORTCUT"
        private const val UNINSTALL_SHORTCUT_ACTION = "com.android.launcher.action.UNINSTALL_SHORTCUT"
    }

    // Plugin properties
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    // Store initial shortcut data for cold-start retrieval
    private var initialShortcutData: Map<String, Any?>? = null

    //----------------------------------------------------------------------------------------------
    // FlutterPlugin interface implementation
    //----------------------------------------------------------------------------------------------

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
        Log.d(TAG, "Plugin attached to engine")
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        Log.d(TAG, "Plugin detached from engine")
    }

    //----------------------------------------------------------------------------------------------
    // ActivityAware interface implementation
    //----------------------------------------------------------------------------------------------

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.d(TAG, "Plugin attached to activity")
        activity = binding.activity
        binding.addOnNewIntentListener(this)

        // Check if the activity was launched from a shortcut (Cold Start check)
        val intent = activity?.intent
        if (intent != null) {
            checkForShortcutIntent(intent, isColdStart = true)
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "Plugin detached from activity for config changes")
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        Log.d(TAG, "Plugin reattached to activity for config changes")
        activity = binding.activity
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivity() {
        Log.d(TAG, "Plugin detached from activity")
        activity = null
    }

    //----------------------------------------------------------------------------------------------
    // NewIntentListener interface implementation
    //----------------------------------------------------------------------------------------------

    override fun onNewIntent(intent: Intent): Boolean {
        Log.d(TAG, "New intent received: ${intent.action}")
        return checkForShortcutIntent(intent, isColdStart = false)
    }

    //----------------------------------------------------------------------------------------------
    // MethodCallHandler interface implementation
    //----------------------------------------------------------------------------------------------

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        Log.d(TAG, "Method call received: ${call.method}")

        when (call.method) {
            "isSupported" -> {
                val isSupported = isShortcutSupported()
                Log.d(TAG, "Shortcuts supported: $isSupported")
                result.success(isSupported)
            }

            // **ADDED:** Retrieve initial shortcut data when app starts cold
            "getInitialShortcut" -> {
                Log.d(TAG, "Fetching initial shortcut data")
                result.success(initialShortcutData)
                // Clear out after fetching so it's only returned once
                initialShortcutData = null
            }

            "createPinnedShortcut" -> {
                val id = call.argument<String>("id") ?: ""
                val label = call.argument<String>("label") ?: ""
                val longLabel = call.argument<String>("longLabel") ?: label
                val imageSource = call.argument<String>("imageSource") ?: ""
                val imageSourceType = call.argument<String>("imageSourceType") ?: "asset"
                val extraDataString = call.argument<String>("extraData")

                // Adaptive icon parameters
                val useAdaptiveIcon = call.argument<Boolean>("useAdaptiveIcon") ?: false
                val adaptiveIconForeground = call.argument<String>("adaptiveIconForeground")
                val adaptiveIconBackground = call.argument<String>("adaptiveIconBackground")
                val adaptiveIconBackgroundType = call.argument<String>("adaptiveIconBackgroundType") ?: "color"

                Log.d(TAG, "Creating shortcut: $id, type: $imageSourceType, useAdaptiveIcon: $useAdaptiveIcon")

                scope.launch {
                    val success = createShortcut(
                        id, label, longLabel, imageSource, imageSourceType, extraDataString,
                        useAdaptiveIcon, adaptiveIconForeground, adaptiveIconBackground, adaptiveIconBackgroundType
                    )
                    result.success(success)
                }
            }

            "isPinned" -> {
                val id = call.argument<String>("id") ?: ""
                Log.d(TAG, "Checking if shortcut is pinned: $id")

                val isPinned = isShortcutPinned(id)
                result.success(isPinned)
            }

            else -> {
                Log.w(TAG, "Method not implemented: ${call.method}")
                result.notImplemented()
            }
        }
    }

    //----------------------------------------------------------------------------------------------
    // Private methods
    //----------------------------------------------------------------------------------------------

    /**
     * Check if the intent was triggered by a shortcut click
     */
    private fun checkForShortcutIntent(intent: Intent?, isColdStart: Boolean): Boolean {
        if (intent == null) return false

        val shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID)
        val action = intent.action

        if (shortcutId != null || action == SHORTCUT_CLICKED_ACTION) {
            val resolvedId = shortcutId ?: intent.getStringExtra(EXTRA_SHORTCUT_ID)
            val shortcutData = intent.getStringExtra(EXTRA_SHORTCUT_DATA)

            if (resolvedId != null) {
                val returnMap = HashMap<String, Any?>()
                returnMap[EXTRA_SHORTCUT_ID] = resolvedId
                returnMap[EXTRA_SHORTCUT_DATA] = shortcutData

                if (isColdStart) {
                    // Save for getInitialShortcut request when Flutter initializes
                    initialShortcutData = returnMap
                    Log.d(TAG, "Stored cold-start shortcut data for ID: $resolvedId")
                } else {
                    // Push directly to active stream listener
                    try {
                        Log.d(TAG, "Shortcut clicked (running app): $resolvedId")
                        channel.invokeMethod("onShortcutClick", returnMap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error invoking method: ${e.message}")
                        e.printStackTrace()
                    }
                }
                return true
            }
        }
        return false
    }

    /**
     * Check if pinned shortcuts are supported on the device
     */
    private fun isShortcutSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            shortcutManager?.isRequestPinShortcutSupported ?: false
        } else {
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        }
    }

    /**
     * Create a new pinned shortcut
     */
    private suspend fun createShortcut(
        id: String,
        label: String,
        longLabel: String,
        imageSource: String,
        imageSourceType: String,
        extraDataString: String?,
        useAdaptiveIcon: Boolean = false,
        adaptiveIconForeground: String? = null,
        adaptiveIconBackground: String? = null,
        adaptiveIconBackgroundType: String = "color"
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Creating shortcut: $id")

                if (!isShortcutSupported()) {
                    Log.e(TAG, "Shortcuts not supported on this device")
                    return@withContext false
                }

                // Create the intent that will be launched when the shortcut is clicked
                val intent = Intent(context, activity?.javaClass)
                intent.action = SHORTCUT_CLICKED_ACTION
                intent.putExtra(EXTRA_SHORTCUT_ID, id)

                if (extraDataString != null) {
                    intent.putExtra(EXTRA_SHORTCUT_DATA, extraDataString)
                }

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

                // Load icons based on whether we should use adaptive icons
                var shortcutInfo: ShortcutInfoCompat? = null

                if (useAdaptiveIcon && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adaptiveIconForeground != null) {
                    Log.d(TAG, "Using adaptive icon for shortcut")

                    val foregroundIcon = loadIconFromSource(adaptiveIconForeground, imageSourceType)
                    if (foregroundIcon == null) {
                        Log.e(TAG, "Failed to load adaptive icon foreground")
                        return@withContext false
                    }

                    val builder = ShortcutInfoCompat.Builder(context, id)
                        .setShortLabel(label)
                        .setLongLabel(longLabel)
                        .setIntent(intent)

                    if (adaptiveIconBackgroundType == "color" && adaptiveIconBackground != null) {
                        try {
                            val backgroundColor = parseColor(adaptiveIconBackground)
                            val adaptiveIcon = createAdaptiveIcon(foregroundIcon, backgroundColor)
                            if (adaptiveIcon != null) {
                                builder.setIcon(adaptiveIcon)
                            } else {
                                builder.setIcon(foregroundIcon)
                                Log.w(TAG, "Failed to create adaptive icon, using foreground as fallback")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing background color: ${e.message}")
                            builder.setIcon(foregroundIcon)
                        }
                    } else if (adaptiveIconBackgroundType == "image" && adaptiveIconBackground != null) {
                        val backgroundIcon = loadIconFromSource(adaptiveIconBackground, imageSourceType)

                        if (backgroundIcon != null) {
                            val adaptiveIcon = createAdaptiveIconWithImageBackground(foregroundIcon, backgroundIcon)
                            if (adaptiveIcon != null) {
                                builder.setIcon(adaptiveIcon)
                            } else {
                                builder.setIcon(foregroundIcon)
                                Log.w(
                                    TAG,
                                    "Failed to create adaptive icon with image background, using foreground as fallback"
                                )
                            }
                        } else {
                            builder.setIcon(foregroundIcon)
                            Log.e(TAG, "Failed to load background image, using foreground only")
                        }
                    } else {
                        val adaptiveIcon = createAdaptiveIcon(foregroundIcon, Color.TRANSPARENT)
                        if (adaptiveIcon != null) {
                            builder.setIcon(adaptiveIcon)
                        } else {
                            builder.setIcon(foregroundIcon)
                        }
                    }

                    shortcutInfo = builder.build()
                } else {
                    Log.d(TAG, "Using regular icon for shortcut")

                    val icon = loadIconFromSource(imageSource, imageSourceType)
                        ?: return@withContext false

                    shortcutInfo = ShortcutInfoCompat.Builder(context, id)
                        .setShortLabel(label)
                        .setLongLabel(longLabel)
                        .setIcon(icon)
                        .setIntent(intent)
                        .build()
                }

                val settings = FlutterPinnedShortcutsSettings(context)
                settings.saveShortcut(id, label, longLabel, extraDataString)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                        val iconCompat = shortcutInfo.icon
                        if (iconCompat != null) {
                            val drawable = iconCompat.toIcon(context).loadDrawable(context)
                            val bitmap = Bitmap.createBitmap(
                                drawable!!.intrinsicWidth,
                                drawable.intrinsicHeight,
                                Bitmap.Config.ARGB_8888
                            )

                            val canvas = android.graphics.Canvas(bitmap)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)

                            val shortcutInfoApi26 = ShortcutInfo.Builder(context, id)
                                .setShortLabel(label)
                                .setLongLabel(longLabel)
                                .setIcon(Icon.createWithBitmap(bitmap))
                                .setIntent(intent)
                                .build()

                            shortcutManager?.addDynamicShortcuts(listOf(shortcutInfoApi26))
                            Log.d(TAG, "Added dynamic shortcut: $id")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding dynamic shortcut: ${e.message}")
                    }
                }

                val success = ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
                Log.d(TAG, "Shortcut creation requested: $id, result: $success")

                return@withContext success
            } catch (e: Exception) {
                Log.e(TAG, "Error creating shortcut: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    /**
     * Parse a color string to an integer color value
     */
    private fun parseColor(colorString: String): Int {
        return try {
            if (colorString.startsWith("#")) {
                return Color.parseColor(colorString)
            } else when (colorString.lowercase()) {
                "red" -> Color.RED
                "blue" -> Color.BLUE
                "green" -> Color.GREEN
                "black" -> Color.BLACK
                "white" -> Color.WHITE
                "gray", "grey" -> Color.GRAY
                "yellow" -> Color.YELLOW
                "cyan" -> Color.CYAN
                "magenta" -> Color.MAGENTA
                "transparent" -> Color.TRANSPARENT
                else -> {
                    try {
                        colorString.toInt()
                    } catch (e: Exception) {
                        Color.TRANSPARENT
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing color: $colorString, ${e.message}")
            Color.TRANSPARENT
        }
    }

    /**
     * Create an adaptive icon with a color background
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createAdaptiveIcon(foregroundIcon: IconCompat, backgroundColor: Int): IconCompat? {
        try {
            val foregroundDrawable = foregroundIcon.toIcon(context).loadDrawable(context) ?: return null
            val size = 108
            val foregroundBitmap = drawableToBitmap(foregroundDrawable, size, size)
            val backgroundBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            backgroundBitmap.eraseColor(backgroundColor)

            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            canvas.drawBitmap(backgroundBitmap, 0f, 0f, null)
            val left = (size - foregroundBitmap.width) / 2f
            val top = (size - foregroundBitmap.height) / 2f
            canvas.drawBitmap(foregroundBitmap, left, top, null)

            return IconCompat.createWithAdaptiveBitmap(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating adaptive icon: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * Create an adaptive icon with an image background
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createAdaptiveIconWithImageBackground(
        foregroundIcon: IconCompat,
        backgroundIcon: IconCompat
    ): IconCompat? {
        try {
            val foregroundDrawable = foregroundIcon.toIcon(context).loadDrawable(context)
            val backgroundDrawable = backgroundIcon.toIcon(context).loadDrawable(context)

            if (foregroundDrawable == null || backgroundDrawable == null) return null

            val size = 108
            val foregroundBitmap = drawableToBitmap(foregroundDrawable, size, size)
            val backgroundBitmap = drawableToBitmap(backgroundDrawable, size, size)

            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            canvas.drawBitmap(backgroundBitmap, 0f, 0f, null)
            val left = (size - foregroundBitmap.width) / 2f
            val top = (size - foregroundBitmap.height) / 2f
            canvas.drawBitmap(foregroundBitmap, left, top, null)

            return IconCompat.createWithAdaptiveBitmap(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating adaptive icon with image background: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * Convert a drawable to a bitmap
     */
    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Check if a shortcut is pinned
     */
    private fun isShortcutPinned(id: String): Boolean {
        Log.d(TAG, "Checking if shortcut is pinned: $id")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                val isPinned = shortcutManager?.pinnedShortcuts?.any { it.id == id } ?: false

                if (!isPinned) {
                    val settings = FlutterPinnedShortcutsSettings(context)
                    return settings.hasShortcut(id)
                }

                return isPinned
            } else {
                val settings = FlutterPinnedShortcutsSettings(context)
                return settings.hasShortcut(id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if shortcut is pinned: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * Load an icon from various sources (asset, resource, file, etc.)
     */
    private suspend fun loadIconFromSource(
        source: String,
        sourceType: String
    ): IconCompat? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Loading icon from $sourceType: $source")

                when (sourceType.lowercase()) {
                    "asset" -> {
                        val assetManager = context.assets
                        val inputStream = assetManager.open("flutter_assets/$source")
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream.close()

                        if (bitmap != null) {
                            IconCompat.createWithBitmap(bitmap)
                        } else null
                    }

                    "resource" -> {
                        val resourceId = context.resources.getIdentifier(
                            source, "drawable", context.packageName
                        )

                        if (resourceId != 0) {
                            IconCompat.createWithResource(context, resourceId)
                        } else null
                    }

                    "network", "file" -> {
                        val file = File(source)
                        if (file.exists()) {
                            val inputStream = FileInputStream(file)
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            inputStream.close()

                            if (bitmap != null) {
                                IconCompat.createWithBitmap(bitmap)
                            } else null
                        } else null
                    }

                    else -> null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading icon: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }
}