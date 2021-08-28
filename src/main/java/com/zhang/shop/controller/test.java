package com.zhang.shop.controller;

public class test {
    /*
     * How is the RateLimiter designed, and why?
     *
     * The primary feature of a RateLimiter is its "stable rate", the maximum rate that
     * is should allow at normal conditions. This is enforced by "throttling" incoming
     * requests as needed, i.e. compute, for an incoming request, the appropriate throttle time,
     * and make the calling thread wait as much.
     *
     * The simplest way to maintain a rate of QPS is to keep the timestamp of the last
     * granted request, and ensure that (1/QPS) seconds have elapsed since then. For example,
     * for a rate of QPS=5 (5 tokens per second), if we ensure that a request isn't granted
     * earlier than 200ms after the last one, then we achieve the intended rate.
     * If a request comes and the last request was granted only 100ms ago, then we wait for
     * another 100ms. At this rate, serving 15 fresh permits (i.e. for an acquire(15) request)
     * naturally takes 3 seconds.
     *
     * It is important to realize that such a RateLimiter has a very superficial memory
     * of the past: it only remembers the last request. What if the RateLimiter was unused for
     * a long period of time, then a request arrived and was immediately granted?
     * This RateLimiter would immediately forget about that past underutilization. This may
     * result in either underutilization or overflow, depending on the real world consequences
     * of not using the expected rate.
     *
     * Past underutilization could mean that excess resources are available. Then, the RateLimiter
     * should speed up for a while, to take advantage of these resources. This is important
     * when the rate is applied to networking (limiting bandwidth), where past underutilization
     * typically translates to "almost empty buffers", which can be filled immediately.
     *
     * On the other hand, past underutilization could mean that "the server responsible for
     * handling the request has become less ready for future requests", i.e. its caches become
     * stale, and requests become more likely to trigger expensive operations (a more extreme
     * case of this example is when a server has just booted, and it is mostly busy with getting
     * itself up to speed).
     *
     * To deal with such scenarios, we add an extra dimension, that of "past underutilization",
     * modeled by "storedPermits" variable. This variable is zero when there is no
     * underutilization, and it can grow up to maxStoredPermits, for sufficiently large
     * underutilization. So, the requested permits, by an invocation acquire(permits),
     * are served from:
     * - stored permits (if available)
     * - fresh permits (for any remaining permits)
     *
     * How this works is best explained with an example:
     *
     * For a RateLimiter that produces 1 token per second, every second
     * that goes by with the RateLimiter being unused, we increase storedPermits by 1.
     * Say we leave the RateLimiter unused for 10 seconds (i.e., we expected a request at time
     * X, but we are at time X + 10 seconds before a request actually arrives; this is
     * also related to the point made in the last paragraph), thus storedPermits
     * becomes 10.0 (assuming maxStoredPermits >= 10.0). At that point, a request of acquire(3)
     * arrives. We serve this request out of storedPermits, and reduce that to 7.0 (how this is
     * translated to throttling time is discussed later). Immediately after, assume that an
     * acquire(10) request arriving. We serve the request partly from storedPermits,
     * using all the remaining 7.0 permits, and the remaining 3.0, we serve them by fresh permits
     * produced by the rate limiter.
     *
     * We already know how much time it takes to serve 3 fresh permits: if the rate is
     * "1 token per second", then this will take 3 seconds. But what does it mean to serve 7
     * stored permits? As explained above, there is no unique answer. If we are primarily
     * interested to deal with underutilization, then we want stored permits to be given out
     * /faster/ than fresh ones, because underutilization = free resources for the taking.
     * If we are primarily interested to deal with overflow, then stored permits could
     * be given out /slower/ than fresh ones. Thus, we require a (different in each case)
     * function that translates storedPermits to throtting time.
     *
     * This role is played by storedPermitsToWaitTime(double storedPermits, double permitsToTake).
     * The underlying model is a continuous function mapping storedPermits
     * (from 0.0 to maxStoredPermits) onto the 1/rate (i.e. intervals) that is effective at the given
     * storedPermits. "storedPermits" essentially measure unused time; we spend unused time
     * buying/storing permits. Rate is "permits / time", thus "1 / rate = time / permits".
     * Thus, "1/rate" (time / permits) times "permits" gives time, i.e., integrals on this
     * function (which is what storedPermitsToWaitTime() computes) correspond to minimum intervals
     * between subsequent requests, for the specified number of requested permits.
     *
     * Here is an example of storedPermitsToWaitTime:
     * If storedPermits == 10.0, and we want 3 permits, we take them from storedPermits,
     * reducing them to 7.0, and compute the throttling for these as a call to
     * storedPermitsToWaitTime(storedPermits = 10.0, permitsToTake = 3.0), which will
     * evaluate the integral of the function from 7.0 to 10.0.
     *
     * Using integrals guarantees that the effect of a single acquire(3) is equivalent
     * to { acquire(1); acquire(1); acquire(1); }, or { acquire(2); acquire(1); }, etc,
     * since the integral of the function in [7.0, 10.0] is equivalent to the sum of the
     * integrals of [7.0, 8.0], [8.0, 9.0], [9.0, 10.0] (and so on), no matter
     * what the function is. This guarantees that we handle correctly requests of varying weight
     * (permits), /no matter/ what the actual function is - so we can tweak the latter freely.
     * (The only requirement, obviously, is that we can compute its integrals).
     *
     * Note well that if, for this function, we chose a horizontal line, at height of exactly
     * (1/QPS), then the effect of the function is non-existent: we serve storedPermits at
     * exactly the same cost as fresh ones (1/QPS is the cost for each). We use this trick later.
     *
     * If we pick a function that goes /below/ that horizontal line, it means that we reduce
     * the area of the function, thus time. Thus, the RateLimiter becomes /faster/ after a
     * period of underutilization. If, on the other hand, we pick a function that
     * goes /above/ that horizontal line, then it means that the area (time) is increased,
     * thus storedPermits are more costly than fresh permits, thus the RateLimiter becomes
     * /slower/ after a period of underutilization.
     *
     * Last, but not least: consider a RateLimiter with rate of 1 permit per second, currently
     * completely unused, and an expensive acquire(100) request comes. It would be nonsensical
     * to just wait for 100 seconds, and /then/ start the actual task. Why wait without doing
     * anything? A much better approach is to /allow/ the request right away (as if it was an
     * acquire(1) request instead), and postpone /subsequent/ requests as needed. In this version,
     * we allow starting the task immediately, and postpone by 100 seconds future requests,
     * thus we allow for work to get done in the meantime instead of waiting idly.
     *
     * This has important consequences: it means that the RateLimiter doesn't remember the time
     * of the _last_ request, but it remembers the (expected) time of the _next_ request. This
     * also enables us to tell immediately (see tryAcquire(timeout)) whether a particular
     * timeout is enough to get us to the point of the next scheduling time, since we always
     * maintain that. And what we mean by "an unused RateLimiter" is also defined by that
     * notion: when we observe that the "expected arrival time of the next request" is actually
     * in the past, then the difference (now - past) is the amount of time that the RateLimiter
     * was formally unused, and it is that amount of time which we translate to storedPermits.
     * (We increase storedPermits with the amount of permits that would have been produced
     * in that idle time). So, if rate == 1 permit per second, and arrivals come exactly
     * one second after the previous, then storedPermits is _never_ increased -- we would only
     * increase it for arrivals _later_ than the expected one second.
     */

/**
 * This implements the following function:
 *
 *             ^ throttling
 *             |
 * 3*稳定的间隔  +                  /
 *             |                 /.
 *             |                / .
 *             |               /  .   <-- ”热机“区域是一个梯形的形式，最大存储令牌数和一半之间
 * 2*稳定的间隔  +              /   .
 *             |             /    .
 *             |            /     .
 *             |           /      .
 * 稳定的间隔    +----------/  WARM . }
 *             |          .   UP  . } <-- 这一部分就是一个矩形了，成为冷却过程，
 *             |          . PERIOD. }     我们希望冷却过程的时间等于热机时间
 *             |          .       . }     (from 0 to maxPermits, and height == stableInterval)
 *             |---------------------------------> storedPermits
 *                  (halfPermits) (maxPermits)
 *
 **/
}
