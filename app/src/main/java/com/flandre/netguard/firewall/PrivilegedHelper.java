package com.flandre.netguard.firewall;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/** Platform operations shared by the Shizuku user service and root subprocesses. */
public final class PrivilegedHelper {
    private static final String TAG = "NetGuardHelper";
    private static final String FW_UID_RULES_COMMAND = "fw-uid-rules";
    private static final String FW_UID_SERVER_COMMAND = "fw-uid-server";
    private static final String FW_CHAIN_COMMAND = "fw-chain";
    private static final String FG_TASK_COMMAND = "fg-task";
    private static final String FG_WATCH_COMMAND = "fg-watch";
    private static final long FG_DEBOUNCE_MS = 150;
    private static final long FG_HEARTBEAT_MS = 30000;
    private static final String FG_KEEPALIVE = ".";
    private static final int FIREWALL_CHAIN_OEM_DENY_3 = 9;
    private static final String UID_OWNER_MAP_MISSING = "suidownermap does not have entry for uid";

    private static void logD(String message) {
        System.err.println(TAG + " [D] " + message);
    }

    private static void logW(String message) {
        System.err.println(TAG + " [W] " + message);
    }

    private static void logE(String message, Throwable t) {
        System.err.println(TAG + " [E] " + message);
        if (t != null) t.printStackTrace(System.err);
    }

