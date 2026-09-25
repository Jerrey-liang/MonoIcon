package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared title and content spacing for every primary settings page. */
internal object PageSpacing {
    /** Page gutter: the horizontal margin shared by the title and every card. */
    val gutter = 16.dp

    /** Inner padding of a card's own content, as used by `CardDefaults.insideMargin`. */
    val cardStart = 16.dp

    /**
     * Vertical inner margin of a standalone card row. Taller than Miuix's own
     * default so a one-line entry matches the reference row height.
     */
    val cardVerticalPadding = 10.dp

    /** Align the page title with the leading text inside the cards. */
    val titleStart = gutter + cardStart

    /** Air between the top bar and the page title. */
    val titleTop = 4.dp

    /** Air between the page title and the first card of the page. */
    val titleToContent = 24.dp

    /** Vertical rhythm between two sibling cards. */
    val betweenCards = 12.dp

    /** Air above a section label (`作用域`, `语言`, …) that follows a card. */
    val sectionLabelGap = 24.dp
}

/**
 * Large left-aligned page title.
 *
 * Uses the shared [PageSpacing] insets so all primary pages start at exactly the
 * same place. The color token is unchanged (`onBackground`).
 */
@Composable
fun LargeScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.headline1.copy(
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 40.sp,
        ),
        color = MiuixTheme.colorScheme.onBackground,
        modifier = modifier.padding(
            start = PageSpacing.titleStart,
            end = PageSpacing.gutter,
            top = PageSpacing.titleTop,
        ),
    )
}

/**
 * Page content: applies [PageSpacing.titleToContent] below the large title.
 *
 * Wrapping the page body (instead of letting every screen add its own spacer)
 * is what makes the title → first card distance identical on every page: the
 * title is the first child and reports its bottom edge, the body follows.
 *
 * The host's scrolling column is expected to keep its own top inset at zero, so
 * the title's [PageSpacing.titleTop] is the page's only top offset.
 */
@Composable
fun PageContent(
    title: String,
    modifier: Modifier = Modifier,
    onTitleBottomPositioned: ((Int) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        LargeScreenTitle(
            text = title,
            modifier = if (onTitleBottomPositioned != null) {
                Modifier.onGloballyPositioned { coordinates ->
                    onTitleBottomPositioned(
                        (coordinates.positionInParent().y + coordinates.size.height).toInt(),
                    )
                }
            } else {
                Modifier
            },
        )
        Column(modifier = Modifier.padding(top = PageSpacing.titleToContent)) {
            content()
        }
    }
}
