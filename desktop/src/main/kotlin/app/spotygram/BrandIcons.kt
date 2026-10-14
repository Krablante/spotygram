package app.spotygram

import java.io.File
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** System icons use the glass tile; screens keep the standalone shared mark. */
object BrandIcons {
    fun write(directory: File) {
        directory.mkdirs()
        val original = checkNotNull(javaClass.classLoader.getResourceAsStream("drawable-nodpi/spotygram_launcher.png"))
            .use { checkNotNull(ImageIO.read(it)) }
        fun png(size: Int): ByteArray {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(original, 0, 0, size, size, null)
            } finally {
                graphics.dispose()
            }
            return ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output))
                output.toByteArray()
            }
        }
        File(directory, "spotygram.png").writeBytes(png(512))
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val images = sizes.map(::png)
        val header = ByteBuffer.allocate(6 + sizes.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(sizes.size.toShort())
        var offset = header.capacity()
        sizes.zip(images).forEach { (size, image) ->
            header.put((size % 256).toByte()).put((size % 256).toByte()).put(0).put(0).putShort(1).putShort(32)
            header.putInt(image.size).putInt(offset)
            offset += image.size
        }
        File(directory, "spotygram.ico").outputStream().use { output ->
            output.write(header.array())
            images.forEach(output::write)
        }
        val chunks = listOf("icp4" to 16, "icp5" to 32, "icp6" to 64, "ic07" to 128,
            "ic08" to 256, "ic09" to 512, "ic10" to 1024).map { (kind, size) ->
            val data = png(size)
            kind.toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(4).putInt(data.size + 8).array() + data
        }.fold(ByteArray(0)) { result, chunk -> result + chunk }
        File(directory, "spotygram.icns").writeBytes("icns".toByteArray(Charsets.US_ASCII) +
            ByteBuffer.allocate(4).putInt(chunks.size + 8).array() + chunks)
    }
}
