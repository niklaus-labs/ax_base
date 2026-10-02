/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.vibrator;

import static android.os.Trace.TRACE_TAG_VIBRATOR;

import android.annotation.NonNull;
import android.hardware.vibrator.IVibrator;
import android.os.Handler;
import android.os.IVibratorStateListener;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.RichTapVibrationEffect;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.PwlePoint;
import android.os.vibrator.RampSegment;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;

/**
 * Wraps a base {@link HalVibrator} (normally a {@link VintfHalVibrator.DefaultHalVibrator}) to
 * route effects and primitives through the RichTap vendor extension instead.
 *
 * <p>Composition instead of modification: rather than editing AOSP-owned, frequently-refactored
 * files ({@link VintfHalVibrator}, {@link VintfHalVibratorManager}) to splice RichTap calls into
 * them, this class sits in front of the real {@code HalVibrator} and only requires those files
 * to wrap their constructed instance with {@link #wrapIfNeeded} at the point they build one.
 *
 * <p>The vendor {@code IRichtapVibrator} interface is entirely {@code oneway}: nothing it
 * exposes returns a duration, and its callback interface has no request id to match a
 * completion signal back to a particular call. There is therefore no way to learn from the HAL
 * itself when a pulse actually finishes. This class instead estimates how long each dispatch
 * should run and uses {@link Handler#postDelayed} to both simulate the step-complete callback
 * and pace multi-primitive compositions, which have no timing of their own once split into
 * separate one-primitive-per-call dispatches.
 */
final class AacHalVibrator implements HalVibrator {
    private static final String TAG = "AacHalVibrator";

    private final HalVibrator mBaseVibrator;
    private final RichTapVibratorService mAacService;
    private final Handler mHandler;
    private final Object mLock = new Object();

    // Listeners registered by external callers. Kept separately from mBaseVibrator's own listener
    // bookkeeping (which is private to it) so this class can broadcast RichTap-driven
    // transitions itself; the same listener is also registered directly with mBaseVibrator so it
    // still hears about delegate-driven transitions (setExternalControl, on(millis),
    // on(VendorEffect), on(PwlePoint[]), or any prebaked/primitive call RichTap couldn't handle).
    private final RemoteCallbackList<IVibratorStateListener> mStateListeners =
            new RemoteCallbackList<>();

    @GuardedBy("mLock")
    private boolean mPulseActive;
    @GuardedBy("mLock")
    private float mPulseAmplitude;
    @GuardedBy("mLock")
    private long mPulseDurationMs;
    @GuardedBy("mLock")
    private Object mPulseToken;

    private volatile Callbacks mCallbacks;
    private volatile VibratorInfo mInfo;

    /** Wraps {@code delegate} with RichTap support if this device is configured to use it. */
    static HalVibrator wrapIfNeeded(HalVibrator delegate, Handler handler) {
        return RichTapVibrationEffect.isSupported()
                ? new AacHalVibrator(delegate, handler)
                : delegate;
    }

    private AacHalVibrator(HalVibrator delegate, Handler handler) {
        mBaseVibrator = delegate;
        mHandler = handler;
        mAacService = new RichTapVibratorService();
        mInfo = delegate.getInfo();
    }

    @Override
    public void init(@NonNull Callbacks callbacks) {
        mCallbacks = callbacks;
        mBaseVibrator.init(callbacks);
        rebuildVibratorInfo();
    }

    @Override
    public void onSystemReady() {
        mBaseVibrator.onSystemReady();
        rebuildVibratorInfo();
    }

    @NonNull
    @Override
    public VibratorInfo getInfo() {
        return mInfo;
    }

    @Override
    public boolean isVibrating() {
        synchronized (mLock) {
            return mPulseActive || mBaseVibrator.isVibrating();
        }
    }

    @Override
    public boolean usesRichTap() {
        return true;
    }

    @Override
    public float getCurrentAmplitude() {
        synchronized (mLock) {
            if (mPulseActive) {
                return mPulseAmplitude;
            }
        }
        return mBaseVibrator.getCurrentAmplitude();
    }

