package io.grokify.os.ui.chat

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * Lightweight GFM-ish renderer for chat (headings, lists, code fences, tables,
 * mermaid diagrams, bold/italic/code/links).
 * Normalizes stream artifacts (orphaned **, mid-word spaces) to match web System Chat.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFFE5E7EB),
) {
    val blocks = remember(markdown) {
        runCatching { parseBlocks(normalizeChatMarkdown(markdown)) }
            .getOrElse { listOf(MdBlock.Paragraph(markdown)) }
    }
    SelectionContainer {
        Column(modifier = modifier) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> Text(
                        text = inlineMarkdown(block.text, textColor),
                        color = textColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = when (block.level) {
                            1 -> 20.sp
                            2 -> 17.sp
                            else -> 15.sp
                        },
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    is MdBlock.Code -> {
                        if (block.lang.equals("mermaid", ignoreCase = true)) {
                            MermaidBlock(block.code)
                        } else {
                            CodeBlock(block.lang, block.code)
                        }
                    }
                    is MdBlock.Table -> MarkdownTable(block, textColor)
                    is MdBlock.ListItem -> Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            if (block.ordered) "${block.index}. " else "• ",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = inlineMarkdown(block.text, textColor),
                            color = textColor,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    is MdBlock.Quote -> Text(
                        text = inlineMarkdown(block.text, textColor),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF12151A))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    is MdBlock.Paragraph -> Text(
                        text = inlineMarkdown(block.text, textColor),
                        color = textColor,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    is MdBlock.Blank -> Spacer(Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D1117))
    ) {
        if (lang.isNotBlank()) {
            Text(
                lang,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            code.trimEnd(),
            color = Color(0xFFD1D5DB),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MermaidBlock(source: String) {
    val context = LocalContext.current
    var showSource by remember(source) { mutableStateOf(false) }
    val url = remember(source) { mermaidInkUrl(source) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D1117))
            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(10.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "mermaid",
                color = Color(0xFF34D399),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showSource = !showSource }) {
                Text(
                    if (showSource) "Diagram" else "Source",
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                )
            }
        }
        if (showSource) {
            Text(
                source.trimEnd(),
                color = Color(0xFFD1D5DB),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = "Mermaid diagram",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 420.dp)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp)),
                loading = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF34D399),
                        )
                    }
                },
                error = {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "Couldn’t render diagram — showing source",
                            color = Color(0xFFF87171),
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            source.trimEnd(),
                            color = Color(0xFFD1D5DB),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 17.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                },
            )
        }
    }
}

