package com.batterymonitor.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * 特权命令执行器，按优先级降级：
 *   1. Shizuku UserService（root / adb 身份执行 shell）
 *   2. Shizuku newProcess（反射，作为兜底）
 *   3. root su（Magisk / KernelSU 等提供的 su）
 *   4. null（交给调用方做直读 sysfs 兜底）
 */
public class ShizukuExec {

    public static final int MODE_NONE = 0;
    public static final int MODE_SHIZUKU = 1;
    public static final int MODE_ROOT = 2;

    private static IBinder userService;
    private static boolean bindRequested;
    private static Conn conn;
    public static String lastError = "";

    public static class Conn implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            userService = binder;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            userService = null;
        }
    }

    /** Shizuku binder 是否存活且已授权 */
    public static boolean isShizukuReady() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 是否在运行但尚未授权 */
    public static boolean isShizukuInstalledButNoPermission() {
        try {
            return Shizuku.pingBinder() && !isShizukuReady();
        } catch (Throwable t) {
            return false;
        }
    }

    public static int requestPermission(int code) {
        try {
            Shizuku.requestPermission(code);
            return 1;
        } catch (Throwable t) {
            lastError = t.toString();
            return -1;
        }
    }

    /** 0=shell(adb) 2000, 0=root, -1=未知 */
    public static int getUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void bind(Context ctx) {
        if (bindRequested || !isShizukuReady()) return;
        bindRequested = true;
        try {
            ComponentName cn = new ComponentName(ctx.getPackageName(),
                    BatteryUserService.class.getName());
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(cn)
                    .daemon(false)
                    .tag("battery-monitor")
                    .version(1);
            conn = new Conn();
            Shizuku.bindUserService(args, conn);
        } catch (Throwable t) {
            lastError = t.toString();
            bindRequested = false;
        }
    }

    /** 通过 UserService 执行（特权身份） */
    public static String execViaUserService(String cmd) {
        IBinder b = userService;
        if (b == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BatteryUserService.DESCRIPTOR);
            data.writeString(cmd);
            boolean ok = b.transact(BatteryUserService.CMD_EXEC, data, reply, 0);
            if (!ok) return null;
            reply.readException();
            return reply.readString();
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 反射调用 Shizuku.newProcess 执行（兜底） */
    public static String execViaNewProcess(String cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            Object proc = m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            if (proc == null) return null;
            Process p = (Process) proc;
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    /** root su 执行 */
    public static String execViaSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            p.waitFor();
            String out = sb.toString();
            if (out.trim().length() == 0) return null;
            return out;
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    /** 仅通过 Shizuku（UserService 优先，其次 newProcess）执行，不回退到 root su */
    public static String execShizukuOnly(String cmd) {
        if (!isShizukuReady()) return null;
        String out = execViaUserService(cmd);
        if (out != null && out.trim().length() > 0) return out;
        out = execViaNewProcess(cmd);
        if (out != null && out.trim().length() > 0) return out;
        return null;
    }

    /** 仅通过 root su 执行（不走 Shizuku 通道）。用于读取最高权威性的电池数据，
     *  其结果绝不应被 Shizuku（adb 身份）读取覆盖。 */
    public static String execRootOnly(String cmd) {
        return execViaSu(cmd);
    }

    /** 依次尝试各通道；返回 null 表示都不可用。
     *  注意：本方法以 Shizuku 优先，可能让 Shizuku(adb) 读数盖过 root。
     *  电池容量等需要"root 优先"的场景请用 execRootOnly + execShizukuOnly 分别读取后合并。 */
    public static String exec(String cmd) {
        String out;
        if (isShizukuReady()) {
            out = execViaUserService(cmd);
            if (out != null && out.trim().length() > 0) return out;
            out = execViaNewProcess(cmd);
            if (out != null && out.trim().length() > 0) return out;
        }
        out = execViaSu(cmd);
        if (out != null && out.trim().length() > 0) return out;
        return null;
    }

    /** 系统里是否存在 su（缓存结果，不执行命令） */
    public static boolean hasSu() {
        if (suCached != null) return suCached;
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/local/su"};
        boolean found = false;
        for (int i = 0; i < paths.length; i++) {
            if (new java.io.File(paths[i]).exists()) {
                found = true;
                break;
            }
        }
        suCached = found;
        return found;
    }

    private static Boolean suCached;

    /** 当前可用模式描述 */
    public static String modeText() {
        if (isShizukuReady()) {
            int uid = getUid();
            if (uid == 0) return "Shizuku (root)";
            if (uid == 2000) return "Shizuku (adb)";
            return "Shizuku (uid=" + uid + ")";
        }
        if (isShizukuInstalledButNoPermission()) return "Shizuku 未授权";
        if (hasSu()) return "root su 可用";
        return "无";
    }
}
