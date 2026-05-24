package com.example.myapplication

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocxBuilder {

    fun createSimpleDocx(title: String, text: String): ByteArray {
        val cleanTitle = title.trim().ifBlank { "Документ" }
        val cleanBody = removeDuplicatedTitle(cleanTitle, text.trim())

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.writeEntry(
                "[Content_Types].xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                </Types>
                """.trimIndent()
            )

            zip.writeEntry(
                "_rels/.rels",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """.trimIndent()
            )

            zip.writeEntry(
                "word/_rels/document.xml.rels",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """.trimIndent()
            )

            zip.writeEntry(
                "word/styles.xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
                        <w:name w:val="Normal"/>
                        <w:rPr>
                            <w:sz w:val="24"/>
                            <w:szCs w:val="24"/>
                        </w:rPr>
                    </w:style>
                    <w:style w:type="paragraph" w:styleId="Title">
                        <w:name w:val="Title"/>
                        <w:pPr>
                            <w:jc w:val="center"/>
                            <w:spacing w:after="240"/>
                        </w:pPr>
                        <w:rPr>
                            <w:b/>
                            <w:sz w:val="32"/>
                            <w:szCs w:val="32"/>
                        </w:rPr>
                    </w:style>
                    <w:style w:type="paragraph" w:styleId="Heading">
                        <w:name w:val="Heading"/>
                        <w:pPr>
                            <w:spacing w:before="160" w:after="80"/>
                        </w:pPr>
                        <w:rPr>
                            <w:b/>
                            <w:sz w:val="26"/>
                            <w:szCs w:val="26"/>
                        </w:rPr>
                    </w:style>
                </w:styles>
                """.trimIndent()
            )

            zip.writeEntry("word/document.xml", buildDocumentXml(cleanTitle, cleanBody))
        }
        return output.toByteArray()
    }

    private fun buildDocumentXml(title: String, text: String): String {
        val titleParagraph = paragraph(title, styleId = "Title")
        val bodyParagraphs = text
            .lineSequence()
            .map { it.trimEnd() }
            .map { line ->
                if (looksLikeHeading(line)) {
                    paragraph(line, styleId = "Heading")
                } else {
                    paragraph(line)
                }
            }
            .joinToString(separator = "\n")

        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    $titleParagraph
                    $bodyParagraphs
                    <w:sectPr>
                        <w:pgSz w:w="11906" w:h="16838"/>
                        <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>
                    </w:sectPr>
                </w:body>
            </w:document>
        """.trimIndent()
    }

    private fun removeDuplicatedTitle(title: String, text: String): String {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty()) return ""

        val firstNonBlankIndex = lines.indexOfFirst { it.isNotBlank() }
        if (firstNonBlankIndex == -1) return ""

        val firstLine = lines[firstNonBlankIndex].trim()
        val normalizedTitle = normalizeForCompare(title)
        val normalizedFirstLine = normalizeForCompare(firstLine)

        return if (normalizedTitle == normalizedFirstLine) {
            lines.drop(firstNonBlankIndex + 1).joinToString("\n").trimStart()
        } else {
            text
        }
    }

    private fun looksLikeHeading(line: String): Boolean {
        val value = line.trim()
        if (value.isBlank()) return false
        if (value.length > 90) return false
        if (value.matches(Regex("^\\d+\\.\\s+[^.]+$"))) return true
        if (value.uppercase() == value && value.any { it.isLetter() }) return true
        return value.endsWith(":")
    }

    private fun paragraph(text: String, styleId: String? = null): String {
        val styleXml = if (styleId != null) "<w:pPr><w:pStyle w:val=\"$styleId\"/></w:pPr>" else ""
        val safeText = escapeXml(text).ifBlank { " " }
        return """
            <w:p>
                $styleXml
                <w:r>
                    <w:t xml:space="preserve">$safeText</w:t>
                </w:r>
            </w:p>
        """.trimIndent()
    }

    private fun normalizeForCompare(value: String): String {
        return value
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