/** mermaid.ink URL — same encoding style as bot/src/ai/mermaidRender.js for short sources. */
internal fun mermaidInkUrl(source: String, width: Int = 900): String {
    val encoded = Base64.encodeToString(
        source.trim().toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP,
    )
    return "https://mermaid.ink/img/$encoded?type=png&theme=dark&width=$width&bgColor=0e121c"
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, textColor: Color) {
    val border = Color(0xFF2A3142)
    val headerBg = Color(0xFF141A26)
    val cellBg = Color(0xFF0D1117)
    val cols = table.headers.size.coerceAtLeast(1)
    val maxCols = maxOf(cols, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
    ) {
        // Header — last column expands to fill remaining width
        Row(Modifier.fillMaxWidth().background(headerBg)) {
            for (i in 0 until maxCols) {
                TableCell(
                    text = table.headers.getOrNull(i).orEmpty(),
                    textColor = Color(0xFF67E8F9),
                    bold = true,
                    border = border,
                    isLast = i == maxCols - 1,
                )
            }
        }
        table.rows.forEachIndexed { rowIdx, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (rowIdx % 2 == 0) cellBg else Color(0xFF11151C)),
            ) {
                for (c in 0 until maxCols) {
                    TableCell(
                        text = row.getOrNull(c).orEmpty(),
                        textColor = textColor,
                        bold = false,
                        border = border,
                        isLast = c == maxCols - 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    textColor: Color,
    bold: Boolean,
    border: Color,
    isLast: Boolean,
) {
    Box(
        Modifier
            .then(
                if (isLast) {
                    // Last column absorbs remaining table width
                    Modifier.weight(1f).widthIn(min = 96.dp)
                } else {
                    // Leading columns size to content (capped so they don't dominate)
                    Modifier.widthIn(min = 64.dp, max = 200.dp)
                },
            )
            .then(
                if (!isLast) Modifier.border(width = 0.5.dp, color = border)
                else Modifier,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = inlineMarkdown(text.trim(), textColor),
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            lineHeight = 16.sp,
        )
    }
}

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    data class ListItem(val text: String, val ordered: Boolean, val index: Int) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data object Blank : MdBlock()
}

private val HEAL_KEEP = setOf(
    "a", "an", "the", "and", "or", "but", "if", "in", "on", "at", "to", "of", "for", "from",
    "by", "as", "is", "it", "be", "we", "he", "she", "they", "you", "me", "my", "our", "your",
    "his", "her", "its", "are", "was", "were", "has", "had", "have", "will", "can", "may",
    "not", "no", "yes", "so", "up", "out", "off", "all", "any", "new", "old", "via", "per",
    "with", "this", "that", "than", "then", "when", "what", "who", "how", "why", "which",
    "into", "onto", "over", "under", "about", "after", "before", "between", "through",
    "during", "without", "within", "also", "just", "more", "most", "some", "such", "only",
    "other", "upon", "like", "back", "even", "well", "very", "much", "many", "own", "same",
    "too", "still", "need", "each", "few", "plus", "vs", "mode", "dark", "light", "full",
    "real", "next", "last", "first", "both", "once", "file", "files", "data", "code", "text",
    "chat", "line", "lines", "page", "pages", "site", "name", "names", "type", "types",
)

/** Align with web system-chat.js normalizeChatMarkdown. */
internal fun normalizeChatMarkdown(raw: String): String {
    if (raw.isEmpty()) return raw
    var s = raw.replace("\r\n", "\n")

    val fences = mutableListOf<String>()
    s = Regex("```[\\s\\S]*?```").replace(s) { m ->
        fences += m.value
        "\u0000FENCE${fences.lastIndex}\u0000"
    }
    val inlines = mutableListOf<String>()
    s = Regex("`[^`\\n]+`").replace(s) { m ->
        inlines += m.value
        "\u0000INLINE${inlines.lastIndex}\u0000"
    }

    s = healMidwordSpaces(s)
    s = fixSentenceSpacing(s)
    s = balanceDoubleStars(s)
    s = balanceSingleStars(s)

    s = Regex("\u0000INLINE(\\d+)\u0000").replace(s) { m ->
        inlines.getOrNull(m.groupValues[1].toInt()) ?: ""
    }
    s = Regex("\u0000FENCE(\\d+)\u0000").replace(s) { m ->
        fences.getOrNull(m.groupValues[1].toInt()) ?: ""
    }
    return s
}

/**
 * Insert missing spaces after .!? before a new sentence.
 * Align with assets/system-chat.js fixSentenceSpacing.
 */
internal fun fixSentenceSpacing(text: String): String {
    if (text.isEmpty()) return text
    var s = text

    s = Regex("""(.?)([A-Za-z0-9)\]"'”’»])([.!?])([A-Z])""").replace(s) { m ->
        val pre = m.groupValues[1]
        val before = m.groupValues[2]
        val punct = m.groupValues[3]
        val after = m.groupValues[4]
        val singleLetterAbbr =
            before[0].isUpperCase() && (pre.isEmpty() || !pre[0].isLetter())
        if (singleLetterAbbr) m.value else pre + before + punct + " " + after
    }
    s = Regex("""([a-z0-9)\]"'”’])([.!?])(\*{1,2})(?=[A-Za-z])""").replace(s, "$1$2 $3")
    s = Regex("""(\*{1,2})([.!?])([A-Z])""").replace(s, "$1$2 $3")

    return s
}

/**
 * Reverse stream-join damage. High-precision — never glue common phrase words.
 * Keep in sync with assets/system-chat.js and bridge/server.js.
 */
internal fun healMidwordSpaces(text: String): String {
    if (text.isEmpty()) return text
    var s = text

    s = s.replace(Regex("""\bI\s+Ds\b"""), "IDs")
    s = s.replace(Regex("""\bI\s+D\b"""), "ID")
    s = Regex("""\b([A-Z]{1,3})\s+([A-Z]{1,3})\b""").replace(s) { m ->
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        if (a.length + b.length <= 5) a + b else m.value
    }

    val suff =
        "izers?|izing|ized|ifies|ify|ifying|able|ible|ables|ibles|apsible|apsible|" +
            "ates|ating|ated|ation|ations|ments?|ness|less|ful|ings?|edly|tions?|sions?|" +
            "ests?|wards?|ures?|ences?|ances?|ents?|ants?|ous|ives?|icals?|ials?|ying|" +
            "ened|ships?|hoods?|isms?|ists?|izes?|ises?|ories?|aries?|uals?|iests?|iers?|" +
            "ies|ied|ily|iness|ably|ibly|atives?|ators?|ability|ibility|" +
            "oring|aring|ering|uring|oping|aping|uting|oting|isting|asting|esting|" +
            "igned|igning|ifying|ified|ifier|ifiers|ocket|ockets|erver|ervers|" +
            "ession|essions|essage|essages|istory|istories|ermission|ermissions|" +
            "ersion|ersions|ackage|ackages|evice|evices|otals|ounts?|okens?|pot|ify|kify"
    val suffRe = Regex("""\b([A-Za-z]{2,})\s+($suff)\b""", RegexOption.IGNORE_CASE)
    repeat(8) {
        val next = suffRe.replace(s, "$1$2")
        if (next == s) return@repeat
        s = next
    }

    val capRe = Regex("""\b([B-HJ-Z])\s+([a-z]{2,12})\b""")
    repeat(4) {
        val next = capRe.replace(s) { m ->
            val b = m.groupValues[2]
            if (b in HEAL_KEEP) m.value else m.groupValues[1] + b
        }
        if (next == s) return@repeat
        s = next
    }

    val camel =
        "Http|Https|Url|Uri|Json|Xml|Html|Sql|Api|Uuid|Null|True|False|Socket|" +
            "Stream|Client|Server|Token|Header|Request|Response|Config|Object|Array|" +
            "String|Number|Boolean|Integer|Double|Float|Class|Method|Field|Error|" +
            "Exception|Status|Code|Type|Name|Value|Key|Path|File|Dir|Query|Param|" +
            "Params|Body|Auth|User|Session|Device|Bridge|Model|Prompt|Chunk|Delta"
    val camelRe = Regex("""([a-z0-9])\s+($camel)\b""")
    repeat(6) {
        val next = camelRe.replace(s, "$1$2")
        if (next == s) return@repeat
        s = next
    }

    return s
}

private fun balanceDoubleStars(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') {
            var j = i + 2
            var found = -1
            while (j < s.length - 1) {
                if (s[j] == '*' && s[j + 1] == '*') {
                    found = j
                    break
                }
                j++
            }
            when {
                found > i + 2 -> {
                    val inner = s.substring(i + 2, found).trim()
                    if (inner.isNotEmpty()) {
                        out.append("**").append(inner).append("**")
                    }
                    i = found + 2
                }
                found == i + 2 -> i = found + 2
                else -> i += 2 // unpaired opener — drop stars
            }
        } else {
            out.append(s[i])
            i++
        }
    }
    return out.toString()
}

