package btm.m.leicaunlocker.hook;

import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import btm.m.leicaunlocker.shared.ModuleConfig;
import io.github.libxposed.api.XposedModule;

public final class LeicaUnlockHook extends XposedModule {
    private static final String TAG = "LeicaUnlocker";
    private static final String CAMERA_CONFIG_FACTORY = "Je.e";
    private static final String LEGENDARY_VENDOR_TAG = "com.xiaomi.sessionparams.legendMode";
    private static final int LEGENDARY_MODE_M9 = 1;
    private static final int LEGENDARY_MODE_M3 = 2;
    private static final Set<String> EXCLUSIVE_LEICA_WATERMARK_IDS =
            Set.of("88", "89", "90", "91", "92", "111");
    private static final String[] FOCAL_CONFIG_METHODS = {
            "e1", "K0", "v1", "y0", "A1", "C1", "x1", "q0"
    };

    private volatile SharedPreferences preferences;
    private volatile boolean targetProcess;
    private volatile Object nativeCameraConfig;
    private volatile Object nezhaCameraConfig;
    private final Map<String, Method> nativeFocalMethods = new ConcurrentHashMap<>();
    private final Map<CaptureRequest.Builder, Integer> legendaryBuilders =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Builder.set() is hooked below; prevent fallback writes from re-entering mode tracking. */
    private final ThreadLocal<Boolean> legendaryFallbackWrite =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Set<Class<?>> galleryWatermarkManagers = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkCapabilityClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkUsageClasses = ConcurrentHashMap.newKeySet();
    private Method cameraConfigGetter;
    private Field cameraConfigCacheField;
    private Class<?> deviceSelectorClass;
    private Object deviceConfigLazy;
    private Map<Field, Object> deviceConfigEvaluatedState;
    private Field deviceConfigValueField;
    private boolean cameraFactoryTouched;
    private volatile boolean galleryWatermarkClassLoadHookInstalled;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        targetProcess = ModuleConfig.isSupportedProcess(param.getProcessName());
        if (!targetProcess) {
            detach();
            return;
        }

