package me.kismeria.smpcore.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Durations {

    private static final Pattern RELATIVE = Pattern.compile("(\\d+)\\s*(д|d|ч|h|м|m|мин|min|с|s|сек|sec)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2})[:.](\\d{2})$");
    private static final Pattern DATE_CLOCK = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?\\s+(\\d{1,2})[:.](\\d{2})$");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT);

    private Durations() {
    }

    /** Только цифры: 1:05:09, 4:59, 9. */
    public static String digits(long totalSeconds) {
        long s = Math.max(0, totalSeconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec);
        }
        if (m > 0) {
            return String.format(Locale.ROOT, "%d:%02d", m, sec);
        }
        return Long.toString(sec);
    }

    /** Человекочитаемо: 1ч 5м 9с. */
    public static String human(long totalSeconds) {
        long s = Math.max(0, totalSeconds);
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("д ");
        if (h > 0) sb.append(h).append("ч ");
        if (m > 0) sb.append(m).append("м ");
        if (sec > 0 || sb.isEmpty()) sb.append(sec).append("с");
        return sb.toString().trim();
    }

    public static String clock(long epochMillis, ZoneId zone) {
        return DATE_FORMAT.format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), zone));
    }

    /**
     * Разбирает момент старта. Поддерживает:
     * 18:30 · 06.10 18:30 · 06.10.2026 18:30 · 30m · 1h30m · 2ч · +15м
     *
     * @return epoch millis или -1, если не понято
     */
    public static long parseMoment(String input, ZoneId zone, long nowMillis) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("+")) {
            s = s.substring(1).trim();
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone);

        Matcher clock = CLOCK.matcher(s);
        if (clock.matches()) {
            LocalTime time = time(clock.group(1), clock.group(2));
            if (time == null) return -1;
            ZonedDateTime at = now.with(time).withSecond(0).withNano(0);
            if (!at.isAfter(now)) {
                at = at.plusDays(1);
            }
            return at.toInstant().toEpochMilli();
        }

        Matcher dateClock = DATE_CLOCK.matcher(s);
        if (dateClock.matches()) {
            LocalTime time = time(dateClock.group(4), dateClock.group(5));
            if (time == null) return -1;
            try {
                int day = Integer.parseInt(dateClock.group(1));
                int month = Integer.parseInt(dateClock.group(2));
                int year = dateClock.group(3) != null ? Integer.parseInt(dateClock.group(3)) : now.getYear();
                ZonedDateTime at = LocalDateTime.of(LocalDate.of(year, month, day), time).atZone(zone);
                if (dateClock.group(3) == null && !at.isAfter(now)) {
                    at = at.plusYears(1);
                }
                return at.toInstant().toEpochMilli();
            } catch (RuntimeException e) {
                return -1;
            }
        }

        long seconds = parseDurationSeconds(s);
        return seconds > 0 ? nowMillis + seconds * 1000L : -1;
    }

    /** 1h30m / 90м / 45s → секунды, -1 если не понято. */
    public static long parseDurationSeconds(String input) {
        String s = input.replace(" ", "");
        if (s.isEmpty()) return -1;
        Matcher m = RELATIVE.matcher(s);
        long total = 0;
        int consumed = 0;
        while (m.find()) {
            if (m.start() != consumed) return -1;
            consumed = m.end();
            long value = Long.parseLong(m.group(1));
            String unit = m.group(2);
            total += switch (unit.charAt(0)) {
                case 'д', 'd' -> value * 86400;
                case 'ч', 'h' -> value * 3600;
                case 'м', 'm' -> value * 60;
                default -> value;
            };
        }
        return consumed == s.length() && total > 0 ? total : -1;
    }

    private static LocalTime time(String h, String m) {
        int hour = Integer.parseInt(h);
        int minute = Integer.parseInt(m);
        if (hour > 23 || minute > 59) return null;
        return LocalTime.of(hour, minute);
    }
}
