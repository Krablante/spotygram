package app.spotygram

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path
import org.jetbrains.skia.Surface
import org.w3c.dom.Element

/** Launcher icons are generated from the same vector used by the shared interface. */
object BrandIcons {
    fun write(directory: File) {
        directory.mkdirs()
        val document = checkNotNull(javaClass.classLoader.getResourceAsStream("drawable/spotygram_mark.xml"))
            .use { DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it) }
        val nodes = document.getElementsByTagName("path")
        fun png(size: Int): ByteArray {
            Surface.makeRasterN32Premul(size, size).use { surface ->
                surface.canvas.scale(size / 108f, size / 108f)
                Paint().use { paint ->
                    paint.isAntiAlias = true
                    for (i in 0 until nodes.length) {
                        val element = nodes.item(i) as Element
                        val color = element.getAttributeNS("http://schemas.android.com/apk/res/android", "fillColor").removePrefix("#").toLong(16)
                        paint.color = (color or 0xff000000L).toInt()
                        Path.makeFromSVGString(element.getAttributeNS("http://schemas.android.com/apk/res/android", "pathData")).use { path ->
                            surface.canvas.drawPath(path, paint)
                        }
                    }
                }
                return surface.makeImageSnapshot().use { image ->
                    checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                }
            }
        }
        val icon = png(256)
        File(directory, "spotygram.png").writeBytes(png(512))
        val header = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(1)
        header.put(0).put(0).put(0).put(0).putShort(1).putShort(32)
        header.putInt(icon.size).putInt(22)
        File(directory, "spotygram.ico").writeBytes(header.array() + icon)
        val chunks = listOf("ic07" to 128, "ic08" to 256, "ic09" to 512, "ic10" to 1024).map { (kind, size) ->
            val data = png(size)
            kind.toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(4).putInt(data.size + 8).array() + data
        }.fold(ByteArray(0)) { result, chunk -> result + chunk }
        File(directory, "spotygram.icns").writeBytes("icns".toByteArray(Charsets.US_ASCII) +
            ByteBuffer.allocate(4).putInt(chunks.size + 8).array() + chunks)
    }
}
