package com.iconcraft.studio.apk

import com.iconcraft.studio.model.CustomIconQueueItem
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Self-contained encoder for `appfilter.xml` and `drawable.xml` (both UTF-8 plain XML
 * for `assets/` & `res/raw/` and compiled Android Binary XML [AXML] for `res/xml/`),
 * plus an in-place `versionCode` patcher for `AndroidManifest.xml`.
 *
 * - When no Base Pack is selected (`extras.isEmpty == true`), `appfilter.xml` ONLY contains
 *   `<item component="..." drawable="..." />` for customized apps (no `<iconback>`, `<iconmask>`,
 *   `<iconupon>`, or `<scale>` tags).
 * - When a Base Pack is inherited, `<iconback>`, `<iconmask>`, `<iconupon>`, and `<scale>` from
 *   the Base Pack are included alongside the merged `<item>` entries.
 */
object AxmlEncoder {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104

    private const val UTF8_FLAG = 0x00000100
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val NO_INDEX = -1

    private const val ATTR_VERSION_CODE_RES_ID = 0x0101021b

    /**
     * Generates plain-text UTF-8 `appfilter.xml`.
     */
    fun encodeAppFilterPlainXml(
        items: List<CustomIconQueueItem>,
        extras: AppFilterExtras = AppFilterExtras()
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<resources>\n")

        if (extras.iconBackSlotNames.isNotEmpty()) {
            val attrs = extras.iconBackSlotNames.mapIndexed { idx, slot ->
                "img${idx + 1}=\"${escapeXmlAttr(slot)}\""
            }.joinToString(" ")
            sb.append("    <iconback $attrs />\n")
        }
        if (extras.iconMaskSlotNames.isNotEmpty()) {
            val attrs = extras.iconMaskSlotNames.mapIndexed { idx, slot ->
                "img${idx + 1}=\"${escapeXmlAttr(slot)}\""
            }.joinToString(" ")
            sb.append("    <iconmask $attrs />\n")
        }
        if (extras.iconUponSlotNames.isNotEmpty()) {
            val attrs = extras.iconUponSlotNames.mapIndexed { idx, slot ->
                "img${idx + 1}=\"${escapeXmlAttr(slot)}\""
            }.joinToString(" ")
            sb.append("    <iconupon $attrs />\n")
        }
        if (!extras.scaleFactor.isNullOrBlank()) {
            sb.append("    <scale factor=\"${escapeXmlAttr(extras.scaleFactor)}\" />\n")
        }

        for (item in items) {
            val comp = escapeXmlAttr(item.app.componentInfo)
            val draw = escapeXmlAttr(item.drawableName)
            sb.append("    <item component=\"$comp\" drawable=\"$draw\" />\n")
        }
        sb.append("</resources>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Generates plain-text UTF-8 `drawable.xml` listing ONLY actively used drawable slots.
     */
    fun encodeDrawablePlainXml(items: List<CustomIconQueueItem>): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<resources>\n")
        sb.append("    <version>1</version>\n")
        for (item in items) {
            val draw = escapeXmlAttr(item.drawableName)
            sb.append("    <item drawable=\"$draw\" />\n")
        }
        sb.append("</resources>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Encodes valid compiled Android Binary XML (AXML) for `res/xml/appfilter.xml`.
     */
    fun encodeAppFilterAxml(
        items: List<CustomIconQueueItem>,
        extras: AppFilterExtras = AppFilterExtras()
    ): ByteArray {
        val strings = LinkedHashSet<String>()
        strings.add("resources")

        if (extras.iconBackSlotNames.isNotEmpty()) {
            strings.add("iconback")
            extras.iconBackSlotNames.forEachIndexed { idx, slot ->
                strings.add("img${idx + 1}")
                strings.add(slot)
            }
        }
        if (extras.iconMaskSlotNames.isNotEmpty()) {
            strings.add("iconmask")
            extras.iconMaskSlotNames.forEachIndexed { idx, slot ->
                strings.add("img${idx + 1}")
                strings.add(slot)
            }
        }
        if (extras.iconUponSlotNames.isNotEmpty()) {
            strings.add("iconupon")
            extras.iconUponSlotNames.forEachIndexed { idx, slot ->
                strings.add("img${idx + 1}")
                strings.add(slot)
            }
        }
        if (!extras.scaleFactor.isNullOrBlank()) {
            strings.add("scale")
            strings.add("factor")
            strings.add(extras.scaleFactor)
        }

        if (items.isNotEmpty()) {
            strings.add("item")
            strings.add("component")
            strings.add("drawable")
            for (item in items) {
                strings.add(item.app.componentInfo)
                strings.add(item.drawableName)
            }
        }
        val stringList = strings.toList()
        val stringIndexMap = stringList.withIndex().associate { (idx, str) -> str to idx }

        val nodesStream = ByteArrayOutputStream()
        var line = 2

        // <resources>
        nodesStream.write(
            buildStartElementChunk(
                lineNumber = line++,
                nameIdx = stringIndexMap.getValue("resources"),
                attributes = emptyList()
            )
        )

        fun writeImgTag(tagName: String, slotNames: List<String>) {
            if (slotNames.isEmpty()) return
            val tagIdx = stringIndexMap.getValue(tagName)
            val attrs = slotNames.mapIndexed { idx, slot ->
                stringIndexMap.getValue("img${idx + 1}") to stringIndexMap.getValue(slot)
            }
            val currentLine = line++
            nodesStream.write(
                buildStartElementChunk(
                    lineNumber = currentLine,
                    nameIdx = tagIdx,
                    attributes = attrs
                )
            )
            nodesStream.write(
                buildEndElementChunk(
                    lineNumber = currentLine,
                    nameIdx = tagIdx
                )
            )
        }

        writeImgTag("iconback", extras.iconBackSlotNames)
        writeImgTag("iconmask", extras.iconMaskSlotNames)
        writeImgTag("iconupon", extras.iconUponSlotNames)

        if (!extras.scaleFactor.isNullOrBlank()) {
            val scaleIdx = stringIndexMap.getValue("scale")
            val factorAttrIdx = stringIndexMap.getValue("factor")
            val factorValIdx = stringIndexMap.getValue(extras.scaleFactor)
            val currentLine = line++
            nodesStream.write(
                buildStartElementChunk(
                    lineNumber = currentLine,
                    nameIdx = scaleIdx,
                    attributes = listOf(factorAttrIdx to factorValIdx)
                )
            )
            nodesStream.write(
                buildEndElementChunk(
                    lineNumber = currentLine,
                    nameIdx = scaleIdx
                )
            )
        }

        // <item component="ComponentInfo{...}" drawable="custom_icon_XXX" />
        for (item in items) {
            val itemIdx = stringIndexMap.getValue("item")
            val compAttrIdx = stringIndexMap.getValue("component")
            val drawAttrIdx = stringIndexMap.getValue("drawable")
            val compValIdx = stringIndexMap.getValue(item.app.componentInfo)
            val drawValIdx = stringIndexMap.getValue(item.drawableName)

            val currentLine = line++
            nodesStream.write(
                buildStartElementChunk(
                    lineNumber = currentLine,
                    nameIdx = itemIdx,
                    attributes = listOf(
                        compAttrIdx to compValIdx,
                        drawAttrIdx to drawValIdx
                    )
                )
            )
            nodesStream.write(
                buildEndElementChunk(
                    lineNumber = currentLine,
                    nameIdx = itemIdx
                )
            )
        }

        // </resources>
        nodesStream.write(
            buildEndElementChunk(
                lineNumber = line,
                nameIdx = stringIndexMap.getValue("resources")
            )
        )

        return assembleAxmlFile(stringList, nodesStream.toByteArray())
    }

    /**
     * Encodes valid compiled Android Binary XML (AXML) for `res/xml/drawable.xml`.
     */
    fun encodeDrawableAxml(items: List<CustomIconQueueItem>): ByteArray {
        val strings = LinkedHashSet<String>()
        strings.add("1")
        strings.add("resources")
        strings.add("version")
        if (items.isNotEmpty()) {
            strings.add("item")
            strings.add("drawable")
            for (item in items) {
                strings.add(item.drawableName)
            }
        }
        val stringList = strings.toList()
        val stringIndexMap = stringList.withIndex().associate { (idx, str) -> str to idx }

        val nodesStream = ByteArrayOutputStream()
        var line = 2

        // <resources>
        val resourcesIdx = stringIndexMap.getValue("resources")
        nodesStream.write(
            buildStartElementChunk(
                lineNumber = line++,
                nameIdx = resourcesIdx,
                attributes = emptyList()
            )
        )

        // <version>1</version>
        val versionIdx = stringIndexMap.getValue("version")
        val oneIdx = stringIndexMap.getValue("1")
        val versionLine = line++
        nodesStream.write(
            buildStartElementChunk(
                lineNumber = versionLine,
                nameIdx = versionIdx,
                attributes = emptyList()
            )
        )
        nodesStream.write(
            buildCdataChunk(
                lineNumber = versionLine,
                textIdx = oneIdx
            )
        )
        nodesStream.write(
            buildEndElementChunk(
                lineNumber = versionLine,
                nameIdx = versionIdx
            )
        )

        // <item drawable="custom_icon_XXX" />
        for (item in items) {
            val itemIdx = stringIndexMap.getValue("item")
            val drawAttrIdx = stringIndexMap.getValue("drawable")
            val drawValIdx = stringIndexMap.getValue(item.drawableName)
            val currentLine = line++

            nodesStream.write(
                buildStartElementChunk(
                    lineNumber = currentLine,
                    nameIdx = itemIdx,
                    attributes = listOf(drawAttrIdx to drawValIdx)
                )
            )
            nodesStream.write(
                buildEndElementChunk(
                    lineNumber = currentLine,
                    nameIdx = itemIdx
                )
            )
        }

        // </resources>
        nodesStream.write(
            buildEndElementChunk(
                lineNumber = line,
                nameIdx = resourcesIdx
            )
        )

        return assembleAxmlFile(stringList, nodesStream.toByteArray())
    }

    /**
     * Increments / updates `android:versionCode` (`0x0101021b`) inside a compiled
     * `AndroidManifest.xml` binary XML byte array so Android allows seamless updates
     * over previously installed packs without uninstalling first.
     */
    fun patchManifestVersionCode(manifestBytes: ByteArray, newVersionCode: Int): ByteArray {
        val patched = manifestBytes.copyOf()
        val buf = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)
        if (patched.size < 8) return patched

        val fileType = buf.getShort(0).toInt() and 0xFFFF
        if (fileType != RES_XML_TYPE) return patched

        var versionCodeNameIdx = -1
        var pos = 8
        while (pos + 8 <= patched.size) {
            val chunkType = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > patched.size) break

            if (chunkType == RES_XML_RESOURCE_MAP_TYPE) {
                val count = (chunkSize - headerSize) / 4
                for (i in 0 until count) {
                    val resId = buf.getInt(pos + headerSize + i * 4)
                    if (resId == ATTR_VERSION_CODE_RES_ID) {
                        versionCodeNameIdx = i
                        break
                    }
                }
            } else if (chunkType == RES_XML_START_ELEMENT_TYPE && versionCodeNameIdx >= 0) {
                // First start element is <manifest>
                val attrStart = buf.getShort(pos + 16 + 8).toInt() and 0xFFFF
                val attrSize = buf.getShort(pos + 16 + 10).toInt() and 0xFFFF
                val attrCount = buf.getShort(pos + 16 + 12).toInt() and 0xFFFF
                for (i in 0 until attrCount) {
                    val attrOffset = pos + 16 + attrStart + i * attrSize
                    if (attrOffset + 20 <= pos + chunkSize) {
                        val nameIdx = buf.getInt(attrOffset + 4)
                        val dataType = buf.get(attrOffset + 15).toInt() and 0xFF
                        if (nameIdx == versionCodeNameIdx && dataType == TYPE_INT_DEC) {
                            buf.putInt(attrOffset + 16, newVersionCode)
                            return patched
                        }
                    }
                }
                break
            }

            pos += chunkSize
        }
        return patched
    }

    private fun assembleAxmlFile(strings: List<String>, nodesBytes: ByteArray): ByteArray {
        val stringPoolChunk = buildUtf8StringPoolChunk(strings)
        val resourceMapChunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(RES_XML_RESOURCE_MAP_TYPE.toShort())
            putShort(8.toShort())
            putInt(8)
        }.array()

        val totalSize = 8 + stringPoolChunk.size + resourceMapChunk.size + nodesBytes.size
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_XML_TYPE.toShort())
        out.putShort(8.toShort())
        out.putInt(totalSize)
        out.put(stringPoolChunk)
        out.put(resourceMapChunk)
        out.put(nodesBytes)
        return out.array()
    }

    private fun buildUtf8StringPoolChunk(strings: List<String>): ByteArray {
        val stringDataStream = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)

        for ((index, str) in strings.withIndex()) {
            offsets[index] = stringDataStream.size()
            val utf8Bytes = str.toByteArray(Charsets.UTF_8)
            val utf16Len = str.length
            writeUtf8Length(stringDataStream, utf16Len)
            writeUtf8Length(stringDataStream, utf8Bytes.size)
            stringDataStream.write(utf8Bytes)
            stringDataStream.write(0) // Null terminator
        }

        // Pad string pool data to 4-byte boundary
        while (stringDataStream.size() % 4 != 0) {
            stringDataStream.write(0)
        }

        val stringDataBytes = stringDataStream.toByteArray()
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val chunkSize = stringsStart + stringDataBytes.size

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE.toShort())
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(strings.size) // stringCount
        buf.putInt(0)            // styleCount
        buf.putInt(UTF8_FLAG)    // flags
        buf.putInt(stringsStart) // stringsStart
        buf.putInt(0)            // stylesStart
        for (offset in offsets) {
            buf.putInt(offset)
        }
        buf.put(stringDataBytes)
        return buf.array()
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
        if (length >= 0x80) {
            out.write(((length shr 8) and 0x7F) or 0x80)
        }
        out.write(length and 0xFF)
    }

    private fun buildStartElementChunk(
        lineNumber: Int,
        nameIdx: Int,
        attributes: List<Pair<Int, Int>>
    ): ByteArray {
        val chunkSize = 36 + attributes.size * 20
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)

        // ResXMLTree_node header (16 bytes)
        buf.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
        buf.putShort(16.toShort())
        buf.putInt(chunkSize)
        buf.putInt(lineNumber)
        buf.putInt(NO_INDEX)

        // ResXMLTree_attrExt (20 bytes)
        buf.putInt(NO_INDEX) // ns
        buf.putInt(nameIdx)  // name
        buf.putShort(20.toShort()) // attributeStart
        buf.putShort(20.toShort()) // attributeSize
        buf.putShort(attributes.size.toShort()) // attributeCount
        buf.putShort(0.toShort()) // idIndex
        buf.putShort(0.toShort()) // classIndex
        buf.putShort(0.toShort()) // styleIndex

        // Attributes (20 bytes each)
        for ((attrNameIdx, attrValIdx) in attributes) {
            buf.putInt(NO_INDEX)    // ns
            buf.putInt(attrNameIdx) // name
            buf.putInt(attrValIdx)  // rawValue
            buf.putShort(8.toShort()) // typedValue.size
            buf.put(0.toByte())       // typedValue.res0
            buf.put(TYPE_STRING.toByte()) // typedValue.dataType
            buf.putInt(attrValIdx)    // typedValue.data
        }

        return buf.array()
    }

    private fun buildEndElementChunk(lineNumber: Int, nameIdx: Int): ByteArray {
        val chunkSize = 24
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_END_ELEMENT_TYPE.toShort())
        buf.putShort(16.toShort())
        buf.putInt(chunkSize)
        buf.putInt(lineNumber)
        buf.putInt(NO_INDEX)
        buf.putInt(NO_INDEX) // ns
        buf.putInt(nameIdx)  // name
        return buf.array()
    }

    private fun buildCdataChunk(lineNumber: Int, textIdx: Int): ByteArray {
        val chunkSize = 28
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_CDATA_TYPE.toShort())
        buf.putShort(16.toShort())
        buf.putInt(chunkSize)
        buf.putInt(lineNumber)
        buf.putInt(NO_INDEX)
        buf.putInt(textIdx) // data
        buf.putShort(8.toShort()) // typedData.size
        buf.put(0.toByte())
        buf.put(0.toByte())
        buf.putInt(0)
        return buf.array()
    }

    private fun escapeXmlAttr(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
