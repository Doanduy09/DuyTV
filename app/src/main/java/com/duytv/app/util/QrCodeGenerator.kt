package com.duytv.app.util

/**
 * Bộ sinh Mã QR chuẩn ISO/IEC 18004 (Byte Mode, Error Correction Level M, Version 1..4)
 * viết hoàn toàn bằng Kotlin thuần (0 thư viện ngoài, 0MB APK bloat).
 * Đủ sức mã hóa mọi URL mạng LAN như "http://192.168.1.172:8888" (tối đa 62 byte).
 */
object QrCodeGenerator {

    data class QrMatrix(val size: Int, val modules: BooleanArray) {
        operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
    }

    // Version 1..4, Error Correction Level M: (totalCodewords, ecCodewordsPerBlock, numBlocks, maxDataBytes)
    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val totalCodewords: Int,
        val ecPerBlock: Int,
        val numBlocks: Int,
        val dataCodewords: Int,
        val alignmentPos: IntArray
    )

    private val SPECS = arrayOf(
        VersionSpec(1, 21, 26, 10, 1, 16, intArrayOf()),
        VersionSpec(2, 25, 44, 16, 1, 28, intArrayOf(6, 18)),
        VersionSpec(3, 29, 70, 26, 1, 44, intArrayOf(6, 22)),
        VersionSpec(4, 33, 100, 18, 2, 64, intArrayOf(6, 26))
    )

    // Format info bits for Error Correction Level M (00) and Mask 0..7 (precomputed BCH(15,5) ^ 0x5412)
    private val FORMAT_INFO_M = intArrayOf(
        0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0
    )

    fun encode(text: String): QrMatrix {
        val rawBytes = text.toByteArray(Charsets.UTF_8)
        // Byte mode overhead = 4 bits mode + 8 bits len + 4 bits terminator = 2 bytes
        val spec = SPECS.firstOrNull { rawBytes.size + 2 <= it.dataCodewords }
            ?: SPECS.last()
        val payload = if (rawBytes.size + 2 <= spec.dataCodewords) {
            rawBytes
        } else {
            rawBytes.copyOf(spec.dataCodewords - 2)
        }

        val dataCodewords = buildDataCodewords(payload, spec.dataCodewords)
        val finalCodewords = interleaveWithEcc(dataCodewords, spec)

        // Build base matrix with function patterns
        val size = spec.size
        val isFunction = BooleanArray(size * size)
        val baseModules = BooleanArray(size * size)

        drawFinderPattern(baseModules, isFunction, size, 0, 0)
        drawFinderPattern(baseModules, isFunction, size, size - 7, 0)
        drawFinderPattern(baseModules, isFunction, size, 0, size - 7)
        drawTimingPatterns(baseModules, isFunction, size)
        drawAlignmentPatterns(baseModules, isFunction, size, spec.alignmentPos)
        reserveFormatAreas(baseModules, isFunction, size)

        // Place data bits in zig-zag columns
        placeDataBits(baseModules, isFunction, size, finalCodewords)

        // Pick best mask pattern (0..7) by lowest penalty score
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestModules = baseModules

        for (mask in 0..7) {
            val candidate = baseModules.copyOf()
            applyMask(candidate, isFunction, size, mask)
            drawFormatBits(candidate, size, FORMAT_INFO_M[mask])
            val penalty = computePenalty(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestModules = candidate
            }
        }
        return QrMatrix(size, bestModules)
    }

    private fun buildDataCodewords(payload: ByteArray, capacity: Int): ByteArray {
        val bits = ArrayList<Int>(capacity * 8)
        fun appendBits(value: Int, count: Int) {
            for (i in (count - 1) downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }

        // Mode indicator: Byte = 0100
        appendBits(0b0100, 4)
        // Character count indicator (8 bits for versions 1..9)
        appendBits(payload.size, 8)
        for (b in payload) {
            appendBits(b.toInt() and 0xFF, 8)
        }
        // Terminator up to 4 zero bits
        val rem = capacity * 8 - bits.size
        appendBits(0, minOf(4, rem))
        // Pad to byte boundary
        while (bits.size % 8 != 0) bits.add(0)

        val out = ByteArray(capacity)
        var byteIdx = 0
        while (byteIdx * 8 < bits.size) {
            var v = 0
            for (b in 0..7) {
                v = (v shl 1) or bits[byteIdx * 8 + b]
            }
            out[byteIdx++] = v.toByte()
        }
        // Pad codewords alternating 0xEC, 0x11
        var padToggle = true
        while (byteIdx < capacity) {
            out[byteIdx++] = (if (padToggle) 0xEC else 0x11).toByte()
            padToggle = !padToggle
        }
        return out
    }

    private fun interleaveWithEcc(data: ByteArray, spec: VersionSpec): ByteArray {
        val blockDataLen = spec.dataCodewords / spec.numBlocks
        val gen = rsGeneratorPoly(spec.ecPerBlock)
        val dataBlocks = Array(spec.numBlocks) { i ->
            data.copyOfRange(i * blockDataLen, (i + 1) * blockDataLen)
        }
        val ecBlocks = Array(spec.numBlocks) { i ->
            rsComputeRemainder(dataBlocks[i], gen)
        }

        val result = ByteArray(spec.totalCodewords)
        var idx = 0
        for (i in 0 until blockDataLen) {
            for (b in 0 until spec.numBlocks) {
                result[idx++] = dataBlocks[b][i]
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (b in 0 until spec.numBlocks) {
                result[idx++] = ecBlocks[b][i]
            }
        }
        return result
    }

    private fun gfMul(x: Int, y: Int): Int {
        var r = 0
        var a = x
        var b = y
        for (i in 0 until 8) {
            if ((b and 1) != 0) r = r xor a
            val hi = a and 0x80
            a = (a shl 1) and 0xFF
            if (hi != 0) a = a xor 0x1D
            b = b ushr 1
        }
        return r
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        val poly = IntArray(degree)
        poly[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                poly[j] = gfMul(poly[j], root)
                if (j + 1 < degree) {
                    poly[j] = poly[j] xor poly[j + 1]
                }
            }
            root = gfMul(root, 2)
        }
        return poly
    }

    private fun rsComputeRemainder(data: ByteArray, gen: IntArray): ByteArray {
        val deg = gen.size
        val rem = IntArray(deg)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            System.arraycopy(rem, 1, rem, 0, deg - 1)
            rem[deg - 1] = 0
            for (i in 0 until deg) {
                rem[i] = rem[i] xor gfMul(gen[i], factor)
            }
        }
        return ByteArray(deg) { rem[it].toByte() }
    }

    private fun setFunc(m: BooleanArray, f: BooleanArray, size: Int, x: Int, y: Int, dark: Boolean) {
        if (x in 0 until size && y in 0 until size) {
            m[y * size + x] = dark
            f[y * size + x] = true
        }
    }

    private fun drawFinderPattern(m: BooleanArray, f: BooleanArray, size: Int, ox: Int, oy: Int) {
        for (dy in -1..7) {
            for (dx in -1..7) {
                val x = ox + dx
                val y = oy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val dist = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
                val dark = (dx in 0..6 && dy in 0..6) && (dist == 0 || dist == 1 || dist == 3)
                setFunc(m, f, size, x, y, dark)
            }
        }
    }

    private fun drawTimingPatterns(m: BooleanArray, f: BooleanArray, size: Int) {
        for (i in 8 until size - 8) {
            val dark = (i % 2 == 0)
            if (!f[6 * size + i]) setFunc(m, f, size, i, 6, dark)
            if (!f[i * size + 6]) setFunc(m, f, size, 6, i, dark)
        }
    }

    private fun drawAlignmentPatterns(m: BooleanArray, f: BooleanArray, size: Int, positions: IntArray) {
        for (cy in positions) {
            for (cx in positions) {
                if (f[cy * size + cx]) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunc(m, f, size, cx + dx, cy + dy, dist != 1)
                    }
                }
            }
        }
    }

    private fun reserveFormatAreas(m: BooleanArray, f: BooleanArray, size: Int) {
        for (i in 0..8) {
            if (i != 6) {
                setFunc(m, f, size, 8, i, false)
                setFunc(m, f, size, i, 8, false)
            }
        }
        for (i in 0..7) {
            setFunc(m, f, size, size - 1 - i, 8, false)
            setFunc(m, f, size, 8, size - 1 - i, false)
        }
        // Dark module always at (8, size - 8)
        setFunc(m, f, size, 8, size - 8, true)
    }

    private fun drawFormatBits(m: BooleanArray, size: Int, formatBits: Int) {
        fun bit(i: Int): Boolean = ((formatBits ushr i) and 1) != 0
        // Around top-left finder
        for (i in 0..5) m[i * size + 8] = bit(i)
        m[7 * size + 8] = bit(6)
        m[8 * size + 8] = bit(7)
        m[8 * size + 7] = bit(8)
        for (i in 9..14) m[8 * size + (14 - i)] = bit(i)

        // Around top-right & bottom-left finders
        for (i in 0..7) m[8 * size + (size - 1 - i)] = bit(i)
        for (i in 8..14) m[(size - 15 + i) * size + 8] = bit(i)
        m[(size - 8) * size + 8] = true
    }

    private fun placeDataBits(m: BooleanArray, f: BooleanArray, size: Int, data: ByteArray) {
        var bitIdx = 0
        val totalBits = data.size * 8
        var upward = true
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right-- // Skip vertical timing column
            for (vert in 0 until size) {
                val y = if (upward) size - 1 - vert else vert
                for (j in 0..1) {
                    val x = right - j
                    if (!f[y * size + x]) {
                        val dark = if (bitIdx < totalBits) {
                            ((data[bitIdx ushr 3].toInt() ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else {
                            false
                        }
                        m[y * size + x] = dark
                        bitIdx++
                    }
                }
            }
            upward = !upward
            right -= 2
        }
    }

    private fun applyMask(m: BooleanArray, f: BooleanArray, size: Int, mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (f[y * size + x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> ((y / 2) + (x / 3)) % 2 == 0
                    5 -> (x * y) % 2 + (x * y) % 3 == 0
                    6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                    7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                    else -> false
                }
                if (invert) {
                    m[y * size + x] = !m[y * size + x]
                }
            }
        }
    }

    private fun computePenalty(m: BooleanArray, size: Int): Int {
        var penalty = 0
        // Rule 1: 5+ consecutive same-color modules in row/col
        for (y in 0 until size) {
            var runColor = m[y * size]
            var runLen = 1
            for (x in 1 until size) {
                if (m[y * size + x] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = m[y * size + x]
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }
        for (x in 0 until size) {
            var runColor = m[x]
            var runLen = 1
            for (y in 1 until size) {
                if (m[y * size + x] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = m[y * size + x]
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }
        // Rule 2: 2x2 blocks
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = m[y * size + x]
                if (c == m[y * size + x + 1] && c == m[(y + 1) * size + x] && c == m[(y + 1) * size + x + 1]) {
                    penalty += 3
                }
            }
        }
        return penalty
    }
}
