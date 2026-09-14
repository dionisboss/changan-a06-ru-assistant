package com.stand.bridge;

import java.util.ArrayList;
import java.util.List;

/** Run on Android/ART for the platform JSON implementation. Only FakeSdk receives calls. */
public final class E07TrunkTest {
    private static int checks;
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static String property(String key, String value) {
        return "{\"key\":\"" + key + "\",\"status\":0,\"value\":\"" + value + "\"}";
    }
    static String state(String gear, String speed) {
        return "{\"code\":0,\"data\":{\"propertys\":[" + property(E07Trunk.SPEED, speed)
                + "," + property(E07Trunk.GEAR, gear) + "]}}";
    }

    public static class FakeSdk {
        boolean ready = true;
        Object state = E07TrunkTest.state("1", "0"), result = Integer.valueOf(0);
        final List<String> writes = new ArrayList<>();
        int reads;
        Runnable onRead;
        public boolean haveInit() { return ready; }
        public Object callServiceByJsonSync(String module, String service, String method, String json) {
            if (module.equals("virtual_vehicle")) {
                check(service.equals("vehicle_service") && method.equals("getVirtualVehicle"), "read endpoint");
                check(json.equals("{\"pkeys\":[\"" + E07Trunk.GEAR + "\",\"" + E07Trunk.SPEED + "\"],\"BOOL_IS_SYNC\":true}"), "read keys");
                reads++;
                if (onRead != null) onRead.run();
                return state;
            }
            writes.add(module + "/" + service + "/" + method + " " + json);
            return result;
        }
    }

    /** Chained 天地门 (board, then roof). No org.json on any path here, so this also runs on a plain JVM
     *  via tests/run_trunk_chain_tests.py; the park guard and its JSON stay in main() on the car. */
    static void chain() throws Exception {
        for (String[] c : new String[][]{{"打开天地门", "101", "control_sky_window_status {\"value\":1}", "открыть"},
                {"关闭天地门", "102", "control_sky_window_status {\"value\":0}", "закрыть"},
                {"停止天地门", "103", "control_sky_window_percent {\"value\":101}", "остановить"}}) {
            E07Trunk command = E07Trunk.parse(c[0]);
            check(command != null && command.refusal == null && command.next != null, "chained " + c[0]);
            check(command.method.equals("control_back_trunk_door") && command.value == Integer.parseInt(c[1]), "board route " + c[0]);
            check(command.next.service.equals("window_service") && command.next.next == null, "roof route " + c[0]);
            check(command.label.equals("нижний борт") && command.next.label.equals("крышу багажника"), "labels " + c[0]);
        }
        // Only the stop chain skips the park guard, so it is the one that actuates without org.json.
        FakeSdk pair = new FakeSdk(); pair.state = null;
        String reply = E07Trunk.parse("停止天地门").execute(pair, new Object(), () -> true);
        check(pair.writes.size() == 2, "board then roof");
        check(pair.writes.get(0).equals("vehicle_control/door_service/control_back_trunk_door {\"value\":103}"), "board first");
        check(pair.writes.get(1).equals("vehicle_control/window_service/control_sky_window_percent {\"value\":101}"), "roof second");
        check(pair.reads == 0, "stop needs no park guard");
        check(reply.equals("Передала команду: остановить нижний борт и крышу багажника"), "combined reply: " + reply);

        FakeSdk boardFails = new FakeSdk(); boardFails.state = null; boardFails.result = -400;
        String boardReply = E07Trunk.parse("停止天地门").execute(boardFails, new Object(), () -> true);
        check(boardFails.writes.size() == 1, "board failure stops the chain");
        check(boardReply.equals("Эта функция недоступна в комплектации автомобиля"), "board failure reported");

        FakeSdk roofFails = new FakeSdk() {
            @Override public Object callServiceByJsonSync(String module, String service, String method, String json) {
                Object r = super.callServiceByJsonSync(module, service, method, json);
                return writes.size() == 2 ? Integer.valueOf(16393) : r;
            }
        };
        roofFails.state = null;
        String roofReply = E07Trunk.parse("停止天地门").execute(roofFails, new Object(), () -> true);
        check(roofFails.writes.size() == 2, "roof still attempted");
        check(roofReply.equals("Передала команду: остановить нижний борт. Крыша багажника: сработала защита от защемления"),
                "roof failure reported: " + roofReply);

        E07Trunk percent = E07Trunk.parse("天地门打开50%");
        check(percent != null && percent.refusal != null && percent.next == null, "no percentage for the pair");
        System.out.println("PASS trunk chain: " + checks + " checks");
    }

