package com.hesi.snotes

/**
 * A growable list of primitive floats.
 *
 * Stroke points used to live in `ArrayList<Float>`, which boxes EVERY coordinate
 * into its own java.lang.Float object (16 bytes + a 4 byte reference instead of
 * 4 bytes). A busy page held hundreds of thousands of them, and every pen MOVE
 * allocated fresh boxes, so the garbage collector kept waking up while writing
 * and RAM climbed stroke after stroke. This class stores the raw values in one
 * FloatArray: ~5x less memory and zero allocations per point.
 *
 * Only the small subset of the List API the app actually uses is provided.
 */
class FloatList(initialCapacity: Int = 16) : Iterable<Float> {

    private var data = FloatArray(if (initialCapacity < 4) 4 else initialCapacity)

    var size = 0
        private set

    constructor(src: FloatList) : this(src.size) {
        System.arraycopy(src.data, 0, data, 0, src.size)
        size = src.size
    }

    constructor(src: FloatArray, count: Int = src.size) : this(count) {
        System.arraycopy(src, 0, data, 0, count)
        size = count
    }

    fun isEmpty() = size == 0
    fun isNotEmpty() = size != 0

    val indices: IntRange get() = 0 until size

    operator fun get(i: Int): Float {
        if (i >= size) throw IndexOutOfBoundsException("$i >= $size")
        return data[i]
    }

    operator fun set(i: Int, v: Float) {
        if (i >= size) throw IndexOutOfBoundsException("$i >= $size")
        data[i] = v
    }

    fun add(v: Float) {
        if (size == data.size) grow(size + 1)
        data[size++] = v
    }

    fun add(a: Float, b: Float) {
        if (size + 2 > data.size) grow(size + 2)
        data[size] = a
        data[size + 1] = b
        size += 2
    }

    fun addAll(other: FloatList) {
        if (size + other.size > data.size) grow(size + other.size)
        System.arraycopy(other.data, 0, data, size, other.size)
        size += other.size
    }

    fun last(): Float {
        if (size == 0) throw NoSuchElementException("empty")
        return data[size - 1]
    }

    fun clear() { size = 0 }

    /** Drops spare capacity once a stroke is finished (it never grows again). */
    fun trimToSize() {
        if (data.size > size + 8) data = data.copyOf(if (size < 4) 4 else size)
    }

    fun min(): Float {
        var m = Float.POSITIVE_INFINITY
        for (i in 0 until size) if (data[i] < m) m = data[i]
        return m
    }

    fun max(): Float {
        var m = Float.NEGATIVE_INFINITY
        for (i in 0 until size) if (data[i] > m) m = data[i]
        return m
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)

    /** Direct access for tight loops (valid up to [size]); do not keep it. */
    fun rawArray(): FloatArray = data

    private fun grow(min: Int) {
        var n = data.size + (data.size shr 1)
        if (n < min) n = min
        data = data.copyOf(n)
    }

    override fun iterator(): FloatIterator = object : FloatIterator() {
        private var i = 0
        override fun hasNext() = i < size
        override fun nextFloat() = data[i++]
    }

    companion object {
        fun of(vararg v: Float): FloatList = FloatList(v, v.size)
    }
}
