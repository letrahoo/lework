@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.lework.platform

import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Random
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmClipboardImageTest {

    private fun createSolidBufferedImage(width: Int, height: Int, color: Color): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = color
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return img
    }

    private fun encodeBufferedImage(img: BufferedImage, format: String): ByteArray {
        val baos = ByteArrayOutputStream()
        assertTrue(ImageIO.write(img, format, baos), "Expected ImageIO to write $format")
        return baos.toByteArray()
    }

    @Test
    fun `extracts BufferedImage and selects highest resolution variant from MultiResolutionImage`() {
        val sample = createSolidBufferedImage(120, 80, Color(0x83, 0xC9, 0x95))
        val imageTransferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
                return sample
            }
        }

        val payloads = JvmDesktopRuntime.extractImagesFromTransferable(imageTransferable)
        assertEquals(1, payloads.size)
        val payload = payloads.first()
        assertEquals("image/png", payload.mimeType)
        assertTrue(payload.fileName.endsWith(".png"))
        assertTrue(payload.dataBase64.isNotBlank())
        assertTrue(!payload.thumbnailBase64.isNullOrBlank())

        val decoded = decodeImageBytes(Base64.decode(payload.dataBase64))
        assertNotNull(decoded)
        assertEquals(120, decoded.width)
        assertEquals(80, decoded.height)

        // Retina MultiResolutionImage should pick the 2x (320x200) variant over 1x (160x100)
        val lowRes = createSolidBufferedImage(160, 100, Color(0xE6, 0xC3, 0x84))
        val highRes = createSolidBufferedImage(320, 200, Color(0x7F, 0xBB, 0xB3))
        val multiRes = BaseMultiResolutionImage(lowRes, highRes)
        val retinaTransferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
                return multiRes
            }
        }

        val retinaPayloads = JvmDesktopRuntime.extractImagesFromTransferable(retinaTransferable)
        assertEquals(1, retinaPayloads.size)
        val retinaDecoded = decodeImageBytes(Base64.decode(retinaPayloads.first().dataBase64))
        assertNotNull(retinaDecoded)
        assertEquals(320, retinaDecoded.width)
        assertEquals(200, retinaDecoded.height)
    }

    @Test
    fun `extracts only image files from javaFileListFlavor and honors maxCount`() {
        val tempDir = Files.createTempDirectory("lework-clip-files-").toFile()
        try {
            val txtFile = File(tempDir, "notes.txt").apply { writeText("plain text file") }
            val png1 = File(tempDir, "screen_one.png").apply {
                writeBytes(encodeBufferedImage(createSolidBufferedImage(64, 48, Color.RED), "png"))
            }
            val jpg1 = File(tempDir, "photo_two.jpg").apply {
                val rgb = BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB)
                writeBytes(encodeBufferedImage(rgb, "jpg"))
            }
            val png2 = File(tempDir, "screen_three.png").apply {
                writeBytes(encodeBufferedImage(createSolidBufferedImage(50, 50, Color.BLUE), "png"))
            }

            val filesTransferable = object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
                override fun getTransferData(flavor: DataFlavor): Any {
                    if (flavor != DataFlavor.javaFileListFlavor) throw UnsupportedFlavorException(flavor)
                    return listOf(txtFile, png1, jpg1, png2)
                }
            }

            val limited = JvmDesktopRuntime.extractImagesFromTransferable(filesTransferable, maxCount = 2)
            assertEquals(2, limited.size)
            assertEquals(listOf("screen_one.png", "photo_two.jpg"), limited.map { it.fileName })
            assertEquals("image/png", limited[0].mimeType)
            assertEquals("image/jpeg", limited[1].mimeType)
            assertTrue(limited.all { it.dataBase64.isNotBlank() && !it.thumbnailBase64.isNullOrBlank() })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `returns empty list for plain text transferable so normal text paste is never hijacked`() {
        val textSelection = StringSelection("请帮我 review 这段代码 https://github.com/letrahoo/lework")
        val extracted = JvmDesktopRuntime.extractImagesFromTransferable(textSelection)
        assertTrue(extracted.isEmpty(), "Expected plain text clipboard to yield zero images")
    }

    @Test
    fun `downscales oversized image above 700KB to JPEG within 1600px limit`() {
        // 1. Realistic detailed 2200x1650 image (smooth gradient + UI blocks + fine texture) exceeding 700KB raw PNG
        val width = 2200
        val height = 1650
        val detailed = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val rng = Random(42L)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val baseR = (x * 255 / width)
                val baseG = (y * 255 / height)
                val baseB = ((x + y) * 127 / (width + height))
                val jitter = (rng.nextInt(19) - 9)
                val r = (baseR + jitter).coerceIn(0, 255)
                val g = (baseG + jitter).coerceIn(0, 255)
                val b = (baseB + jitter).coerceIn(0, 255)
                pixels[y * width + x] = (r shl 16) or (g shl 8) or b
            }
        }
        detailed.setRGB(0, 0, width, height, pixels, 0, width)
        val rawPngBytes = encodeBufferedImage(detailed, "png")
        assertTrue(rawPngBytes.size > 700_000, "Expected test PNG to exceed 700KB, got ${rawPngBytes.size}")

        val payload = JvmDesktopRuntime.encodePickedImageBytes(
            rawBytes = rawPngBytes,
            fileName = "huge_screenshot.png",
            rawMime = "image/png",
        )
        assertNotNull(payload)
        assertEquals("image/jpeg", payload.mimeType)
        assertTrue(
            payload.dataBase64.length < 1_000_000,
            "Expected base64 payload under 1MB protocol limit, got ${payload.dataBase64.length}",
        )
        assertTrue(!payload.thumbnailBase64.isNullOrBlank())

        val decodedMain = decodeImageBytes(Base64.decode(payload.dataBase64))
        assertNotNull(decodedMain)
        assertTrue(decodedMain.width <= 1600 && decodedMain.height <= 1600)
        assertEquals(1600, maxOf(decodedMain.width, decodedMain.height))

        val decodedThumb = decodeImageBytes(Base64.decode(payload.thumbnailBase64!!))
        assertNotNull(decodedThumb)
        assertTrue(decodedThumb.width <= 220 && decodedThumb.height <= 220)

        // 2. Worst-case incompressible pure white-noise image MUST still compress under 1MB protocol limit via ladder
        val noisy = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val noisyPixels = IntArray(width * height) {
            (rng.nextInt(256) shl 16) or (rng.nextInt(256) shl 8) or rng.nextInt(256)
        }
        noisy.setRGB(0, 0, width, height, noisyPixels, 0, width)
        val noisyPng = encodeBufferedImage(noisy, "png")
        val noisyPayload = JvmDesktopRuntime.encodePickedImageBytes(noisyPng, "noisy.png", "image/png")
        assertNotNull(noisyPayload)
        assertEquals("image/jpeg", noisyPayload.mimeType)
        assertTrue(
            noisyPayload.dataBase64.length < 1_000_000,
            "Expected worst-case noisy image base64 under 1MB, got ${noisyPayload.dataBase64.length}",
        )
    }

    @Test
    fun `extracts direct image MIME stream flavor when standard imageFlavor is absent`() {
        val pngBytes = encodeBufferedImage(createSolidBufferedImage(96, 64, Color(0xA7, 0xC0, 0x80)), "png")
        val pngStreamFlavor = DataFlavor("image/png; class=java.io.InputStream", "PNG Stream")
        val streamTransferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(pngStreamFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == pngStreamFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (flavor != pngStreamFlavor) throw UnsupportedFlavorException(flavor)
                return ByteArrayInputStream(pngBytes) as InputStream
            }
        }

        val payloads = JvmDesktopRuntime.extractImagesFromTransferable(streamTransferable)
        assertEquals(1, payloads.size)
        assertEquals("image/png", payloads.first().mimeType)
        val decoded = decodeImageBytes(Base64.decode(payloads.first().dataBase64))
        assertNotNull(decoded)
        assertEquals(96, decoded.width)
        assertEquals(64, decoded.height)
    }
}
