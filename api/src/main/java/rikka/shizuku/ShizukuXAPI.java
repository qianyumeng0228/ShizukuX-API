package rikka.shizuku;

import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import af.shizuku.server.IActivityManagerPlus;
import af.shizuku.server.IAICorePlus;
import af.shizuku.server.IContinuityBridge;
import af.shizuku.server.INetworkGovernorPlus;
import af.shizuku.server.IOverlayManagerPlus;
import moe.shizuku.server.IShizukuService;
import af.shizuku.server.IStorageProxy;
import af.shizuku.server.IVirtualMachineManager;
import af.shizuku.server.IWindowManagerPlus;

/**
 * ShizukuXAPI —— 当所连接的 Shizuku 服务器为已启用增强 API 的 ShizukuX 构建时，
 * 可用的扩展功能。
 *
 * <p>所有涉及远程 binder 的方法都可从任意线程安全调用。
 * 当 Shizuku 未连接、增强 API 不受支持或发生瞬时 IPC 错误时，
 * 它们返回 {@code null}/{@code false}/空列表。
 */
public class ShizukuXAPI {
    private static final String TAG = "ShizukuXAPI";

    /** 阻塞式 Shell 命令读取的超时时间（秒）。 */
    private static final long SHELL_TIMEOUT_SECONDS = 30;

    // -------------------------------------------------------------------------
    // 核心连接辅助方法
    // -------------------------------------------------------------------------

    /**
     * 若所连接的服务器是已启用增强 API 的 ShizukuX 构建，则返回 {@code true}。
     * 可从任意线程安全调用。
     */
    public static boolean isEnhancedApiSupported() {
        return Shizuku.isCustomApiEnabled();
    }

