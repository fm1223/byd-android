package dev.vehiclebridge.byd.protocol

import java.util.Base64
import java.util.Properties

/** China WBSK byte-domain implementation, based on the local BYD-re/pybyd_china reference. */
object Wbsk {
    private val properties = Properties().apply {
        requireNotNull(Wbsk::class.java.getResourceAsStream("/wbsk.properties")).use { load(it) }
    }
    private fun bytes(name: String) = Base64.getDecoder().decode(properties.getProperty(name))
    private fun hex(value: String): IntArray {
        require(value.length % 2 == 0 && Regex("[a-fA-F0-9]+").matches(value))
        return value.chunked(2).map { it.toInt(16) }.toIntArray()
    }
    private fun unsigned(value: ByteArray) = value.map { it.toInt() and 255 }.toIntArray()
    private fun binary(value: IntArray) = value.map { it.toByte() }.toByteArray()
    private val encode = intArrayOf(0,8,4,12,1,9,5,13,2,10,6,14,3,11,7,15)
    private val decode = intArrayOf(0,4,8,12,2,6,10,14,1,5,9,13,3,7,11,15)
    private val mystery = IntArray(16) { encode[encode[it xor 8]] }
    private val transform = IntArray(16) { encode[encode[it]] }
    private fun nibbles(value: IntArray, table: IntArray) = IntArray(value.size) { (table[value[it] ushr 4] shl 4) or table[value[it] and 15] }
    private fun xor(table: IntArray, a: Int, b: Int): Int =
        (table[((a ushr 4) shl 4) xor (b ushr 4)] and 240) or ((table[((a and 15) shl 4) xor (b and 15)] ushr 4) and 15)
    private data class Key(val data: IntArray, val rounds: Int)
    private fun iv(name: String): IntArray {
        val value = properties.getProperty(name)
        return hex(value.take(value.length / 2 * 2))
    }
    private fun key(name: String): Key = parseKey(properties.getProperty(name))
    private fun parseKey(blob: String): Key {
        val raw = hex(blob)
        require(raw.size >= 5)
        val mode = raw[0] xor raw[3]
        // Embedded China keys use AES-256 modes 0x10/0x11, 14 rounds.
        require(mode == 16 || mode == 17)
        val data = IntArray(raw.size - 4) { raw[it+4] xor raw[(it+4)%3] }
        require(data.size >= 240)
        return Key(data, 14)
    }
    private class Tables(prefix: String, inverse: Boolean) {
        val initial = unsigned(bytes(prefix+"InitXor"))
        val round = unsigned(bytes(prefix+"RoundXor"))
        val final = unsigned(bytes(prefix+"FinalXor"))
        val box = unsigned(bytes(if(inverse) "decInvSbox" else "encSbox"))
        val words = Array(4) { index ->
            val raw = unsigned(bytes(prefix+(if(inverse) "Td" else "Te")+index))
            require(raw.size == 1024)
            IntArray(256) { i -> raw[i*4] or (raw[i*4+1] shl 8) or (raw[i*4+2] shl 16) or (raw[i*4+3] shl 24) }
        }
        init { require(listOf(initial,round,final,box).all { it.size == 256 }) }
    }
    private val enc = Tables("enc",false)
    private val dec = Tables("dec",true)
    private fun block(input: IntArray, key: Key, inverse: Boolean): IntArray {
        require(input.size == 16)
        val t = if(inverse) dec else enc
        val state = IntArray(16) { xor(t.initial,input[it],key.data[it]) }
        val offsets = if(inverse) arrayOf(intArrayOf(0,4,8,12),intArrayOf(13,1,5,9),intArrayOf(10,14,2,6),intArrayOf(7,11,15,3))
            else arrayOf(intArrayOf(0,4,8,12),intArrayOf(5,9,13,1),intArrayOf(10,14,2,6),intArrayOf(15,3,7,11))
        for(r in 1 until key.rounds) {
            val combined = IntArray(16)
            for(tableIndex in 0..3) for(c in 0..3) {
                val word = t.words[tableIndex][state[offsets[tableIndex][c]]]
                for(j in 0..3) {
                    val byte = (word ushr (24-j*8)) and 255
                    combined[c*4+j] = if(tableIndex==0) byte else xor(t.round,combined[c*4+j],byte)
                }
            }
            for(i in 0..15) state[i] = xor(t.round,combined[i],key.data[r*16+i])
        }
        val shift = if(inverse) intArrayOf(0,13,10,7,4,1,14,11,8,5,2,15,12,9,6,3) else intArrayOf(0,5,10,15,4,9,14,3,8,13,2,7,12,1,6,11)
        return IntArray(16) { xor(t.final,t.box[state[shift[it]]],key.data[key.rounds*16+it]) }
    }
    private fun cbc(input: IntArray, key: Key, iv: IntArray, inverse: Boolean): IntArray {
        require(input.size % 16 == 0 && iv.size == 16)
        var previous = iv
        val out = IntArray(input.size)
        for(offset in input.indices step 16) {
            val chunk = input.copyOfRange(offset,offset+16)
            val processed = block(if(inverse) chunk else IntArray(16) { chunk[it] xor previous[it] },key,inverse)
            for(i in 0..15) out[offset+i] = if(inverse) processed[i] xor previous[i] else processed[i]
            previous = if(inverse) chunk else processed
        }
        return out
    }
    private fun pad(value: IntArray): IntArray {
        val count = 16-value.size%16
        val byte = (mystery[count ushr 4] shl 4) or mystery[count and 15]
        return value + IntArray(count) { byte }
    }
    private fun strip(value: IntArray): IntArray {
        require(value.isNotEmpty())
        val count=value.last()
        require(count in 1..16 && count<=value.size && value.takeLast(count).all { it==count })
        return value.copyOf(value.size-count)
    }
    fun encrypt(plaintext: String): String = encryptWithKeys(plaintext,properties.getProperty("innerEncryptKey"),properties.getProperty("innerEncryptIv"),properties.getProperty("outerEncryptKey"),properties.getProperty("outerEncryptIv"))
    internal fun encryptWithKeys(plaintext: String, innerKey: String, innerIv: String, outerKey: String, outerIv: String): String {
        val iv=hex(innerIv.take(innerIv.length / 2 * 2))
        val inner=cbc(pad(nibbles(unsigned(plaintext.toByteArray(Charsets.UTF_8)),mystery)),parseKey(innerKey),iv,false)
        val innerB64=Base64.getEncoder().encodeToString(binary(nibbles(inner,transform)))
        val content=unsigned(innerB64.toByteArray(Charsets.ISO_8859_1))+nibbles(iv,transform)
        val outer=cbc(pad(nibbles(content,mystery)),parseKey(outerKey),hex(outerIv.take(outerIv.length / 2 * 2)),false)
        return Base64.getEncoder().encodeToString(binary(nibbles(outer,transform)))
    }
    fun decrypt(envelope: String): String {
        require(envelope.length<=4_000_000)
        val raw=unsigned(Base64.getDecoder().decode(envelope.trim()))
        require(raw.isNotEmpty() && raw.size%16==0)
        val outer=cbc(nibbles(raw,encode)+IntArray(256),key("outerDecryptKey"),iv("outerDecryptIv"),true)
        val content=strip(nibbles(outer.copyOf(raw.size),decode))
        require(content.size>16)
        val size=content.size-16
        val innerRaw=unsigned(Base64.getDecoder().decode(binary(content.copyOf(size))))
        require(innerRaw.isNotEmpty() && innerRaw.size%16==0)
        val inner=cbc(nibbles(innerRaw,encode)+IntArray(256),key("innerDecryptKey"),outer.copyOfRange(size,size+16),true)
        return binary(strip(nibbles(inner.copyOf(innerRaw.size),decode))).toString(Charsets.UTF_8)
    }
}
