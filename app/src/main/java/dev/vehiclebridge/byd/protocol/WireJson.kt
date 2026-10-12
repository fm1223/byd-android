package dev.vehiclebridge.byd.protocol

object WireJson {
    fun encode(value: Any?): String = when(value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { quote(it.key as String) + ":" + encode(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> error("Unsupported wire value")
    }
    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when (c.code) {
            34 -> append("\\\"")
            92 -> append("\\\\")
            8 -> append("\\b")
            12 -> append("\\f")
            10 -> append("\\n")
            13 -> append("\\r")
            9 -> append("\\t")
            else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}
