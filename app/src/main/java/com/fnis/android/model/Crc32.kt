package com.fnis.android.model

object Crc32 {

    private val table = IntArray(256)

    init {
        val poly = 0xEDB88320.toInt()
        for (i in 0 until 256) {
            var crc = i
            for (j in 0 until 8) {
                if ((crc and 1) == 1) {
                    crc = (crc ushr 1) xor poly
                } else {
                    crc = crc ushr 1
                }
            }
            table[i] = crc
        }
    }

    fun calculate(data: ByteArray): Int {
        var crc: Int = -1
        for (b in data) {
            val index = ((crc xor (b.toInt() and 0xFF)) and 0xFF)
            crc = (crc ushr 8) xor table[index]
        }
        return crc xor -1
    }

    fun calculateString(text: String): Int {
        return calculate(text.toByteArray(Charsets.UTF_8))
    }

    fun toUnsignedString(value: Int): String {
        return (value.toLong() and 0xFFFFFFFFL).toString()
    }
}
