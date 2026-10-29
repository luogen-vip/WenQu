package com.wenqu.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.wenqu.app.Nlp

private const val LINK_TAG = "wenqu_link"

private data class Seg(val isLink: Boolean, val text: String)

private fun parseSegments(text: String): List<Seg> {
    val out = ArrayList<Seg>()
    var last = 0
    for (m in Nlp.urlRegex.findAll(text)) {
        if (m.range.first > last) out.add(Seg(false, text.substring(last, m.range.first)))
        out.add(Seg(true, m.value))
        last = m.range.last + 1
    }
    if (last < text.length) out.add(Seg(false, text.substring(last)))
    return out
}

/** 自动识别并高亮链接，点击后交给内置 WebView 打开 */
@Composable
fun LinkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    onOpenLink: (String) -> Unit
) {
    val annotated: AnnotatedString = remember(text, linkColor) {
        buildAnnotatedString {
            parseSegments(text).forEach { seg ->
                if (seg.isLink) {
                    pushStringAnnotation(LINK_TAG, seg.text)
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold
                        )
                    ) { append(seg.text) }
                    pop()
                } else {
                    append(seg.text)
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures { pos ->
                layout?.let { lr ->
                    val off = lr.getOffsetForPosition(pos)
                    annotated.getStringAnnotations(LINK_TAG, off, off)
                        .firstOrNull()?.let { onOpenLink(it.item) }
                }
            }
        },
        onTextLayout = { layout = it }
    )
}
