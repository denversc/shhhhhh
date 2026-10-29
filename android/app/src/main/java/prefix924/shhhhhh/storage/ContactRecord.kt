package prefix924.shhhhhh.storage

import java.util.UUID

/**
 * Record representing a saved contact for message encryption.
 *
 * @property id Unique persistent identifier for this contact entry (e.g. UUID string).
 * @property name Display name of the contact.
 * @property publicKeyBase64Url Unpadded Base64URL string (43 characters) of the 32-byte X25519
 *   public key.
 * @property createdAt Timestamp in epoch milliseconds when the contact was created.
 */
data class ContactRecord(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val publicKeyBase64Url: String,
  val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Zero-dependency JSON serializer and parser for [ContactRecord] lists. Ensures consistent behavior
 * across both Android runtime and host JVM unit tests.
 */
object ContactSerialization {

  fun serialize(contacts: List<ContactRecord>): String {
    val sb = StringBuilder()
    sb.append("[")
    contacts.forEachIndexed { index, contact ->
      if (index > 0) sb.append(",")
      sb.append("{")
      sb.append("\"id\":").append(quote(contact.id)).append(",")
      sb.append("\"name\":").append(quote(contact.name)).append(",")
      sb.append("\"publicKeyBase64Url\":").append(quote(contact.publicKeyBase64Url)).append(",")
      sb.append("\"createdAt\":").append(contact.createdAt)
      sb.append("}")
    }
    sb.append("]")
    return sb.toString()
  }

  fun deserialize(raw: String): List<ContactRecord> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
    require(trimmed.startsWith("[") && trimmed.endsWith("]")) {
      "Expected JSON array starting with '[' and ending with ']'"
    }

    val items = mutableListOf<ContactRecord>()
    var i = 1
    val len = trimmed.length - 1

    while (i < len) {
      // Find opening brace of next object
      while (i < len && trimmed[i].isWhitespace()) i++
      if (i >= len) break
      if (trimmed[i] == ',') {
        i++
        continue
      }
      if (trimmed[i] != '{') {
        i++
        continue
      }

      // Extract single object inside { ... }
      val objStart = i
      var braceCount = 0
      var inString = false
      var escape = false
      var objEnd = -1

      while (i < len) {
        val c = trimmed[i]
        if (escape) {
          escape = false
        } else if (c == '\\') {
          escape = true
        } else if (c == '"') {
          inString = !inString
        } else if (!inString) {
          if (c == '{') braceCount++
          else if (c == '}') {
            braceCount--
            if (braceCount == 0) {
              objEnd = i
              i++
              break
            }
          }
        }
        i++
      }

      if (objEnd != -1) {
        val objJson = trimmed.substring(objStart + 1, objEnd)
        parseObject(objJson)?.let { items.add(it) }
      }
    }

    return items
  }

  private fun parseObject(objContent: String): ContactRecord? {
    val map = mutableMapOf<String, String>()
    var i = 0
    val len = objContent.length

    while (i < len) {
      // Find key starting with quote
      val keyStartQuote = objContent.indexOf('"', i)
      if (keyStartQuote == -1) break
      val keyEnd = findStringEnd(objContent, keyStartQuote + 1)
      if (keyEnd == -1) break
      val key = unescape(objContent.substring(keyStartQuote + 1, keyEnd))

      // Find colon
      val colonIdx = objContent.indexOf(':', keyEnd + 1)
      if (colonIdx == -1) break

      // Skip whitespace after colon
      var valStart = colonIdx + 1
      while (valStart < len && objContent[valStart].isWhitespace()) valStart++
      if (valStart >= len) break

      if (objContent[valStart] == '"') {
        // String value
        val valEnd = findStringEnd(objContent, valStart + 1)
        if (valEnd == -1) break
        val value = unescape(objContent.substring(valStart + 1, valEnd))
        map[key] = value
        i = valEnd + 1
      } else {
        // Primitive value (e.g. number)
        var valEnd = valStart
        while (valEnd < len && objContent[valEnd] != ',' && objContent[valEnd] != '}') valEnd++
        val value = objContent.substring(valStart, valEnd).trim()
        map[key] = value
        i = valEnd
      }
    }

    val id = map["id"] ?: return null
    val name = map["name"] ?: return null
    val pubKey = map["publicKeyBase64Url"] ?: return null
    val createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis()

    return ContactRecord(id = id, name = name, publicKeyBase64Url = pubKey, createdAt = createdAt)
  }

  private fun findStringEnd(s: String, startIdx: Int): Int {
    var i = startIdx
    var escape = false
    while (i < s.length) {
      val c = s[i]
      if (escape) {
        escape = false
      } else if (c == '\\') {
        escape = true
      } else if (c == '"') {
        return i
      }
      i++
    }
    return -1
  }

  private fun quote(value: String): String {
    val sb = StringBuilder("\"")
    for (c in value) {
      when (c) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\b' -> sb.append("\\b")
        '\u000C' -> sb.append("\\f")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> {
          if (c.code in 0x00..0x1F) {
            sb.append(String.format("\\u%04x", c.code))
          } else {
            sb.append(c)
          }
        }
      }
    }
    sb.append("\"")
    return sb.toString()
  }

  private fun unescape(value: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < value.length) {
      val c = value[i]
      if (c == '\\' && i + 1 < value.length) {
        when (val next = value[i + 1]) {
          '\\' -> {
            sb.append('\\')
            i += 2
          }
          '"' -> {
            sb.append('"')
            i += 2
          }
          'b' -> {
            sb.append('\b')
            i += 2
          }
          'f' -> {
            sb.append('\u000C')
            i += 2
          }
          'n' -> {
            sb.append('\n')
            i += 2
          }
          'r' -> {
            sb.append('\r')
            i += 2
          }
          't' -> {
            sb.append('\t')
            i += 2
          }
          'u' -> {
            if (i + 5 < value.length) {
              val hex = value.substring(i + 2, i + 6)
              hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
              i += 6
            } else {
              sb.append(next)
              i += 2
            }
          }
          else -> {
            sb.append(next)
            i += 2
          }
        }
      } else {
        sb.append(c)
        i++
      }
    }
    return sb.toString()
  }
}
