/*
 * Copyright (C) 2024-2025 Paranoid Android
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
package android.os;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.util.Slog;

import com.android.internal.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * RichTap vibration effect implementation.
 * @hide
 */
public final class RichTapVibrationEffect {
    private static final String TAG = RichTapVibrationEffect.class.getSimpleName();

    // Tuned .he resource file support (mirrors the AOSPA/CLO richtap-haptics implementation for
    // this chipset). Ported because this device's own vendor blobs (kanged from the same source
    // as the rest of this RichTap port) already ship real, per-strength-tier tuned .he resource
    // files under PREBAKED_HE_RESOURCE_ROOT - previously unused. These are genuine OEM-tuned
    // patterns for THIS chip, not the kanged pattern arrays baked into getInnerEffect() below
    // (which were ported from a different device's firmware dump).
    private static final int PREBAKED_HE_MAX_EVENTS = 16;
    private static final int PREBAKED_HE_EVENT_SIZE = 17;
    private static final int PREBAKED_HE_CONTINUOUS_EVENT = 0x1000;
    private static final int PREBAKED_HE_TRANSIENT_EVENT = 0x1001;
    private static final int PREBAKED_HE_DEFAULT_RELATIVE_TIME_STEP_MS = 400;
    private static final String PREBAKED_HE_RESOURCE_ROOT = "/vendor/etc/richtapresources";

    private static final Object sPrebakedHeCacheLock = new Object();
    private static final Map<String, int[]> sPrebakedHeCache = new HashMap<>();

    // Prevent instantiation
    private RichTapVibrationEffect() {}

    /**
     * Checks if RichTap vibration is supported on this device.
     */
    public static boolean isSupported() {
        return Resources.getSystem().getBoolean(R.bool.config_usesRichtapVibration);
    }

