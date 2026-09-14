package com.stand.bridge;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** E07 SDK routes, audited against the installed SDK/GUI; never fall back to the old trunk DM.
 * See out/trunk-probe/trunk-semantic-contract.md. SDK interlocks/orchestration stay enabled. */
final class E07Trunk {
    static final String GEAR = "vehicle.drive.gearLevel", SPEED = "vehicle.drive.speedValue";
    private static final String PARTS = "后舱滑移顶盖|后隔断玻璃|后备窗|后备箱|前备箱|天地门|天门|地门|后隔断|全后舱";
    private static final Pattern COMMAND = Pattern.compile("(?:(打开|关闭|停止|暂停)(" + PARTS
            + ")|(" + PARTS + ")打开([0-9]{1,3})%)");
    final String module, service, method, label, action, refusal;
    final int value;
    final boolean stop;
    final E07Trunk next; // ponytail: only 天地门 chains a second part (board, then roof); no general sequencing

    private E07Trunk(String module, String service, String method, int value, boolean stop,
                     String label, String action, String refusal) {
        this(module, service, method, value, stop, label, action, refusal, null);
    }
    private E07Trunk(String module, String service, String method, int value, boolean stop,
                     String label, String action, String refusal, E07Trunk next) {
        this.module = module; this.service = service; this.method = method; this.value = value;
        this.stop = stop; this.label = label; this.action = action; this.refusal = refusal; this.next = next;
    }

    private static E07Trunk refuse(String reason) {
        return new E07Trunk(null, null, null, 0, false, null, null, reason);
    }

    static E07Trunk parse(String zh) {
        if (zh == null) return null;
        Matcher m = COMMAND.matcher(zh);
        if (!m.matches()) {
            // Defensive boundary: a malformed trunk command must not reach the unsafe OEM DM.
            if (zh.matches(".*(?:" + PARTS + ").*") && !zh.contains("灯"))
                return refuse("Не удалось определить действие с багажником");
            return null;
        }
        boolean percent = m.group(3) != null;
        String part = percent ? m.group(3) : m.group(2);
        int position = percent ? Integer.parseInt(m.group(4)) : -1;
        if (position > 100) return refuse("Укажите положение от нуля до ста процентов");
        boolean stop = "停止".equals(m.group(1)) || "暂停".equals(m.group(1));
        boolean open = "打开".equals(m.group(1));
        String action = percent ? "положение " + position + " процентов" : stop ? "остановить" : open ? "открыть" : "закрыть";
        String module = "vehicle_control", service = "door_service", method, label;
        int value;
        switch (part) {
            case "后备箱": case "地门":
                method = "control_back_trunk_door"; value = percent ? position : stop ? 103 : open ? 101 : 102;
                label = "нижний борт"; break;
            case "后舱滑移顶盖":
                service = "window_service"; label = "крышу багажника";
                method = percent || stop ? "control_sky_window_percent" : "control_sky_window_status";
                value = percent ? position : stop ? 101 : open ? 1 : 0; break;
            case "后备窗":
                if (stop || percent) return refuse("Для заднего стекла подтверждены только открытие и закрытие");
                method = "control_back_trunk_door_win"; value = open ? 1 : 2; label = "заднее стекло"; break;
            case "后隔断玻璃":
                method = "control_mot_win_pos"; value = percent ? position : stop ? 101 : open ? 100 : 0;
                label = "стекло перегородки"; break;
            case "全后舱":
                if (percent) return refuse("Укажите отдельную часть багажника для положения в процентах");
                // OEM mode 3 stops the roof and lower gate only, leaving both glasses moving.
                if (stop) return refuse("Остановить можно отдельно борт, крышу и стекло перегородки");
                module = "business_forward"; service = "control_all_back_service"; method = "set_all_back_status";
                value = open ? 1 : 0; label = "весь задний отсек"; break;
            case "天地门": {
                // Owner decision 2026-09-12: «весь багажник» = lower board first, then the sliding roof; no glass moves.
                if (percent) return refuse("Укажите отдельную часть багажника для положения в процентах");
                E07Trunk board = parse(m.group(1) + "地门"), roof = parse(m.group(1) + "后舱滑移顶盖");
                return new E07Trunk(board.module, board.service, board.method, board.value, stop, board.label, action, null, roof);
            }
            case "天门":
                return refuse("Уточните часть багажника: сдвижная крыша, заднее стекло или нижний борт");
            case "后隔断":
                return refuse("Для глухой перегородки команда не подтверждена. Стеклом можно управлять отдельно");
            default:
                return refuse("Для переднего багажника команда замка пока не подтверждена");
        }
        return new E07Trunk(module, service, method, value, stop, label, action, null);
    }

