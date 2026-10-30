package com.butang.codextop;

import java.util.Calendar;
import java.util.TimeZone;

/** 中文日期规则：固定时区与合成时刻，不依赖运行测试的电脑时区或语言。 */
public final class CodexDatesTest {
    public static void main(String[] args) {
        TimeZone shanghai = TimeZone.getTimeZone("Asia/Shanghai");
        long now = at(shanghai, 2026, 10, 30, 9, 5);
        check("8:07", CodexDates.format(at(shanghai, 2026, 10, 30, 8, 7), now, shanghai), "今天");
        check("0:00", CodexDates.format(at(shanghai, 2026, 10, 30, 0, 0), now, shanghai), "今天零点");
        check("昨天 23:59", CodexDates.format(at(shanghai, 2026, 10, 29, 23, 59), now, shanghai), "昨天");
        check("10月14日 11:29", CodexDates.format(at(shanghai, 2026, 10, 14, 11, 29), now, shanghai), "今年");
        check("2025年12月31日", CodexDates.format(at(shanghai, 2025, 12, 31, 23, 0), now, shanghai), "往年");
        check("2025年12月31日 23:00", CodexDates.formatWithTime(at(shanghai, 2025, 12, 31, 23, 0), now, shanghai), "往年带时间");
        check("10月14日 11:29", CodexDates.formatWithTime(at(shanghai, 2026, 10, 14, 11, 29), now, shanghai), "今年带时间不重复");
        long newYear = at(shanghai, 2027, 1, 1, 0, 30);
        check("昨天 22:00", CodexDates.format(at(shanghai, 2026, 12, 31, 22, 0), newYear, shanghai), "跨年昨天");
        check("--:--", CodexDates.format(-1, now, shanghai), "缺失时间");
        TimeZone utc = TimeZone.getTimeZone("UTC");
        check("昨天 18:00", CodexDates.format(at(shanghai, 2026, 10, 30, 2, 0), now, utc), "按手机时区判断日期");
        System.out.println("CodexDates: 今天、昨天、今年、往年、跨年、缺失时间与时区通过");
    }

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute);
        return calendar.getTimeInMillis();
    }

    private static void check(String expected, String actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + "：" + actual);
    }
}