    /**
     * Gets the inner effect pattern for a given vibration effect ID.
     * @param id The vibration effect ID
     * @return Array containing the effect pattern, or null if invalid
     */
    @Nullable
    public static int[] getInnerEffect(int id) {
        switch (id) {
            case VibrationEffect.EFFECT_CLICK:
                return new int[]{1, 4097, 0, 100, 65, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            case VibrationEffect.EFFECT_DOUBLE_CLICK:
                return new int[]{1, 4097, 0, 100, 80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4097, 70, 100, 80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            case VibrationEffect.EFFECT_TICK:
                return new int[]{1, 4097, 0, 100, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            case VibrationEffect.EFFECT_THUD:
                return new int[]{1, 4097, 0, 100, 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            case VibrationEffect.EFFECT_POP:
                return new int[]{1, 4097, 0, 100, 65, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            case VibrationEffect.EFFECT_HEAVY_CLICK:
                return new int[]{1, 4097, 0, 100, 57, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            case VibrationEffect.EFFECT_TEXTURE_TICK:
                return new int[]{1, 4097, 0, 50, 33, 29, 0, 0, 0, 12, 59, 0, 22, 75, -21, 29, 0, 0, 4097, 30, 100, 30, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            default:
                Slog.w(TAG, "Invalid effect id: " + id);
                return null;
        }
    }

    /**
     * Gets the inner effect strength value for a given strength level.
     * @param strength The desired effect strength
     * @return Strength value, or 0 if invalid
     */
    public static int getInnerEffectStrength(int strength) {
        switch (strength) {
            case VibrationEffect.EFFECT_STRENGTH_LIGHT:
                return 150;
            case VibrationEffect.EFFECT_STRENGTH_MEDIUM:
                return 200;
            case VibrationEffect.EFFECT_STRENGTH_STRONG:
                return 250;
            default:
                Slog.e(TAG, "Invalid effect strength: " + strength);
                return 0;
        }
    }

    /**
     * Loads a tuned HE resource for a standard Android prebaked effect, or the TICK primitive
     * (via {@link VibrationEffect#EFFECT_TICK} - see VibratorController's primitive dispatch,
     * which reuses this for TICK specifically since a tick.he tier exists).
     *
     * @param id The Android vibration effect ID
     * @param strength The desired effect strength (VibrationEffect.EFFECT_STRENGTH_*)
     * @return Parsed HE pattern, or {@code null} when unsupported/unavailable - callers should
     *     fall back to {@link #getInnerEffect} in that case.
     */
    @Nullable
    public static int[] getPrebakedHeEffect(int id, int strength) {
        if (!new File(PREBAKED_HE_RESOURCE_ROOT).isDirectory()) {
            return null;
        }

        String fileName = getPrebakedHeFileName(id);
        String strengthDir = getPrebakedHeStrengthDir(strength);
        if (fileName == null || strengthDir == null) {
            return null;
        }

        String cacheKey = strengthDir + '/' + fileName;
        synchronized (sPrebakedHeCacheLock) {
            int[] cached = sPrebakedHeCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        int[] pattern = loadPrebakedHeEffect(PREBAKED_HE_RESOURCE_ROOT, strengthDir, fileName);
        if (pattern != null) {
            synchronized (sPrebakedHeCacheLock) {
                sPrebakedHeCache.put(cacheKey, pattern);
            }
        }
        return pattern;
    }

    @Nullable
    private static String getPrebakedHeFileName(int id) {
        switch (id) {
            case VibrationEffect.EFFECT_CLICK:
                return "click.he";
            case VibrationEffect.EFFECT_DOUBLE_CLICK:
                return "double_click.he";
            case VibrationEffect.EFFECT_TICK:
                return "tick.he";
            case VibrationEffect.EFFECT_THUD:
                return "thud.he";
            case VibrationEffect.EFFECT_POP:
                return "pop.he";
            case VibrationEffect.EFFECT_HEAVY_CLICK:
                return "heavy_click.he";
            case VibrationEffect.EFFECT_TEXTURE_TICK:
                return "texture_tick.he";
            default:
                return null;
        }
    }

    @Nullable
    private static String getPrebakedHeStrengthDir(int strength) {
        switch (strength) {
            case VibrationEffect.EFFECT_STRENGTH_LIGHT:
                return "weak";
            case VibrationEffect.EFFECT_STRENGTH_MEDIUM:
                return "default";
            case VibrationEffect.EFFECT_STRENGTH_STRONG:
                return "strong";
            default:
                Slog.e(TAG, "Invalid effect strength: " + strength);
                return null;
        }
    }

    @Nullable
    private static int[] loadPrebakedHeEffect(String root, String strengthDir, String fileName) {
        File rootDir = new File(root);
        File heFile = new File(new File(rootDir, strengthDir), fileName);
        if (!heFile.exists()) {
            heFile = new File(rootDir, fileName);
        }
        if (!heFile.exists()) {
            Slog.w(TAG, "Missing RichTap HE resource: " + heFile);
            return null;
        }

        try {
            return parsePrebakedHeEffect(readHeFile(heFile));
        } catch (Exception e) {
            Slog.e(TAG, "Failed to load RichTap HE resource: " + heFile, e);
            return null;
        }
    }

    private static String readHeFile(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        return content.toString();
    }

    @Nullable
    private static int[] parsePrebakedHeEffect(String content) throws Exception {
        JSONObject root = new JSONObject(content);
        if (root.getJSONObject("Metadata").getInt("Version") != 1) {
            return null;
        }

        JSONArray pattern = root.getJSONArray("Pattern");
        int eventCount = Math.min(pattern.length(), PREBAKED_HE_MAX_EVENTS);
        int[] he = new int[eventCount * PREBAKED_HE_EVENT_SIZE + 1];
        he[0] = 1;

        for (int i = 0; i < eventCount; i++) {
            JSONObject event = pattern.getJSONObject(i).getJSONObject("Event");
            int type = getPrebakedHeEventType(event.getString("Type"));
            if (type == 0) {
                Slog.e(TAG, "Unsupported RichTap HE event type");
                return null;
            }

            int relativeTime = event.has("RelativeTime")
                    ? event.getInt("RelativeTime")
                    : i * PREBAKED_HE_DEFAULT_RELATIVE_TIME_STEP_MS;
            if (!isInRange(relativeTime, 0, 50000)) {
                Slog.e(TAG, "RelativeTime must be between 0 and 50000");
                return null;
            }

            JSONObject params = event.getJSONObject("Parameters");
            int intensity = params.getInt("Intensity");
            int frequency = params.getInt("Frequency");
            if (!isInRange(intensity, 0, 100) || !isInRange(frequency, 0, 100)) {
                Slog.e(TAG, "Intensity or Frequency must be between 0 and 100");
                return null;
            }

            int base = i * PREBAKED_HE_EVENT_SIZE;
            he[base + 1] = type;
            he[base + 2] = relativeTime;
            he[base + 3] = intensity;
            he[base + 4] = frequency;
        }
        return he;
    }

    private static int getPrebakedHeEventType(String type) {
        if ("continuous".equals(type)) {
            return PREBAKED_HE_CONTINUOUS_EVENT;
        }
        if ("transient".equals(type)) {
            return PREBAKED_HE_TRANSIENT_EVENT;
        }
        return 0;
    }

    private static boolean isInRange(int value, int min, int max) {
        return value >= min && value <= max;
    }
}
