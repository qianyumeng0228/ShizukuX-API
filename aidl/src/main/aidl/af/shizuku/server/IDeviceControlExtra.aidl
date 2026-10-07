package af.shizuku.server;

/**
 * Device control operations accessible from uid 2000 (ADB/shell).
 *
 * Covers connectivity (airplane/wifi/bluetooth/mobile/NFC), USB function,
 * power (reboot/shutdown), display (brightness/timeout/rotate), audio
 * (stream volume), system appearance (font scale/animations), and raw
 * settings get/put convenience methods.
 *
 * Shell has WRITE_SETTINGS and WRITE_SECURE_SETTINGS as install-time grants,
 * and is authorised to call svc/media utilities without additional permissions.
 *
 * Note: reboot()/shutdown() require REBOOT permission which shell uid (2000)
 * does NOT have — these will fail on ADB mode and only work reliably on root.
 */
interface IDeviceControlExtra {

    // ── Connectivity ──────────────────────────────────────────────────────────

    boolean setAirplaneModeEnabled(boolean enabled);

    boolean setWifiEnabled(boolean enabled);

    boolean setBluetoothEnabled(boolean enabled);

    boolean setMobileDataEnabled(boolean enabled);

    boolean setNfcEnabled(boolean enabled);

    // ── USB ───────────────────────────────────────────────────────────────────

    /**
     * Set the default USB function.
     * @param function one of "mtp", "adb", "charging", "none", "rndis", "midi", "ncm"
     */
    boolean setUsbFunction(String function);

    // ── Power ─────────────────────────────────────────────────────────────────

    /**
     * Reboot the device.
     * @param reason null for normal reboot, or one of "recovery", "bootloader",
     *               "fastboot", "quiescent". "edl" is deliberately rejected (bricking risk).
     * Note: requires REBOOT permission — fails on ADB (shell) mode, works on root.
     */
    boolean reboot(String reason);

    /**
     * Shut down the device.
     * Note: requires REBOOT permission — fails on ADB (shell) mode, works on root.
     */
    boolean shutdown();

    // ── Display ───────────────────────────────────────────────────────────────

    /** Set screen brightness 0-255 (disables auto-brightness first). */
    boolean setScreenBrightness(int level);

    boolean setAutoBrightnessEnabled(boolean enabled);

    boolean setScreenTimeout(int ms);

    boolean setAutoRotateEnabled(boolean enabled);

    // ── Audio ─────────────────────────────────────────────────────────────────

    /**
     * Set media stream volume.
     * @param stream 0-5 (STREAM_VOICE_CALL=0, STREAM_SYSTEM=1, STREAM_RING=2,
     *               STREAM_MUSIC=3, STREAM_ALARM=4, STREAM_NOTIFICATION=5)
     * @param level volume index (clamped by system)
     */
    boolean setStreamVolume(int stream, int level);

    /**
     * Get current volume for a stream.
     * @return current volume index, or -1 on failure
     */
    int getStreamVolume(int stream);

    // ── System Appearance ─────────────────────────────────────────────────────

    /** Set font scale (clamped 0.70-2.00). */
    boolean setFontScale(float scale);

    /** Enable/disable all window/transition/animator animations. */
    boolean setAnimationsEnabled(boolean enabled);

    // ── Settings convenience ──────────────────────────────────────────────────

    /**
     * Put a settings value.
     * @param namespace "system", "secure", or "global"
     */
    boolean putSetting(String namespace, String key, String value);

    /**
     * Get a settings value.
     * @param namespace "system", "secure", or "global"
     * @return the value, or null if not set
     */
    String getSetting(String namespace, String key);
}