private fun balanceSingleStars(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        if (s[i] == '*' && (i + 1 >= s.length || s[i + 1] != '*')) {
            val lineStart = i == 0 || s[i - 1] == '\n'
            if (lineStart && i + 1 < s.length && (s[i + 1] == ' ' || s[i + 1] == '\t')) {
                out.append(s[i])
                i++
                continue
            }
            var j = i + 1
            var found = -1
            while (j < s.length) {
                if (s[j] == '*' && (j + 1 >= s.length || s[j + 1] != '*')) {
                    found = j
                    break
                }
                if (j + 1 < s.length && s[j] == '*' && s[j + 1] == '*') break
                if (s[j] == '\n') break
                j++
            }
            if (found > i + 1) {
                val inner = s.substring(i + 1, found).trim()
                if (inner.isNotEmpty()) {
                    out.append('*').append(inner).append('*')
                }
                i = found + 1
            } else {
                i++ // unpaired — drop
            }
        } else if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') {
            out.append("**")
            i += 2
        } else {
            out.append(s[i])
            i++
        }
    }
    return out.toString()
}

private val TABLE_SEP = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*$""")
private val TABLE_ROW = Regex("""^\s*\|.+\|\s*$""")

private fun splitTableCells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split('|').map { it.trim() }
}

private fun parseBlocks(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<MdBlock>()
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        val t = para.toString().trimEnd()
        if (t.isNotEmpty()) out += MdBlock.Paragraph(t)
        para.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                flushPara()
                val lang = line.removePrefix("```").trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                out += MdBlock.Code(lang, body.toString())
            }
            // GFM pipe table: header + separator + rows
            TABLE_ROW.matches(line) && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) -> {
                flushPara()
                val headers = splitTableCells(line)
                i += 2 // skip header + separator
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && TABLE_ROW.matches(lines[i])) {
                    rows += splitTableCells(lines[i])
                    i++
                }
                out += MdBlock.Table(headers, rows)
                continue // already advanced i
            }
            line.matches(Regex("^#{1,6}\\s+.+")) -> {
                flushPara()
                val m = Regex("^(#{1,6})\\s+(.*)").find(line)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            line.matches(Regex("^\\s*[-*]\\s+.+")) -> {
                flushPara()
                val text = line.replace(Regex("^\\s*[-*]\\s+"), "")
                out += MdBlock.ListItem(text, ordered = false, index = 0)
            }
            line.matches(Regex("^\\s*\\d+\\.\\s+.+")) -> {
                flushPara()
                val m = Regex("^\\s*(\\d+)\\.\\s+(.*)").find(line)
                if (m != null) {
                    out += MdBlock.ListItem(
                        m.groupValues[2],
                        ordered = true,
                        index = m.groupValues[1].toIntOrNull() ?: 1,
                    )
                } else {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line)
                }
            }
            line.startsWith("> ") || line == ">" -> {
                flushPara()
                out += MdBlock.Quote(line.removePrefix("> ").ifBlank { "" })
            }
            line.isBlank() -> {
                flushPara()
                out += MdBlock.Blank
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flushPara()
    return out.ifEmpty { listOf(MdBlock.Paragraph(src)) }
}

private val LinkSpanStyle = SpanStyle(
    color = Color(0xFF60A5FA),
    textDecoration = TextDecoration.Underline,
)

private val LinkStyles = TextLinkStyles(style = LinkSpanStyle)

/** Bare http(s):// or www. URLs (not inside markdown syntax). */
private val BareUrlAtStart = Regex(
    """^(https?://|www\.)[^\s<>\[\]"'`]+""",
    RegexOption.IGNORE_CASE,
)

