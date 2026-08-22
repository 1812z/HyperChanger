package btm.m.os4.systemuihook

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

object SettingsAppearanceApplier {
    private val layers = Collections.synchronizedMap(WeakHashMap<Activity, LayerSession>())
    private val deviceLayers = Collections.synchronizedMap(WeakHashMap<Any, DeviceLayerSession>())
    private val originalTextColors = Collections.synchronizedMap(WeakHashMap<TextView, Int>())
    private val textModes = Collections.synchronizedMap(WeakHashMap<TextView, Int>())
    private val logoSessions = Collections.synchronizedMap(WeakHashMap<Any, LogoSession>())
    private val cardSessions = Collections.synchronizedMap(WeakHashMap<Activity, CardAlphaSession>())
    private val internalTextColor = ThreadLocal<Boolean>()
    private val internalLogo = ThreadLocal<Boolean>()

    fun applyHome(activity: Activity) = applyActivity(activity, APPEARANCE_SLOT_HOME)
    fun shouldSuppressDeviceShader(fragment: Any): Boolean = runCatching {
        val context = fragment.javaClass.getMethod("getContext").invoke(fragment) as? android.content.Context ?: return false
        SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE).exists
    }.getOrDefault(false)

    fun applyDevice(fragment: Any) {
        runCatching {
            val context = fragment.javaClass.getMethod("getContext").invoke(fragment) as? android.content.Context ?: return
            val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE)
            fragmentActivity(fragment)?.let { applyCardOpacity(it, source.lightCardOpacity) }
            val old = deviceLayers[fragment]
            Log.i(TAG, "device apply class=${fragment.javaClass.name} exists=${source.exists} enabled=${source.enabled} mime=${source.mime} size=${source.size}")
            if (!source.exists) {
                old?.remove()
                deviceLayers.remove(fragment)
                fragmentActivity(fragment)?.let { applyFontMode(it, source.fontMode) }
                return
            }
            if (old != null && old.view.sourceKey() == source.cacheKey() && old.view.parent === old.parent) {
                stopOriginalDeviceShader(fragment, old.systemBackground)
                old.systemBackground.visibility = View.INVISIBLE
                old.view.onHostResume()
                old.refresh(context)
                fragmentActivity(fragment)?.let { applyFontMode(it, source.fontMode) }
                return
            }
            val background = findDeviceBackground(fragment, context) ?: run {
                Log.w(TAG, "device fragment has no mBgEffectView class=${fragment.javaClass.name}")
                return
            }
            val parent = background.parent as? ViewGroup ?: return
            Log.i(TAG, "device target=${background.javaClass.name} parent=${parent.javaClass.name} index=${parent.indexOfChild(background)} visibility=${background.visibility}")
            old?.remove()
            stopOriginalDeviceShader(fragment, background)
            val media = SettingsBackgroundView(context, source)
            val index = parent.indexOfChild(background).coerceAtLeast(0)
            parent.addView(media, index + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val session = DeviceLayerSession(parent, background, background.visibility, media)
            background.visibility = View.INVISIBLE
            deviceLayers[fragment] = session
            Log.i(TAG, "device attached media=${media.javaClass.name} parent=${media.parent?.javaClass?.name}")
            fragmentActivity(fragment)?.let { applyFontMode(it, source.fontMode) }
        }.onFailure { error -> Log.e(TAG, "device apply failed", error) }
    }

    fun applyDevice(activity: Activity) {
        runCatching {
            val source = SettingsAppearanceSources.query(activity, APPEARANCE_SLOT_DEVICE)
            applyCardOpacity(activity, source.lightCardOpacity)
            val old = deviceLayers[activity]
            if (!source.exists) {
                old?.remove()
                deviceLayers.remove(activity)
                applyFontMode(activity, source.fontMode)
                return
            }
            val id = activity.resources.getIdentifier("bgEffectView", "id", activity.packageName)
            val background = activity.findViewById<View>(id) ?: return
            val parent = background.parent as? ViewGroup ?: return
            Log.i(TAG, "device background hit activity=${activity.javaClass.name} id=$id source=${source.cacheKey()}")
            if (old != null && old.view.sourceKey() == source.cacheKey() && old.view.parent === old.parent) {
                old.view.onHostResume()
                old.refresh(activity)
                applyFontMode(activity, source.fontMode)
                return
            }
            old?.remove()
            background.setRenderEffect(null)
            val media = SettingsBackgroundView(activity, source)
            val index = parent.indexOfChild(background).coerceAtLeast(0)
            parent.addView(media, index + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val session = DeviceLayerSession(parent, background, background.visibility, media)
            background.visibility = View.INVISIBLE
            deviceLayers[activity] = session
            applyFontMode(activity, source.fontMode)
        }
    }

    fun stopDevice(fragment: Any?) {
        fragment?.let { deviceLayers[it]?.view?.onHostStop() }
    }
    fun destroyDevice(fragment: Any?) {
        if (fragment == null) return
        deviceLayers.remove(fragment)?.remove()
    }
    fun stop(activity: Activity?) { activity?.let { layers[it]?.view?.onHostStop() } }
    fun destroy(activity: Activity?) {
        if (activity == null) return
        layers.remove(activity)?.remove()
        cardSessions.remove(activity)?.remove()
        restoreTextColors(activity.window?.decorView)
    }

    fun destroyLogo(fragment: Any?) {
        if (fragment == null) return
        logoSessions.remove(fragment)?.restore()
    }

    private fun fragmentActivity(fragment: Any): Activity? = runCatching {
        fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
    }.getOrNull()

    private fun findDeviceBackground(fragment: Any, context: android.content.Context): View? {
        runCatching {
            getObjectField(fragment, "mBgEffectView") as? View
        }.getOrNull()?.let { return it }
        val root = fragment.javaClass.getMethod("getView").invoke(fragment) as? View ?: return null
        val id = context.resources.getIdentifier("bgEffectView", "id", context.packageName)
        return root.findViewById(id)
    }

    fun applyLogo(fragment: Any) {
        runCatching {
            val context = fragment.javaClass.getMethod("getContext").invoke(fragment) as? android.content.Context ?: return
            val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_LOGO)
            val root = fragment.javaClass.getMethod("getView").invoke(fragment) as? View ?: return
            val existing = logoSessions[fragment]
            val target = existing?.view ?: findLogoView(context, root) ?: return
            if (!source.exists || source.logoMode == LOGO_MODE_SYSTEM) {
                existing?.restore()
                logoSessions.remove(fragment)
                return
            }
            val session = existing ?: LogoSession(target).also { logoSessions[fragment] = it }
            val drawable = LogoDrawableLoader.load(context, source) ?: run {
                Log.e(TAG, "logo drawable load returned null mime=${source.mime} size=${source.size}")
                return
            }
            applyLogoDrawable(target, drawable)
            session.applyMaterialPolicy(source.logoMode == LOGO_MODE_NO_ADVANCED_MATERIAL)
        }.onFailure { error -> Log.e(TAG, "logo apply failed", error) }
    }

    fun applyLogo(activity: Activity) {
        runCatching {
            val source = SettingsAppearanceSources.query(activity, APPEARANCE_SLOT_LOGO)
            val root = activity.window?.decorView ?: return
            val existing = logoSessions[activity]
            val target = existing?.view ?: findLogoView(activity, root) ?: return
            Log.i(TAG, "logo hit activity=${activity.javaClass.name} id=${target.id} source=${source.cacheKey()}")
            if (!source.exists || source.logoMode == LOGO_MODE_SYSTEM) {
                existing?.restore()
                logoSessions.remove(activity)
                return
            }
            val session = existing ?: LogoSession(target).also { logoSessions[activity] = it }
            val drawable = LogoDrawableLoader.load(activity, source) ?: return
            applyLogoDrawable(target, drawable)
            session.applyMaterialPolicy(source.logoMode == LOGO_MODE_NO_ADVANCED_MATERIAL)
        }
    }

    private fun sourceScale(context: android.content.Context): Float {
        return SettingsAppearanceSources.query(context, APPEARANCE_SLOT_LOGO).scale / 100f
    }

    fun logoReplacement(view: ImageView): Drawable? {
        if (internalLogo.get() == true || view.context.packageName != "com.android.settings") return null
        val idName = runCatching { view.resources.getResourceEntryName(view.id).lowercase() }.getOrDefault("")
        if (idName != "miui_logo_view" && !idName.contains("logo")) return null
        val source = SettingsAppearanceSources.query(view.context, APPEARANCE_SLOT_LOGO)
        if (!source.exists || source.logoMode == LOGO_MODE_SYSTEM) return null
        return LogoDrawableLoader.load(view.context, source)
    }

    fun logoResourceReplacement(context: android.content.Context, resources: Resources, resourceId: Int): Drawable? {
        return logoResourceReplacementInternal(context, resources, resourceId)
    }

    fun logoResourceReplacement(resources: Resources, resourceId: Int): Drawable? {
        val context = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        }.getOrNull() ?: return null
        return logoResourceReplacementInternal(context, resources, resourceId)
    }

    private fun logoResourceReplacementInternal(
        context: android.content.Context,
        resources: Resources,
        resourceId: Int,
    ): Drawable? {
        if (internalLogo.get() == true || context.packageName != "com.android.settings") return null
        val packageName = runCatching { resources.getResourcePackageName(resourceId) }.getOrNull()
        if (packageName != null && packageName != "com.android.settings") return null
        val name = runCatching { resources.getResourceEntryName(resourceId).lowercase() }.getOrNull() ?: return null
        val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_LOGO)
        if (!source.exists || source.logoMode == LOGO_MODE_SYSTEM || !isLogoResource(name, source.logoMode)) return null
        val drawable = LogoDrawableLoader.load(context, source) ?: run {
            Log.e(TAG, "logo resource replacement load failed name=$name mime=${source.mime} size=${source.size}")
            return null
        }
        Log.i(TAG, "logo resource hit name=$name mode=${source.logoMode} mime=${source.mime}")
        return LogoDrawableLoader.forBackground(drawable, source.scale / 100f)
    }

    private fun isLogoResource(name: String, mode: Int): Boolean {
        val xiaomi = name == "xiaomi_os_logo" || name == "xiaomi_os_logo_new" || name == "xiaomi_os_logo_new_lite"
        val provision = name == "provision_os_logo" || name == "provision_os_logo_big" ||
            name == "provision_os_logo_lite" || name == "provision_os_logo_small"
        return when (mode) {
            LOGO_MODE_NO_ADVANCED_MATERIAL -> xiaomi
            LOGO_MODE_KEEP_ADVANCED_MATERIAL -> provision || xiaomi
            else -> false
        }
    }

    fun cardColorResourceReplacement(
        context: android.content.Context,
        resources: Resources,
        resourceId: Int,
        resolvedColor: Int,
    ): Int? {
        if (context.packageName != "com.android.settings" || !isLightMode(resources)) return null
        val packageName = runCatching { resources.getResourcePackageName(resourceId) }.getOrNull().orEmpty()
        val name = runCatching { resources.getResourceEntryName(resourceId).lowercase() }.getOrNull() ?: return null
        val opacity = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_HOME)
            .lightCardOpacity.coerceIn(0, 100)
        if (opacity >= 100) return null
        if (name !in LIGHT_CARD_COLOR_RESOURCES) return null
        val alpha = opacity * 255 / 100
        val replacement = (alpha shl 24) or 0x00FFFFFF
        Log.i(
            TAG,
            "card color resource hit package=$packageName name=$name original=0x${resolvedColor.toUInt().toString(16)} replacement=0x${replacement.toUInt().toString(16)}",
        )
        return replacement
    }

    fun cardColorStateListResourceReplacement(
        context: android.content.Context,
        resources: Resources,
        resourceId: Int,
        original: ColorStateList,
    ): ColorStateList? = cardColorResourceReplacement(
        context,
        resources,
        resourceId,
        original.defaultColor,
    )?.let(ColorStateList::valueOf)

    fun cardFinalColorReplacement(view: View, original: Int): Int? {
        if (view.context.packageName != "com.android.settings" || !isLightMode(view)) return null
        if (!isCardLike(view)) return null
        val opacity = lightCardOpacity(view.context)
        if (opacity >= 100) return null
        return cardColor(opacity)
    }

    fun cardFinalStateListReplacement(view: View, original: ColorStateList): ColorStateList? {
        return cardFinalColorReplacement(view, original.defaultColor)?.let(ColorStateList::valueOf)
    }

    fun cardFinalDrawableReplacement(view: View, drawable: Drawable) {
        if (view.context.packageName != "com.android.settings" || !isLightMode(view)) return
        if (!isCardLike(view)) return
        val opacity = lightCardOpacity(view.context)
        if (opacity >= 100) return

        // HyperCardView may recreate its drawable after the resource lookup. For
        // plain color drawables, replace the actual color; for vendor drawables,
        // retain their shape and apply the requested alpha at the final setter.
        runCatching {
            val colorDrawable = drawable as? ColorDrawable
            if (colorDrawable != null) colorDrawable.color = cardColor(opacity)
            else drawable.mutate().alpha = opacity * 255 / 100
        }
    }

    private fun lightCardOpacity(context: android.content.Context): Int =
        SettingsAppearanceSources.query(context, APPEARANCE_SLOT_HOME).lightCardOpacity.coerceIn(0, 100)

    private fun cardColor(opacity: Int): Int = (opacity * 255 / 100 shl 24) or 0x00FFFFFF

    private fun isLightMode(resources: Resources): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES

    private val LIGHT_CARD_COLOR_RESOURCES = setOf(
        "card_background_stroke_color",
        "card_style_summary_normal_color",
        "card_view_background_color",
        "cardview_light_background",
        "default_home_preference_item_background",
        "device_card_background",
        "list_card_background",
        "locale_cardview_background_color",
        "miuix_default_card_drawable_color_light",
        "miuix_default_color_container_list_light",
        "my_card_bg",
        "miuix_preference_card_group_background_light",
        "miuix_preference_card_group_background_color_light",
        "miuix_recyclerview_card_group_background_light",
        "wifi_cardview_background_color",
    )

    fun applyLogoDrawable(view: ImageView, drawable: Drawable) {
        internalLogo.set(true)
        try {
            view.visibility = View.VISIBLE
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.scaleX = 1f
            view.scaleY = 1f
            view.setImageDrawable(drawable)
            view.scaleX = SettingsAppearanceSources.query(view.context, APPEARANCE_SLOT_LOGO).scale / 100f
            view.scaleY = view.scaleX
        } finally {
            internalLogo.remove()
        }
    }

    fun cardBlurAlpha(view: View): Float? {
        if (view.context.packageName != "com.android.settings") return null
        val source = SettingsAppearanceSources.query(view.context, APPEARANCE_SLOT_DEVICE)
        if (!isLightMode(view) || source.lightCardOpacity >= 100 || !isCardLike(view)) return null
        return source.lightCardOpacity.coerceIn(0, 100) / 100f
    }

    private fun applyActivity(activity: Activity, slot: String) {
        runCatching {
            val source = SettingsAppearanceSources.query(activity, slot)
            applyCardOpacity(activity, source.lightCardOpacity)
            val old = layers[activity]
            if (!source.exists) {
                old?.remove()
                layers.remove(activity)
                applyFontMode(activity, source.fontMode)
                return
            }
            val content = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            if (old != null && old.view.sourceKey() == source.cacheKey() && old.view.parent === content) {
                old.view.onHostResume()
                applyFontMode(activity, source.fontMode)
                return
            }
            old?.remove()
            val media = SettingsBackgroundView(activity, source)
            val session = LayerSession(content, media)
            session.clear(content)
            content.getChildAt(0)?.let { session.clear(it) }
            clearNamedSurfaces(activity, session)
            content.addView(media, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            session.attach(activity)
            layers[activity] = session
            applyFontMode(activity, source.fontMode)
        }
    }

    private fun applyFontMode(activity: Activity, mode: Int) {
        applyTextColor(activity.window?.decorView, mode)
    }

    /** Mirrors HyperBackground: remember each TextView's original color and reapply the selected mode. */
    private fun applyTextColor(view: View?, mode: Int) {
        if (view is TextView) {
            if (mode == 0) {
                textModes.remove(view)
                originalTextColors.remove(view)?.let { color ->
                    internalTextColor.set(true)
                    try { view.setTextColor(color) } finally { internalTextColor.remove() }
                }
            } else {
                if (!originalTextColors.containsKey(view)) originalTextColors[view] = view.currentTextColor
                textModes[view] = mode
                internalTextColor.set(true)
                try { view.setTextColor(forcedTextColor(mode)) } finally { internalTextColor.remove() }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) applyTextColor(view.getChildAt(i), mode)
    }

    private fun forcedTextColor(mode: Int): Int = if (mode == 1) Color.WHITE else Color.rgb(24, 24, 26)

    /** Called by the TextView hooks so MIUIX rebinding cannot undo a selected font mode. */
    fun overrideTextColor(view: TextView, argument: Any?, colorStateList: Boolean): Any? {
        if (internalTextColor.get() == true) return argument
        val mode = textModes[view] ?: fallbackFontMode(view)
        if (mode == 0) return argument
        val color = forcedTextColor(mode)
        return if (colorStateList) ColorStateList.valueOf(color) else color
    }

    private fun fallbackFontMode(view: TextView): Int = runCatching {
        val context = view.context
        val deviceId = context.resources.getIdentifier("bgEffectView", "id", context.packageName)
        val isDevicePage = deviceId != 0 && view.rootView.findViewById<View>(deviceId) != null
        SettingsAppearanceSources.query(context, if (isDevicePage) APPEARANCE_SLOT_DEVICE else APPEARANCE_SLOT_HOME).fontMode
    }.getOrDefault(0)

    private fun restoreTextColors(view: View?) {
        if (view is TextView) {
            textModes.remove(view)
            originalTextColors.remove(view)?.let { color ->
                internalTextColor.set(true)
                try { view.setTextColor(color) } finally { internalTextColor.remove() }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) restoreTextColors(view.getChildAt(i))
    }

    private fun applyCardOpacity(activity: Activity, opacity: Int) {
        val session = cardSessions[activity] ?: CardAlphaSession(activity).also { cardSessions[activity] = it }
        session.apply(opacity.coerceIn(0, 100))
    }

    private fun clearNamedSurfaces(activity: Activity, session: LayerSession) {
        listOf(
            "nestedheaderlayout", "scroll_headers", "main_content", "prefs_container",
            "preference_recyclerview", "recycler_view", "content", "content_view",
            "content_wrapper", "action_bar_activity_content", "area_content", "auto_content",
        ).forEach { name ->
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            if (id != 0) activity.findViewById<View>(id)?.let(session::clear)
        }
    }

    private fun stopOriginalDeviceShader(fragment: Any, background: View) {
        runCatching {
            val controller = getObjectField(fragment, "mBgEffectController")
            controller?.javaClass?.methods?.firstOrNull { it.name == "stop" && it.parameterCount == 0 }?.invoke(controller)
        }
        runCatching { background.setRenderEffect(null) }
    }

    private fun getObjectField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null && type != Any::class.java) {
            runCatching {
                return type!!.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }
            type = type.superclass
        }
        return null
    }

    private fun findLogoView(context: android.content.Context, root: View): ImageView? {
        val exactId = context.resources.getIdentifier("miui_logo_view", "id", context.packageName)
        if (exactId != 0) root.findViewById<ImageView>(exactId)?.let { return it }
        var fallback: ImageView? = null
        fun visit(view: View) {
            if (view is ImageView) {
                val id = runCatching { context.resources.getResourceEntryName(view.id).lowercase() }.getOrDefault("")
                val score = when {
                    id == "miui_logo_view" -> 100
                    id.contains("logo") -> 80
                    id.contains("device") && id.contains("image") -> 50
                    else -> 0
                }
                if (score > 0 && (fallback == null || score > logoScore(context, fallback!!))) fallback = view
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        return fallback
    }

    private fun logoScore(context: android.content.Context, view: ImageView): Int {
        val id = runCatching { context.resources.getResourceEntryName(view.id).lowercase() }.getOrDefault("")
        return when {
            id == "miui_logo_view" -> 100
            id.contains("logo") -> 80
            id.contains("device") && id.contains("image") -> 50
            else -> 0
        }
    }

    private class LogoSession(val view: ImageView) {
        private val originalDrawable = view.drawable
        private val originalScaleX = view.scaleX
        private val originalScaleY = view.scaleY
        private val materialViews = ArrayList<Pair<View, Drawable?>>()
        private var materialCleared = false

        fun applyMaterialPolicy(removeMaterial: Boolean) {
            if (!removeMaterial) {
                restoreMaterial()
                return
            }
            if (materialCleared) return
            var current: View? = view
            repeat(4) {
                val target = current ?: return@repeat
                if (target !== view) {
                    materialViews += target to target.background
                    target.background = null
                }
                current = (target.parent as? View)
            }
            materialCleared = true
        }

        private fun restoreMaterial() {
            materialViews.asReversed().forEach { (target, background) -> target.background = background }
            materialViews.clear()
            materialCleared = false
        }

        fun restore() {
            view.setImageDrawable(originalDrawable)
            view.scaleX = originalScaleX
            view.scaleY = originalScaleY
            restoreMaterial()
        }
    }

    private class LayerSession(val parent: ViewGroup, val view: SettingsBackgroundView) {
        private val backgrounds = ArrayList<Pair<View, Drawable?>>()
        private var observer: ViewTreeObserver.OnGlobalLayoutListener? = null
        fun clear(target: View) {
            if (target === view || backgrounds.any { it.first === target }) return
            backgrounds += target to target.background
            target.background = null
        }
        fun remove() {
            observer?.let { listener ->
                runCatching { parent.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
            }
            observer = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.dispose()
            backgrounds.asReversed().forEach { (target, background) -> target.background = background }
            backgrounds.clear()
        }

        fun attach(activity: Activity) {
            refresh(activity)
            val listener = ViewTreeObserver.OnGlobalLayoutListener { refresh(activity) }
            observer = listener
            runCatching { parent.viewTreeObserver.addOnGlobalLayoutListener(listener) }
        }

        fun refresh(activity: Activity) {
            clearNamedSurfaces(activity, this)
            val root = parent.getChildAt(0) ?: return
            clearPageSurfaces(activity, root, root)
        }

        private fun clearPageSurfaces(activity: Activity, view: View, root: View) {
            if (view === this.view || view.visibility != View.VISIBLE) return
            if (isPageSurface(activity, view, root)) clear(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    clearPageSurfaces(activity, view.getChildAt(index), root)
                }
            }
        }

        private fun isPageSurface(activity: Activity, view: View, root: View): Boolean {
            if (view === root) return true
            if (view.background == null) return false
            val metrics = activity.resources.displayMetrics
            val rootWidth = maxOf(root.width, metrics.widthPixels)
            val rootHeight = maxOf(root.height, metrics.heightPixels)
            val large = view.width >= rootWidth * 0.72f && view.height >= rootHeight * 0.32f
            if (!large) return false
            val idName = runCatching {
                if (view.id == View.NO_ID || view.id == 0) ""
                else activity.resources.getResourceEntryName(view.id).lowercase()
            }.getOrDefault("")
            val cls = view.javaClass.name.lowercase()
            if (containsAny(idName, "card", "button", "switch", "checkbox", "icon", "avatar", "image", "banner", "header_card")) return false
            if (containsAny(cls, "cardview", "button", "switch", "checkbox", "imageview")) return false
            if (containsAny(idName, "content", "container", "recycler", "list", "prefs", "preference", "nestedheader", "scroll", "fragment", "root", "main", "area", "panel")) return true
            if (containsAny(cls, "recyclerview", "nestedscrollview", "scrollview", "listview", "coordinatorlayout", "fragmentcontainerview", "viewpager")) return true
            return view is ViewGroup && view.width >= rootWidth * 0.90f && view.height >= rootHeight * 0.62f
        }

        private fun containsAny(value: String, vararg needles: String): Boolean {
            return needles.any(value::contains)
        }
    }

    private class CardAlphaSession(private val activity: Activity) {
        private data class Entry(val view: View, val drawable: Drawable, val alpha: Int)
        private val entries = ArrayList<Entry>()
        private val root: View = activity.window?.decorView ?: returnRoot(activity)
        private val listener = ViewTreeObserver.OnGlobalLayoutListener { refresh(currentOpacity) }
        private var currentOpacity = 100

        init { runCatching { root.viewTreeObserver.addOnGlobalLayoutListener(listener) } }

        fun apply(opacity: Int) {
            currentOpacity = opacity
            if (!isLightMode() || opacity >= 100) {
                restore()
                return
            }
            refresh(opacity)
        }

        private fun refresh(opacity: Int) {
            if (!isLightMode() || opacity >= 100) { restore(); return }
            visit(root, opacity)
        }

        private fun visit(view: View, opacity: Int) {
            if (isCard(view)) {
                val drawable = view.background
                if (drawable != null && entries.none { it.view === view && it.drawable === drawable }) {
                    entries += Entry(view, drawable, drawable.alpha)
                }
                drawable?.mutate()?.alpha = opacity * 255 / 100
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), opacity)
        }

        private fun isCard(view: View): Boolean {
            if (view.width <= 0 || view.height <= 0) return false
            val name = runCatching {
                if (view.id == View.NO_ID || view.id == 0) ""
                else activity.resources.getResourceEntryName(view.id).lowercase()
            }.getOrDefault("")
            val cls = view.javaClass.name.lowercase()
            return (name.contains("card") || cls.contains("card")) &&
                !name.contains("icon") && !name.contains("button") &&
                view.width >= activity.resources.displayMetrics.widthPixels * 0.55f
        }

        private fun isLightMode(): Boolean =
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES

        private fun restore() {
            entries.forEach { entry -> runCatching { entry.drawable.alpha = entry.alpha } }
            entries.clear()
        }

        fun remove() {
            runCatching { root.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
            restore()
        }

        private fun returnRoot(activity: Activity): View = activity.window?.decorView ?: View(activity)
    }

    private fun isLightMode(view: View): Boolean =
        view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES

    private fun isCardLike(view: View): Boolean {
        val name = runCatching {
            if (view.id == View.NO_ID || view.id == 0) "" else view.resources.getResourceEntryName(view.id).lowercase()
        }.getOrDefault("")
        val cls = view.javaClass.name.lowercase()
        return (name.contains("card") || cls.contains("card")) &&
            !name.contains("icon") && !name.contains("button") && view.width > 0 && view.height > 0
    }

    private class DeviceLayerSession(
        val parent: ViewGroup,
        val systemBackground: View,
        private val originalVisibility: Int,
        val view: SettingsBackgroundView,
    ) {
        fun refresh(context: android.content.Context) {
            if (view.parent === parent) view.onHostResume()
        }
        fun remove() {
            (view.parent as? ViewGroup)?.removeView(view)
            view.dispose()
            systemBackground.visibility = originalVisibility
        }
    }

    private const val TAG = "HyperChangerSettingsAppearance"
}