    static void route(String zh, String service, String method, int value, boolean stop) throws Exception {
        E07Trunk command = E07Trunk.parse(zh);
        check(command != null && command.refusal == null, "supported " + zh);
        FakeSdk sdk = new FakeSdk();
        if (stop) sdk.state = null; // Stops must still work when gear/speed reads are unavailable.
        String reply = command.execute(sdk, new Object(), () -> true);
        check(sdk.writes.size() == 1, "exactly one write " + zh);
        String module = service.equals("control_all_back_service") ? "business_forward" : "vehicle_control";
        check(sdk.writes.get(0).equals(module + "/" + service + "/" + method + " {\"value\":" + value + "}"), "exact route " + zh);
        check(sdk.reads == (stop ? 0 : 1), "park guard " + zh);
        check(reply.startsWith("Передала команду:"), "acknowledge submission only " + zh);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--chain")) { chain(); return; }   // host subset: no org.json
        for (String part : new String[]{"后备箱", "地门"}) {
            route("打开" + part, "door_service", "control_back_trunk_door", 101, false);
            route("关闭" + part, "door_service", "control_back_trunk_door", 102, false);
            route("停止" + part, "door_service", "control_back_trunk_door", 103, true);
            route("暂停" + part, "door_service", "control_back_trunk_door", 103, true);
        }
        route("打开后舱滑移顶盖", "window_service", "control_sky_window_status", 1, false);
        route("关闭后舱滑移顶盖", "window_service", "control_sky_window_status", 0, false);
        route("停止后舱滑移顶盖", "window_service", "control_sky_window_percent", 101, true);
        route("打开后备窗", "door_service", "control_back_trunk_door_win", 1, false);
        route("关闭后备窗", "door_service", "control_back_trunk_door_win", 2, false);
        route("打开后隔断玻璃", "door_service", "control_mot_win_pos", 100, false);
        route("关闭后隔断玻璃", "door_service", "control_mot_win_pos", 0, false);
        route("停止后隔断玻璃", "door_service", "control_mot_win_pos", 101, true);
        route("打开全后舱", "control_all_back_service", "set_all_back_status", 1, false);
        route("关闭全后舱", "control_all_back_service", "set_all_back_status", 0, false);
        for (int percent : new int[]{0, 1, 25, 50, 99, 100}) {
            route("地门打开" + percent + "%", "door_service", "control_back_trunk_door", percent, false);
            route("后备箱打开" + percent + "%", "door_service", "control_back_trunk_door", percent, false);
            route("后舱滑移顶盖打开" + percent + "%", "window_service", "control_sky_window_percent", percent, false);
            route("后隔断玻璃打开" + percent + "%", "door_service", "control_mot_win_pos", percent, false);
        }
        chain();
        for (String zh : new String[]{"打开天门", "天地门打开50%", "停止后备窗", "后备窗打开50%", "打开前备箱",
                "关闭后隔断", "停止全后舱", "全后舱打开50%", "后备箱打开101%", "后备箱打开-1%", "后备箱打开1.5%", "后备箱"}) {
            E07Trunk command = E07Trunk.parse(zh);
            check(command != null && command.refusal != null, "no stock fallback " + zh);
            FakeSdk sdk = new FakeSdk();
            check(command.execute(sdk, new Object(), () -> true).equals(command.refusal) && sdk.reads == 0 && sdk.writes.isEmpty(), "no refused actuation " + zh);
        }
        check(E07Trunk.parse("打开后备箱灯") == null, "light remains separate");
        check(E07Trunk.parse("主驾空调二十二度") == null, "unrelated OEM route");
        check(E07Trunk.parked(state("1", "0.0")), "reordered keys");
        for (Object bad : new Object[]{null, 0, "", "{}", "{\"code\":0}", state("-252", "0"), state("1", "-400"),
                state("1", "NaN"), state("1", "Infinity"), state("1", "0.1"), state("1.5", "0"), state("2", "0"),
                state("1", "0").replace("\"status\":0", "\"status\":1"),
                state("1", "0").replace(E07Trunk.SPEED, E07Trunk.GEAR),
                state("1", "0").replace(E07Trunk.SPEED, "vehicle.drive.unknown"),
                state("1", "0").replace("\"code\":0", "\"code\":-1"),
                state("1", "0").replace("\"value\":\"0\"", "\"value\":null")}) {
            check(!E07Trunk.parked(bad), "invalid state " + bad);
            FakeSdk sdk = new FakeSdk(); sdk.state = bad;
            E07Trunk.parse("打开后备箱").execute(sdk, new Object(), () -> true);
            check(sdk.writes.isEmpty(), "no write with invalid park state");
        }
        FakeSdk sdk = new FakeSdk(); sdk.ready = false;
        E07Trunk.parse("打开后备箱").execute(sdk, new Object(), () -> true);
        check(sdk.reads == 0 && sdk.writes.isEmpty(), "no queue before SDK initialization");
        for (Object result : new Object[]{null, "0", -1, -2, -200, -400, 8192, 16393, 16394}) {
            sdk = new FakeSdk(); sdk.result = result;
            String reply = E07Trunk.parse("打开后备箱").execute(sdk, new Object(), () -> true);
            check(!reply.startsWith("Передала команду:"), "failure not success " + result);
            check(sdk.writes.size() == 1, "failure never retried " + result);
        }
        Object sessionLock = new Object();
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        sdk = new FakeSdk();
        sdk.onRead = () -> {
            Thread cancel = new Thread(() -> { synchronized (sessionLock) { active.set(false); } });
            cancel.start();
            try { cancel.join(2000); } catch (InterruptedException e) { throw new AssertionError(e); }
            check(!cancel.isAlive(), "SDK read must not hold session lock and block cancellation");
        };
        check(E07Trunk.parse("打开后备箱").execute(sdk, sessionLock, active::get) == null, "discard cancelled read");
        check(sdk.reads == 1 && sdk.writes.isEmpty(), "no actuation after cancellation during read");
        active.set(false); sdk = new FakeSdk();
        check(E07Trunk.parse("停止后备箱").execute(sdk, sessionLock, active::get) == null
                && sdk.reads == 0 && sdk.writes.isEmpty(), "old session cannot stop a new session's command");
        System.out.println("PASS E07 trunk: " + checks + " checks; all SDK writes captured by FakeSdk");
    }
}
