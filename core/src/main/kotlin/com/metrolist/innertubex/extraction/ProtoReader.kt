package com.metrolist.innertubex.extraction

private const val MAX_PROTOBUF_FIELD_NUMBER = 536_870_911
private const val FIRST_RESERVED_FIELD_NUMBER = 19_000
private const val LAST_RESERVED_FIELD_NUMBER = 19_999

private fun isValidProtobufFieldNumber(field: Long): Boolean =
    field in 1..MAX_PROTOBUF_FIELD_NUMBER.toLong() && field !in FIRST_RESERVED_FIELD_NUMBER.toLong()..LAST_RESERVED_FIELD_NUMBER.toLong()

internal class ProtoReader(
    private val data: ByteArray,
    from: Int = 0,
    private val limit: Int = data.size,
) {
    init {
        require(from >= 0 && from <= limit && limit <= data.size) { "Invalid protobuf input range" }
    }

    var position: Int = from
        private set

    val hasRemaining: Boolean
        get() = position < limit

    fun tag(): ProtoTag {
        val raw = varint()
        val field = raw ushr 3
        if (!isValidProtobufFieldNumber(field)) throw IllegalStateException("Invalid protobuf field number $field")
        return ProtoTag(field = field.toInt(), wireType = (raw and 7).toInt())
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        repeat(10) {
            if (position >= limit) throw IllegalStateException("Truncated protobuf varint")
            val byte = data[position++].toInt() and 0xff
            if (shift == 63 && ((byte and 0x7f) > 1 || (byte and 0x80) != 0)) {
                throw IllegalStateException("Protobuf varint overflows 64 bits")
            }
            result = result or ((byte and 0x7f).toLong() shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalStateException("Protobuf varint is too long")
    }


    fun bytes(maxSize: Int = Int.MAX_VALUE): ByteArray {
        val length = varint()
        if (length < 0 || length > Int.MAX_VALUE) throw IllegalStateException("Invalid protobuf length $length")
        if (length > maxSize) throw IllegalStateException("Protobuf field exceeded $maxSize bytes")
        val end = position + length.toInt()
        if (end < position || end > limit) throw IllegalStateException("Truncated protobuf field")
        return data.copyOfRange(position, end).also { position = end }
    }

    fun string(): String = bytes().decodeToString()

    fun skip(tag: ProtoTag) {
        when (tag.wireType) {
            0 -> varint()
            1 -> advance(8)
            2 -> advanceLengthDelimited()
            5 -> advance(4)
            else -> throw IllegalStateException("Unsupported protobuf wire type ${tag.wireType}")
        }
    }

    private fun advanceLengthDelimited() {
        val length = varint()
        if (length < 0 || length > Int.MAX_VALUE) throw IllegalStateException("Invalid protobuf length $length")
        advance(length.toInt())
    }

    private fun advance(count: Int) {
        val end = position + count
        if (end < position || end > limit) throw IllegalStateException("Truncated protobuf field")
        position = end
    }
}

internal data class ProtoTag(
    val field: Int,
    val wireType: Int,
)