    /** Object is the already initialized in-process CaSdkManager, or a recording fake in tests.
     * One synchronous submission, no retry or delayed connection callback. */
    String execute(Object sdk, Object sessionLock, java.util.function.BooleanSupplier current) throws Exception {
        if (!current.getAsBoolean()) return null;
        if (refusal != null) return refusal;
        if (sdk == null || !Boolean.TRUE.equals(sdk.getClass().getMethod("haveInit").invoke(sdk)))
            return "Связь с управлением багажником пока не готова";
        Method call = sdk.getClass().getMethod("callServiceByJsonSync", String.class, String.class, String.class, String.class);
        if (!stop) {
            // This is SDK-reported state, not a guaranteed fresh SOA sample: the OEM caches these
            // signals. Keep the SDK/SOA power, speed, gear, configuration and anti-pinch checks.
            Object state = call.invoke(sdk, "virtual_vehicle", "vehicle_service", "getVirtualVehicle",
                    "{\"pkeys\":[\"" + GEAR + "\",\"" + SPEED + "\"],\"BOOL_IS_SYNC\":true}");
            if (!parked(state)) return "Управление багажником доступно при остановке в режиме парковки";
        }
        String reply = submit(call, sdk, sessionLock, current);
        if (next == null || reply == null || !reply.startsWith("Передала команду")) return reply;
        String second = next.submit(call, sdk, sessionLock, current);
        if (second == null) return reply;
        if (second.startsWith("Передала команду")) return reply + " и " + next.label;
        return reply + ". Крыша багажника: " + Character.toLowerCase(second.charAt(0)) + second.substring(1);
    }

    /** One SDK write under the session lock; the park guard already ran in execute(). */
    private String submit(Method call, Object sdk, Object sessionLock, java.util.function.BooleanSupplier current) throws Exception {
        Object result;
        synchronized (sessionLock) {
            // A wake/cancel may arrive while the read blocks. Never submit its stale command.
            if (!current.getAsBoolean()) return null;
            result = call.invoke(sdk, module, service, method, "{\"value\":" + value + "}");
        }
        android.util.Log.i("E07Trunk", module + "/" + service + "/" + method + " value=" + value + " result=" + result);
        // 0 also includes accepted/partial success; never announce completed physical movement.
        if (result instanceof Integer && ((Integer) result) == 0)
            return "Передала команду: " + action + " " + label;
        if (result instanceof Integer && ((Integer) result) == 12293)
            return "Уже установлено нужное положение";
        if (Integer.valueOf(-400).equals(result)) return "Эта функция недоступна в комплектации автомобиля";
        if (Integer.valueOf(-2).equals(result) && method.equals("control_back_trunk_door_win"))
            return "Для закрытия заднего стекла сначала закройте крышу багажника";
        if (Integer.valueOf(16393).equals(result)) return "Сработала защита от защемления";
        if (Integer.valueOf(16394).equals(result)) return "Сработала защита привода от перегрева";
        return "Автомобиль не подтвердил команду багажнику";
    }

    static boolean parked(Object raw) {
        if (!(raw instanceof String)) return false;
        try {
            JSONObject envelope = new JSONObject((String) raw);
            if (!(envelope.get("code") instanceof Number) || envelope.getDouble("code") != 0) return false;
            JSONArray properties = envelope.getJSONObject("data").getJSONArray("propertys");
            Double gear = null, speed = null;
            for (int i = 0; i < properties.length(); i++) {
                JSONObject property = properties.getJSONObject(i);
                String key = property.getString("key");
                if (!GEAR.equals(key) && !SPEED.equals(key)) continue;
                if (!(property.get("status") instanceof Number) || property.getDouble("status") != 0) return false;
                Object v = property.get("value");
                if (!(v instanceof String) && !(v instanceof Number)) return false;
                double number = Double.parseDouble(v.toString());
                if (Double.isNaN(number) || Double.isInfinite(number) || number < 0) return false;
                if (GEAR.equals(key)) { if (gear != null) return false; gear = number; }
                else { if (speed != null) return false; speed = number; }
            }
            return gear != null && speed != null && gear == 1 && speed == 0;
        } catch (Exception malformed) { return false; }
    }
}
