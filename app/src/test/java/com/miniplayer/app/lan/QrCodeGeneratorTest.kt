package com.miniplayer.app.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QrCodeGeneratorTest {

    @Test
    fun capacitiesMatchIso18004Table7AcrossVersions1To10() {
        val expectedByteCapM = listOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213)
        val expectedByteCapL = listOf(17, 32, 53, 78, 106, 134, 154, 192, 230, 271)

        for (v in 1..10) {
            val headerBits = 4 + QrCodeGenerator.charCountBits(v)
            val actualM = (QrCodeGenerator.getNumDataCodewords(v, QrCodeGenerator.EccLevel.M) * 8 - headerBits) / 8
            val actualL = (QrCodeGenerator.getNumDataCodewords(v, QrCodeGenerator.EccLevel.L) * 8 - headerBits) / 8
            assertEquals("Version $v-M Byte capacity", expectedByteCapM[v - 1], actualM)
            assertEquals("Version $v-L Byte capacity", expectedByteCapL[v - 1], actualL)
        }
    }

    @Test
    fun encodesLanStudioHttpUrlsWithZeroReedSolomonSyndromesAndExactDecode() {
        val samples = listOf(
            "http://192.168.1.42:8088",
            "http://192.168.1.42:8088/?token=4826",
            "http://10.0.0.15:8088/",
            "http://172.16.10.8:8088/api/stream?itemId=1024",
            "MiniPlayer 局域网管理台 http://192.168.31.88:8088",
            "A".repeat(140),
        )

        for (payload in samples) {
            val qr = QrCodeGenerator.encode(payload)
            assertEquals(17 + 4 * qr.version, qr.size)
            assertFinderAndDarkModuleInvariants(qr)
            val decoded = decodeMatrixAndVerifyReedSolomon(qr)
            assertEquals(payload, decoded)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPayloadBeyondVersion10() {
        QrCodeGenerator.encode("X".repeat(300))
    }

    private fun assertFinderAndDarkModuleInvariants(qr: QrCodeGenerator.QrMatrix) {
        val n = qr.size
        for ((cx, cy) in listOf(3 to 3, (n - 4) to 3, 3 to (n - 4))) {
            assertTrue(qr[cx, cy])
            for (dy in -3..3) {
                for (dx in -3..3) {
                    val dist = maxOf(abs(dx), abs(dy))
                    assertEquals(dist != 2, qr[cx + dx, cy + dy])
                }
            }
        }
        for (i in 0..7) {
            assertFalse(qr[i, 7])
            assertFalse(qr[7, i])
        }
        assertTrue(qr[8, 4 * qr.version + 9])
    }

    private fun decodeMatrixAndVerifyReedSolomon(qr: QrCodeGenerator.QrMatrix): String {
        val n = qr.size
        val version = (n - 17) / 4

        var fmtCopy1 = 0
        for (i in 0..5) if (qr[8, i]) fmtCopy1 = fmtCopy1 or (1 shl i)
        if (qr[8, 7]) fmtCopy1 = fmtCopy1 or (1 shl 6)
        if (qr[8, 8]) fmtCopy1 = fmtCopy1 or (1 shl 7)
        if (qr[7, 8]) fmtCopy1 = fmtCopy1 or (1 shl 8)
        for (i in 9..14) if (qr[14 - i, 8]) fmtCopy1 = fmtCopy1 or (1 shl i)

        var fmtCopy2 = 0
        for (i in 0..7) if (qr[n - 1 - i, 8]) fmtCopy2 = fmtCopy2 or (1 shl i)
        for (i in 8..14) if (qr[8, n - 15 + i]) fmtCopy2 = fmtCopy2 or (1 shl i)

        assertEquals(fmtCopy1, fmtCopy2)

        val unmaskedFmt = fmtCopy1 xor 0x5412
        var bchRem = unmaskedFmt
        for (bit in 14 downTo 10) {
            if (((bchRem ushr bit) and 1) != 0) {
                bchRem = bchRem xor (0x537 shl (bit - 10))
            }
        }
        assertEquals(0, bchRem)

        val data5 = unmaskedFmt ushr 10
        val ecBits = (data5 ushr 3) and 0b11
        val mask = data5 and 0b111
        val ecc = QrCodeGenerator.EccLevel.entries.first { it.formatBits == ecBits }

        val isFunc = Array(n) { BooleanArray(n) }
        for (i in 0 until n) {
            isFunc[i][6] = true
            isFunc[6][i] = true
        }
        for ((cx, cy) in listOf(3 to 3, (n - 4) to 3, 3 to (n - 4))) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val xx = cx + dx
                    val yy = cy + dy
                    if (xx in 0 until n && yy in 0 until n) isFunc[yy][xx] = true
                }
            }
        }
        val alignCoords = when (version) {
            1 -> intArrayOf()
            in 2..6 -> intArrayOf(6, 10 + 4 * version)
            7 -> intArrayOf(6, 22, 38)
            8 -> intArrayOf(6, 24, 42)
            9 -> intArrayOf(6, 26, 46)
            10 -> intArrayOf(6, 28, 50)
            else -> error("Unexpected version $version")
        }
        for (i in alignCoords.indices) {
            for (j in alignCoords.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == alignCoords.lastIndex) || (i == alignCoords.lastIndex && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) isFunc[alignCoords[j] + dy][alignCoords[i] + dx] = true
            }
        }
        for (i in 0..8) {
            isFunc[i][8] = true
            isFunc[8][i] = true
        }
        for (i in 0..7) isFunc[8][n - 1 - i] = true
        for (i in 0..7) isFunc[n - 1 - i][8] = true
        if (version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    isFunc[i][n - 11 + j] = true
                    isFunc[n - 11 + j][i] = true
                }
            }
        }

        val rawBits = ArrayList<Int>()
        var right = n - 1
        var upward = true
        while (right >= 1) {
            if (right == 6) right = 5
            val ys = if (upward) (n - 1 downTo 0) else (0 until n)
            for (y in ys) {
                for (dx in 0..1) {
                    val x = right - dx
                    if (!isFunc[y][x]) {
                        val unmasked = qr[x, y] xor QrCodeGenerator.maskBit(mask, x, y)
                        rawBits.add(if (unmasked) 1 else 0)
                    }
                }
            }
            upward = !upward
            right -= 2
        }

        val totalCodewords = rawBits.size / 8
        val interleaved = ByteArray(totalCodewords)
        for (i in 0 until totalCodewords) {
            var b = 0
            for (k in 0..7) b = (b shl 1) or rawBits[i * 8 + k]
            interleaved[i] = b.toByte()
        }

        val blocks = QrCodeGenerator.numBlocks(version, ecc)
        val ecLen = QrCodeGenerator.ecCodewordsPerBlock(version, ecc)
        val numShort = blocks - (totalCodewords % blocks)
        val shortDataLen = (totalCodewords / blocks) - ecLen

        val dataBlocks = Array(blocks) { b -> ByteArray(shortDataLen + if (b < numShort) 0 else 1) }
        val ecBlocks = Array(blocks) { ByteArray(ecLen) }
        var pos = 0
        val maxDataLen = shortDataLen + if (numShort < blocks) 1 else 0
        for (col in 0 until maxDataLen) {
            for (b in 0 until blocks) {
                if (col < dataBlocks[b].size) dataBlocks[b][col] = interleaved[pos++]
            }
        }
        for (col in 0 until ecLen) {
            for (b in 0 until blocks) {
                ecBlocks[b][col] = interleaved[pos++]
            }
        }

        val fullData = ArrayList<Byte>()
        for (b in 0 until blocks) {
            val cw = dataBlocks[b] + ecBlocks[b]
            var alphaR = 1
            for (r in 0 until ecLen) {
                var eval = 0
                for (byteVal in cw) {
                    eval = QrCodeGenerator.gfMultiply(eval, alphaR) xor (byteVal.toInt() and 0xFF)
                }
                assertEquals(0, eval)
                alphaR = QrCodeGenerator.gfMultiply(alphaR, 0x02)
            }
            val expectedEc = QrCodeGenerator.computeReedSolomonRemainder(
                dataBlocks[b],
                QrCodeGenerator.computeReedSolomonDivisor(ecLen),
            )
            assertArrayEquals(expectedEc, ecBlocks[b])
            for (d in dataBlocks[b]) fullData.add(d)
        }

        val dataBits = IntArray(fullData.size * 8)
        for (i in fullData.indices) {
            val v = fullData[i].toInt() and 0xFF
            for (k in 0..7) dataBits[i * 8 + k] = (v ushr (7 - k)) and 1
        }
        var bp = 0
        fun readBits(count: Int): Int {
            var acc = 0
            repeat(count) { acc = (acc shl 1) or dataBits[bp++] }
            return acc
        }
        assertEquals(0b0100, readBits(4))
        val byteLen = readBits(QrCodeGenerator.charCountBits(version))
        val payloadBytes = ByteArray(byteLen)
        for (i in 0 until byteLen) {
            payloadBytes[i] = readBits(8).toByte()
        }
        return String(payloadBytes, Charsets.UTF_8)
    }
}
