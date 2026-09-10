package tech.valerochkagym.service.ai

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.model.AiImage

@Component
class AiImageInput {
  fun validate(image: AiImage) {
    if (image.base64.length > 8 * 1024 * 1024)
      throw ApiException(413, "payload_too_large", "Превышен размер изображения")
    if (image.mediaType != "image/jpeg") invalid()
    val bytes =
      try {
        Base64.getDecoder().decode(image.base64)
      } catch (_: IllegalArgumentException) {
        invalid()
      }
    try {
      if (bytes.size > 6 * 1024 * 1024)
        throw ApiException(413, "payload_too_large", "Превышен размер изображения")
      if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) invalid()
      MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
        val readers = ImageIO.getImageReaders(stream)
        if (!readers.hasNext()) invalid()
        val reader = readers.next()
        try {
          reader.input = stream
          if (
            reader.formatName.lowercase() !in setOf("jpeg", "jpg") ||
              reader.getWidth(0) !in 1..3072 ||
              reader.getHeight(0) !in 1..3072
          )
            invalid()
          if (reader.read(0) == null) invalid()
        } finally {
          reader.dispose()
        }
      }
    } catch (e: ApiException) {
      throw e
    } catch (_: Exception) {
      invalid()
    } finally {
      bytes.fill(0)
    }
  }

  private fun invalid(): Nothing =
    throw ApiException(400, "invalid_image", "Не удалось прочитать фото")
}
