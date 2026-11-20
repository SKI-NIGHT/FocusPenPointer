package io.github.skinight.focuspenpointer.hook.pointer

/**
 * Recent pointer positions as actually drawn (after Xiaomi's screen clamping), so the
 * module can ask "where was the pointer N ms ago?".
 *
 * Positions, not deltas: while the pointer is pinned against an edge the pen keeps reporting
 * motion that never moves it. Replaying deltas would undo motion that never happened and pull
 * the pointer off the edge; positions simply stay put. Thread-safe.
 */
internal class PositionHistory(
    private val clock: () -> Long,
    private val capacity: Int = 128,
) {
    private val times = LongArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private var next = 0
    private var count = 0

    @Synchronized
    fun record(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        times[next] = clock()
        xs[next] = x
        ys[next] = y
        next = (next + 1) % capacity
        if (count < capacity) count += 1
    }

    @Synchronized
    fun latest(): CursorPoint? {
        if (count == 0) return null
        val index = (next - 1 + capacity) % capacity
        return CursorPoint(xs[index], ys[index])
    }

    /**
     * The position the pointer had at [time]: the newest sample not later than it. Null when
     * the history does not reach back that far.
     */
    @Synchronized
    fun positionAt(time: Long): CursorPoint? {
        for (i in 0 until count) {
            val index = (next - 1 - i + capacity) % capacity
            if (times[index] <= time) return CursorPoint(xs[index], ys[index])
        }
        return null
    }

    /** Every sample recorded at or after [time], oldest first. */
    @Synchronized
    fun since(time: Long): List<CursorPoint> {
        val result = ArrayList<CursorPoint>()
        for (i in count - 1 downTo 0) {
            val index = (next - 1 - i + capacity) % capacity
            if (times[index] >= time) result += CursorPoint(xs[index], ys[index])
        }
        return result
    }

    /** Every sample recorded in [from, until] with its time, oldest first. */
    @Synchronized
    fun samplesBetween(from: Long, until: Long): List<Sample> {
        val result = ArrayList<Sample>()
        for (i in count - 1 downTo 0) {
            val index = (next - 1 - i + capacity) % capacity
            val time = times[index]
            if (time in from..until) result += Sample(time, CursorPoint(xs[index], ys[index]))
        }
        return result
    }

    data class Sample(val time: Long, val point: CursorPoint)

    @Synchronized
    fun clear() {
        next = 0
        count = 0
    }
}
