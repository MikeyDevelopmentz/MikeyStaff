package mikey.me.staffsystem.utils;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

public class TimeUtil {

    private static final long MAX_DURATION_SECONDS = Math.min(Long.MAX_VALUE / 20L, Long.MAX_VALUE / 1000L);
    // typed durations past 10 years are almost always a typo, use perm for that
    private static final long MAX_INPUT_SECONDS = TimeUnit.DAYS.toSeconds(3650L);

    public long parseDurationSeconds(String input, long defaultSeconds) {
        if (input == null || input.isEmpty()) {
            return defaultSeconds;
        }
        return parseDurationSecondsStrict(input)
                .orElseThrow(() -> new IllegalArgumentException("Invalid duration: " + input));
    }

    public OptionalLong parseDurationSecondsStrict(String input) {
        if (input == null || input.isEmpty()) {
            return OptionalLong.empty();
        }

        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.equals("perm") || lower.equals("permanent") || lower.equals("forever")) {
            return OptionalLong.of(0L);
        }

        long total = 0L;
        long value = 0L;
        boolean hasDigits = false;
        boolean hasNumber = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigits = true;
                hasNumber = true;
                try {
                    value = Math.addExact(Math.multiplyExact(value, 10L), c - '0');
                } catch (ArithmeticException e) {
                    return OptionalLong.empty();
                }
                continue;
            }

            if (!hasNumber) {
                return OptionalLong.empty();
            }
            long multiplier;
            switch (Character.toLowerCase(c)) {
                case 's' -> multiplier = 1L;
                case 'm' -> multiplier = TimeUnit.MINUTES.toSeconds(1L);
                case 'h' -> multiplier = TimeUnit.HOURS.toSeconds(1L);
                case 'd' -> multiplier = TimeUnit.DAYS.toSeconds(1L);
                case 'w' -> multiplier = TimeUnit.DAYS.toSeconds(7L);
                default -> {
                    return OptionalLong.empty();
                }
            }
            try {
                total = Math.addExact(total, Math.multiplyExact(value, multiplier));
            } catch (ArithmeticException e) {
                return OptionalLong.empty();
            }
            value = 0L;
            hasNumber = false;
        }

        if (hasNumber) {
            try {
                total = Math.addExact(total, value);
            } catch (ArithmeticException e) {
                return OptionalLong.empty();
            }
        }
        if (!hasDigits || total <= 0L || total > MAX_INPUT_SECONDS || !isValidDurationSeconds(total)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(total);
    }

    // used by ban/mute to reject garbage instead of silently perm-banning
    public boolean isValidDuration(String input) {
        return parseDurationSecondsStrict(input).isPresent();
    }

    public boolean isValidConfiguredDuration(long seconds) {
        return seconds == 0L || isValidDurationSeconds(seconds);
    }

    public boolean isValidDurationSeconds(long seconds) {
        return seconds > 0L && seconds <= MAX_DURATION_SECONDS;
    }

    public long getSchedulerTicks(long seconds) {
        if (!isValidDurationSeconds(seconds)) {
            throw new IllegalArgumentException("Duration cannot be scheduled: " + seconds);
        }
        return Math.multiplyExact(seconds, 20L);
    }

    public String formatDuration(long seconds) {
        if (seconds <= 0L) {
            return "0s";
        }
        long s = seconds % 60;
        long totalMinutes = seconds / 60;
        long m = totalMinutes % 60;
        long h = totalMinutes / 60;
        if (h > 0) {
            return h + "h " + m + "m " + s + "s";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }
}