private val TrailingUrlPunct = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"', '»', '”', '’')

/**
 * Recursive-ish inline parse: code, bold, italic, clickable links (markdown + bare URLs).
 * Unpaired markers are already stripped by normalizeChatMarkdown.
 */
private fun inlineMarkdown(src: String, base: Color): AnnotatedString = buildAnnotatedString {
    appendInline(src, base)
}

private fun AnnotatedString.Builder.appendInline(src: String, base: Color) {
    var i = 0
    while (i < src.length) {
        // inline code
        if (src[i] == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0xFF1F2937),
                        color = Color(0xFFFBBF24),
                    )
                ) {
                    append(src.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // bold **
        if (i + 1 < src.length && src[i] == '*' && src[i + 1] == '*') {
            val end = src.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = base)) {
                    appendInline(src.substring(i + 2, end), base)
                }
                i = end + 2
                continue
            }
            // unpaired — skip stars (normalize should have handled)
            i += 2
            continue
        }
        // italic *
        if (src[i] == '*' && (i + 1 >= src.length || src[i + 1] != '*')) {
            val end = src.indexOf('*', i + 1)
            if (end > i + 1 && (end + 1 >= src.length || src[end + 1] != '*')) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = base)) {
                    appendInline(src.substring(i + 1, end), base)
                }
                i = end + 1
                continue
            }
            // unpaired — drop
            i++
            continue
        }
        // link [text](url) — tappable via LinkAnnotation
        if (src[i] == '[') {
            val close = src.indexOf(']', i + 1)
            if (close > i && close + 1 < src.length && src[close + 1] == '(') {
                val urlEnd = src.indexOf(')', close + 2)
                if (urlEnd > close) {
                    val label = src.substring(i + 1, close)
                    val rawUrl = src.substring(close + 2, urlEnd).trim()
                    val href = normalizeHref(rawUrl)
                    if (href != null) {
                        withLink(LinkAnnotation.Url(href, LinkStyles)) {
                            append(label.ifBlank { rawUrl })
                        }
                    } else {
                        withStyle(LinkSpanStyle) {
                            append(label.ifBlank { rawUrl })
                        }
                    }
                    i = urlEnd + 1
                    continue
                }
            }
        }
        // bare URL (https://… / http://… / www.…)
        val bare = matchBareUrl(src, i)
        if (bare != null) {
            val (display, href, end) = bare
            withLink(LinkAnnotation.Url(href, LinkStyles)) {
                append(display)
            }
            i = end
            continue
        }
        append(src[i])
        i++
    }
}

