package com.example.myapplication

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocxBuilder {

    fun createSimpleDocx(title: String, text: String): ByteArray {
        val cleanTitle = sanitizeText(title).trim().ifBlank { "Документ" }
        val cleanBody = removeDuplicatedTitle(cleanTitle, normalizeGeneratedText(text))

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.writeEntry("[Content_Types].xml", contentTypesXml())
            zip.writeEntry("_rels/.rels", packageRelationshipsXml())
            zip.writeEntry("docProps/core.xml", corePropertiesXml(cleanTitle))
            zip.writeEntry("docProps/app.xml", appPropertiesXml())
            zip.writeEntry("word/_rels/document.xml.rels", documentRelationshipsXml())
            zip.writeEntry("word/document.xml", documentXml(cleanTitle, cleanBody))
            zip.writeEntry("word/styles.xml", stylesXml())
            zip.writeEntry("word/settings.xml", settingsXml())
            zip.writeEntry("word/fontTable.xml", fontTableXml())
        }
        return output.toByteArray()
    }

    private fun contentTypesXml(): String = xml(
        """
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
          <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
          <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
          <Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
          <Override PartName="/word/fontTable.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml"/>
        </Types>
        """
    )

    private fun packageRelationshipsXml(): String = xml(
        """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
        </Relationships>
        """
    )

    private fun documentRelationshipsXml(): String = xml(
        """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/fontTable" Target="fontTable.xml"/>
        </Relationships>
        """
    )

    private fun documentXml(title: String, body: String): String {
        val paragraphs = buildString {
            appendLine(paragraph(title, styleId = "Title"))
            body.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd()
                appendLine(
                    when {
                        line.isBlank() -> paragraph("")
                        looksLikeHeading(line) -> paragraph(line, styleId = "Heading1")
                        else -> paragraph(line)
                    }
                )
            }
        }

        return xml(
            """
            <w:document
              xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
              xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
              mc:Ignorable="w14 wp14"
              xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"
              xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing">
              <w:body>
                $paragraphs
                <w:sectPr>
                  <w:pgSz w:w="11906" w:h="16838"/>
                  <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>
                  <w:cols w:space="708"/>
                  <w:docGrid w:linePitch="360"/>
                </w:sectPr>
              </w:body>
            </w:document>
            """
        )
    }

    private fun stylesXml(): String = xml(
        """
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:docDefaults>
            <w:rPrDefault>
              <w:rPr>
                <w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial"/>
                <w:sz w:val="24"/>
                <w:szCs w:val="24"/>
                <w:lang w:val="ru-RU"/>
              </w:rPr>
            </w:rPrDefault>
            <w:pPrDefault>
              <w:pPr>
                <w:spacing w:after="120" w:line="276" w:lineRule="auto"/>
              </w:pPr>
            </w:pPrDefault>
          </w:docDefaults>
          <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
            <w:name w:val="Normal"/>
            <w:qFormat/>
            <w:rPr>
              <w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial"/>
              <w:sz w:val="24"/>
              <w:szCs w:val="24"/>
            </w:rPr>
          </w:style>
          <w:style w:type="paragraph" w:styleId="Title">
            <w:name w:val="Title"/>
            <w:basedOn w:val="Normal"/>
            <w:next w:val="Normal"/>
            <w:qFormat/>
            <w:pPr>
              <w:jc w:val="center"/>
              <w:spacing w:after="280"/>
            </w:pPr>
            <w:rPr>
              <w:b/>
              <w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial"/>
              <w:sz w:val="32"/>
              <w:szCs w:val="32"/>
            </w:rPr>
          </w:style>
          <w:style w:type="paragraph" w:styleId="Heading1">
            <w:name w:val="heading 1"/>
            <w:basedOn w:val="Normal"/>
            <w:next w:val="Normal"/>
            <w:qFormat/>
            <w:pPr>
              <w:keepNext/>
              <w:spacing w:before="220" w:after="120"/>
            </w:pPr>
            <w:rPr>
              <w:b/>
              <w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial"/>
              <w:sz w:val="28"/>
              <w:szCs w:val="28"/>
            </w:rPr>
          </w:style>
        </w:styles>
        """
    )

    private fun settingsXml(): String = xml(
        """
        <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:zoom w:percent="100"/>
          <w:defaultTabStop w:val="708"/>
          <w:characterSpacingControl w:val="doNotCompress"/>
          <w:compat>
            <w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>
          </w:compat>
        </w:settings>
        """
    )

    private fun fontTableXml(): String = xml(
        """
        <w:fonts xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:font w:name="Arial">
            <w:panose1 w:val="020B0604020202020204"/>
            <w:charset w:val="CC"/>
            <w:family w:val="swiss"/>
            <w:pitch w:val="variable"/>
          </w:font>
        </w:fonts>
        """
    )

    private fun corePropertiesXml(title: String): String {
        val now = utcNow()
        return xml(
            """
            <cp:coreProperties
              xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
              xmlns:dc="http://purl.org/dc/elements/1.1/"
              xmlns:dcterms="http://purl.org/dc/terms/"
              xmlns:dcmitype="http://purl.org/dc/dcmitype/"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <dc:title>${escapeXml(title)}</dc:title>
              <dc:creator>AI Lawyer</dc:creator>
              <cp:lastModifiedBy>AI Lawyer</cp:lastModifiedBy>
              <dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>
              <dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified>
            </cp:coreProperties>
            """
        )
    }

    private fun appPropertiesXml(): String = xml(
        """
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
          <Application>AI Lawyer</Application>
          <DocSecurity>0</DocSecurity>
          <ScaleCrop>false</ScaleCrop>
          <LinksUpToDate>false</LinksUpToDate>
          <SharedDoc>false</SharedDoc>
          <HyperlinksChanged>false</HyperlinksChanged>
          <AppVersion>16.0000</AppVersion>
        </Properties>
        """
    )

    private fun paragraph(text: String, styleId: String? = null): String {
        val safeText = escapeXml(sanitizeText(text))
        val styleXml = if (styleId != null) "<w:pPr><w:pStyle w:val=\"$styleId\"/></w:pPr>" else ""
        val runXml = if (safeText.isBlank()) {
            "<w:r><w:t></w:t></w:r>"
        } else {
            "<w:r><w:t xml:space=\"preserve\">$safeText</w:t></w:r>"
        }
        return "<w:p>$styleXml$runXml</w:p>"
    }

    private fun normalizeGeneratedText(text: String): String {
        return sanitizeText(text)
            .replace("```docx", "", ignoreCase = true)
            .replace("```text", "", ignoreCase = true)
            .replace("```", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("\n{4,}"), "\n\n\n")
            .trim()
    }

    private fun removeDuplicatedTitle(title: String, text: String): String {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty()) return ""

        val firstNonBlankIndex = lines.indexOfFirst { it.isNotBlank() }
        if (firstNonBlankIndex == -1) return ""

        val firstLine = lines[firstNonBlankIndex].trim()
        return if (normalizeForCompare(title) == normalizeForCompare(firstLine)) {
            lines.drop(firstNonBlankIndex + 1).joinToString("\n").trimStart()
        } else {
            text
        }
    }

    private fun looksLikeHeading(line: String): Boolean {
        val value = line.trim()
        if (value.isBlank() || value.length > 110) return false
        if (value.matches(Regex("^\\d+([.)]|\\.\\d+)*\\s+.+$"))) return true
        if (value.matches(Regex("^[IVXLCDM]+[.)]\\s+.+$", RegexOption.IGNORE_CASE))) return true
        if (value.uppercase(Locale.getDefault()) == value && value.any { it.isLetter() }) return true
        return value.endsWith(":")
    }

    private fun sanitizeText(value: String): String {
        return buildString(value.length) {
            value.forEach { char ->
                if (isValidXmlChar(char)) append(char)
            }
        }
    }

    private fun isValidXmlChar(char: Char): Boolean {
        val code = char.code
        return code == 0x9 || code == 0xA || code == 0xD ||
                code in 0x20..0xD7FF || code in 0xE000..0xFFFD
    }

    private fun normalizeForCompare(value: String): String {
        return value.lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ").trim()
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun xml(body: String): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + body.trimIndent().trim()
    }

    private fun utcNow(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
