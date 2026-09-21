package com.batterymonitor.app;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku UserService：以 root(UID 0) 或 shell(UID 2000) 身份在独立进程中运行。
 * 客户端通过 Binder transact 请求执行 shell 命令，用于读取普通 App 无权访问的
 * 电池 sysfs 节点（charge_full / charge_full_design）。
 */
public class BatteryUserService extends Binder {

    public static final String DESCRIPTOR = "com.batterymonitor.app.IBatteryUserService";
    public static final int CMD_EXEC = 1;
    public static final int CMD_PING = 2;

    public BatteryUserService() {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == CMD_EXEC) {
            data.enforceInterface(DESCRIPTOR);
            String cmd = data.readString();
            String out = ShellUtil.run(cmd);
            reply.writeNoException();
            reply.writeString(out);
            return true;
        }
        if (code == CMD_PING) {
            data.enforceInterface(DESCRIPTOR);
            reply.writeNoException();
            reply.writeString("pong:" + android.os.Process.myUid());
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    public static class ShellUtil {
        static String run(String cmd) {
            StringBuilder sb = new StringBuilder();
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                r.close();
                p.waitFor();
            } catch (Throwable t) {
                sb.append("ERR:").append(t.toString());
            } finally {
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return sb.toString();
        }
    }
}
