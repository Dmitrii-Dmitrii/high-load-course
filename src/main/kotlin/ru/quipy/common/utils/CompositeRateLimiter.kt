package ru.quipy.common.utils

import java.time.Duration

class CompositeRateLimiter(
    private val rl1: RateLimiter,
    private val rl2: RateLimiter,
) : RateLimiter {
    override fun tick(): Boolean {
        return rl1.tick() || rl2.tick()
    }

    override fun tickBlocking() {
        rl1.tickBlocking()
        rl2.tickBlocking()
    }

    override fun tickBlocking(duration: Duration): Boolean {
        if (rl1.tick()) return true
        if (rl2.tick()) return true

        val short = Duration.ofMillis( minOf(50L, duration.toMillis()) )
        if (rl1.tickBlocking(short)) return true
        if (rl2.tickBlocking(duration)) return true

        return false

//        val firstOk = rl1.tickBlocking(duration)
//        if (!firstOk) return false
//        return rl2.tickBlocking(duration)
    }
}