        try {
            preferences = getRemotePreferences(ModuleConfig.PREFERENCE_GROUP);
            log(Log.INFO, TAG, "Loaded in " + param.getProcessName() + " with API " + getApiVersion());
        } catch (RuntimeException error) {
            log(Log.WARN, TAG, "Remote preferences are unavailable; using enabled defaults", error);
        }
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!isTargetPackage(param.getPackageName(), param.isFirstPackage()) || !isEnabled()) {
            return;
        }
        if (!ModuleConfig.TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        boolean preserveNativeFocalLengths = pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true);

        if (preserveNativeFocalLengths) {
            captureNativeFocalLengthConfig(param.getDefaultClassLoader());
        }
        installSystemPropertyHooks();
        applyBuildProfile();
        if (preserveNativeFocalLengths
                && nativeCameraConfig != null
                && activateNezhaCameraConfig()) {
            installNativeFocalLengthHooks();
            installNativeWatermarkLabelHook();
        } else if (cameraFactoryTouched) {
            resetCameraFactoryForNezha();
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!isTargetPackage(param.getPackageName(), param.isFirstPackage()) || !isEnabled()) {
            return;
        }

        ClassLoader classLoader = param.getClassLoader();
        if (ModuleConfig.isGalleryPackage(param.getPackageName())) {
            installGalleryWatermarkHooks(classLoader);
            return;
        }
        if (!ModuleConfig.TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        installSecurityCompatibilityHooks(classLoader);
        installExclusiveWatermarkFilterHook(classLoader);
        installCameraWatermarkCatalogHooks(classLoader);
        installLegendaryFallbackHook();
    }

    private boolean isTargetPackage(String packageName, boolean firstPackage) {
        return targetProcess && firstPackage && ModuleConfig.isSupportedPackage(packageName);
    }

    private boolean isEnabled() {
        return pref(ModuleConfig.KEY_MASTER_ENABLED, true);
    }

    private boolean pref(String key, boolean defaultValue) {
        SharedPreferences current = preferences;
        return current == null ? defaultValue : current.getBoolean(key, defaultValue);
    }

    private void installSystemPropertyHooks() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties", false, null);
            hookPropertyGetter(systemProperties.getDeclaredMethod("get", String.class), "system_property_get");
            hookPropertyGetter(
                    systemProperties.getDeclaredMethod("get", String.class, String.class),
                    "system_property_get_default"
            );
            log(Log.INFO, TAG, "System property profile hooks installed");
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR, TAG, "Unable to install SystemProperties hooks", error);
        }
    }

    private void hookPropertyGetter(Method method, String id) {
        method.setAccessible(true);
        hook(method)
                .setPriority(PRIORITY_HIGHEST)
                .setId(id)
                .intercept(chain -> {
                    if (!isEnabled()) {
                        return chain.proceed();
                    }
                    String key = (String) chain.getArg(0);
                    String replacement = ModuleConfig.propertyOverride(
                            key,
                            pref(ModuleConfig.KEY_LEICA_UI, true)
                    );
                    return replacement != null ? replacement : chain.proceed();
                });
    }

    private void applyBuildProfile() {
        Map<String, String> values = Map.of(
                "DEVICE", "nezha",
                "PRODUCT", "nezha",
                "MODEL", "25128PNA1C",
                "BRAND", "Xiaomi",
                "MANUFACTURER", "Xiaomi"
        );

        for (Map.Entry<String, String> entry : values.entrySet()) {
            setStaticStringField(Build.class, entry.getKey(), entry.getValue());
        }
        log(Log.INFO, TAG, "Build profile applied: nezha / 25128PNA1C");
    }

    private void captureNativeFocalLengthConfig(ClassLoader classLoader) {
        Map<Field, Object> lazyState = null;
        Object lazy = null;
        Field cachedConfig = null;
        try {
            Class<?> selectorClass = Class.forName("Je.a", true, classLoader);
            lazy = findDeviceConfigLazy(selectorClass);
            lazyState = snapshotMutableFields(lazy);
            Class<?> factoryClass = Class.forName(CAMERA_CONFIG_FACTORY, true, classLoader);
            Method getConfig = factoryClass.getDeclaredMethod("G0");
            getConfig.setAccessible(true);
            cachedConfig = factoryClass.getDeclaredField("b");
            cachedConfig.setAccessible(true);

            cameraFactoryTouched = true;
            Object localConfig = getConfig.invoke(null);
            if (localConfig == null) {
                throw new IllegalStateException("The native camera configuration is null");
            }

            Map<Field, Object> evaluatedState = snapshotMutableFields(lazy);
            Field valueField = findEvaluatedStringField(evaluatedState);
            cachedConfig.set(null, null);
            restoreMutableFields(lazy, lazyState);
            cameraConfigGetter = getConfig;
            cameraConfigCacheField = cachedConfig;
            deviceSelectorClass = selectorClass;
            deviceConfigLazy = lazy;
            deviceConfigEvaluatedState = evaluatedState;
            deviceConfigValueField = valueField;
            nativeCameraConfig = localConfig;
            nativeFocalMethods.clear();
            log(Log.INFO, TAG, "Captured native focal configuration " + localConfig.getClass().getName());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            clearFactoryCache(cachedConfig);
            restoreMutableFields(lazy, lazyState);
            nativeCameraConfig = null;
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            log(
                    Log.WARN,
                    TAG,
                    "Unable to preserve native focal configuration; continuing with the Nezha profile",
                    error
            );
        }
    }

    private Object findDeviceConfigLazy(Class<?> selectorClass) throws ReflectiveOperationException {
        for (Field field : selectorClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (value == null) {
                continue;
            }
            try {
                value.getClass().getMethod("getValue");
                return value;
            } catch (NoSuchMethodException ignored) {
                // Continue until the Kotlin Lazy used for the device selector is found.
            }
        }
        throw new NoSuchFieldException("Device configuration Lazy field");
    }

    private Map<Field, Object> snapshotMutableFields(Object owner) throws IllegalAccessException {
        Map<Field, Object> state = new HashMap<>();
        for (Field field : owner.getClass().getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }
            field.setAccessible(true);
            state.put(field, field.get(owner));
        }
        return state;
    }

    private Field findEvaluatedStringField(Map<Field, Object> state) throws NoSuchFieldException {
        for (Map.Entry<Field, Object> entry : state.entrySet()) {
            if (entry.getValue() instanceof String) {
                return entry.getKey();
            }
        }
        throw new NoSuchFieldException("Evaluated Camera device selector value");
    }

    private void restoreMutableFields(Object owner, Map<Field, Object> state) {
        if (owner == null || state == null) {
            return;
        }
        for (Map.Entry<Field, Object> entry : state.entrySet()) {
            try {
                entry.getKey().set(owner, entry.getValue());
            } catch (IllegalAccessException | RuntimeException error) {
                log(Log.WARN, TAG, "Unable to restore Camera device selector state", error);
            }
        }
    }

    private boolean activateNezhaCameraConfig() {
        try {
            if (!resetCameraFactoryForNezha()) {
                return false;
            }
            Object replacementConfig = cameraConfigGetter.invoke(null);
            if (replacementConfig == null) {
                throw new IllegalStateException("The Nezha camera configuration is null");
            }
            if (replacementConfig.getClass() == nativeCameraConfig.getClass()) {
                throw new IllegalStateException("Camera factory returned the native configuration after spoofing");
            }

            nezhaCameraConfig = replacementConfig;
            nativeFocalMethods.clear();
            for (String methodName : FOCAL_CONFIG_METHODS) {
                try {
                    Method delegate = nativeCameraConfig.getClass().getMethod(methodName);
                    delegate.setAccessible(true);
                    replacementConfig.getClass().getDeclaredMethod(methodName).setAccessible(true);
                    nativeFocalMethods.put(methodName, delegate);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log(Log.WARN, TAG, "Unable to map focal configuration method " + methodName, error);
                }
            }
            log(Log.INFO, TAG, "Activated Nezha configuration " + replacementConfig.getClass().getName());
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            log(Log.WARN, TAG, "Unable to activate the Nezha camera configuration", error);
            return false;
        }
    }

    private boolean resetCameraFactoryForNezha() {
        try {
            if (deviceSelectorClass == null
                    || deviceConfigLazy == null
                    || deviceConfigEvaluatedState == null
                    || deviceConfigValueField == null) {
                throw new IllegalStateException("Camera device selector state is incomplete");
            }
            String nezhaConfigKey = computeDeviceConfigKey(deviceSelectorClass, "nezha");
            Map<Field, Object> nezhaState = new HashMap<>(deviceConfigEvaluatedState);
            nezhaState.put(deviceConfigValueField, nezhaConfigKey);
            clearFactoryCache(cameraConfigCacheField);
            restoreMutableFields(deviceConfigLazy, nezhaState);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to reset the Camera configuration factory for Nezha", error);
            return false;
        }
    }

    private String computeDeviceConfigKey(Class<?> selectorClass, String device)
            throws ReflectiveOperationException {
        Object defaultRule = null;
        Object matchingRule = null;
        for (Field field : selectorClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Map<?, ?> rules) {
                Object candidate = rules.get(device);
                if (candidate != null) {
                    matchingRule = candidate;
                }
            } else if (value != null && hasDeviceRuleMethod(value.getClass())) {
                defaultRule = value;
            }
        }
        Object rule = matchingRule != null ? matchingRule : defaultRule;
        if (rule == null) {
            throw new NoSuchFieldException("Camera device selector rule for " + device);
        }
        Method transform = rule.getClass().getMethod("a", StringBuilder.class);
        transform.setAccessible(true);
        Object result = transform.invoke(rule, new StringBuilder(device));
        return String.valueOf(result);
    }

    private boolean hasDeviceRuleMethod(Class<?> type) {
        try {
            type.getMethod("a", StringBuilder.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private void clearFactoryCache(Field field) {
        if (field == null) {
            return;
        }
        try {
            field.set(null, null);
        } catch (IllegalAccessException | RuntimeException error) {
            log(Log.WARN, TAG, "Unable to clear Camera configuration cache", error);
        }
    }

    private void installNativeFocalLengthHooks() {
        Object replacementConfig = nezhaCameraConfig;
        if (replacementConfig == null) {
            return;
        }
        Class<?> nezhaConfigClass = replacementConfig.getClass();
        int installed = 0;
        for (String methodName : FOCAL_CONFIG_METHODS) {
            try {
                Method target = nezhaConfigClass.getDeclaredMethod(methodName);
                target.setAccessible(true);
                hook(target)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("native_focal_" + methodName)
                        .intercept(chain -> {
                            Object localConfig = nativeCameraConfig;
                            Method delegate = nativeFocalMethods.get(methodName);
                            if (chain.getThisObject() != nezhaCameraConfig
                                    || localConfig == null
                                    || delegate == null
                                    || !isEnabled()
                                    || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                                return chain.proceed();
                            }
                            try {
                                return delegate.invoke(localConfig);
                            } catch (Throwable error) {
                                nativeFocalMethods.remove(methodName);
                                log(
                                        Log.WARN,
                                        TAG,
                                        "Native focal method failed; falling back to Nezha: " + methodName,
                                        error
                                );
                                return chain.proceed();
                            }
                        });
                installed++;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                nativeFocalMethods.remove(methodName);
                log(Log.WARN, TAG, "Unable to hook focal configuration method " + methodName, error);
            }
        }
        log(Log.INFO, TAG, "Native focal configuration hooks installed: " + installed);
    }

    private void installNativeWatermarkLabelHook() {
        Object replacementConfig = nezhaCameraConfig;
        Object localConfig = nativeCameraConfig;
        if (replacementConfig == null || localConfig == null) {
            return;
        }
        try {
            Method target = replacementConfig.getClass().getDeclaredMethod("d");
            Method delegate = localConfig.getClass().getMethod("d");
            target.setAccessible(true);
            delegate.setAccessible(true);
            hook(target)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("native_watermark_label")
                    .intercept(chain -> {
                        Object nativeConfig = nativeCameraConfig;
                        if (chain.getThisObject() != nezhaCameraConfig
                                || nativeConfig == null
                                || !isEnabled()
                                || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                            return chain.proceed();
                        }
                        try {
                            return delegate.invoke(nativeConfig);
                        } catch (Throwable error) {
                            log(Log.WARN, TAG, "Native watermark label lookup failed", error);
                            return chain.proceed();
                        }
                    });
            log(Log.INFO, TAG, "Native watermark label hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook native watermark label configuration", error);
        }
    }

    private void installLegendaryFallbackHook() {
        try {
            Method set = CaptureRequest.Builder.class.getDeclaredMethod(
                    "set",
                    CaptureRequest.Key.class,
                    Object.class
            );
            set.setAccessible(true);
            hook(set)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("legendary_camera2_fallback")
                    .intercept(chain -> {
                        if (!isEnabled()) {
                            return chain.proceed();
                        }
                        if (Boolean.TRUE.equals(legendaryFallbackWrite.get())) {
                            return chain.proceed();
                        }
                        if (!(chain.getThisObject() instanceof CaptureRequest.Builder builder)
                                || !(chain.getArg(0) instanceof CaptureRequest.Key<?> key)) {
                            return chain.proceed();
                        }

                        String keyName = key.getName();
                        if (LEGENDARY_VENDOR_TAG.equals(keyName)) {
                            Integer legendaryMode = getLegendaryMode(chain.getArg(1));
                            if (legendaryMode == null) {
                                legendaryBuilders.remove(builder);
                            } else {
                                legendaryBuilders.put(builder, legendaryMode);
                            }
                            Object result;
                            try {
                                // A vendor tag can be advertised by the APK but absent on the
                                // device HAL. Do not let that optional write crash the camera.
                                result = chain.proceed();
                            } catch (RuntimeException | LinkageError error) {
                                log(Log.WARN, TAG, "Legendary vendor tag is unavailable; using Camera2 fallback", error);
                                result = null;
                            }
                            if (legendaryMode != null) {
                                applyLegendaryCamera2Fallback(builder, legendaryMode);
                            }
                            return result;
                        }

                        Integer legendaryMode = legendaryBuilders.get(builder);
                        if (legendaryMode == null) {
                            return chain.proceed();
                        }
                        if (CaptureRequest.CONTROL_EFFECT_MODE.getName().equals(keyName)) {
                            return chain.proceed(new Object[]{
                                    key,
                                    legendaryMode == LEGENDARY_MODE_M3
                                            ? CaptureRequest.CONTROL_EFFECT_MODE_MONO
                                            : CaptureRequest.CONTROL_EFFECT_MODE_OFF
                            });
                        }
                        if (CaptureRequest.CONTROL_AWB_MODE.getName().equals(keyName)) {
                            return chain.proceed(new Object[]{
                                    key,
                                    legendaryMode == LEGENDARY_MODE_M3
                                            ? CaptureRequest.CONTROL_AWB_MODE_AUTO
                                            : CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                            });
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Legendary Camera2 compatibility hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to install Legendary Camera2 compatibility hook", error);
        }
    }

    private void installExclusiveWatermarkFilterHook(ClassLoader classLoader) {
        installExclusiveWatermarkFilterHook(
                classLoader,
                "Gg.B",
                "exclusive_leica_watermark_property_filter"
        );
        installExclusiveWatermarkFilterHook(
                classLoader,
                "Gg.C0313w",
                "exclusive_leica_watermark_supported_list_filter"
        );
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C", "exclusive_leica_watermark_theme_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C0314x", "exclusive_leica_watermark_device_allow_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C0315y", "exclusive_leica_watermark_device_deny_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C0312v", "exclusive_leica_watermark_region_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.C0316z", "exclusive_leica_watermark_device_type_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.A", "exclusive_leica_watermark_name_length_filter");
        installExclusiveWatermarkFilterHook(classLoader, "Gg.E", "exclusive_leica_watermark_custom_property_filter");
    }

    private void installCameraWatermarkCatalogHooks(ClassLoader classLoader) {
        try {
            Class<?> manager = Class.forName("Gg.P", false, classLoader);
            Method filterData = manager.getDeclaredMethod("d", boolean.class);
            filterData.setAccessible(true);
            hook(filterData)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("camera_watermark_catalog_limitations")
                    .intercept(chain -> {
                        if (isEnabled()) {
                            // The manager removes local and cloud templates after applying limitations.
                            return null;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Camera watermark catalog limitation hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to install Camera watermark catalog limitation hook", error);
        }

        try {
            Class<?> jsonObject = Class.forName("org.json.JSONObject", false, null);
            Method optJSONObject = jsonObject.getDeclaredMethod("optJSONObject", String.class);
            optJSONObject.setAccessible(true);
            hook(optJSONObject)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("camera_watermark_json_limitations")
                    .intercept(chain -> {
                        Object name = chain.getArg(0);
                        if (isEnabled()
                                && name instanceof String key
                                && key.toLowerCase(java.util.Locale.ROOT).contains("limitation")) {
                            return null;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Camera watermark JSON limitation hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to install Camera watermark JSON limitation hook", error);
        }
    }

    private void installExclusiveWatermarkFilterHook(
            ClassLoader classLoader,
            String filterClassName,
            String hookId
    ) {
        try {
            Class<?> filterClass = Class.forName(filterClassName, false, classLoader);
            Method invoke = filterClass.getDeclaredMethod("invoke", Object.class);
            invoke.setAccessible(true);
            hook(invoke)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId(hookId)
                    .intercept(chain -> {
                        if (isEnabled()
                                && isExclusiveLeicaWatermark(chain.getArg(0))) {
                            // This filter removes templates when the app's property cache is stale.
                            return Boolean.FALSE;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Exclusive Leica watermark filter hook installed: " + filterClassName);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(
                    Log.WARN,
                    TAG,
                    "Unable to install exclusive Leica watermark filter hook: " + filterClassName,
                    error
            );
        }
    }

    private boolean isExclusiveLeicaWatermark(Object watermark) {
        if (watermark == null) {
            return false;
        }
        try {
            Method idGetter = watermark.getClass().getMethod("U");
            Object id = idGetter.invoke(watermark);
            return EXCLUSIVE_LEICA_WATERMARK_IDS.contains(String.valueOf(id));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    private void installGalleryWatermarkHooks(ClassLoader classLoader) {
        if (!pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
            return;
        }

        boolean managerHooked = installGalleryWatermarkManagerHook(classLoader);
        boolean capabilitiesHooked = installGalleryWatermarkCapabilityHooks(classLoader);
        boolean usageHooked = installGalleryWatermarkUsageHook(classLoader);
        if (!managerHooked || !capabilitiesHooked || !usageHooked) {
            installDeferredGalleryWatermarkManagerHook();
        }
    }

    private boolean installGalleryWatermarkCapabilityHooks(ClassLoader classLoader) {
        try {
            // In MediaEditor 2.10.37.9, zn.a.g/h/i are the renamed equivalents of
            // the three feature gates modified by the 2.4.0.4.3 reference build.
            Class<?> capabilityClass = Class.forName("zn.a", false, classLoader);
            if (!galleryWatermarkCapabilityClasses.add(capabilityClass)) {
                return true;
            }

            int installed = 0;
            for (String methodName : new String[]{"g", "h", "i"}) {
                Method gate = capabilityClass.getDeclaredMethod(methodName);
                gate.setAccessible(true);
                hook(gate)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_watermark_capability_" + methodName)
                        .intercept(chain -> {
                            if (isEnabled() && pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        });
                installed++;
            }
            log(
                    Log.INFO,
                    TAG,
                    "Gallery watermark capability hooks installed: " + installed + " (zn.a.g/h/i)"
            );
            return installed == 3;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkCapabilityClasses.removeIf(
                    capabilityClass -> capabilityClass.getClassLoader() == classLoader
            );
            log(Log.WARN, TAG, "Unable to install Gallery watermark capability hooks", error);
            return false;
        }
    }

    private boolean installGalleryWatermarkUsageHook(ClassLoader classLoader) {
        try {
            // MediaEditor 2.10.37.9 rejects a visible watermark in vy.m0.a when
            // the source photo lacks one of its device/EXIF/cloud parameters.
            Class<?> checker = Class.forName("vy.m0", false, classLoader);
            if (!galleryWatermarkUsageClasses.add(checker)) {
                return true;
            }
            Class<?> itemClass = Class.forName("fz.d", false, classLoader);
            Class<?> photoInfoClass = Class.forName("v8.b", false, classLoader);
            Class<?> configClass = Class.forName("k00.g", false, classLoader);
            Method check = checker.getDeclaredMethod(
                    "a",
                    itemClass,
                    String.class,
                    photoInfoClass,
                    configClass
            );
            check.setAccessible(true);

            Class<?> successClass = Class.forName("bz.a$a", false, classLoader);
            Field successField = successClass.getDeclaredField("a");
            successField.setAccessible(true);
            Object success = successField.get(null);
            if (success == null) {
                throw new IllegalStateException("Watermark success result is null");
            }

            hook(check)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_usage_restrictions")
                    .intercept(chain -> {
                        if (isEnabled() && pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return success;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery watermark usage restriction hook installed (vy.m0.a)");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkUsageClasses.removeIf(
                    usageClass -> usageClass.getClassLoader() == classLoader
            );
            log(Log.WARN, TAG, "Unable to install Gallery watermark usage restriction hook", error);
            return false;
        }
    }

    private boolean installGalleryWatermarkManagerHook(ClassLoader classLoader) {
        try {
            Class<?> manager = Class.forName("tb0.o0", false, classLoader);
            if (!galleryWatermarkManagers.add(manager)) {
                return true;
            }
            Method filterData = manager.getDeclaredMethod("b", boolean.class);
            filterData.setAccessible(true);
            hook(filterData)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_all_watermark_limitations")
                    .intercept(chain -> {
                        if (isEnabled() && pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            // This method filters and can delete templates by ID, device, region, theme,
                            // system property, time window, and name length. Keep the catalog intact.
                            return null;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery all-watermark limitation hook installed");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkManagers.removeIf(manager -> manager.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery all-watermark limitation hook", error);
            return false;
        }
    }

    private void installDeferredGalleryWatermarkManagerHook() {
        if (galleryWatermarkClassLoadHookInstalled) {
            return;
        }
        synchronized (this) {
            if (galleryWatermarkClassLoadHookInstalled) {
                return;
            }
            try {
                Method loadClass = ClassLoader.class.getDeclaredMethod(
                        "loadClass",
                        String.class,
                        boolean.class
                );
                loadClass.setAccessible(true);
                hook(loadClass)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_dynamic_watermark_manager")
                        .intercept(chain -> {
                            Object loadedClass = chain.proceed();
                            Object name = chain.getArg(0);
                            if (loadedClass instanceof Class<?> loaded) {
                                if ("tb0.o0".equals(name)) {
                                    installGalleryWatermarkManagerHook(loaded.getClassLoader());
                                } else if ("zn.a".equals(name)) {
                                    installGalleryWatermarkCapabilityHooks(loaded.getClassLoader());
                                } else if ("vy.m0".equals(name)) {
                                    installGalleryWatermarkUsageHook(loaded.getClassLoader());
                                }
                            }
                            return loadedClass;
                        });
                galleryWatermarkClassLoadHookInstalled = true;
                log(Log.INFO, TAG, "Gallery dynamic watermark manager hook installed");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.WARN, TAG, "Unable to install Gallery dynamic watermark manager hook", error);
            }
        }
    }

    private Integer getLegendaryMode(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        int mode = number.intValue();
        return mode == LEGENDARY_MODE_M9 || mode == LEGENDARY_MODE_M3 ? mode : null;
    }

    private void applyLegendaryCamera2Fallback(CaptureRequest.Builder builder, int legendaryMode) {
        if (Boolean.TRUE.equals(legendaryFallbackWrite.get())) {
            return;
        }
        legendaryFallbackWrite.set(Boolean.TRUE);
        try {
            builder.set(
                    CaptureRequest.CONTROL_EFFECT_MODE,
                    legendaryMode == LEGENDARY_MODE_M3
                            ? CaptureRequest.CONTROL_EFFECT_MODE_MONO
                            : CaptureRequest.CONTROL_EFFECT_MODE_OFF
            );
            builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    legendaryMode == LEGENDARY_MODE_M3
                            ? CaptureRequest.CONTROL_AWB_MODE_AUTO
                            : CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            );
        } catch (RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to apply Legendary Camera2 compatibility parameters", error);
        } finally {
            legendaryFallbackWrite.remove();
        }
    }

    private void setStaticStringField(Class<?> type, String name, String value) {
        try {
            Field field = type.getDeclaredField(name);
            if (!Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException(name + " is not static");
            }
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.WARN, TAG, "Unable to set Build." + name, error);
        }
    }

    private void installSecurityCompatibilityHooks(ClassLoader classLoader) {
        try {
            Class<?> guard = Class.forName("com.camera.LSsdQFvLalapDwvA", false, classLoader);
            hookBooleanResult(guard, "RitIeKoenwCSqcPf", true, "security_valid");
            hookBooleanResult(guard, "QiVkoLmEuZWFFHiA", false, "security_reject_a");
            hookBooleanResult(guard, "qkPDndbXdHyDtWXd", false, "security_reject_b");
            log(Log.INFO, TAG, "Nezha security compatibility hooks installed");
        } catch (ClassNotFoundException | RuntimeException error) {
            log(Log.ERROR, TAG, "Camera security class was not found", error);
        }
    }

    private void hookBooleanResult(Class<?> owner, String methodName, boolean result, String id) {
        try {
            Method method = owner.getDeclaredMethod(methodName);
            method.setAccessible(true);
            hook(method)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId(id)
                    .intercept(chain -> {
                        if (!isEnabled()) {
                            return chain.proceed();
                        }
                        return result;
                    });
        } catch (NoSuchMethodException | RuntimeException error) {
            log(Log.ERROR, TAG, "Camera security method was not found: " + methodName, error);
        }
    }

}