    /**
     * 返回一个实时的 {@link IShizukuService} 代理；若 Shizuku 未连接
     * 或 binder 已失效，则返回 {@code null}。
     */
    @Nullable
    private static IShizukuService getShizukuService() {
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null || !binder.isBinderAlive()) return null;
            return IShizukuService.Stub.asInterface(binder);
        } catch (Exception e) {
            Log.w(TAG, "getShizukuService: failed to obtain binder", e);
            return null;
        }
    }

    /**
     * 仅当确认增强 API 已激活时才返回实时的 {@link IShizukuService} 代理，
     * 否则返回 {@code null}。
     */
    @Nullable
    private static IShizukuService requirePlusService() {
        if (!isEnhancedApiSupported()) return null;
        return getShizukuService();
    }

    // -------------------------------------------------------------------------
    // Shell
    // -------------------------------------------------------------------------

    /** 同步 Shell 命令执行的结果。 */
    public static class CommandResult {
        public final int exitCode;
        @NonNull public final String output;
        @NonNull public final String error;

        public CommandResult(int exitCode, @NonNull String output, @NonNull String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() { return exitCode == 0; }
    }

    /**
     * 通过 Shizuku 执行 Shell 命令字符串（经由 {@code sh -c}）并同步返回结果。
     * 在返回错误结果前，最多阻塞调用线程 {@link #SHELL_TIMEOUT_SECONDS} 秒。
     *
     * <p>请勿在主线程调用。
     */
    @NonNull
    public static CommandResult executeShell(@NonNull String command) {
        return executeShell(new String[]{"sh", "-c", command});
    }

    /**
     * 通过 Shizuku 执行参数数组并同步返回结果。
     * 最多阻塞 {@link #SHELL_TIMEOUT_SECONDS} 秒。
     *
     * <p>请勿在主线程调用。
     */
    @NonNull
    public static CommandResult executeShell(@NonNull String[] cmd) {
        try {
            // newProcess 是 Shizuku Shell 执行的正确公共 API 入口。
            ShizukuRemoteProcess process = Shizuku.newProcess(cmd, null, null);
            if (process == null) {
                return new CommandResult(-1, "", "Process creation returned null");
            }

            final StringBuilder output = new StringBuilder();
            final StringBuilder error  = new StringBuilder();

            // 在并行线程中排空 stderr：若我们阻塞读取 stdout 时 stdout 填满 OS 管道
            // 缓冲区，则必须同时排空 stderr，否则会死锁。
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        error.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            }, "shizuku-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            stderrThread.join(TimeUnit.SECONDS.toMillis(SHELL_TIMEOUT_SECONDS));
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, output.toString().trim(), error.toString().trim());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", "Interrupted");
        } catch (Exception e) {
            return new CommandResult(-1, "", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // 系统设置
    // -------------------------------------------------------------------------

    /** Android 系统设置（system / secure / global）的封装。 */
    public static class Settings {

        public static boolean putSystem(@NonNull String key, @NonNull String value) {
            return executeShell(new String[]{"settings", "put", "system", key, value}).isSuccess();
        }

        public static boolean putSecure(@NonNull String key, @NonNull String value) {
            return executeShell(new String[]{"settings", "put", "secure", key, value}).isSuccess();
        }

        public static boolean putGlobal(@NonNull String key, @NonNull String value) {
            return executeShell(new String[]{"settings", "put", "global", key, value}).isSuccess();
        }

        @NonNull
        public static String getSystem(@NonNull String key) {
            return executeShell(new String[]{"settings", "get", "system", key}).output;
        }

        @NonNull
        public static String getSecure(@NonNull String key) {
            return executeShell(new String[]{"settings", "get", "secure", key}).output;
        }

        @NonNull
        public static String getGlobal(@NonNull String key) {
            return executeShell(new String[]{"settings", "get", "global", key}).output;
        }
    }

    // -------------------------------------------------------------------------
    // 包管理器
    // -------------------------------------------------------------------------

    /** 通过 Shizuku 进行的包管理器操作封装。 */
    public static class PackageManager {

        public static boolean installPackage(@NonNull String apkFilePath) {
            return executeShell(new String[]{"pm", "install", "-r", apkFilePath}).isSuccess();
        }

        public static boolean uninstallPackage(@NonNull String packageName) {
            return executeShell(new String[]{"pm", "uninstall", packageName}).isSuccess();
        }

        public static boolean clearPackageData(@NonNull String packageName) {
            return executeShell(new String[]{"pm", "clear", packageName}).isSuccess();
        }
    }

    // -------------------------------------------------------------------------
    // OverlayManager —— 需要增强 API
    // -------------------------------------------------------------------------

    /** 通过 Plus AIDL 进行运行时资源叠加层（RRO）管理。 */
    public static class OverlayManager {

        @Nullable
        private static IOverlayManagerPlus getService() {
            IShizukuService svc = requirePlusService();
            if (svc == null) return null;
            try { return svc.getOverlayManagerPlus(); }
            catch (RemoteException e) { Log.w(TAG, "getOverlayManagerPlus", e); return null; }
        }

        public static boolean enableOverlay(@NonNull String packageName) {
            IOverlayManagerPlus s = getService();
            if (s != null) {
                try { return s.setOverlayEnabled(packageName, true); }
                catch (RemoteException e) { Log.w(TAG, "enableOverlay " + packageName, e); }
            }
            return executeShell(new String[]{"cmd", "overlay", "enable", "--user", "current", packageName}).isSuccess();
        }

        public static boolean disableOverlay(@NonNull String packageName) {
            IOverlayManagerPlus s = getService();
            if (s != null) {
                try { return s.setOverlayEnabled(packageName, false); }
                catch (RemoteException e) { Log.w(TAG, "disableOverlay " + packageName, e); }
            }
            return executeShell(new String[]{"cmd", "overlay", "disable", "--user", "current", packageName}).isSuccess();
        }

        public static boolean setHighestPriority(@NonNull String packageName) {
            IOverlayManagerPlus s = getService();
            if (s != null) {
                try { return s.setHighestPriority(packageName); }
                catch (RemoteException e) { Log.w(TAG, "setHighestPriority " + packageName, e); }
            }
            return false;
        }

        @NonNull
        public static List<String> getAllOverlays() {
            IOverlayManagerPlus s = getService();
            if (s != null) {
                try { return s.getAllOverlays(); }
                catch (RemoteException e) { Log.w(TAG, "getAllOverlays", e); }
            }
            return Collections.emptyList();
        }

        public static boolean injectResourceOverlay(
                @NonNull String targetPackage, @NonNull String resourceName,
                int type, @NonNull String value) {
            IOverlayManagerPlus s = getService();
            if (s != null) {
                try { return s.injectResourceOverlay(targetPackage, resourceName, type, value); }
                catch (RemoteException e) { Log.w(TAG, "injectResourceOverlay " + targetPackage, e); }
            }
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // ActivityManager —— 需要增强 API
    // -------------------------------------------------------------------------

    /** 高级 Activity Manager 操作。 */
    public static class ActivityManager {

        @Nullable
        private static IActivityManagerPlus getService() {
            IShizukuService svc = requirePlusService();
            if (svc == null) return null;
            try { return svc.getActivityManagerPlus(); }
            catch (RemoteException e) { Log.w(TAG, "getActivityManagerPlus", e); return null; }
        }

        public static boolean deepForceStop(@NonNull String packageName) {
            IActivityManagerPlus s = getService();
            if (s != null) {
                try { return s.deepForceStop(packageName); }
                catch (RemoteException e) { Log.w(TAG, "deepForceStop " + packageName, e); }
            }
            return executeShell(new String[]{"am", "force-stop", packageName}).isSuccess();
        }

        public static boolean killAllBackgroundProcesses() {
            IActivityManagerPlus s = getService();
            if (s == null) return false;
            try { return s.killAllBackgroundProcesses(); }
            catch (RemoteException e) { Log.w(TAG, "killAllBackgroundProcesses", e); return false; }
        }

        public static boolean setAppStandbyBucket(@NonNull String packageName, int bucket) {
            IActivityManagerPlus s = getService();
            if (s == null) return false;
            try { return s.setAppStandbyBucket(packageName, bucket); }
            catch (RemoteException e) { Log.w(TAG, "setAppStandbyBucket " + packageName, e); return false; }
        }
    }

    // -------------------------------------------------------------------------
    // WindowManager —— 需要增强 API
    // -------------------------------------------------------------------------

    /** 窗口管理器与桌面模式功能。 */
    public static class WindowManager {

        @Nullable
        private static IWindowManagerPlus getService() {
            IShizukuService svc = requirePlusService();
            if (svc == null) return null;
            try { return svc.getWindowManagerPlus(); }
            catch (RemoteException e) { Log.w(TAG, "getWindowManagerPlus", e); return null; }
        }

        public static void forceResizable(@NonNull String packageName, boolean enabled) {
            IWindowManagerPlus s = getService();
            if (s == null) return;
            try { s.forceResizable(packageName, enabled); }
            catch (RemoteException e) { Log.w(TAG, "forceResizable " + packageName, e); }
        }

        public static void setAlwaysOnTop(int taskId, boolean enabled) {
            IWindowManagerPlus s = getService();
            if (s == null) return;
            try { s.setAlwaysOnTop(taskId, enabled); }
            catch (RemoteException e) { Log.w(TAG, "setAlwaysOnTop task=" + taskId, e); }
        }
    }

    // -------------------------------------------------------------------------
    // NetworkGovernor —— 需要增强 API
    // -------------------------------------------------------------------------

    /** 特权网络与 DNS 管理。 */
    public static class NetworkGovernor {

        @Nullable
        private static INetworkGovernorPlus getService() {
            IShizukuService svc = requirePlusService();
            if (svc == null) return null;
            try { return svc.getNetworkGovernorPlus(); }
            catch (RemoteException e) { Log.w(TAG, "getNetworkGovernorPlus", e); return null; }
        }

        public static boolean setPrivateDns(@Nullable String mode, @Nullable String hostname) {
            INetworkGovernorPlus s = getService();
            if (s == null) return false;
            try { return s.setPrivateDns(mode, hostname); }
            catch (RemoteException e) { Log.w(TAG, "setPrivateDns", e); return false; }
        }

        public static boolean restrictAppNetwork(@NonNull String packageName, boolean restricted) {
            INetworkGovernorPlus s = getService();
            if (s == null) return false;
            try { return s.restrictAppNetwork(packageName, restricted); }
            catch (RemoteException e) { Log.w(TAG, "restrictAppNetwork " + packageName, e); return false; }
        }

        public static boolean isAppNetworkRestricted(@NonNull String packageName) {
            INetworkGovernorPlus s = getService();
            if (s == null) return false;
            try { return s.isAppNetworkRestricted(packageName); }
            catch (RemoteException e) { Log.w(TAG, "isAppNetworkRestricted " + packageName, e); return false; }
        }
    }

    // -------------------------------------------------------------------------
    // AICore —— 需要增强 API
    // -------------------------------------------------------------------------

    /** AI 与屏幕感知功能（像素检测、输入模拟等）。 */
    public static class AICore {

        @Nullable
        private static IAICorePlus getService() {
            IShizukuService svc = requirePlusService();
            if (svc == null) return null;
            try { return svc.getAICorePlus(); }
            catch (RemoteException e) { Log.w(TAG, "getAICorePlus", e); return null; }
        }

        public static int getPixelColor(int x, int y) {
            IAICorePlus s = getService();
            if (s == null) return 0;
            try { return s.getPixelColor(x, y); }
            catch (RemoteException e) { Log.w(TAG, "getPixelColor", e); return 0; }
        }

        @Nullable
        public static Bundle scheduleNPULoad(@NonNull Bundle taskData) {
            IAICorePlus s = getService();
            if (s == null) return null;
            try { return s.scheduleNPULoad(taskData); }
            catch (RemoteException e) { Log.w(TAG, "scheduleNPULoad", e); return null; }
        }

        @Nullable
        public static Bitmap captureLayer(int layerId) {
            IAICorePlus s = getService();
            if (s == null) return null;
            try { return s.captureLayer(layerId); }
            catch (RemoteException e) { Log.w(TAG, "captureLayer " + layerId, e); return null; }
        }

        @Nullable
        public static Bundle getSystemContext() {
            IAICorePlus s = getService();
            if (s == null) return null;
            try { return s.getSystemContext(); }
            catch (RemoteException e) { Log.w(TAG, "getSystemContext", e); return null; }
        }

        public static boolean simulateTouch(float x, float y) {
            IAICorePlus s = getService();
            if (s == null) return false;
            try { return s.simulateTouch(x, y); }
            catch (RemoteException e) { Log.w(TAG, "simulateTouch", e); return false; }
        }

        public static boolean simulateSwipe(float x1, float y1, float x2, float y2, int durationMs) {
            IAICorePlus s = getService();
            if (s == null) return false;
            try { return s.simulateSwipe(x1, y1, x2, y2, durationMs); }
            catch (RemoteException e) { Log.w(TAG, "simulateSwipe", e); return false; }
        }

        public static boolean simulateText(@NonNull String text) {
            IAICorePlus s = getService();
            if (s == null) return false;
            try { return s.simulateText(text); }
            catch (RemoteException e) { Log.w(TAG, "simulateText", e); return false; }
        }

        @Nullable
        public static String getWindowHierarchy() {
            IAICorePlus s = getService();
            if (s == null) return null;
            try { return s.getWindowHierarchy(); }
            catch (RemoteException e) { Log.w(TAG, "getWindowHierarchy", e); return null; }
        }
    }

    // -------------------------------------------------------------------------
    // Continuity —— 需要增强 API
    // -------------------------------------------------------------------------

    /** 多设备特权连续性功能。 */
    public static class Continuity {

        @Nullable
        private static IContinuityBridge getService() {
            IShizukuService svc = requirePlusService();
            if (svc == null) return null;
            try { return svc.getContinuityBridge(); }
            catch (RemoteException e) { Log.w(TAG, "getContinuityBridge", e); return null; }
        }

        @NonNull
        public static List<String> listEligibleDevices() {
            IContinuityBridge s = getService();
            if (s == null) return Collections.emptyList();
            try { return s.listEligibleDevices(); }
            catch (RemoteException e) { Log.w(TAG, "listEligibleDevices", e); return Collections.emptyList(); }
        }
    }

    // -------------------------------------------------------------------------
    // VirtualMachine —— 需要增强 API
    // -------------------------------------------------------------------------

    /** Android 虚拟化框架（AVF）/ Microdroid 虚拟机管理。 */
    public static class VirtualMachine {

        @Nullable
        private static IVirtualMachineManager getService() {
            IShizukuService svc = requirePlusService();
            if (svc == null) return null;
            try { return svc.getVirtualMachineManager(); }
            catch (RemoteException e) { Log.w(TAG, "getVirtualMachineManager", e); return null; }
        }

        @NonNull
        public static List<String> list() {
            IVirtualMachineManager s = getService();
            if (s == null) return Collections.emptyList();
            try { return s.list(); }
            catch (RemoteException e) { Log.w(TAG, "vm list", e); return Collections.emptyList(); }
        }

        public static boolean start(@NonNull String name) {
            IVirtualMachineManager s = getService();
            if (s == null) return false;
            try { return s.start(name); }
            catch (RemoteException e) { Log.w(TAG, "vm start " + name, e); return false; }
        }

        public static boolean stop(@NonNull String name) {
            IVirtualMachineManager s = getService();
            if (s == null) return false;
            try { return s.stop(name); }
            catch (RemoteException e) { Log.w(TAG, "vm stop " + name, e); return false; }
        }

        public static boolean create(@NonNull String name, @NonNull Bundle config) {
            IVirtualMachineManager s = getService();
            if (s == null) return false;
            try { return s.create(name, config); }
            catch (RemoteException e) { Log.w(TAG, "vm create " + name, e); return false; }
        }

        public static boolean delete(@NonNull String name) {
            IVirtualMachineManager s = getService();
            if (s == null) return false;
            try { return s.delete(name); }
            catch (RemoteException e) { Log.w(TAG, "vm delete " + name, e); return false; }
        }

        @Nullable
        public static String getStatus(@NonNull String name) {
            IVirtualMachineManager s = getService();
            if (s == null) return null;
            try { return s.getStatus(name); }
            catch (RemoteException e) { Log.w(TAG, "vm status " + name, e); return null; }
        }
    }

    // -------------------------------------------------------------------------
    // StorageProxy —— 需要增强 API
    // -------------------------------------------------------------------------

    /** 通过 Plus 存储桥进行特权文件系统操作。 */
    public static class StorageProxy {

        @Nullable
        private static IStorageProxy getService() {
            IShizukuService svc = requirePlusService();
            if (svc == null) return null;
            try { return svc.getStorageProxy(); }
            catch (RemoteException e) { Log.w(TAG, "getStorageProxy", e); return null; }
        }

        public static boolean exists(@NonNull String path) {
            IStorageProxy s = getService();
            if (s == null) return false;
            try { return s.exists(path); }
            catch (RemoteException e) { Log.w(TAG, "exists " + path, e); return false; }
        }

        public static boolean delete(@NonNull String path) {
            IStorageProxy s = getService();
            if (s == null) return false;
            try { return s.delete(path); }
            catch (RemoteException e) { Log.w(TAG, "delete " + path, e); return false; }
        }

        @Nullable
        public static ParcelFileDescriptor openFile(@NonNull String path, int mode) {
            IStorageProxy s = getService();
            if (s == null) return null;
            try { return s.openFile(path, mode); }
            catch (RemoteException e) { Log.w(TAG, "openFile " + path, e); return null; }
        }

        @Nullable
        public static List<String> listFiles(@NonNull String path) {
            IStorageProxy s = getService();
            if (s == null) return null;
            try { return s.listFiles(path); }
            catch (RemoteException e) { Log.w(TAG, "listFiles " + path, e); return null; }
        }

        @Nullable
        public static Bundle getFileInfo(@NonNull String path) {
            IStorageProxy s = getService();
            if (s == null) return null;
            try { return s.getFileInfo(path); }
            catch (RemoteException e) { Log.w(TAG, "getFileInfo " + path, e); return null; }
        }
    }

    // -------------------------------------------------------------------------
    // Dhizuku —— 设备所有者兼容
    // -------------------------------------------------------------------------

    /** Plus 服务器暴露的 Dhizuku（设备所有者）兼容层。 */
    public static class Dhizuku {

        @Nullable
        public static IBinder getBinder() {
            return Shizuku.Dhizuku.getBinder();
        }

        public static boolean isAvailable() {
            return getBinder() != null;
        }
    }
}
