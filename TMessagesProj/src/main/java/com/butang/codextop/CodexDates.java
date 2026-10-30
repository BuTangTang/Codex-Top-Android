package com.butang.codextop;

import java.util.Calendar;
import java.util.TimeZone;

/** D-83 中文日期：今天只写时间，昨天写“昨天”，今年写月日，往年写年月日；不受系统语言影响。 */
public final class CodexDates {
    private CodexDates() { }

    /** 按手机当前时区格式化；毫秒值小于0表示来源未给时间。 */
    public static String format(long timeMs) {
        return format(timeMs, System.currentTimeMillis(), TimeZone.getDefault());
    }

    static String format(long timeMs, long nowMs, TimeZone zone) {
        if (timeMs < 0) return "--:--";
        Calendar time = Calendar.getInstance(zone), now = Calendar.getInstance(zone);
        time.setTimeInMillis(timeMs);
        now.setTimeInMillis(nowMs);
        String clock = String.format(java.util.Locale.ROOT, "%d:%02d", time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE));
        if (sameDay(time, now)) return clock;
        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(time, yesterday)) return "昨天 " + clock;
        String day = (time.get(Calendar.MONTH) + 1) + "月" + time.get(Calendar.DAY_OF_MONTH) + "日";
        if (time.get(Calendar.YEAR) == now.get(Calendar.YEAR)) return day + " " + clock;
        return time.get(Calendar.YEAR) + "年" + day;
    }

    /** 采集、重置这类需要具体时刻的地方：今天以外也保留时间。 */
    public static String formatWithTime(long timeMs) {
        return formatWithTime(timeMs, System.currentTimeMillis(), TimeZone.getDefault());
    }

    static String formatWithTime(long timeMs, long nowMs, TimeZone zone) {
        String text = format(timeMs, nowMs, zone);
        if (timeMs < 0 || !text.contains("年")) return text;
        Calendar time = Calendar.getInstance(zone);
        time.setTimeInMillis(timeMs);
        return text + String.format(java.util.Locale.ROOT, " %d:%02d", time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
