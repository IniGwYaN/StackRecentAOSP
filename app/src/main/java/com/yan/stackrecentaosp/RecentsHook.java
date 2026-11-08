package com.yan.stackrecentaosp;

import android.content.Context;
import android.graphics.Outline;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.os.SystemClock;
import android.animation.TimeInterpolator;
import android.view.animation.LinearInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

/**
 * 核心 Hook 逻辑，通过修改 RecentsView 实现多任务界面的卡片堆叠视觉效果。
 */
public class RecentsHook {
    private static final String PREFS_NAME = "com.yan.stackrecentaosp_preferences";
    private static final String KEY_SPACING = "card_spacing_factor";
    private static final String KEY_TIGHTNESS = "card_tightness_factor";
    private static final float DEFAULT_SPACING = 4.7f;
    private static final float DEFAULT_TIGHTNESS = 0.72f;
    private static final long DEBUG_LOG_THROTTLE_MS = 1500;
    private static final float DISMISS_FILL_SHIFT_FACTOR = 0.75f;
    // How many px getPageSpacing() pulls neighbouring cards closer than stock Launcher does.
    private static final int PAGE_SPACING_REDUCTION_PX = 120;
    // During a quick-switch gesture the cards on the right ease back to their natural slot
    // once they are closer to the focus than this fraction of the carousel size.
    private static final float QUICK_SWITCH_RESTORE_RANGE = 0.35f;
    // Safety net in case onGestureAnimationEnd is never delivered.
    private static final long GESTURE_FLAG_MAX_AGE_MS = 30000L;
    private static final String FIELD_GESTURE_ACTIVE_SINCE = "apple_gesture_active_since";
    // Keep the overview card shape independent of icon-mask changes exposed by some ROMs.
    private static final float FIXED_TASK_CORNER_RADIUS_DP = 28f;

    private final ClassLoader classLoader;
    private final XSharedPreferences prefs;

    private Class<?> recentsViewClass;
    private Class<?> taskViewClass;
    private Class<?> orientationHandlerClass;
    
    private Field persistentTranslationXField;
    private Field nonGridTranslationXField;
    private Field dismissTranslationXField;
    private Field dismissTranslationYField;
    private Method isLayoutNaturallyHorizontalMethod;
    
    private Method getPagedOrientationHandlerMethod;
    private Method getPrimaryScrollMethod;
    private Method getMeasuredSizeMethod;
    private Method getChildStartMethod;
    private Method getOffsetAdjustmentMethod;

    public RecentsHook(ClassLoader classLoader) {
        this.classLoader = classLoader;
        this.prefs = new XSharedPreferences("com.yan.stackrecentaosp");
        
        try {
            java.io.File file = this.prefs.getFile();
            XposedBridge.log("StackRecentAOSP: Checking prefs at: " + file.getAbsolutePath());
            if (file.exists()) {
                XposedBridge.log("StackRecentAOSP: Prefs file found. Readable: " + file.canRead());
            } else {
                XposedBridge.log("StackRecentAOSP: Prefs file NOT found! This usually means MainActivity hasn't saved settings yet or permissions are restricted.");
            }
        } catch (Throwable t) {
            XposedBridge.log("StackRecentAOSP: Error checking prefs file: " + t.getMessage());
        }
    }

    public void initHooks() {
        XposedBridge.log("StackRecentAOSP: Initializing hooks for ClassLoader: " + classLoader);
        try {
            recentsViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.RecentsView", classLoader);
            taskViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.TaskView", classLoader);
            orientationHandlerClass = XposedHelpers.findClassIfExists("com.android.launcher3.touch.PagedOrientationHandler", classLoader);

            if (recentsViewClass == null || taskViewClass == null || orientationHandlerClass == null) {
                XposedBridge.log("StackRecentAOSP: Critical classes not found, aborting.");
                return;
            }

            // Keep Recent Apps task-card corners consistent when the ROM changes its icon mask.
            initCornerRadiusHooks();

            // Probing Fields
            persistentTranslationXField = findField(taskViewClass, "persistentTranslationX", "mPersistentTranslationX");
            nonGridTranslationXField = findField(taskViewClass, "nonGridTranslationX", "mNonGridTranslationX");
            dismissTranslationXField = findField(taskViewClass, "dismissTranslationX", "mDismissTranslationX");
            dismissTranslationYField = findField(taskViewClass, "dismissTranslationY", "mDismissTranslationY");

            // Probing Methods
            getPagedOrientationHandlerMethod = findMethod(recentsViewClass, "getPagedOrientationHandler");
            getPrimaryScrollMethod = findMethod(orientationHandlerClass, "getPrimaryScroll", View.class);
            getMeasuredSizeMethod = findMethod(orientationHandlerClass, "getMeasuredSize", View.class);
            getChildStartMethod = findMethod(orientationHandlerClass, "getChildStart", View.class);
            // Used to tell whether the carousel scrolls horizontally (portrait) or vertically (rotated/landscape).
            isLayoutNaturallyHorizontalMethod = findMethod(orientationHandlerClass, "isLayoutNaturallyHorizontal");
            
            // Hook getOffsetAdjustment to report our stack offset to Launcher's layout/snapping logic.
            // This ensures that snap-to-page and hit-testing work correctly with our shifts.
            Method getOffsetAdjustmentMethod = findMethod(taskViewClass, "getOffsetAdjustment");
            if (getOffsetAdjustmentMethod != null) {
                XposedBridge.hookMethod(getOffsetAdjustmentMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View child = (View) param.thisObject;
                        Float extra = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                        if (extra != null && extra != 0f) {
                            float original = (float) param.getResult();
                            param.setResult(original + extra);
                        }
                    }
                });
                XposedBridge.log("StackRecentAOSP: Hooked getOffsetAdjustment");
             }