    @Override
    public boolean registerVibratorStateListener(@NonNull IVibratorStateListener listener) {
        boolean registered;
        boolean currentlyVibrating;
        synchronized (mLock) {
            registered = mStateListeners.register(listener);
            currentlyVibrating = mPulseActive || mBaseVibrator.isVibrating();
        }
        if (!registered) {
            return false;
        }
        // Let the delegate notify this listener directly for transitions it drives itself.
        mBaseVibrator.registerVibratorStateListener(listener);
        announceVibratingState(listener, currentlyVibrating);
        return true;
    }

    @Override
    public boolean unregisterVibratorStateListener(@NonNull IVibratorStateListener listener) {
        mBaseVibrator.unregisterVibratorStateListener(listener);
        synchronized (mLock) {
            return mStateListeners.unregister(listener);
        }
    }

    @Override
    public boolean setExternalControl(boolean externalControl) {
        return mBaseVibrator.setExternalControl(externalControl);
    }

    @Override
    public boolean setAlwaysOn(int id, PrebakedSegment prebaked) {
        return mBaseVibrator.setAlwaysOn(id, prebaked);
    }

    @Override
    public boolean setAmplitude(float amplitude) {
        synchronized (mLock) {
            if (mAacService.isAvailable()) {
                int strength = (int) (255.0f * amplitude);
                mAacService.richTapVibratorSetAmplitude(strength);
                // ALWAYS update the stored amplitude so Prebaked effects read the correct state
                mPulseAmplitude = amplitude;
                return true;
            }
        }
        return mBaseVibrator.setAmplitude(amplitude);
    }