/**
 * Only http(s) and mailto — never javascript: / intent: / file: from chat text.
 * Returns navigable href or null if unsafe / empty.
 */
internal fun normalizeHref(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val lower = t.lowercase()
    return when {
        lower.startsWith("https://") || lower.startsWith("http://") -> t
        lower.startsWith("mailto:") && t.length > "mailto:".length -> t
        lower.startsWith("www.") -> "https://$t"
        else -> null
    }
}

/**
 * If [src] has a bare URL starting at [start], returns (display text, href, exclusive end index).
 * Uses a substring + ^ match so startIndex isn't broken by the start-of-input anchor.
 */
internal fun matchBareUrl(src: String, start: Int): Triple<String, String, Int>? {
    if (start < 0 || start >= src.length) return null
    // Avoid matching mid-token (e.g. foohttps://…)
    if (start > 0) {
        val prev = src[start - 1]
        if (prev.isLetterOrDigit() || prev == '_' || prev == '/' || prev == '=' || prev == '@') {
            return null
        }
    }
    val rest = src.substring(start)
    val m = BareUrlAtStart.find(rest) ?: return null
    var display = m.value
    // Trim common trailing sentence punctuation (keep balanced ) inside URLs)
    while (display.isNotEmpty() && display.last() in TrailingUrlPunct) {
        val last = display.last()
        if (last == ')') {
            val open = display.count { it == '(' }
            val close = display.count { it == ')' }
            if (open >= close) break
        }
        display = display.dropLast(1)
    }
    if (display.length < 4) return null
    val href = normalizeHref(display) ?: return null
    return Triple(display, href, start + display.length)
}
