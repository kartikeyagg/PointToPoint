package com.kartikey.p2pchat.conf.net;

/** Token bucket rate limiter on a millisecond clock. Not thread-safe; callers hold their lock. */
final class TokenBucket {

    private final double ratePerMilli;
    private final double burst;
    private double tokens;
    private long last;

    TokenBucket(double perSecond, double burst, long nowMillis) {
        this.ratePerMilli = perSecond / 1000.0;
        this.burst = burst;
        this.tokens = burst;
        this.last = nowMillis;
    }

    boolean take(long nowMillis) {
        if (nowMillis > last) {
            tokens = Math.min(burst, tokens + (nowMillis - last) * ratePerMilli);
            last = nowMillis;
        }
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