    public static void main(String[] args) {
        if (args.length == 1 && FW_UID_SERVER_COMMAND.equals(args[0])) {
            runUidRuleServer();
        } else if (args.length == 1 && FG_WATCH_COMMAND.equals(args[0])) {
            final PrintWriter stdout = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)), true);
            watchForegroundTask(value -> {
                stdout.println(value);
                return !stdout.checkError();
            });
        } else {
            System.err.println("Unsupported helper operation");
            System.exit(2);
        }
    }

    private static Object connectivityService() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "connectivity");
        if (binder == null) {
            return null;
        }
        return Class.forName("android.net.IConnectivityManager$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder"))
                .invoke(null, binder);
    }

    public static String setChainEnabled(boolean enabled) {
        try {
            Object service = connectivityService();
            if (service == null) return "Error (code 1): connectivity service unavailable";
            service.getClass()
                    .getMethod("setFirewallChainEnabled", int.class, boolean.class)
                    .invoke(service, FIREWALL_CHAIN_OEM_DENY_3, enabled);
            return "OK";
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            logE("setFirewallChainEnabled failed", cause);
            return "Error (code 1): " + cause;
        }
    }

    private static String setUidFirewallRules(String encodedRules) {
        Object service = null;
        Method setter = null;
        String setupError = null;
        try {
            service = connectivityService();
            if (service == null) {
                setupError = "Error (code 1): connectivity service unavailable";
            } else {
                setter = service.getClass()
                        .getMethod("setUidFirewallRule", int.class, int.class, int.class);
            }
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            logE("could not bind IConnectivityManager", cause);
            setupError = "Error (code 1): " + cause;
        }

        StringBuilder result = new StringBuilder();
        for (String encodedRule : encodedRules.split(",")) {
            String response;
            String[] values = encodedRule.split(":");
            if (setupError != null) {
                response = setupError;
            } else if (values.length != 2) {
                response = "Error (code 22): invalid uid rule";
            } else {
                int uid = 0;
                int rule = 0;
                boolean parsed;
                try {
                    uid = Integer.parseInt(values[0].trim());
                    rule = Integer.parseInt(values[1].trim());
                    parsed = true;
                } catch (NumberFormatException e) {
                    parsed = false;
                }
                if (!parsed) {
                    response = "Error (code 22): uid and rule must be integers";
                } else {
                    try {
                        setter.invoke(service, FIREWALL_CHAIN_OEM_DENY_3, uid, rule);
                        response = "OK " + uid + " " + rule;
                    } catch (Throwable t) {
                        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                        if (cause.toString().toLowerCase(Locale.ROOT).contains(UID_OWNER_MAP_MISSING)) {
                            response = "OK " + uid + " " + rule;
                        } else {
                            logE("setUidFirewallRule failed for uid " + uid, cause);
                            response = "Error (code 1): " + cause;
                        }
                    }
                }
            }
            if (result.length() > 0) result.append(';');
            result.append(response.replace(';', ',').replace('\n', ' ').replace('\r', ' '));
        }
        return result.toString();
    }

    private static Object fieldValue(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object[] taskQueryArgs(Class<?>[] types) {
        Object[] args = new Object[types.length];
        int intIndex = 0;
        int boolIndex = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class || types[i] == Integer.class) {
                args[i] = (intIndex++ == 0) ? Integer.valueOf(1) : Integer.valueOf(0);
            } else if (types[i] == boolean.class || types[i] == Boolean.class) {
                args[i] = Boolean.valueOf(boolIndex++ == 0);
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    public static String foregroundTask() {
        try {
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "activity_task");
            if (binder == null) {
                return "Error (code 1): activity_task service unavailable";
            }
            Object atm = Class.forName("android.app.IActivityTaskManager$Stub")
                    .getMethod("asInterface", Class.forName("android.os.IBinder"))
                    .invoke(null, binder);
            if (atm == null) {
                return "Error (code 1): IActivityTaskManager unavailable";
            }
            Method getTasks = null;
            for (Method method : atm.getClass().getMethods()) {
                if (!"getTasks".equals(method.getName())) continue;
                if (getTasks == null
                        || method.getParameterTypes().length > getTasks.getParameterTypes().length) {
                    getTasks = method;
                }
            }
            if (getTasks == null) {
                return "Error (code 1): getTasks unavailable";
            }
            Object raw = getTasks.invoke(atm, taskQueryArgs(getTasks.getParameterTypes()));
            if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
                return "Error (code 2): no visible task";
            }
            Object task = ((List<?>) raw).get(0);
            Object component = fieldValue(task, "topActivity");
            if (component == null) {
                return "Error (code 2): task has no activity";
            }
            String packageName = (String) component.getClass()
                    .getMethod("getPackageName")
                    .invoke(component);
            if (packageName == null || packageName.isEmpty()) {
                return "Error (code 2): task has no package";
            }
            Object userId = fieldValue(task, "userId");
            int user = (userId instanceof Integer) ? (Integer) userId : 0;
            return user + ":" + packageName;
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            return "Error (code 1): " + cause;
        }
    }

    public interface ForegroundEmitter {
        boolean emit(String value);
    }

    private static boolean registerTaskStackListener(IBinder listener) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "activity_task");
            if (service == null) {
                return false;
            }
            Field codeField = Class.forName("android.app.IActivityTaskManager$Stub")
                    .getDeclaredField("TRANSACTION_registerTaskStackListener");
            codeField.setAccessible(true);
            int code = codeField.getInt(null);
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            data.writeStrongBinder(listener);
            if (!service.transact(code, data, reply, 0)) {
                return false;
            }
            reply.readException();
            return true;
        } catch (Throwable t) {
            logW("registerTaskStackListener failed: " + t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static void watchForegroundTask(ForegroundEmitter emitter) {
        watchForegroundTask(emitter, true);
    }

    public static void watchForegroundTask(ForegroundEmitter emitter, boolean keepalive) {
        final Object lock = new Object();
        final boolean[] dirty = { true };
        Binder listener = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code >= IBinder.FIRST_CALL_TRANSACTION) {
                    synchronized (lock) {
                        dirty[0] = true;
                        lock.notifyAll();
                    }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        if (!registerTaskStackListener(listener)) {
            emitter.emit("Error (code 1): registerTaskStackListener rejected");
            return;
        }
        logD("Foreground watch started");

        String last = null;
        while (true) {
            synchronized (lock) {
                if (!dirty[0]) {
                    try {
                        lock.wait(FG_HEARTBEAT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                dirty[0] = false;
            }
            try {
                Thread.sleep(FG_DEBOUNCE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (lock) {
                dirty[0] = false;
            }
            String current = foregroundTask();
            if (current.startsWith("Error (code 1)")) {
                emitter.emit(current);
                return;
            }
            if (current.startsWith("Error")) {
                continue;
            }
            boolean alive;
            if (current.equals(last)) {
                alive = !keepalive || emitter.emit(FG_KEEPALIVE);
            } else {
                last = current;
                alive = emitter.emit(current);
            }
            if (!alive) {
                logD("Foreground watch client disconnected");
                return;
            }
        }
    }

    private static void runUidRuleServer() {
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)), true)
        ) {
            writer.println("READY");
            String command;
            while ((command = reader.readLine()) != null) {
                if ("exit".equals(command)) return;
                String result;
                if (command.startsWith(FW_UID_RULES_COMMAND + " ")) {
                    result = setUidFirewallRules(command.substring(FW_UID_RULES_COMMAND.length() + 1));
                } else if (command.startsWith(FW_CHAIN_COMMAND + " ")) {
                    result = setChainEnabled(command.endsWith(" 1"));
                } else if (FG_TASK_COMMAND.equals(command)) {
                    result = foregroundTask();
                } else {
                    result = "Error (code 22): unsupported command";
                }
                writer.println(result);
            }
        } catch (Exception ignored) {
        }
    }

}