    @Override
    public long on(long vibrationId, long stepId, long milliseconds) {
        if (!mAacService.isAvailable()) {
            return mBaseVibrator.on(vibrationId, stepId, milliseconds);
        }
        // Route timed vibrations through the RichTap HAL extension. The standard
        // vibrator HAL on RichTap devices ignores the AIDL on() call, so we use
        // richTapVibratorOn() which calls IRichtapVibrator.on(millis, callback).
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "AacHalVibrator.onTimed");
        try {
            Object token = newPulseToken();
            // Timed vibrations must respect the amplitude set by the framework via setAmplitude().
            // If the framework didn't call setAmplitude(), mPulseAmplitude will be 1.0f (default max).
            if (mAacService.isAvailable()) mAacService.richTapVibratorSetAmplitude((int) (255 * mPulseAmplitude));
            mAacService.richTapVibratorOn(milliseconds);
            synchronized (mLock) {
                mPulseToken = token;
                setPulseStateLocked(true, 1f, milliseconds);
            }
            schedulePulseEnd(token, vibrationId, stepId, milliseconds);
            return milliseconds;
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public long on(long vibrationId, long stepId, VibrationEffect.VendorEffect vendorEffect) {
        return mBaseVibrator.on(vibrationId, stepId, vendorEffect);
    }

    @Override
    public long on(long vibrationId, long stepId, PrebakedSegment prebaked) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "AacHalVibrator.onPrebaked");
        try {
            // The RichTap HAL's IVibrator.perform() returns near-zero duration (3-7ms), causing
            // the motor to cut off before the effect is felt. Use richTapVibratorOn(duration) with
            // the correct effect duration instead - the HAL's on(millis) path works correctly.
            // richTapVibratorOnRawPattern() / performHe() is not used here because this device's
            // AacRichTapPerformer rejects our int[] patterns (logs "perform_cmd reset").
            long duration = RichTapVibrationEffect.getInnerEffectDuration(prebaked.getEffectId());
            if (duration <= 0 || !mAacService.isAvailable()) {
                return mBaseVibrator.on(vibrationId, stepId, prebaked);
            }
            Object token = newPulseToken();
            // Prebaked effects (like Back Gesture) do not receive a scale from the framework.
            // They must always fire at full hardware strength (255). Relying on mPulseAmplitude here
            // causes them to randomly use the amplitude of the last timed waveform (like a faded notification).
            if (mAacService.isAvailable()) mAacService.richTapVibratorSetAmplitude(255);
            mAacService.richTapVibratorOn(duration);
            synchronized (mLock) {
                mPulseToken = token;
                setPulseStateLocked(true, 1f, duration);
            }
            schedulePulseEnd(token, vibrationId, stepId, duration);
            return duration;
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public long on(long vibrationId, long stepId, PrimitiveSegment[] primitives) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "AacHalVibrator.onPrimitives");
        try {
            if (primitives == null || primitives.length == 0 || !mAacService.isAvailable()) {
                return mBaseVibrator.on(vibrationId, stepId, primitives);
            }

            Object token = newPulseToken();
            long totalDuration = 0;
            boolean anyDispatched = false;

            // To prevent terrible double-buzzes when the framework sends legacy composed button clicks
            // (like [TICK, CLICK] for the volume button), we ONLY dispatch the final click primitive.
            if (primitives.length == 2 && primitives[0].getDelay() == 0 && primitives[1].getDelay() == 0
                    && (primitives[1].getPrimitiveId() == VibrationEffect.Composition.PRIMITIVE_CLICK
                            || primitives[1].getPrimitiveId() == VibrationEffect.Composition.PRIMITIVE_THUD)) {
                PrimitiveSegment primitive = primitives[1];
                int mappedEffectId = resolvePrimitiveEffectId(primitive.getPrimitiveId());
                int[] pattern = RichTapVibrationEffect.getInnerEffect(mappedEffectId);
                float scale = primitive.getScale();
                int strength = (int) (255 * scale);
                if (strength > 20) {
                    long effectDuration = RichTapVibrationEffect.getInnerEffectDuration(mappedEffectId);
                    dispatchPulse(token, pattern, strength, 0, effectDuration);
                    totalDuration = effectDuration;
                    anyDispatched = true;
                }
            } else if (primitives.length == 1) {
                PrimitiveSegment primitive = primitives[0];
                int mappedEffectId = resolvePrimitiveEffectId(primitive.getPrimitiveId());
                int[] pattern = RichTapVibrationEffect.getInnerEffect(mappedEffectId);
                float scale = primitive.getScale();
                int strength = (int) (255 * scale);
                int primId = primitive.getPrimitiveId();
                if (primId == VibrationEffect.Composition.PRIMITIVE_LOW_TICK && strength > 10) {
                    strength = Math.max(45, strength);
                }
                if (strength > 20) {
                    long effectDuration = estimatePrimitiveDurationMs(primId);
                    long reportedDuration = effectDuration;
                    // For rapid texture ticks (slider drags), 25ms is too long and causes
                    // the framework to queue them up, resulting in vibrations continuing
                    // after the finger is lifted. Force the reported duration to be super short (12ms)
                    // without altering the HAL's actual playback time to avoid sharp active braking.
                    if (mappedEffectId == VibrationEffect.EFFECT_TICK) {
                        reportedDuration = 12;
                    }
                    dispatchPulse(token, pattern, strength, 0, effectDuration);
                    totalDuration = reportedDuration;
                    anyDispatched = true;
                }
            } else {
                // Multi-primitive composition:
                // Handle continuous rumble sequences (e.g. repeated LOW_TICKs with 0 delay during gesture hold)
                // and multi-stage compositions (e.g. gesture assistant invocation: rumble -> quick rise -> tick).
                int i = 0;
                long currentOffset = 0;
                while (i < primitives.length) {
                    PrimitiveSegment p = primitives[i];
                    int pId = p.getPrimitiveId();

                    // Detect a run of consecutive LOW_TICK primitives (continuous rumble)
                    if (pId == VibrationEffect.Composition.PRIMITIVE_LOW_TICK && p.getDelay() == 0) {
                        int runEnd = i;
                        float maxScale = p.getScale();
                        while (runEnd < primitives.length
                                && primitives[runEnd].getPrimitiveId() == VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                                && (runEnd == i || primitives[runEnd].getDelay() == 0)) {
                            maxScale = Math.max(maxScale, primitives[runEnd].getScale());
                            runEnd++;
                        }
                        int count = runEnd - i;
                        if (count >= 2) {
                            long rumbleDuration = count * 10L;
                            int strength = Math.max(45, (int) (255 * maxScale));
                            dispatchPulse(token, null, strength, currentOffset, rumbleDuration);
                            currentOffset += rumbleDuration;
                            anyDispatched = true;
                            i = runEnd;
                            continue;
                        }
                    }

                    // Individual primitive segment
                    long scheduledTime = currentOffset + p.getDelay();
                    float scale = p.getScale();
                    int strength = (int) (255 * scale);
                    if (pId == VibrationEffect.Composition.PRIMITIVE_LOW_TICK && strength > 10) {
                        strength = Math.max(45, strength);
                    }
                    if (strength > 20) {
                        long playDuration = estimatePrimitiveDurationMs(pId);
                        dispatchPulse(token, null, strength, scheduledTime, playDuration);
                        currentOffset = scheduledTime + playDuration;
                        anyDispatched = true;
                    } else {
                        currentOffset = scheduledTime;
                    }
                    i++;
                }
                totalDuration = Math.max(1, currentOffset);
            }

            if (!anyDispatched) {
                return mBaseVibrator.on(vibrationId, stepId, primitives);
            }

            synchronized (mLock) {
                mPulseToken = token;
                setPulseStateLocked(true, 1f, totalDuration);
            }
            schedulePulseEnd(token, vibrationId, stepId, totalDuration);
            return totalDuration;
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public long on(long vibrationId, long stepId, RampSegment[] primitives) {
        return mBaseVibrator.on(vibrationId, stepId, primitives);
    }

    @Override
    public long on(long vibrationId, long stepId, PwlePoint[] pwlePoints) {
        return mBaseVibrator.on(vibrationId, stepId, pwlePoints);
    }

    @Override
    public boolean off() {
        Object staleToken;
        synchronized (mLock) {
            staleToken = mPulseToken;
            mPulseToken = null;
            // Only send richTapVibratorOff if we were actively emulating a RichTap timed vibration.
            // Avoid active braking thump for short primitives by not sending off() if duration <= 50ms
            if (mAacService.isAvailable() && mPulseActive && mPulseDurationMs > 50) {
                mAacService.richTapVibratorOff();
            }
            setPulseStateLocked(false, 1.0f, 0); // Reset to default max amplitude
        }
        if (staleToken != null) {
            // Cancel any not-yet-fired scheduled primitives/completion callback from this token.
            mHandler.removeCallbacksAndMessages(staleToken);
        }
        // Only call the real HAL off() if it was actively vibrating or RichTap is unavailable.
        // On Nothing Phone (2), calling IVibrator.off() on the native HAL triggers active braking
        // in hardware, creating an audible mechanical thump (double vibration) after short clicks.
        if (mBaseVibrator.isVibrating() || !mAacService.isAvailable()) {
            return mBaseVibrator.off();
        }
        return true;
    }

    @Override
    public void dump(IndentingPrintWriter pw) {
        boolean richTapVibrating;
        float richTapAmplitude;
        synchronized (mLock) {
            richTapVibrating = mPulseActive;
            richTapAmplitude = mPulseAmplitude;
        }
        pw.println("AacHalVibrator:");
        pw.increaseIndent();
        pw.println("richTapAvailable = " + mAacService.isAvailable());
        pw.println("richTapVibrating = " + richTapVibrating);
        pw.println("richTapAmplitude = " + richTapAmplitude);
        mBaseVibrator.dump(pw);
        pw.decreaseIndent();
    }

    @Override
    public String toString() {
        return "AacHalVibrator{mInfo=" + mInfo + ", mBaseVibrator=" + mBaseVibrator + '}';
    }

    /** Maps a {@link VibrationEffect.Composition} primitive id to a RichTap inner effect id. */
    private static int resolvePrimitiveEffectId(int primitiveId) {
        if (primitiveId == VibrationEffect.Composition.PRIMITIVE_CLICK) {
            return VibrationEffect.EFFECT_CLICK;
        } else if (primitiveId == VibrationEffect.Composition.PRIMITIVE_THUD) {
            return VibrationEffect.EFFECT_THUD;
        } else if (primitiveId == VibrationEffect.Composition.PRIMITIVE_SPIN) {
            return VibrationEffect.EFFECT_TEXTURE_TICK;
        } else if (primitiveId == VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                || primitiveId == VibrationEffect.Composition.PRIMITIVE_QUICK_FALL) {
            return VibrationEffect.EFFECT_HEAVY_CLICK;
        }
        return VibrationEffect.EFFECT_TICK;
    }

    private static long estimatePrimitiveDurationMs(int primitiveId) {
        switch (primitiveId) {
            case VibrationEffect.Composition.PRIMITIVE_CLICK:
                return RichTapVibrationEffect.getInnerEffectDuration(VibrationEffect.EFFECT_CLICK);
            case VibrationEffect.Composition.PRIMITIVE_THUD:
                return RichTapVibrationEffect.getInnerEffectDuration(VibrationEffect.EFFECT_THUD);
            case VibrationEffect.Composition.PRIMITIVE_SPIN:
                return RichTapVibrationEffect.getInnerEffectDuration(VibrationEffect.EFFECT_TEXTURE_TICK);
            case VibrationEffect.Composition.PRIMITIVE_QUICK_RISE:
            case VibrationEffect.Composition.PRIMITIVE_QUICK_FALL:
                return 50L;
            case VibrationEffect.Composition.PRIMITIVE_SLOW_RISE:
                return 150L;
            case VibrationEffect.Composition.PRIMITIVE_LOW_TICK:
                return 15L;
            case VibrationEffect.Composition.PRIMITIVE_TICK:
            default:
                return RichTapVibrationEffect.getInnerEffectDuration(VibrationEffect.EFFECT_TICK);
        }
    }

    /**
     * Returns a new, unique token to correlate a dispatch (see {@link #dispatchPulse}/
     * {@link #schedulePulseEnd}) with the {@link #mPulseToken} that {@link #off} checks to
     * decide whether it needs to cancel any not-yet-fired {@link Handler} callbacks. Does not
     * itself assign {@link #mPulseToken} - callers must do that under {@link #mLock} once
     * they're ready to commit to this dispatch.
     */
    private static Object newPulseToken() {
        return new Object();
    }

    private void dispatchPulse(Object token, int[] pattern, int strength, long delayMillis, long effectDuration) {
        // AacRichTapPerformer rejects inline int[] patterns and logs "perform_cmd reset".
        // Using richTapVibratorOnRawPattern causes the HAL to enter a bad state where subsequent
        // valid vibrations (like keyboard clicks) fail until the screen is turned off and on.
        // Therefore, we emulate primitives by using the simple timed richTapVibratorOn(duration)
        if (delayMillis <= 0) {
            if (mAacService.isAvailable()) mAacService.richTapVibratorSetAmplitude(strength);
            mAacService.richTapVibratorOn(effectDuration);
            return;
        }
        mHandler.postDelayed(() -> {
            if (mAacService.isAvailable()) mAacService.richTapVibratorSetAmplitude(strength);
            mAacService.richTapVibratorOn(effectDuration);
        }, token, delayMillis);
    }

    private void schedulePulseEnd(Object token, long vibrationId, long stepId, long duration) {
        mHandler.postDelayed(() -> {
            synchronized (mLock) {
                if (mPulseToken == token) {
                    mPulseToken = null;
                    setPulseStateLocked(false, 1.0f, 0); // Reset to default max amplitude
                }
            }
            Callbacks callbacks = mCallbacks;
            if (callbacks != null) {
                callbacks.onVibrationStepComplete(mInfo.getId(), vibrationId, stepId);
            }
        }, token, duration);
    }

    @GuardedBy("mLock")
    private void setPulseStateLocked(boolean vibrating, float amplitude, long duration) {
        mPulseDurationMs = duration;
        boolean previousOverall = mPulseActive || mBaseVibrator.isVibrating();
        mPulseActive = vibrating;
        mPulseAmplitude = amplitude;
        boolean currentOverall = mPulseActive || mBaseVibrator.isVibrating();
        if (previousOverall != currentOverall) {
            mStateListeners.broadcast(listener -> announceVibratingState(listener, currentOverall));
        }
    }

    private void announceVibratingState(IVibratorStateListener listener, boolean isVibrating) {
        try {
            listener.onVibrating(isVibrating);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Vibrator state listener failed to call", e);
        }
    }

    /**
     * Rebuilds {@link #mInfo} as the union of whatever the real HAL ({@link #mBaseVibrator}) already
     * reports and what RichTap adds, so {@link VibratorInfo#isEffectSupported} /
     * {@link VibratorInfo#isPrimitiveSupported} - which now drive fallback substitution and
     * segment validation upstream in {@link DeviceAdapter} - reflect reality.
     */
    private void rebuildVibratorInfo() {
        VibratorInfo base = mBaseVibrator.getInfo();
        VibratorInfo.Builder builder = new VibratorInfo.Builder(base.getId());

        builder.setCapabilities(base.getCapabilities()
                | IVibrator.CAP_COMPOSE_EFFECTS
                | IVibrator.CAP_AMPLITUDE_CONTROL);

        IntArray supportedEffects = new IntArray();
        supportedEffects.add(VibrationEffect.EFFECT_CLICK);
        supportedEffects.add(VibrationEffect.EFFECT_DOUBLE_CLICK);
        supportedEffects.add(VibrationEffect.EFFECT_TICK);
        supportedEffects.add(VibrationEffect.EFFECT_THUD);
        supportedEffects.add(VibrationEffect.EFFECT_POP);
        supportedEffects.add(VibrationEffect.EFFECT_HEAVY_CLICK);
        supportedEffects.add(VibrationEffect.EFFECT_TEXTURE_TICK);
        SparseBooleanArray baseEffects = base.getSupportedEffects();
        if (baseEffects != null) {
            for (int i = 0; i < baseEffects.size(); i++) {
                if (baseEffects.valueAt(i)) {
                    supportedEffects.add(baseEffects.keyAt(i));
                }
            }
        }
        builder.setSupportedEffects(supportedEffects.toArray());

        // Keep whatever primitives the real HAL already declares...
        for (int primitiveId = VibrationEffect.Composition.PRIMITIVE_NOOP;
                primitiveId <= VibrationEffect.Composition.PRIMITIVE_LOW_TICK; primitiveId++) {
            if (base.isPrimitiveSupported(primitiveId)) {
                builder.setSupportedPrimitive(primitiveId, base.getPrimitiveDuration(primitiveId));
            }
        }
        // ...then layer RichTap's approximated primitives on top (overriding duration for any
        // the real HAL also claimed to support, since RichTap is what will actually play them).
        builder.setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10);
        builder.setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 10);
        builder.setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, 10);
        builder.setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 50);
        builder.setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 150);
        builder.setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 50);
        builder.setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 10);
        builder.setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 10);

        builder.setPrimitiveDelayMax(base.getPrimitiveDelayMax());
        builder.setCompositionSizeMax(base.getCompositionSizeMax());
        builder.setFrequencyProfileLegacy(base.getFrequencyProfileLegacy());
        builder.setFrequencyProfile(base.getFrequencyProfile());
        builder.setQFactor(base.getQFactor());
        builder.setMaxEnvelopeEffectSize(base.getMaxEnvelopeEffectSize());
        builder.setMinEnvelopeEffectControlPointDurationMillis(
                base.getMinEnvelopeEffectControlPointDurationMillis());
        builder.setMaxEnvelopeEffectControlPointDurationMillis(
                base.getMaxEnvelopeEffectControlPointDurationMillis());

        mInfo = builder.build();
    }
}
