// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.leicaunlocker.hook;

import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private volatile String nativeDefaultFocal;
    private volatile Object nezhaCameraConfig;
    private final Map<String, Method> nativeFocalMethods = new ConcurrentHashMap<>();
    private final Map<CaptureRequest.Builder, Integer> legendaryBuilders =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Class<?>> galleryWatermarkManagers = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkCapabilityClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkUsageClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkRestrictionClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryModernWatermarkClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryModernCapabilityClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> galleryWatermarkFragmentClasses = ConcurrentHashMap.newKeySet();
    private Method cameraConfigGetter;
    private Field cameraConfigCacheField;
    private Class<?> deviceSelectorClass;
    private Object deviceConfigLazy;
    private Map<Field, Object> deviceConfigEvaluatedState;
    private Field deviceConfigValueField;
    private Field modernConfigCacheField;
    private Field modernConfigDeviceField;
    private boolean modernConfigProvider;
    private volatile boolean modernDeviceSelectorOverride;
    private boolean cameraFactoryTouched;
    private volatile boolean galleryWatermarkClassLoadHookInstalled;
    private volatile CaptureRequest.Key<Integer> legendaryVendorKey;
    private volatile boolean nativeFocalDefaultHookInstalled;
    private volatile boolean nativeFocalComponentHookInstalled;
    private volatile boolean nativeFocalPreferenceHookInstalled;
    private volatile boolean nativeFocalRuntimeProbeAttempted;
    private volatile boolean nativeFocalRetryScheduled;
    private volatile boolean nativeFocalCaptureFailureLogged;
    private volatile boolean nativeFocalHookFailureLogged;
    private volatile boolean cameraClassLoadHookInstalled;
    private final Set<ClassLoader> cameraClassLoaders =
            Collections.newSetFromMap(new WeakHashMap<>());

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
        if (!btm.m.os4.systemuihook.OsCompatibility.areHooksAllowed()) {
            return;
        }
        if (!isTargetPackage(param.getPackageName(), param.isFirstPackage())) {
            return;
        }
        if (ModuleConfig.isGalleryPackage(param.getPackageName())) {
            installGalleryWatermarkHooksForChain(param.getDefaultClassLoader());
            return;
        }
        if (ModuleConfig.TARGET_PACKAGE.equals(param.getPackageName())) {
            installLeicaUiHooks(param.getDefaultClassLoader());
            installDeferredCameraClassHook(param.getDefaultClassLoader());
        }
        if (!isEnabled()) {
            return;
        }
        if (!ModuleConfig.TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        boolean preserveNativeFocalLengths = pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true);

        if (preserveNativeFocalLengths) {
            captureNativeFocalLengthConfig(param.getDefaultClassLoader());
            captureNativeFocalPreference(param.getDefaultClassLoader());
            installNativeFocalPreferenceHook(param.getDefaultClassLoader());
        }
        installSystemPropertyHooks();
        if (pref(ModuleConfig.KEY_LEICA_UI, true)) {
            applyBuildProfile();
        }
        if (preserveNativeFocalLengths
                && nativeCameraConfig != null
                && activateNezhaCameraConfig()) {
            installNativeFocalLengthHooks();
            installNativeWatermarkLabelHook();
        } else if (cameraFactoryTouched) {
            resetCameraFactoryForNezha();
        }
        // Install before Application.onCreate/first camera mode initialization;
        // PackageReady can arrive after v0 has already populated its focal array.
        installNativeFocalDefaultValueHook(param.getDefaultClassLoader());
        installNativeFocalComponentHook(param.getDefaultClassLoader());
        scheduleCameraFocalHookRetries(param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!isTargetPackage(param.getPackageName(), param.isFirstPackage())) {
            return;
        }

        ClassLoader classLoader = param.getClassLoader();
        if (ModuleConfig.isGalleryPackage(param.getPackageName())) {
            installGalleryWatermarkHooksForChain(classLoader);
            return;
        }
        if (!ModuleConfig.TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        installLeicaUiHooks(classLoader);
        installDeferredCameraClassHook(classLoader);
        installNativeFocalDefaultValueHook(classLoader);
        installNativeFocalComponentHook(classLoader);
        if (pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
            installNativeFocalPreferenceHook(classLoader);
        }
        scheduleCameraFocalHookRetries(classLoader);
        // Watermark catalog and model-limit hooks are independent of the camera
        // master switch. The switch only gates the camera profile/security hooks.
        installSecurityCompatibilityHooks(classLoader);
        installExclusiveWatermarkFilterHook(classLoader);
        installCameraWatermarkCatalogHooks(classLoader);
        installLegendaryFallbackHook();
        installCameraFeatureHooks(classLoader);
    }

    private boolean isTargetPackage(String packageName, boolean firstPackage) {
        // PackageReady can be delivered after another package initialized the
        // process (notably MediaEditor :photo_editor/:editor_service). Requiring
        // firstPackage here silently skipped the watermark module.
        return targetProcess && ModuleConfig.isSupportedPackage(packageName);
    }

    private boolean isEnabled() {
        return pref(ModuleConfig.KEY_MASTER_ENABLED, true);
    }

    private boolean pref(String key, boolean defaultValue) {
        SharedPreferences current = preferences;
        return current == null ? defaultValue : current.getBoolean(key, defaultValue);
    }

    private int prefInt(String key, int defaultValue) {
        SharedPreferences current = preferences;
        return current == null ? defaultValue : current.getInt(key, defaultValue);
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

    private void installLeicaUiHooks(ClassLoader classLoader) {
        try {
            Class<?> config = Class.forName("Te.b", false, classLoader);
            Method method = config.getDeclaredMethod("W");
            method.setAccessible(true);
            hook(method)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("camera_leica_ui_gate")
                    .intercept(chain -> !isEnabled() || !pref(ModuleConfig.KEY_LEICA_UI, true)
                            ? Boolean.FALSE : Boolean.TRUE);
            log(Log.INFO, TAG, "Camera Leica UI gate hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook Te.b.W Leica UI gate", error);
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

    private void installCameraFeatureHooks(ClassLoader classLoader) {
        try {
            Class<?> configUtil = Class.forName("com.android.camera.data.data.o", false, classLoader);
            Method motionSupport = configUtil.getDeclaredMethod("H", int.class);
            motionSupport.setAccessible(true);
            hook(motionSupport)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("motion_capture_support")
                    .intercept(chain -> isEnabled() || Boolean.TRUE.equals(chain.proceed()));
            log(Log.INFO, TAG, "Camera motion-capture capability hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook motion-capture capability", error);
        }
        try {
            // H() calls U() again from the slow-motion shutter path.  On 6.7
            // U() is the device/component gate, so bypass it as well or the
            // feature is still rejected after the capability check succeeds.
            Class<?> configUtil = Class.forName("com.android.camera.data.data.o", false, classLoader);
            Method motionSwitch = configUtil.getDeclaredMethod("U", int.class);
            motionSwitch.setAccessible(true);
            hook(motionSwitch)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("motion_capture_switch")
                    .intercept(chain -> isEnabled() || Boolean.TRUE.equals(chain.proceed()));
            log(Log.INFO, TAG, "Camera motion-capture switch hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook motion-capture switch", error);
        }
        try {
            // The request writer checks the camera-characteristics key map
            // before emitting xiaomi.motiondetection.enabled.  Some 6.7
            // devices omit that vendor key even though the implementation is
            // present, so expose this one capability without changing any
            // other characteristic checks.
            Class<?> capabilities = Class.forName("p468n9.C4678f", false, classLoader);
            Method hasKey = capabilities.getDeclaredMethod("S0", String.class);
            hasKey.setAccessible(true);
            hook(hasKey)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("motion_capture_vendor_capability")
                    .intercept(chain -> chain.proceed());
            log(Log.INFO, TAG, "Camera motion-capture vendor capability hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook motion-capture vendor capability", error);
        }
        try {
            Class<?> liveSupport = Class.forName("Wr.C2887n", false, classLoader);
            Method support = liveSupport.getDeclaredMethod("a");
            support.setAccessible(true);
            hook(support)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("live_photo_support")
                    .intercept(chain -> chain.proceed());
            log(Log.INFO, TAG, "Camera Live Photo capability hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to hook Live Photo capability", error);
        }
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
        try {
            Class.forName(CAMERA_CONFIG_FACTORY, true, classLoader);
            captureLegacyNativeFocalLengthConfig(classLoader);
            // Je.e is still present in 6.7, but it is an unrelated utility
            // class.  Only stop here when the legacy capture actually worked.
            if (nativeCameraConfig != null) {
                return;
            }
            log(Log.INFO, TAG, "Legacy camera configuration capture produced no config; trying modern provider");
        } catch (ClassNotFoundException ignored) {
            log(Log.INFO, TAG, "Legacy camera configuration factory is absent; trying modern provider");
        } catch (LinkageError error) {
            log(Log.INFO, TAG, "Legacy camera configuration factory is unavailable; trying modern provider", error);
        }

        captureModernNativeFocalLengthConfig(classLoader);
    }

    /**
     * Camera 6.7 initializes the main-camera focal preference from the real
     * CameraCharacteristics.  Capture it before any device/profile spoofing
     * so a first-run empty preference cannot be initialized from the Nezha
     * profile instead.
     */
    private void captureNativeFocalPreference(ClassLoader classLoader) {
        try {
            Class<?> deviceRegistry = Class.forName("p827x6.e", true, classLoader);
            Object registry = deviceRegistry.getDeclaredMethod("V").invoke(null);
            // v0.getDefaultValue() uses Vr.c.c(), which includes the 6.7
            // fallback for devices whose registry has not selected a camera yet.
            Class<?> cameraIdResolver = Class.forName("Vr.c", true, classLoader);
            int cameraId = ((Number) cameraIdResolver.getDeclaredMethod("c").invoke(null)).intValue();
            if (cameraId < 0) {
                cameraId = ((Number) deviceRegistry.getDeclaredMethod("g").invoke(registry)).intValue();
            }
            Object capabilities = deviceRegistry.getDeclaredMethod("Q", int.class).invoke(registry, cameraId);
            Class<?> capabilitiesUtil = Class.forName("p468n9.C4681g", true, classLoader);
            float focalRatio = ((Number) capabilitiesUtil
                    .getDeclaredMethod("s", Class.forName("p468n9.C4678f", false, classLoader))
                    .invoke(null, capabilities)).floatValue();
            Class<?> focalUtil = Class.forName("Bl.l", true, classLoader);
            float focalMillimeters = ((Number) focalUtil
                    .getDeclaredMethod("k1", float.class)
                    .invoke(null, focalRatio)).floatValue();
            int rounded = Math.round(focalMillimeters);
            if (rounded >= 5 && rounded <= 200) {
                nativeDefaultFocal = String.valueOf(rounded);
                installNativeFocalPreferenceHook(classLoader);
                log(Log.INFO, TAG, "Captured native default focal preference: " + nativeDefaultFocal);
            } else {
                log(Log.WARN, TAG, "Ignoring invalid native focal value: " + rounded);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (!nativeFocalCaptureFailureLogged) {
                nativeFocalCaptureFailureLogged = true;
                log(Log.INFO, TAG, "Native focal preference is not ready yet; deferred retry will continue");
            }
        }
    }

    private void scheduleCameraFocalHookRetries(ClassLoader initialLoader) {
        if (nativeFocalRetryScheduled || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
            return;
        }
        nativeFocalRetryScheduled = true;
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] task = new Runnable[1];
        task[0] = new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (!targetProcess || !isEnabled()
                        || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                    return;
                }
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                if (loader == null) {
                    loader = initialLoader;
                }
                rememberThreadContextClassLoaders();
                if (nativeDefaultFocal == null) {
                    captureNativeFocalPreference(loader);
                }
                installNativeFocalPreferenceHook(loader);
                installNativeFocalDefaultValueHook(loader);
                installNativeFocalComponentHook(loader);
                if (nativeFocalDefaultHookInstalled && nativeFocalComponentHookInstalled
                        && nativeFocalPreferenceHookInstalled) {
                    return;
                }
                if (++attempts < 120) {
                    handler.postDelayed(task[0], 250L);
                } else {
                    log(Log.INFO, TAG, "Camera focal hook retry window expired");
                }
            }
        };
        handler.post(task[0]);
    }

    private void installNativeFocalPreferenceHook(ClassLoader classLoader) {
        if (nativeFocalPreferenceHookInstalled) {
            return;
        }
        try {
            Class<?> settings = Class.forName("com.android.camera.data.data.z", false, classLoader);
            Method getter = settings.getDeclaredMethod("o");
            getter.setAccessible(true);
            hook(getter)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("native_default_focal_preference")
                    .intercept(chain -> {
                        ClassLoader runtimeLoader = settings.getClassLoader();
                        rememberCameraClassLoader(runtimeLoader);
                        if (nativeDefaultFocal == null && !nativeFocalRuntimeProbeAttempted) {
                            nativeFocalRuntimeProbeAttempted = true;
                            captureNativeFocalPreference(runtimeLoader);
                        }
                        installNativeFocalDefaultValueHook(runtimeLoader);
                        installNativeFocalComponentHook(runtimeLoader);
                        Object result = chain.proceed();
                        String captured = nativeDefaultFocal;
                        String current = result == null ? "" : String.valueOf(result);
                        if (isEnabled()
                                && pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)
                                && captured != null
                                && (current.isEmpty() || "23".equals(current))) {
                            return captured;
                        }
                        return result;
                    });
            nativeFocalPreferenceHookInstalled = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            logFocalHookFailure("Unable to hook native default focal preference", error);
        }
    }

    /** Directly fixes the 6.7 focal selector, whose empty preference is
     * initialized after the camera profile has already been spoofed. */
    private void installNativeFocalDefaultValueHook(ClassLoader classLoader) {
        if (nativeFocalDefaultHookInstalled) {
            return;
        }
        try {
            Class<?> focalData = resolveCameraClass("p785w2.v0", classLoader);
            Method getter = focalData.getDeclaredMethod("getDefaultValue", int.class);
            getter.setAccessible(true);
            hook(getter)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("native_default_focal_value_67")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!isEnabled() || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                            return result;
                        }
                        int mode = ((Number) chain.getArg(0)).intValue();
                        if (mode != 163 && mode != 168 && mode != 231 && mode != 256) {
                            return result;
                        }
                        String current = result == null ? "" : String.valueOf(result);
                        if (!current.isEmpty() && !"1.0".equals(current) && !"23".equals(current)) {
                            return result;
                        }
                        String captured = nativeDefaultFocal;
                        if (captured == null) {
                            captureNativeFocalPreference(classLoader);
                            captured = nativeDefaultFocal;
                        }
                        if (captured == null) {
                            try {
                                Class<?> resolver = Class.forName("Vr.c", true, classLoader);
                                int cameraId = ((Number) resolver.getDeclaredMethod("c").invoke(null)).intValue();
                                Float focal = readNativeFocalMillimeters(classLoader, cameraId);
                                if (focal != null) {
                                    captured = String.valueOf(Math.round(focal));
                                    nativeDefaultFocal = captured;
                                }
                            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                                // Keep the original result when the registry is not ready yet.
                            }
                        }
                        if (captured == null) {
                            return result;
                        }
                        // v0.getDefaultValue() returns a zoom ratio (1.0, 1.1, ...),
                        // while the captured preference is the native focal length
                        // in millimeters (23, 24, ...).
                        return nativeFocalZoomRatio(captured, result);
                    });
            nativeFocalDefaultHookInstalled = true;
            log(Log.INFO, TAG, "Camera 6.7 native focal default hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            logFocalHookFailure("Unable to hook p785w2.v0.getDefaultValue", error);
        }
    }

    /** Camera 6.7 keeps obfuscated classes in a secondary dex that is loaded
     * after both package lifecycle callbacks. Resolve focal hooks when the
     * actual camera ClassLoader loads those classes. */
    private void installDeferredCameraClassHook(ClassLoader initialLoader) {
        rememberCameraClassLoader(initialLoader);
        if (cameraClassLoadHookInstalled) {
            return;
        }
        synchronized (this) {
            if (cameraClassLoadHookInstalled) {
                return;
            }
            try {
                Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
                loadClass.setAccessible(true);
                hook(loadClass)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("camera_dynamic_class_load2")
                        .intercept(chain -> {
                            Object loaded = chain.proceed();
                            rememberCameraClassLoader(chain.getThisObject() instanceof ClassLoader
                                    ? (ClassLoader) chain.getThisObject() : null);
                            installDeferredCameraClass(loaded, chain.getArg(0));
                            return loaded;
                        });

                Method loadClassSimple = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
                loadClassSimple.setAccessible(true);
                hook(loadClassSimple)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("camera_dynamic_class_load1")
                        .intercept(chain -> {
                            Object loaded = chain.proceed();
                            rememberCameraClassLoader(chain.getThisObject() instanceof ClassLoader
                                    ? (ClassLoader) chain.getThisObject() : null);
                            installDeferredCameraClass(loaded, chain.getArg(0));
                            return loaded;
                        });
                try {
                    Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader", false, null);
                    Method findClass = baseDex.getDeclaredMethod("findClass", String.class);
                    findClass.setAccessible(true);
                    hook(findClass)
                            .setPriority(PRIORITY_HIGHEST)
                            .setId("camera_dynamic_class_find")
                            .intercept(chain -> {
                                Object loaded = chain.proceed();
                                rememberCameraClassLoader(chain.getThisObject() instanceof ClassLoader
                                        ? (ClassLoader) chain.getThisObject() : null);
                                installDeferredCameraClass(loaded, chain.getArg(0));
                                return loaded;
                            });
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    log(Log.INFO, TAG, "BaseDexClassLoader.findClass hook unavailable for camera");
                }
                // Camera 6.7 creates a split DexClassLoader after package callbacks;
                // retain it so focal classes can be resolved even if they are
                // initialized before the ClassLoader.loadClass hook sees them.
                try {
                    Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader", false, null);
                    for (Constructor<?> constructor : baseDex.getDeclaredConstructors()) {
                        constructor.setAccessible(true);
                        hook(constructor)
                                .setPriority(PRIORITY_HIGHEST)
                                .setId("camera_dynamic_loader_ctor_"
                                        + Integer.toHexString(constructor.toGenericString().hashCode()))
                                .intercept(chain -> {
                                    Object created = chain.proceed();
                                    rememberCameraClassLoader(chain.getThisObject() instanceof ClassLoader
                                            ? (ClassLoader) chain.getThisObject() : null);
                                    return created;
                                });
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    log(Log.INFO, TAG, "BaseDexClassLoader constructor hook unavailable for camera", error);
                }
                cameraClassLoadHookInstalled = true;
                log(Log.INFO, TAG, "Camera dynamic class load hook installed");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.WARN, TAG, "Unable to install camera dynamic class load hook", error);
            }
        }
    }

    private void rememberCameraClassLoader(ClassLoader loader) {
        if (loader == null) {
            return;
        }
        synchronized (cameraClassLoaders) {
            cameraClassLoaders.add(loader);
        }
    }

    private void installDeferredCameraClass(Object loadedClass, Object name) {
        if (!(loadedClass instanceof Class<?> loaded) || !(name instanceof String className)) {
            return;
        }
        if (className.startsWith("p785w2") || className.startsWith("p827x6")
                || className.contains("ComponentRunningSwitchZoom")) {
            log(Log.INFO, TAG, "Camera class resolved: " + className + " via " + loaded.getClassLoader());
        }
        if (!"p785w2.v0".equals(className)
                && !"p827x6.e".equals(className)
                && !"com.android.camera.data.data.z".equals(className)) {
            return;
        }
        ClassLoader loader = loaded.getClassLoader();
        rememberCameraClassLoader(loader);
        log(Log.INFO, TAG, "Camera focal class loaded: " + className + " via " + loader);
        if ("p827x6.e".equals(className) || "p785w2.v0".equals(className)) {
            captureNativeFocalPreference(loader);
        }
        installNativeFocalDefaultValueHook(loader);
        installNativeFocalComponentHook(loader);
    }

    /**
     * Keeps the focal values used to build the zoom selector tied to the real
     * CameraCharacteristics even after the Nezha profile is selected.
     */
    private void installNativeFocalComponentHook(ClassLoader classLoader) {
        if (nativeFocalComponentHookInstalled) {
            return;
        }
        try {
            Class<?> focalData = resolveCameraClass("p785w2.v0", classLoader);
            Method focalReader = focalData.getDeclaredMethod("p", int.class);
            focalReader.setAccessible(true);
            hook(focalReader)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("native_focal_component_67")
                    .intercept(chain -> {
                        if (!isEnabled() || !pref(ModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) {
                            return chain.proceed();
                        }
                        int cameraId = ((Number) chain.getArg(0)).intValue();
                        Float nativeFocal = readNativeFocalMillimeters(classLoader, cameraId);
                        return nativeFocal == null ? chain.proceed() : nativeFocal;
                    });
            nativeFocalComponentHookInstalled = true;
            log(Log.INFO, TAG, "Camera 6.7 native focal component hook installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            logFocalHookFailure("Unable to hook p785w2.v0.p", error);
        }
    }

    private void logFocalHookFailure(String message, Throwable error) {
        if (!nativeFocalHookFailureLogged) {
            nativeFocalHookFailureLogged = true;
            log(Log.INFO, TAG, message + "; waiting for camera Dex loader");
        }
    }

    private Class<?> resolveCameraClass(String name, ClassLoader preferred)
            throws ClassNotFoundException {
        rememberThreadContextClassLoaders();
        LinkedHashSet<ClassLoader> candidates = new LinkedHashSet<>();
        addLoaderChain(candidates, preferred);
        addLoaderChain(candidates, Thread.currentThread().getContextClassLoader());
        synchronized (cameraClassLoaders) {
            for (ClassLoader loader : cameraClassLoaders) {
                addLoaderChain(candidates, loader);
            }
        }
        ClassNotFoundException last = null;
        for (ClassLoader loader : candidates) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException error) {
                last = error;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ClassNotFoundException(name);
    }

    private void rememberThreadContextClassLoaders() {
        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread != null) {
                    rememberCameraClassLoader(thread.getContextClassLoader());
                }
            }
        } catch (SecurityException ignored) {
            // Thread enumeration can be restricted on some vendor builds.
        }
    }

    private void addLoaderChain(Set<ClassLoader> candidates, ClassLoader loader) {
        ClassLoader current = loader;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (!candidates.add(current)) {
                break;
            }
            current = current.getParent();
        }
    }

    private Float readNativeFocalMillimeters(ClassLoader classLoader, int cameraId) {
        try {
            Class<?> deviceRegistry = Class.forName("p827x6.e", true, classLoader);
            Object registry = deviceRegistry.getDeclaredMethod("V").invoke(null);
            Object capabilities = deviceRegistry.getDeclaredMethod("Q", int.class).invoke(registry, cameraId);
            if (capabilities == null) {
                return null;
            }
            Class<?> capabilitiesUtil = Class.forName("p468n9.C4681g", true, classLoader);
            Class<?> capabilitiesType = Class.forName("p468n9.C4678f", false, classLoader);
            float focalRatio = ((Number) capabilitiesUtil
                    .getDeclaredMethod("s", capabilitiesType)
                    .invoke(null, capabilities)).floatValue();
            Class<?> focalUtil = Class.forName("Bl.l", true, classLoader);
            float focalMillimeters = ((Number) focalUtil
                    .getDeclaredMethod("k1", float.class)
                    .invoke(null, focalRatio)).floatValue();
            return focalMillimeters >= 5.0f && focalMillimeters <= 200.0f
                    ? (float) Math.round(focalMillimeters) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Unable to read native focal length for camera " + cameraId, error);
            return null;
        }
    }

    private String nativeFocalZoomRatio(String millimeters, Object fallback) {
        try {
            float focal = Float.parseFloat(millimeters);
            if (focal < 5.0f || focal > 200.0f) {
                return fallback == null ? millimeters : String.valueOf(fallback);
            }
            float ratio = Math.round((focal / 23.0f) * 10.0f) / 10.0f;
            return String.valueOf(ratio);
        } catch (RuntimeException error) {
            return fallback == null ? millimeters : String.valueOf(fallback);
        }
    }

    private void captureLegacyNativeFocalLengthConfig(ClassLoader classLoader) {
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
            modernConfigProvider = false;
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

    private void captureModernNativeFocalLengthConfig(ClassLoader classLoader) {
        Field providerCache = null;
        try {
            Class<?> providerClass = Class.forName("Ag.f", true, classLoader);
            Method getConfig = providerClass.getDeclaredMethod("k");
            getConfig.setAccessible(true);
            providerCache = providerClass.getDeclaredField("b");
            providerCache.setAccessible(true);
            Field providerDevice = providerClass.getDeclaredField("c");
            providerDevice.setAccessible(true);
            Class<?> selectorClass = Class.forName("Je.a", true, classLoader);
            Field selectorField = selectorClass.getDeclaredField("c");
            selectorField.setAccessible(true);
            Object deviceSelector = selectorField.get(null);

            Object localConfig = getConfig.invoke(null);
            if (localConfig == null) {
                throw new IllegalStateException("Ag.f returned a null camera configuration");
            }

            cameraFactoryTouched = true;
            modernConfigProvider = true;
            cameraConfigGetter = getConfig;
            cameraConfigCacheField = providerCache;
            modernConfigCacheField = providerCache;
            modernConfigDeviceField = providerDevice;
            deviceConfigLazy = deviceSelector;
            nativeCameraConfig = localConfig;
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            installModernDeviceSelectorHook(deviceSelector);
            log(Log.INFO, TAG, "Captured native camera configuration via Ag.f: "
                    + localConfig.getClass().getName()
                    + ", device=" + providerDevice.get(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            clearFactoryCache(providerCache);
            nativeCameraConfig = null;
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            log(Log.WARN, TAG, "Unable to capture the modern native focal configuration", error);
        }
    }

    private void installModernDeviceSelectorHook(Object deviceSelector) {
        if (deviceSelector == null) {
            throw new IllegalStateException("Je.a device selector Lazy is null");
        }
        try {
            Method getValue = deviceSelector.getClass().getMethod("getValue");
            getValue.setAccessible(true);
            hook(getValue)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("modern_device_selector")
                    .intercept(chain -> {
                        if (modernDeviceSelectorOverride
                                && chain.getThisObject() == deviceSelector
                                && isEnabled()) {
                            return "nezha";
                        }
                        return chain.proceed();
                    });
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Unable to hook the modern device selector Lazy", error);
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
        if (modernConfigProvider) {
            return activateModernNezhaCameraConfig();
        }
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
                    replacementConfig.getClass().getMethod(methodName).setAccessible(true);
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

    private boolean activateModernNezhaCameraConfig() {
        try {
            if (cameraConfigGetter == null
                    || modernConfigCacheField == null
                    || modernConfigDeviceField == null) {
                throw new IllegalStateException("Modern camera configuration provider state is incomplete");
            }

            modernDeviceSelectorOverride = true;
            modernConfigDeviceField.set(null, "nezha");
            clearFactoryCache(modernConfigCacheField);
            Object replacementConfig = cameraConfigGetter.invoke(null);
            if (replacementConfig == null) {
                throw new IllegalStateException("Ag.f returned a null Nezha camera configuration");
            }
            if (replacementConfig.getClass() == nativeCameraConfig.getClass()) {
                throw new IllegalStateException("Ag.f returned the native configuration after switching to Nezha");
            }

            nezhaCameraConfig = replacementConfig;
            nativeFocalMethods.clear();
            for (String methodName : FOCAL_CONFIG_METHODS) {
                try {
                    Method delegate = nativeCameraConfig.getClass().getMethod(methodName);
                    delegate.setAccessible(true);
                    replacementConfig.getClass().getMethod(methodName).setAccessible(true);
                    nativeFocalMethods.put(methodName, delegate);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log(Log.WARN, TAG, "Unable to map modern focal configuration method " + methodName, error);
                }
            }
            log(Log.INFO, TAG, "Activated modern Nezha configuration " + replacementConfig.getClass().getName());
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            nezhaCameraConfig = null;
            nativeFocalMethods.clear();
            log(Log.WARN, TAG, "Unable to activate the modern Nezha camera configuration", error);
            return false;
        }
    }

    private boolean resetCameraFactoryForNezha() {
        if (modernConfigProvider) {
            try {
                modernDeviceSelectorOverride = true;
                modernConfigDeviceField.set(null, "nezha");
                clearFactoryCache(modernConfigCacheField);
                return true;
            } catch (IllegalAccessException | RuntimeException error) {
                log(Log.WARN, TAG, "Unable to reset the modern Camera configuration provider", error);
                return false;
            }
        }
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
                Method target = nezhaConfigClass.getMethod(methodName);
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
            Method target = replacementConfig.getClass().getMethod("d");
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
                        if (!(chain.getThisObject() instanceof CaptureRequest.Builder builder)
                                || !(chain.getArg(0) instanceof CaptureRequest.Key<?> key)) {
                            return chain.proceed();
                        }
                        if (!isEnabled()) {
                            legendaryBuilders.remove(builder);
                            return chain.proceed();
                        }

                        // Camera 6.7 only emits the vendor tag after the mode is
                        // selected. Inject it proactively when Legendary mode
                        // is selected in the module settings.
                        if (prefInt(ModuleConfig.KEY_INSTANT_MODE, 0) == 1
                                && !legendaryBuilders.containsKey(builder)
                                && !LEGENDARY_VENDOR_TAG.equals(key.getName())) {
                            try {
                                CaptureRequest.Key<Integer> vendorKey = legendaryVendorKey;
                                if (vendorKey == null) {
                                    vendorKey = new CaptureRequest.Key<>(LEGENDARY_VENDOR_TAG, Integer.class);
                                    legendaryVendorKey = vendorKey;
                                }
                                builder.set(vendorKey, LEGENDARY_MODE_M9);
                            } catch (RuntimeException | LinkageError error) {
                                log(Log.WARN, TAG, "Unable to inject Legendary mode vendor tag", error);
                            }
                        }

                        String keyName = key.getName();
                        if (LEGENDARY_VENDOR_TAG.equals(keyName)) {
                            Integer legendaryMode = getLegendaryMode(chain.getArg(1));
                            if (legendaryMode == null) {
                                legendaryBuilders.remove(builder);
                            } else {
                                legendaryBuilders.put(builder, legendaryMode);
                            }
                            Object result = chain.proceed();
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
                        // Preserve the manager's original result. A null here
                        // makes newer camera builds treat the entire catalog as
                        // unavailable instead of merely skipping restrictions.
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
                        if (name instanceof String key
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
                        if (isExclusiveLeicaWatermark(chain.getArg(0))) {
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
            Method idGetter;
            try {
                idGetter = watermark.getClass().getMethod("U");
            } catch (NoSuchMethodException ignored) {
                idGetter = watermark.getClass().getDeclaredMethod("U");
                idGetter.setAccessible(true);
            }
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

        boolean modernHooked = installModernGalleryWatermarkHooks(classLoader);
        boolean capabilityHooked = installModernWatermarkCapabilityOnly(classLoader);
        boolean managerHooked = installGalleryWatermarkManagerHook(classLoader);
        boolean capabilitiesHooked = installGalleryWatermarkCapabilityHooks(classLoader);
        boolean usageHooked = installGalleryWatermarkUsageHook(classLoader);
        boolean restrictionHooked = installGalleryWatermarkRestrictionHook(classLoader);
        boolean fragmentHooked = installGalleryWatermarkFragmentHook(classLoader);
        if (!modernHooked || !capabilityHooked || !managerHooked || !capabilitiesHooked || !usageHooked
                || !restrictionHooked || !fragmentHooked) {
            installDeferredGalleryWatermarkManagerHook();
        }
    }

    private void installGalleryWatermarkHooksForChain(ClassLoader classLoader) {
        ClassLoader current = classLoader;
        int depth = 0;
        while (current != null && depth++ < 8) {
            log(Log.INFO, TAG, "Trying Gallery watermark hooks in ClassLoader " + current);
            installGalleryWatermarkHooks(current);
            current = current.getParent();
        }
        installDeferredGalleryWatermarkManagerHook();
    }

    /**
     * MediaEditor 2.10.39.x moved all photo-watermark checks into the
     * watermark feature module. Keep the cloud catalog intact and make the
     * render capability check succeed when the all-watermarks preference is on.
     */
    private boolean installModernGalleryWatermarkHooks(ClassLoader classLoader) {
        try {
            Class<?> configClass = Class.forName(
                    "com.miui.mediaeditor.photo.watermark.model.cloudwatermark.CloudWatermarkConfigData",
                    false,
                    classLoader
            );
            Class<?> f0Class = Class.forName("xy.f0", false, classLoader);
            if (!galleryModernWatermarkClasses.add(f0Class)) {
                return true;
            }

            Method filter = f0Class.getDeclaredMethod("a", configClass, List.class);
            filter.setAccessible(true);
            hook(filter)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_cloud_watermark_filter")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                                && chain.getArg(0) != null) {
                            // f0.a removes entries by device, region, version,
                            // time window, and name length. Return the source
                            // object so every cloud template reaches the menu.
                            return chain.getArg(0);
                        }
                        return chain.proceed();
                    });

            Class<?> j0Class = Class.forName("xy.j0", false, classLoader);
            Method capability = findModernCapabilityMethod(j0Class);
            if (capability == null) {
                throw new NoSuchMethodException("xy.j0.a capability method");
            }
            capability.setAccessible(true);
            Object supported = findModernWatermarkSuccess(classLoader);
            if (supported == null) {
                throw new IllegalStateException("Modern watermark success result is unavailable");
            }
            hook(capability)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_watermark_capability")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return supported;
                        }
                        return chain.proceed();
                    });
            galleryModernCapabilityClasses.add(j0Class);

            Class<?> serviceClass = Class.forName("xy.d", false, classLoader);
            Class<?> renderDataClass = Class.forName("nx.g", false, classLoader);
            Class<?> verificationClass = Class.forName("yr.e$b", false, classLoader);
            Field verificationField = verificationClass.getDeclaredField("f54669a");
            verificationField.setAccessible(true);
            Object verificationSuccess = verificationField.get(null);
            Method verify = serviceClass.getDeclaredMethod("b", renderDataClass);
            verify.setAccessible(true);
            hook(verify)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_watermark_render_capability")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return verificationSuccess;
                        }
                        return chain.proceed();
                    });

            Class<?> photoInfoClass = Class.forName("w8.b", false, classLoader);
            Class<?> displayConfigClass = Class.forName("m00.g", false, classLoader);
            Method availability;
            try {
                availability = Class.forName("xy.i", false, classLoader).getDeclaredMethod(
                        "e", Map.class, photoInfoClass,
                        Class.forName("m00.k", false, classLoader), displayConfigClass);
            } catch (NoSuchMethodException missingAvailability) {
                availability = findWatermarkAvailabilityMethod(Class.forName("xy.i", false, classLoader));
                if (availability == null) {
                    throw missingAvailability;
                }
            }
            availability.setAccessible(true);
            hook(availability)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_watermark_availability")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            forceWatermarkItemsAvailable(chain.getArg(0));
                        }
                        return result;
                    });

            log(Log.INFO, TAG, "Modern MediaEditor watermark hooks installed (xy.f0/xy.j0/xy.d/xy.i)");
            return true;
        } catch (ClassNotFoundException error) {
            galleryModernWatermarkClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.INFO, TAG, "Modern watermark classes not ready in " + classLoader + ": " + error.getMessage());
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryModernWatermarkClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install modern MediaEditor watermark hooks", error);
            return false;
        }
    }

    /** Installs the EXIF/model result hook independently of the cloud catalog. */
    private boolean installModernWatermarkCapabilityOnly(ClassLoader classLoader) {
        try {
            Class<?> j0Class = Class.forName("xy.j0", false, classLoader);
            if (!galleryModernCapabilityClasses.add(j0Class)) {
                return true;
            }
            Method capability = findModernCapabilityMethod(j0Class);
            if (capability == null) {
                galleryModernCapabilityClasses.remove(j0Class);
                return false;
            }
            capability.setAccessible(true);
            Object supported = findModernWatermarkSuccess(classLoader);
            if (supported == null) {
                galleryModernCapabilityClasses.remove(j0Class);
                return false;
            }
            hook(capability)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_modern_watermark_capability_only")
                    .intercept(chain -> pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)
                            ? supported : chain.proceed());
            log(Log.INFO, TAG, "Modern watermark capability-only hook installed (xy.j0.a)");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryModernCapabilityClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install capability-only watermark hook", error);
            return false;
        }
    }

    private Method findModernCapabilityMethod(Class<?> j0Class) {
        for (Method method : j0Class.getDeclaredMethods()) {
            if ("a".equals(method.getName())
                    && Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length == 4
                    && "dz.a".equals(method.getReturnType().getName())) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private Method findWatermarkAvailabilityMethod(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if ("e".equals(method.getName())
                    && Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length == 4
                    && Map.class.isAssignableFrom(method.getParameterTypes()[0])) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private Object findModernWatermarkSuccess(ClassLoader classLoader)
            throws ClassNotFoundException, ReflectiveOperationException {
        Class<?> resultBase = Class.forName("dz.a", false, classLoader);
        try {
            Class<?> named = Class.forName("dz.a$C0282a", false, classLoader);
            for (Field field : named.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && resultBase.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Obfuscation can rename the singleton class in another build.
        }
        for (Class<?> nested : resultBase.getDeclaredClasses()) {
            if (nested == null || nested.getName().endsWith(".b")) {
                continue;
            }
            for (Field field : nested.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && resultBase.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private boolean installGalleryWatermarkFragmentHook(ClassLoader classLoader) {
        try {
            Class<?> fragment = Class.forName(
                    "com.miui.mediaeditor.photo.watermark.PhotoWatermarkFragment",
                    false,
                    classLoader
            );
            if (!galleryWatermarkFragmentClasses.add(fragment)) {
                return true;
            }
            Method click = fragment.getDeclaredMethod("G0", fragment, int.class);
            click.setAccessible(true);
            hook(click)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_fragment_click")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            // The feature module may be loaded lazily after the
                            // fragment appears. Re-run resolution at click time.
                            installModernGalleryWatermarkHooks(fragment.getClassLoader());
                            installModernWatermarkCapabilityOnly(fragment.getClassLoader());
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery watermark fragment click hook installed");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkFragmentClasses.removeIf(type -> type.getClassLoader() == classLoader);
            log(Log.WARN, TAG, "Unable to install Gallery watermark fragment click hook", error);
            return false;
        }
    }

    private void forceWatermarkItemsAvailable(Object map) {
        if (!(map instanceof Map<?, ?> watermarkMap)) {
            return;
        }
        for (Object value : watermarkMap.values()) {
            if (!(value instanceof Iterable<?> items)) {
                continue;
            }
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                try {
                    Field available = null;
                    for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass()) {
                        try {
                            available = type.getDeclaredField("f15804d");
                            break;
                        } catch (NoSuchFieldException ignored) {
                            // Continue through intermediate item implementations.
                        }
                    }
                    if (available == null) {
                        continue;
                    }
                    available.setAccessible(true);
                    available.setBoolean(item, true);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Some local item implementations inherit through an
                    // intermediate class; their normal availability remains.
                }
            }
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
                            if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
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
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
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

    /**
     * MediaEditor 2.4.x performs the final device/EXIF/model restriction in
     * vg.t.a before a watermark item is selected. The reference modified APK
     * bypasses this exact result branch, so return the library's success value
     * while the all-watermarks preference is enabled.
     */
    private boolean installGalleryWatermarkRestrictionHook(ClassLoader classLoader) {
        try {
            Class<?> checker = Class.forName("vg.t", false, classLoader);
            if (!galleryWatermarkRestrictionClasses.add(checker)) {
                return true;
            }
            Class<?> itemClass = Class.forName("Fg.b", false, classLoader);
            Class<?> photoInfoClass = Class.forName("S3.b", false, classLoader);
            Class<?> contextClass = Class.forName("p286kh.e", false, classLoader);
            Method check = checker.getDeclaredMethod(
                    "a",
                    itemClass,
                    String.class,
                    photoInfoClass,
                    contextClass
            );
            check.setAccessible(true);

            Class<?> successClass = Class.forName("Bg.a$C0013a", false, classLoader);
            Field successField = successClass.getDeclaredField("f675a");
            successField.setAccessible(true);
            Object success = successField.get(null);
            if (success == null) {
                throw new IllegalStateException("Watermark restriction success result is null");
            }

            hook(check)
                    .setPriority(PRIORITY_HIGHEST)
                    .setId("gallery_watermark_legacy_restrictions")
                    .intercept(chain -> {
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            return success;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Gallery watermark restriction hook installed (vg.t.a)");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            galleryWatermarkRestrictionClasses.removeIf(
                    restrictionClass -> restrictionClass.getClassLoader() == classLoader
            );
            log(Log.WARN, TAG, "Unable to install Gallery watermark restriction hook", error);
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
                        if (pref(ModuleConfig.KEY_GALLERY_ALL_WATERMARKS, true)) {
                            // Keep the original return type/value. Older builds
                            // use this method for both filtering and catalog
                            // loading, so returning null can empty the menu.
                            return chain.proceed();
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
                Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
                loadClass.setAccessible(true);
                hook(loadClass)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_dynamic_watermark_manager_load2")
                        .intercept(chain -> {
                            Object loadedClass = chain.proceed();
                            installDeferredWatermarkClass(loadedClass, chain.getArg(0));
                            return loadedClass;
                        });

                Method loadClassSimple = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
                loadClassSimple.setAccessible(true);
                hook(loadClassSimple)
                        .setPriority(PRIORITY_HIGHEST)
                        .setId("gallery_dynamic_watermark_manager_load1")
                        .intercept(chain -> {
                            Object loadedClass = chain.proceed();
                            installDeferredWatermarkClass(loadedClass, chain.getArg(0));
                            return loadedClass;
                        });

                try {
                    Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader", false, null);
                    Method findClass = baseDex.getDeclaredMethod("findClass", String.class);
                    findClass.setAccessible(true);
                    hook(findClass)
                            .setPriority(PRIORITY_HIGHEST)
                            .setId("gallery_dynamic_watermark_manager_find")
                            .intercept(chain -> {
                                Object loadedClass = chain.proceed();
                                installDeferredWatermarkClass(loadedClass, chain.getArg(0));
                                return loadedClass;
                            });
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    log(Log.INFO, TAG, "BaseDexClassLoader.findClass hook unavailable");
                }
                galleryWatermarkClassLoadHookInstalled = true;
                log(Log.INFO, TAG, "Gallery dynamic watermark manager hook installed");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.WARN, TAG, "Unable to install Gallery dynamic watermark manager hook", error);
            }
        }
    }

    private void installDeferredWatermarkClass(Object loadedClass, Object name) {
        if (!(loadedClass instanceof Class<?> loaded) || !(name instanceof String className)) {
            return;
        }
        ClassLoader loader = loaded.getClassLoader();
        if ("xy.f0".equals(className) || "xy.j0".equals(className)
                || "xy.d".equals(className) || "xy.i".equals(className)) {
            log(Log.INFO, TAG, "Watermark class loaded: " + className + " via " + loader);
            installModernGalleryWatermarkHooks(loader);
            if ("xy.j0".equals(className)) {
                installModernWatermarkCapabilityOnly(loader);
            }
        } else if ("tb0.o0".equals(className)) {
            installGalleryWatermarkManagerHook(loader);
        } else if ("zn.a".equals(className)) {
            installGalleryWatermarkCapabilityHooks(loader);
        } else if ("vy.m0".equals(className)) {
            installGalleryWatermarkUsageHook(loader);
        } else if ("vg.t".equals(className)) {
            installGalleryWatermarkRestrictionHook(loader);
        } else if ("com.miui.mediaeditor.photo.watermark.PhotoWatermarkFragment".equals(className)) {
            installGalleryWatermarkFragmentHook(loader);
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