             // Hook getPageSpacing to physically bring cards closer and reduce scroll distance
             Method getPageSpacingMethod = findMethod(recentsViewClass, "getPageSpacing");
             if (getPageSpacingMethod != null) {
                 XposedBridge.hookMethod(getPageSpacingMethod, new XC_MethodHook() {
                     @Override
                     protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                         prefs.reload();
                         float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
                         int original = (int) param.getResult();
                         param.setResult(original + pageSpacingAdjustmentPx(spacingFactor));
                     }
                 });
                 XposedBridge.log("StackRecentAOSP: Hooked getPageSpacing");
             }

             // Unify entry state: Hook onStateTransitionStart and onGestureAnimationStart
             // to ensure the internal mPageSpacing field is updated before any animation
             Class<?> launcherRecentsViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.LauncherRecentsView", classLoader);
             XC_MethodHook entryHook = new XC_MethodHook() {
                 @Override
                 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                     updatePageSpacingField(param.thisObject);
                 }
             };

             if (launcherRecentsViewClass != null) {
                 XposedBridge.hookAllMethods(launcherRecentsViewClass, "onStateTransitionStart", entryHook);
             }
             XposedBridge.hookAllMethods(recentsViewClass, "onGestureAnimationStart", entryHook);

             // Track "a swipe gesture from an app is running" (quick switch between apps, swipe-up).
             // NOTE: Launcher's own mEnableDrawingLiveTile is the opposite signal: it is switched OFF
             // in onGestureAnimationStart and only comes back ON after the gesture ended in Overview.
             XposedBridge.hookAllMethods(recentsViewClass, "onGestureAnimationStart", new XC_MethodHook() {
                 @Override
                 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                     XposedHelpers.setAdditionalInstanceField(param.thisObject,
                             FIELD_GESTURE_ACTIVE_SINCE, SystemClock.uptimeMillis());
                 }
             });
             XposedBridge.hookAllMethods(recentsViewClass, "onGestureAnimationEnd", new XC_MethodHook() {
                 @Override
                 protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                     XposedHelpers.setAdditionalInstanceField(param.thisObject,
                             FIELD_GESTURE_ACTIVE_SINCE, null);
                 }
             });

             // Additional hooks for early state synchronization
             XposedBridge.hookAllMethods(recentsViewClass, "onMeasure", new XC_MethodHook() {
                 @Override
                 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                     updatePageSpacingField(param.thisObject);
                 }
             });

             XposedBridge.hookAllMethods(recentsViewClass, "onVisibilityChanged", new XC_MethodHook() {
                 @Override
                 protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                     if (param.args != null && param.args.length >= 2 && (int)param.args[1] == View.VISIBLE) {
                         updatePageSpacingField(param.thisObject);
                     }
                 }
             });

             // Ensure mPageSpacing is updated for dismiss animations too (createTaskDismissAnimation uses the field)
             Class<?> pendingAnimationClass = XposedHelpers.findClassIfExists(
                     "com.android.launcher3.anim.PendingAnimation", classLoader);
             if (pendingAnimationClass != null) {
                 Method createTaskDismissAnimationMethod = findMethod(
                         recentsViewClass,
                         "createTaskDismissAnimation",
                         pendingAnimationClass,
                         taskViewClass,
                         boolean.class,
                         boolean.class,
                         long.class,
                         boolean.class
                 );
                 if (createTaskDismissAnimationMethod != null) {
                     XposedBridge.hookMethod(createTaskDismissAnimationMethod, new XC_MethodHook() {
                         @Override
                         protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                             updatePageSpacingField(param.thisObject);
                         }
                     });
                     XposedBridge.log("StackRecentAOSP: Hooked createTaskDismissAnimation (sync page spacing)");
                 }
             }

             // Hook TaskView.applyTranslationX and Y to inject our stack offset
            // NOTE: On many Launcher versions, applyTranslationX already calls getOffsetAdjustment().
            // If it does, our hook above handles it. If it doesn't, we might need this hook as a fallback.
            // We'll keep this hook but ONLY apply the subtraction of dismissTranslationX here,
            // to avoid double-adding the apple_stack_offset if getOffsetAdjustment is already working.
            Method applyTranslationXMethod = findMethod(taskViewClass, "applyTranslationX");
            if (applyTranslationXMethod != null) {
                XposedBridge.hookMethod(applyTranslationXMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View child = (View) param.thisObject;

                        // Landscape fix (issue #3): when the carousel scrolls vertically the stack offset
                        // lives on the Y axis (see applyTranslationY hook), so X must stay untouched.
                        if (!isPrimaryAxisHorizontal(child)) return;

                        // We restore the manual addition of apple_stack_offset here.
                        // While getOffsetAdjustment hook handles logic/snapping, 
                        // many Launcher versions don't actually call getOffsetAdjustment() 
                        // inside their applyTranslationX() implementation for the visual update.
                        Float extra = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                        if (extra != null && extra != 0f) {
                            child.setTranslationX(child.getTranslationX() + extra);
                        }
                        
                        // IMPORTANT: When a task is being dismissed, Launcher applies its own 'dismissTranslationX'.
                        // We counteract it because we handle the gap-filling ourselves via physical linkage.
                        if (dismissTranslationXField != null) {
                            try {
                                float dismissTx = dismissTranslationXField.getFloat(child);
                                if (Math.abs(dismissTx) > 0.1f) {
                                    child.setTranslationX(child.getTranslationX() - dismissTx);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            }

            Method applyTranslationYMethod = findMethod(taskViewClass, "applyTranslationY");
            if (applyTranslationYMethod != null) {
                XposedBridge.hookMethod(applyTranslationYMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View child = (View) param.thisObject;
                        Float extra = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset_y");
                        if (extra != null && extra != 0f) {
                            child.setTranslationY(child.getTranslationY() + extra);
                        }

                        // Landscape fix (issue #3): carousel is vertical, so the stack offset
                        // (computed along the primary axis) is applied to Y instead of X.
                        if (!isPrimaryAxisHorizontal(child)) {
                            Float primary = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                            if (primary != null && primary != 0f) {
                                child.setTranslationY(child.getTranslationY() + primary);
                            }
                            if (dismissTranslationYField != null) {
                                try {
                                    float dismissTy = dismissTranslationYField.getFloat(child);
                                    if (Math.abs(dismissTy) > 0.1f) {
                                        child.setTranslationY(child.getTranslationY() - dismissTy);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                });
            }

            // Hook resetViewTransforms to maintain our Z-index and force re-apply our stack translation
             Method resetViewTransformsMethod = findMethod(taskViewClass, "resetViewTransforms");
             if (resetViewTransformsMethod != null) {
                 XposedBridge.hookMethod(resetViewTransformsMethod, new XC_MethodHook() {
                     @Override
                     protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                         View child = (View) param.thisObject;
                         Float targetZ = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_target_z");
                         if (targetZ != null) {
                             child.setTranslationZ(targetZ);
                         }
                         // Re-apply our custom X/Y translation after the system reset
                         XposedHelpers.callMethod(child, "applyTranslationX");
                         XposedHelpers.callMethod(child, "applyTranslationY");
                     }
                 });
             }

            Method updateCurvePropertiesMethod = findMethod(recentsViewClass, "updateCurveProperties");
            if (updateCurvePropertiesMethod != null) {
                XposedBridge.hookMethod(updateCurvePropertiesMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        handleUpdateCurveProperties(param);
                    }
                });
                XposedBridge.log("StackRecentAOSP: Successfully hooked updateCurveProperties");
            }

            XposedBridge.hookAllMethods(recentsViewClass, "dispatchDraw", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object untilObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "apple_dismiss_until");
                        if (untilObj instanceof Long) {
                            long until = (Long) untilObj;
                            if (SystemClock.uptimeMillis() < until) {
                                XposedHelpers.callMethod(param.thisObject, "updateCurveProperties");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedBridge.hookAllMethods(recentsViewClass, "createTaskDismissAnimation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    updatePageSpacingField(param.thisObject);
                    try {
                        if (param.args != null && param.args.length >= 5) {
                            Object durationObj = param.args[4];
                            long duration = durationObj instanceof Long ? (Long) durationObj
                                    : durationObj instanceof Integer ? ((Integer) durationObj).longValue()
                                    : 0L;
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_dismiss_until",
                                    SystemClock.uptimeMillis() + duration + 200L);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedBridge.hookAllMethods(recentsViewClass, "translateTaskWhenDismissed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    handleTranslateTaskWhenDismissed(param);
                }
            });

            // Fallback for interaction: hook getTaskViewAt if getOffsetAdjustment is not enough
            Method getTaskViewAtMethod = findMethod(recentsViewClass, "getTaskViewAt", float.class, float.class);
            if (getTaskViewAtMethod != null) {
                XposedBridge.hookMethod(getTaskViewAtMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.getResult() == null) {
                            handleGetTaskViewAt(param);
                        }
                    }
                });
            }

        } catch (Throwable t) {
            XposedBridge.log("StackRecentAOSP: Error during initHooks: " + Log.getStackTraceString(t));
        }
    }

    /**
     * Some ROMs couple the launcher's task-card corner radius to the currently selected icon shape.
     * Override the launcher radius provider and thumbnail drawing radius to a stable rounded value.
     * All hooks are optional so Launcher forks with different internals still load normally.
     */
    private void initCornerRadiusHooks() {
        boolean hookedTaskRadius = false;
        Class<?> radiusClass = XposedHelpers.findClassIfExists(
                "com.android.quickstep.util.TaskCornerRadius", classLoader);
        if (radiusClass != null) {
            for (Method method : radiusClass.getDeclaredMethods()) {
                if (!"get".equals(method.getName())
                        || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterTypes().length != 1
                        || (method.getReturnType() != float.class
                            && method.getReturnType() != Float.class)) {
                    continue;
                }
                Class<?> argumentType = method.getParameterTypes()[0];
                if (!Context.class.isAssignableFrom(argumentType)
                        && !argumentType.isAssignableFrom(Context.class)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length > 0
                                    && param.args[0] instanceof Context) {
                                param.setResult(fixedTaskCornerRadius((Context) param.args[0]));
                            }
                        }
                    });
                    hookedTaskRadius = true;
                } catch (Throwable t) {
                    XposedBridge.log("StackRecentAOSP: Could not hook TaskCornerRadius.get: " + t.getMessage());
                }
            }
        }

        // Fallback for Launcher builds that expose the radius directly on TaskView.
        if (taskViewClass != null) {
            for (Method method : taskViewClass.getDeclaredMethods()) {
                if (!"getTaskCornerRadius".equals(method.getName())
                        || method.getParameterTypes().length != 0
                        || (method.getReturnType() != float.class
                            && method.getReturnType() != Float.class)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View) {
                                param.setResult(fixedTaskCornerRadius(
                                        ((View) param.thisObject).getContext()));
                            }
                        }
                    });
                    hookedTaskRadius = true;
                } catch (Throwable t) {
                    XposedBridge.log("StackRecentAOSP: Could not hook TaskView corner radius: " + t.getMessage());
                }
            }
        }

        // TaskThumbnailView paints its snapshot using a separate radius. Override the draw argument
        // as well as the getter/field where present to avoid a mismatch between the snapshot and card.
        Class<?> thumbnailClass = XposedHelpers.findClassIfExists(
                "com.android.quickstep.views.TaskThumbnailView", classLoader);
        if (thumbnailClass != null) {
            try {
                XposedBridge.hookAllConstructors(thumbnailClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Field radiusField = findField(param.thisObject.getClass(), "mCornerRadius", "cornerRadius");
                            if (radiusField != null) {
                                radiusField.setFloat(param.thisObject,
                                        fixedTaskCornerRadius(((View) param.thisObject).getContext()));
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("StackRecentAOSP: Could not set thumbnail corner radius: " + t.getMessage());
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("StackRecentAOSP: Could not hook thumbnail constructor: " + t.getMessage());
            }

            for (Method method : thumbnailClass.getDeclaredMethods()) {
                if ("drawOnCanvas".equals(method.getName())) {
                    try {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args == null || param.args.length == 0
                                        || !(param.args[param.args.length - 1] instanceof Float)
                                        || !(param.thisObject instanceof View)) {
                                    return;
                                }
                                param.args[param.args.length - 1] = fixedTaskCornerRadius(
                                        ((View) param.thisObject).getContext());
                            }
                        });
                    } catch (Throwable t) {
                        XposedBridge.log("StackRecentAOSP: Could not hook thumbnail drawing radius: " + t.getMessage());
                    }
                } else if ("getCornerRadius".equals(method.getName())
                        && method.getParameterTypes().length == 0
                        && (method.getReturnType() == float.class || method.getReturnType() == Float.class)) {
                    try {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (param.thisObject instanceof View) {
                                    param.setResult(fixedTaskCornerRadius(
                                            ((View) param.thisObject).getContext()));
                                }
                            }
                        });
                    } catch (Throwable t) {
                        XposedBridge.log("StackRecentAOSP: Could not hook thumbnail radius getter: " + t.getMessage());
                    }
                }
            }
        }

        boolean outlineProviderHooked = hookTaskOutlineProviders(taskViewClass, thumbnailClass);
        XposedBridge.log("StackRecentAOSP: Stable task-card corner radius enabled (28dp); providerHooked="
                + hookedTaskRadius + ", outlineProviderHooked=" + outlineProviderHooked
                + ", thumbnailClass=" + (thumbnailClass != null));
    }

    /**
     * Some Launcher3 forks draw the outer task-card outline from a nested ViewOutlineProvider
     * rather than from TaskCornerRadius on every frame. Hook that final outline as a fallback
     * so changing the app-icon mask does not make the entire recent-task card square.
     */
    private boolean hookTaskOutlineProviders(Class<?>... hostClasses) {
        boolean hooked = false;
        if (hostClasses == null) return false;

        for (Class<?> hostClass : hostClasses) {
            if (hostClass == null) continue;

            Class<?>[] nestedClasses;
            try {
                nestedClasses = hostClass.getDeclaredClasses();
            } catch (Throwable ignored) {
                nestedClasses = new Class<?>[0];
            }

            for (Class<?> nested : nestedClasses) {
                if (!nested.getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("outline")) {
                    continue;
                }

                for (Method method : nested.getDeclaredMethods()) {
                    if (!"getOutline".equals(method.getName())
                            || method.getParameterTypes().length != 2
                            || !View.class.isAssignableFrom(method.getParameterTypes()[0])
                            || !Outline.class.isAssignableFrom(method.getParameterTypes()[1])) {
                        continue;
                    }

                    try {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!(param.args[0] instanceof View) || !(param.args[1] instanceof Outline)) {
                                    return;
                                }

                                View view = (View) param.args[0];
                                if (view.getWidth() <= 0 || view.getHeight() <= 0) return;

                                int top = 0;
                                Field marginField = findField(
                                        param.thisObject.getClass(), "mMarginTop", "marginTop");
                                if (marginField != null) {
                                    try {
                                        top = marginField.getInt(param.thisObject);
                                    } catch (Throwable ignored) {
                                        // Keep the default top inset if the ROM uses a different field type.
                                    }
                                }
                                top = Math.max(0, Math.min(top, view.getHeight() - 1));

                                Outline outline = (Outline) param.args[1];
                                outline.setRoundRect(0, top, view.getWidth(), view.getHeight(),
                                        fixedTaskCornerRadius(view.getContext()));
                                // The provider returns void; setResult skips its original outline calculation.
                                param.setResult(null);
                            }
                        });
                        hooked = true;
                        XposedBridge.log("StackRecentAOSP: Hooked task outline provider " + nested.getName());
                    } catch (Throwable t) {
                        XposedBridge.log("StackRecentAOSP: Could not hook outline provider "
                                + nested.getName() + ": " + t.getMessage());
                    }
                }
            }
        }
        return hooked;
    }

    private float fixedTaskCornerRadius(Context context) {
        return FIXED_TASK_CORNER_RADIUS_DP * context.getResources().getDisplayMetrics().density;
    }

    private Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            Field f = XposedHelpers.findFieldIfExists(clazz, name);
            if (f != null) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Method m = XposedHelpers.findMethodExactIfExists(clazz, name, (Object[]) parameterTypes);
        if (m != null) {
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** Extra page spacing (px, normally negative) this module adds on top of Launcher's own spacing. */
    private static int pageSpacingAdjustmentPx(float spacingFactor) {
        return Math.round((spacingFactor - DEFAULT_SPACING) * 10f) - PAGE_SPACING_REDUCTION_PX;
    }

    /** True between onGestureAnimationStart and onGestureAnimationEnd of a swipe gesture from an app. */
    private boolean isGestureActive(Object recentsView) {
        try {
            Object since = XposedHelpers.getAdditionalInstanceField(recentsView, FIELD_GESTURE_ACTIVE_SINCE);
            if (!(since instanceof Long)) return false;
            return SystemClock.uptimeMillis() - (Long) since < GESTURE_FLAG_MAX_AGE_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when this card is the running task and Launcher is drawing the live tile for it. */
    private boolean isLiveTileCard(Object recentsView, View child) {
        try {
            if (!XposedHelpers.getBooleanField(recentsView, "mEnableDrawingLiveTile")) return false;
            return (boolean) XposedHelpers.callMethod(child, "isRunningTask");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns the running TaskView while Launcher is drawing it as a live tile, otherwise null. */
    private View getLiveTileTaskView(Object recentsView) {
        try {
            if (!XposedHelpers.getBooleanField(recentsView, "mEnableDrawingLiveTile")) return null;
            Object running = XposedHelpers.callMethod(recentsView, "getRunningTaskView");
            return running instanceof View ? (View) running : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** True when the carousel scrolls horizontally (portrait handler), false for landscape/seascape. */
    private boolean isPrimaryAxisHorizontal(View child) {
        try {
            Object parent = child.getParent();
            if (parent != null && recentsViewClass != null && recentsViewClass.isInstance(parent)
                    && getPagedOrientationHandlerMethod != null) {
                return isPrimaryAxisHorizontalForHandler(getPagedOrientationHandlerMethod.invoke(parent));
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private boolean isPrimaryAxisHorizontalForHandler(Object handler) {
        if (handler == null) return true;
        try {
            if (isLayoutNaturallyHorizontalMethod != null) {
                return (boolean) isLayoutNaturallyHorizontalMethod.invoke(handler);
            }
        } catch (Throwable ignored) {
        }
        String name = handler.getClass().getSimpleName();
        return !(name.contains("Landscape") || name.contains("Seascape"));
    }

    private float lastSpacingFactor = -1;
    private float lastTightnessFactor = -1;
    private long lastDismissHookLogUptime;
    private long lastDiagLogUptime;
    private long lastQuickSwitchLogUptime;

    private void handleUpdateCurveProperties(XC_MethodHook.MethodHookParam param) {
        ViewGroup recentsView = (ViewGroup) param.thisObject;
        if (recentsView == null) return;

        try {
            if (getPagedOrientationHandlerMethod == null) return;
            Object handler = getPagedOrientationHandlerMethod.invoke(recentsView);
            if (handler == null) return;
            final boolean horizontal = isPrimaryAxisHorizontalForHandler(handler);

            // Reload prefs to get dynamic changes
            prefs.reload();
            float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
            float tightnessFactor = prefs.getFloat(KEY_TIGHTNESS, DEFAULT_TIGHTNESS);
            if (spacingFactor != lastSpacingFactor || tightnessFactor != lastTightnessFactor) {
                XposedBridge.log("StackRecentAOSP: Parameters updated - Spacing: " + spacingFactor + ", Tightness: " + tightnessFactor);
                lastSpacingFactor = spacingFactor;
                lastTightnessFactor = tightnessFactor;
            }

            int scroll = (int) getPrimaryScrollMethod.invoke(handler, recentsView);
            int measuredSize = (int) getMeasuredSizeMethod.invoke(handler, recentsView);
            
            if (measuredSize <= 0) return; // Avoid invalid calculations on first frame

            float center = scroll + measuredSize / 2f;
            int diagRunningIndex = -2; // -2 = no running task view
            
            // Physics Linkage Fallback:
            // When entering from an app, scroll is often 0 initially while the 'Running Task' 
            // is actually the primary focus. We use the running task's logical center 
            // as our reference to prevent all cards from overlapping at the beginning.
            try {
                View runningTask = (View) XposedHelpers.callMethod(recentsView, "getRunningTaskView");
                if (runningTask != null) {
                    // Check if scroll is 0 but running task is at a later index
                    int runningIndex = (int) XposedHelpers.callMethod(recentsView, "indexOfChild", runningTask);
                    diagRunningIndex = runningIndex;
                    if (scroll == 0 && runningIndex > 0) {
                        int childStart = (int) getChildStartMethod.invoke(handler, runningTask);
                        int childSize = (int) getMeasuredSizeMethod.invoke(handler, runningTask);
                        center = childStart + childSize / 2f;
                    }
                }
            } catch (Throwable ignored) {}

            long nowUptime = SystemClock.uptimeMillis();
            if (nowUptime - lastDiagLogUptime > DEBUG_LOG_THROTTLE_MS) {
                lastDiagLogUptime = nowUptime;
                XposedBridge.log("StackRecentAOSP[diag]: horizontal=" + horizontal
                        + " scroll=" + scroll + " size=" + measuredSize + " center=" + center
                        + " runningIndex=" + diagRunningIndex + " children=" + recentsView.getChildCount());
            }
            boolean isDismissing = false;
            long dismissUntil = 0L;
            try {
                Object untilObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_dismiss_until");
                if (untilObj instanceof Long) {
                    dismissUntil = (Long) untilObj;
                    isDismissing = nowUptime < dismissUntil;
                }
            } catch (Throwable ignored) {
            }

            int childCount = recentsView.getChildCount();

            // Issue #2 fix: while the running app is drawn as a "live tile", Launcher positions that
            // surface from its own scroll math and knows nothing about our stack offset/scale. If we move
            // the TaskView anyway, the card and the live window drift apart (about 150-180 px on a phone)
            // and the card's empty area shows the wallpaper. So the live-tile card is left untouched.
            final View liveTileTask = getLiveTileTaskView(recentsView);

            // Quick switch (sliding the nav bar between apps). During the gesture Launcher draws the
            // running app as the window itself (no live tile yet) and Android keeps the recents order
            // frozen while the user keeps switching, so from the 2nd app onwards the running app has
            // cards on its RIGHT. Pulling those in (page-spacing overlap + stack pull + 1.04x scale)
            // painted a strip of another app over the running app. Left side behaviour is unchanged.
            boolean runningTaskPresent = false;
            try {
                runningTaskPresent = XposedHelpers.callMethod(recentsView, "getRunningTaskView") != null;
            } catch (Throwable ignored) {
            }
            final boolean quickSwitch = liveTileTask == null && runningTaskPresent
                    && isGestureActive(recentsView);
            if (nowUptime - lastQuickSwitchLogUptime > DEBUG_LOG_THROTTLE_MS
                    && (quickSwitch || isGestureActive(recentsView))) {
                lastQuickSwitchLogUptime = nowUptime;
                XposedBridge.log("StackRecentAOSP[diag]: gesture quickSwitch=" + quickSwitch
                        + " runningTask=" + runningTaskPresent + " liveTile=" + (liveTileTask != null)
                        + " horizontal=" + horizontal + " children=" + childCount);
            }

            // First pass: Calculate distance and weights for all cards
            for (int i = 0; i < childCount; i++) {
                View child = recentsView.getChildAt(i);
                if (child == null || !taskViewClass.isInstance(child)) continue;

                int childStart = (int) getChildStartMethod.invoke(handler, child);
                int childSize = (int) getMeasuredSizeMethod.invoke(handler, child);
                float childCenter = childStart + childSize / 2f;
                float distance = childCenter - center;
                float absDistance = Math.abs(distance);

                XposedHelpers.setAdditionalInstanceField(child, "apple_stack_distance", distance);
                XposedHelpers.setAdditionalInstanceField(child, "apple_stack_abs_distance", absDistance);

                // Calculate dismiss progress from both manual drag (translationY) and auto animation (alpha)
                // The dismiss swipe happens on the axis perpendicular to the carousel:
                // Y when the carousel is horizontal (portrait), X when it is vertical (landscape).
                float secondaryTrans = Math.abs(horizontal ? child.getTranslationY() : child.getTranslationX());
                float secondarySize = horizontal ? child.getHeight() : child.getWidth();
                // Use 35% of the card size as the point where the card is "fully gone" from layout perspective
                float dismissThreshold = secondarySize * 0.35f;
                // Guard: before the first layout pass the size is 0 and the division produced NaN.
                float dragProgress = dismissThreshold > 0f ? clamp(secondaryTrans / dismissThreshold, 0f, 1f) : 0f;
                float alphaProgress = clamp(1.0f - child.getAlpha(), 0f, 1f);
                
                // Total progress is the maximum of drag and alpha animations
                float totalProgress = Math.max(dragProgress, alphaProgress);
                XposedHelpers.setAdditionalInstanceField(child, "apple_dismiss_progress", totalProgress);
                
                // If it's completely gone, weight is 0. Otherwise, it's 1.0 -> 0.0
                float weight = (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.05f) ? 0f : (1.0f - totalProgress);
                XposedHelpers.setAdditionalInstanceField(child, "apple_layout_weight", weight);
            }

            // Second pass: Apply physics and calculate smooth rank-based positions
            for (int i = 0; i < childCount; i++) {
                View child = recentsView.getChildAt(i);
                if (child == null || !taskViewClass.isInstance(child)) continue;

                Float distanceObj = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_distance");
                Float weightObj = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_layout_weight");
                if (distanceObj == null || weightObj == null) continue;

                float originalDistance = distanceObj;
                float selfWeight = weightObj;

                if (child == liveTileTask) {
                    // Drop any offset applied before the live tile became active and let Launcher drive it.
                    Float prevOffset = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", 0f);
                    if (prevOffset != null && prevOffset != 0f) {
                        XposedHelpers.callMethod(child, horizontal ? "applyTranslationX" : "applyTranslationY");
                    }
                    // Keep the same draw order the stacked cards use. Without a Z value this card sat at 0,
                    // so every stacked card (Z ~100) was drawn on top of the running app's card.
                    float liveZ = originalDistance < 0f ? 100f : ((i == childCount - 1) ? 200f : (150f - i));
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", liveZ);
                    child.setTranslationZ(liveZ);
                    continue;
                }

                // Reset for gone cards
                if (selfWeight <= 0f && (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.01f)) {
                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", 0f);
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", 0f);
                    continue;
                }

                // Issue #2: the running task is drawn as a "live tile" (the real app window) on top of its
                // card. The live tile follows Launcher's own card transform, not ours. If we also scale /
                // shift / fade this card, the card and the live tile drift apart and the card behind the
                // window shows the wallpaper. So the live-tile card keeps Launcher's transform untouched.
                if (isLiveTileCard(recentsView, child)) {
                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", 0f);
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", 0f);
                    continue;
                }

                if (originalDistance < 0) { // Left side stacking
                    float effectiveRank = 0f;
                    float dismissPullProgress = 0f; 
                    
                    for (int j = 0; j < childCount; j++) {
                        View other = recentsView.getChildAt(j);
                        if (other == null || other == child || !taskViewClass.isInstance(other)) continue;
                        
                        Float otherDistance = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_stack_distance");
                        Float otherWeight = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_layout_weight");
                        Float otherProgress = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_dismiss_progress");
                        if (otherDistance == null || otherWeight == null || otherProgress == null) continue;

                        // Rank: Only count visible cards that are NOT being dismissed.
                        // We use a smooth transition (100px range) instead of a 1px hard cut to avoid jitter.
                        if (otherDistance > originalDistance && otherWeight > 0.01f) {
                            float rankWeight = clamp((otherDistance - 50f) / 100f, 0f, 1f);
                            if (rankWeight < 1.0f) {
                                effectiveRank += otherWeight * (1.0f - rankWeight);
                            }
                        }

                        // Pull: ONLY count cards actively being swiped up
                        // We restrict this to cards between us and the focus center (plus a buffer)
                        // to avoid right-side dismissals from pulling left-side cards.
                        if (otherDistance > originalDistance && otherDistance < 50f && otherProgress > 0.01f) {
                            dismissPullProgress += (1.0f - otherWeight);
                        }
                    }

                    // --- PHYSICAL LINKAGE CORE ---
                    float scrollUnit = 0f;
                    Object savedStep = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_last_scroll_step");
                    if (savedStep instanceof Float) {
                        scrollUnit = (Float) savedStep;
                    }

                    if (scrollUnit <= 0) {
                        scrollUnit = measuredSize * 0.75f;
                    }

                    // Pull Unloading Mechanism:
                    // As the card approaches the physical center (originalDistance -> 0), 
                    // we fade out the virtual pull because the manual scroll has "filled the gap".
                    float pullFade = clamp(-originalDistance / scrollUnit, 0f, 1f);
                    float effectivePull = dismissPullProgress * scrollUnit * pullFade;
                    
                    // virtualDistance should be 0 when at center
                    float virtualDistance = originalDistance + effectivePull;
                    float absVirtualDistance = Math.abs(virtualDistance);
                    
                    float peek = clamp(spacingFactor * 14f, measuredSize * 0.03f, measuredSize * 0.12f);
                    float stackTarget = -effectiveRank * peek;

                    float baseK = spacingFactor * 55f;
                    float exponent = 1.20f + (tightnessFactor * 0.80f) + (effectiveRank * 0.05f);
                    float hill = (float) (1.0 / (1.0 + Math.pow(absVirtualDistance / baseK, exponent)));

                    float finalTargetPos = (virtualDistance * hill) + (stackTarget * (1f - hill));
                    float extraTranslation = finalTargetPos - originalDistance;
                    
                    // Use higher responsiveness for manual scrolling.
                    // Only use smooth lerp when the card is being pulled purely by a dismissal.
                    Float currentOffsetObj = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                    float currentOffset = currentOffsetObj != null ? currentOffsetObj : 0f;
                    
                    // If the user is actively scrolling (originalDistance changing) or near center, 
                    // increase responsiveness to 1.0.
                    // We also blend the lerp factor to avoid sudden jumps.
                    float targetLerp = (dismissPullProgress > 0.01f && absVirtualDistance > 30f) ? 0.72f : 1.0f;
                    float lerpFactor = currentOffsetObj != null ? 
                            (0.85f * targetLerp + 0.15f * 1.0f) : targetLerp; // Slight bias towards stability
                    
                    float smoothedOffset = currentOffset + (extraTranslation - currentOffset) * lerpFactor;

                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", smoothedOffset);
                    
                    // Manually trigger property update on the carousel axis
                    XposedHelpers.callMethod(child, horizontal ? "applyTranslationX" : "applyTranslationY");
                    
                    // Visual effects (Scale/Alpha) also use virtual distance for perfect synchronization
                    float visualProgress = Math.min(1.0f, absVirtualDistance / (measuredSize * 2.5f));
                    float scale = 1.0f - (visualProgress * 0.20f);
                    child.setScaleX(scale);
                    child.setScaleY(scale);
                    child.setAlpha(1.0f - (visualProgress * 0.2f));

                    XposedHelpers.setAdditionalInstanceField(child, "apple_child_index", effectiveRank);
                    float targetZ = 100f - effectiveRank;
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", targetZ);
                    child.setTranslationZ(targetZ);
                } else {
                    // Right side cards: stable anchor
                    float dismissPullProgressRight = 0f;
                    for (int j = 0; j < childCount; j++) {
                        View other = recentsView.getChildAt(j);
                        if (other == null || other == child || !taskViewClass.isInstance(other)) continue;
                        Float otherDistance = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_stack_distance");
                        Float otherWeight = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_layout_weight");
                        Float otherProgress = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_dismiss_progress");
                        if (otherDistance == null || otherWeight == null || otherProgress == null) continue;

                        // If a card closer to the center is being dismissed, pull this card left.
                         // We ONLY do this if the dismissed card is also on the right side (dist > 50).
                         // If the center card is dismissed, only left cards should move right.
                         if (otherDistance < originalDistance && otherDistance > 50f && otherProgress > 0.01f) {
                             dismissPullProgressRight += (1.0f - otherWeight);
                         }
                    }

                    float scrollUnit = 0f;
                    Object savedStep = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_last_scroll_step");
                    if (savedStep instanceof Float) {
                        scrollUnit = (Float) savedStep;
                    }
                    if (scrollUnit <= 0) {
                        scrollUnit = measuredSize * 0.75f;
                    }

                    // Pull Unloading Mechanism for Right Side:
                    // We use a simpler pull for the right side to maintain spacing between cards.
                    float effectivePull = -dismissPullProgressRight * scrollUnit;
                    float virtualDistance = originalDistance + effectivePull;
                    float absVirtualDistance = Math.abs(virtualDistance);

                    boolean isLastChild = (i == childCount - 1);
                    float targetZ = isLastChild ? 200f : (150f - i);
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", targetZ);
                    child.setTranslationZ(targetZ);

                    float extraTranslation;
                    if (quickSwitch) {
                        // Put right-hand neighbours back where stock Launcher has them (off-screen) by
                        // cancelling the overlap getPageSpacing() adds. The card being centred eases back
                        // to offset 0 so the app-launch animation still lines up with it.
                        float restore = -pageSpacingAdjustmentPx(spacingFactor);
                        float restoreFade = Math.min(1.0f,
                                absVirtualDistance / (measuredSize * QUICK_SWITCH_RESTORE_RANGE));
                        extraTranslation = (restore * restoreFade) + effectivePull;
                    } else {
                        // Increase negative translation to pull right cards closer
                        float peekRightMax = clamp(spacingFactor * 35f, measuredSize * 0.08f, measuredSize * 0.25f);
                        float decayFactor = Math.min(1.0f, absVirtualDistance / (measuredSize * 0.8f));
                        extraTranslation = (-peekRightMax * decayFactor) + effectivePull;
                    }
                    
                    Float currentOffsetRightObj = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                    float currentOffsetRight = currentOffsetRightObj != null ? currentOffsetRightObj : 0f;
                    
                    // Higher responsiveness for dismiss animations
                    float targetLerp = (dismissPullProgressRight > 0.01f && absVirtualDistance > 30f) ? 0.72f : 1.0f;
                    float smoothedOffset = currentOffsetRight + (extraTranslation - currentOffsetRight) * (0.4f * targetLerp + 0.6f * 1.0f);

                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", smoothedOffset);
                    XposedHelpers.callMethod(child, horizontal ? "applyTranslationX" : "applyTranslationY");

                    if (quickSwitch) {
                        // Stock quick-switch look: no depth scaling or fading on the incoming card.
                        child.setScaleX(1.0f);
                        child.setScaleY(1.0f);
                        child.setAlpha(1.0f);
                    } else {
                        // Scale logic: make it slightly LARGER as it moves right to create depth hierarchy
                        float progress = Math.min(1.0f, absVirtualDistance / (measuredSize * 1.5f));
                        float scale = 1.0f + (progress * 0.04f);
                        child.setScaleX(scale);
                        child.setScaleY(scale);
                        child.setAlpha(1.0f - (progress * 0.02f));
                    }
                }

                if (!isDismissing && dismissTranslationXField != null) {
                    try {
                        Object targetObj = XposedHelpers.getAdditionalInstanceField(child, "apple_dismiss_target");
                        if (targetObj instanceof Integer) {
                            float dismissTx = dismissTranslationXField.getFloat(child);
                            if (Math.abs(dismissTx) < 0.1f) {
                                XposedHelpers.setAdditionalInstanceField(child, "apple_dismiss_target", null);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            // log once
        }
    }

    private void handleTranslateTaskWhenDismissed(XC_MethodHook.MethodHookParam param) {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastDismissHookLogUptime > DEBUG_LOG_THROTTLE_MS) {
                lastDismissHookLogUptime = now;
                XposedBridge.log("StackRecentAOSP: translateTaskWhenDismissed hooked");
            }

            if (param.args == null || param.args.length < 6) {
                return;
            }
            View view = (View) param.args[0];
            if (view == null || taskViewClass == null || !taskViewClass.isInstance(view)) {
                return;
            }
            int indexDiff = (int) param.args[1];
            int scrollDiffPerPage = (int) param.args[2];
            Object pendingAnimation = param.args[3];
            int index = (int) param.args[5];

            prefs.reload();
            float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);

            float animationStartProgress = clamp(0.35f + 0.04f * indexDiff, 0f, 1f);
            float animationEndProgress = 1f;
            TimeInterpolator clampedInterpolator = clampToProgress(new LinearInterpolator(),
                    animationStartProgress, animationEndProgress);

            Object translationProperty = XposedHelpers.callMethod(view, "getPrimaryDismissTranslationProperty");

            int[] mDismissPrimaryTranslations = (int[]) XposedHelpers.getObjectField(param.thisObject, "mDismissPrimaryTranslations");
            if (mDismissPrimaryTranslations != null && index >= 0 && index < mDismissPrimaryTranslations.length) {
                mDismissPrimaryTranslations[index] = scrollDiffPerPage;
            }

            XposedHelpers.setAdditionalInstanceField(view, "apple_dismiss_target", scrollDiffPerPage);
            XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_last_scroll_step", (float)scrollDiffPerPage);
            XposedHelpers.callMethod(pendingAnimation, "setFloat", view, translationProperty,
                    (float) scrollDiffPerPage, clampedInterpolator);

            try {
                long duration = 350L;
                try {
                    Object d = XposedHelpers.callMethod(pendingAnimation, "getDuration");
                    if (d instanceof Long) {
                        duration = (Long) d;
                    } else if (d instanceof Integer) {
                        duration = ((Integer) d).longValue();
                    }
                } catch (Throwable ignored) {
                }
                Object untilObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "apple_dismiss_until");
                long until = untilObj instanceof Long ? (Long) untilObj : 0L;
                long newUntil = SystemClock.uptimeMillis() + duration + 200L;
                if (newUntil > until) {
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_dismiss_until", newUntil);
                }
            } catch (Throwable ignored) {
            }

            boolean mEnableDrawingLiveTile = XposedHelpers.getBooleanField(param.thisObject, "mEnableDrawingLiveTile");
            if (mEnableDrawingLiveTile && (boolean)XposedHelpers.callMethod(view, "isRunningTask")) {
                XposedHelpers.callMethod(pendingAnimation, "addOnFrameCallback", (Runnable) () -> {
                    try {
                        XposedHelpers.callMethod(param.thisObject, "redrawLiveTile");
                    } catch (Exception ignored) {}
                });
            }

            try {
                Object added = XposedHelpers.getAdditionalInstanceField(pendingAnimation, "apple_curve_framecb");
                if (!(added instanceof Boolean) || !((Boolean) added)) {
                    XposedHelpers.setAdditionalInstanceField(pendingAnimation, "apple_curve_framecb", true);
                    XposedHelpers.callMethod(pendingAnimation, "addOnFrameCallback", (Runnable) () -> {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "updateCurveProperties");
                        } catch (Throwable ignored) {
                        }
                    });
                }
            } catch (Throwable ignored) {
            }

            // We NO LONGER set param.setResult(null) here.
            // Letting the original method run ensures Launcher3 updates its internal state correctly,
            // which is critical for snapping-to-page logic when a dismiss is canceled.
        } catch (Throwable t) {
            XposedBridge.log("StackRecentAOSP: Error in handleTranslateTaskWhenDismissed: " + t.getMessage());
        }
    }

    private static TimeInterpolator clampToProgress(TimeInterpolator base, float start, float end) {
        final float s = clamp(start, 0f, 1f);
        final float e = clamp(end, 0f, 1f);
        if (e <= s) {
            return input -> input < s ? 0f : 1f;
        }
        return input -> {
            if (input <= s) return 0f;
            if (input >= e) return 1f;
            float t = (input - s) / (e - s);
            t = clamp(t, 0f, 1f);
            return base.getInterpolation(t);
        };
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private void handleGetTaskViewAt(XC_MethodHook.MethodHookParam param) {
        ViewGroup recentsView = (ViewGroup) param.thisObject;
        float x = (float) param.args[0];
        float y = (float) param.args[1];
        
        int scrollX = recentsView.getScrollX();
        int scrollY = recentsView.getScrollY();

        int childCount = recentsView.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View child = recentsView.getChildAt(i);
            // Check alpha to avoid hitting invisible/dismissing cards
            if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.2f || !taskViewClass.isInstance(child)) continue;
            
            // Calculate visual bounds in container coordinates
            float left = child.getLeft() + child.getTranslationX() - scrollX;
            float top = child.getTop() + child.getTranslationY() - scrollY;
            float width = child.getWidth() * child.getScaleX();
            float height = child.getHeight() * child.getScaleY();
            
            // Account for scale centering
            if (child.getScaleX() != 1.0f) {
                left += (child.getWidth() - width) / 2f;
            }
            if (child.getScaleY() != 1.0f) {
                top += (child.getHeight() - height) / 2f;
            }

            if (x >= left && x <= left + width && y >= top && y <= top + height) {
                param.setResult(child);
                return;
            }
        }
    }

    private void applyTranslation(View child, float translation) {
        try {
            // 1. Set the internal fields used by Launcher's own logic
            if (persistentTranslationXField != null) {
                persistentTranslationXField.set(child, translation);
            } else if (nonGridTranslationXField != null) {
                nonGridTranslationXField.set(child, translation);
            }
            
            // 2. Force the actual translationX property to update.
            // This ensures the entire view container (including background/border) moves.
            child.setTranslationX(translation);
            
            // 3. In some Launcher versions, the icon or other overlays are handled via separate fields.
            // We attempt to find and sync them if they exist.
            try {
                // If there's an IconView that needs separate translation, it might be a field in TaskView.
                // However, setting translationX on the TaskView parent usually moves the icon too.
            } catch (Exception ignored) {}

        } catch (Exception e) {
        }
    }

    private void updatePageSpacingField(Object recentsView) {
        try {
            int newSpacing;
            try {
                 newSpacing = (int) XposedHelpers.callMethod(recentsView, "getPageSpacing");
             } catch (Throwable ignored) {
                 prefs.reload();
                 float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
                 newSpacing = pageSpacingAdjustmentPx(spacingFactor);
             }
            
            // 1. Update the mPageSpacing field in PagedView
            Field f = XposedHelpers.findFieldIfExists(recentsView.getClass(), "mPageSpacing");
            if (f == null) {
                f = XposedHelpers.findFieldIfExists(recentsView.getClass().getSuperclass(), "mPageSpacing");
            }
            
            if (f != null) {
                f.setAccessible(true);
                int oldSpacing = f.getInt(recentsView);
                if (oldSpacing != newSpacing) {
                    f.setInt(recentsView, newSpacing);
                    
                    // 2. Clear mPageScrolls to force re-calculation ONLY if spacing changed.
                    // IMPORTANT: We avoid clearing this if a gesture is active (running task exists)
                    // to prevent the "overlap and snap" flicker during the transition.
                    boolean isGestureActive = false;
                    try {
                        Object runningTask = XposedHelpers.callMethod(recentsView, "getRunningTaskView");
                        isGestureActive = (runningTask != null);
                    } catch (Throwable ignored) {}

                    if (!isGestureActive) {
                         Field scrollsField = XposedHelpers.findFieldIfExists(recentsView.getClass().getSuperclass(), "mPageScrolls");
                         if (scrollsField == null) {
                             scrollsField = XposedHelpers.findFieldIfExists(recentsView.getClass(), "mPageScrolls");
                         }
                         
                         if (scrollsField != null) {
                             scrollsField.setAccessible(true);
                             scrollsField.set(recentsView, null);
                         }
                     }
                }
            }
        } catch (Throwable ignored) {}
    }
}
