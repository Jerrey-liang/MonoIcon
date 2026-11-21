package com.jerrey.monoicon.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.stringsFor

/** Labels shared by the global and per-application icon controls. */
internal class IconPreferencesStrings(private val chinese: Boolean) {
    private fun text(zh: String, en: String) = if (chinese) zh else en
    val defaultStyle = text("默认图标风格", "Default icon style")
    val defaultStyleSummary = text("设置应用默认优先使用的图标资源", "Choose the preferred icon source for apps")
    val perAppTitle = text("应用图标定制", "App icon customization")
    val generatedBorderOnly = text("仅作用于 MonoIcon 生成图标", "For MonoIcon generated icons only")
    val artworkRoundness = text("图案圆角", "Artwork roundness")
    val artworkRoundnessSummary = text("调整 MonoIcon 生成图标内层图案的圆角", "Round the inner artwork of MonoIcon generated icons")
    val artworkNoRoundness = text("无圆角", "No rounding")
    val artworkFollowIconRoundness = text("图案跟随图标圆角", "Artwork follows icon roundness")
    val artworkFollowIconSummary = text("内层图案使用图标圆角，独立图案圆角暂停生效", "Use icon roundness for the inner artwork; independent artwork roundness is disabled")
    val artworkIndependentSummary = text("内层图案使用独立的图案圆角", "Use independent roundness for the inner artwork")
    val artworkFollowingIcon = text("已跟随图标圆角，关闭跟随后可调整", "Following icon roundness; turn off following to adjust")
    val on = text("开启", "On")
    val off = text("关闭", "Off")
    val perAppSummary = text("单独设置图标风格、圆角、图案圆角、大小和白边", "Customize each app's style, icon and artwork roundness, size and border")
    val perAppHint = text("仅显示桌面应用及其原始图标。点击应用定制支持的图标风格和参数，各项可独立跟随默认或自定义。", "This list shows desktop apps and their original icons. Open an app to customize its supported styles and settings independently.")
    val followDefault = text("跟随默认", "Follow default")
    fun followDefaultStyle(style: IconSourceStyle) = text("跟随默认(${styleName(style)})", "Follow default (${styleName(style)})")
    val customMatchesDefault = text("独立设置，当前与默认风格相同", "Custom setting; currently matches the default style")
    val custom = text("自定义", "Custom")
    val nativeStyle = text("应用原生", "App native")
    val nativeGeneratedStyle = text("原生 → MonoIcon", "Native → MonoIcon")
    val lawniconsStyle = "Lawnicons"
    val generatedStyle = text("MonoIcon 生成", "MonoIcon generated")
    val iconStyle = text("图标风格", "Icon style")
    val sourceLoading = text("正在加载图标资源…", "Loading icon source…")
    val sourceUnavailable = text("图标资源暂时不可用", "Icon source is temporarily unavailable")
    val search = text("搜索应用名称或包名", "Search app name or package")
    val filters = text("筛选器", "Filters")
    val clearFilters = text("清除筛选", "Clear filters")
    val clearSearch = text("清除搜索", "Clear search")
    val all = text("全部", "All")
    val defaultFilter = text("默认", "Default")
    val hasNative = text("应用自带主题化图标", "Native themed icon")
    val hasLawnicons = text("有对应 Lawnicons 资源", "Lawnicons resource")
    val available = text("有", "Available")
    val unavailable = text("无", "Unavailable")
    val noMatches = text("没有符合条件的应用", "No matching apps")
    val noApps = text("没有可设置的应用", "No apps available")
    val loading = text("正在加载应用…", "Loading apps…")
    val error = text("无法加载应用列表", "Couldn't load apps")
    val edit = text("调整参数", "Edit settings")
    val collapse = text("收起参数", "Collapse settings")
    val resetApp = text("全部恢复默认", "Reset all to defaults")
    fun originalIconDescription(label: String) = text("$label，应用原始图标", "$label, original app icon")
    fun resultCount(count: Int) = text("$count 个应用", if (count == 1) "1 app" else "$count apps")
    fun defaultValue(value: Int) = "$followDefault · $value%"
    fun customValue(value: Int) = "$custom · $value%"
    fun currentSource(style: IconSourceStyle) = text("当前资源：${styleName(style)}", "Current source: ${styleName(style)}")
    fun styleName(style: IconSourceStyle): String = when (style) {
        IconSourceStyle.NATIVE -> nativeStyle
        IconSourceStyle.NATIVE_GENERATED -> nativeGeneratedStyle
        IconSourceStyle.LAWNICONS -> lawniconsStyle
        IconSourceStyle.GENERATED -> generatedStyle
    }
    fun styleSummary(style: IconSourceStyle, allowLawniconsFallback: Boolean = true): String = when (style) {
        IconSourceStyle.NATIVE -> if (allowLawniconsFallback)
            text("优先使用应用自带的主题化图标；缺失时使用 Lawnicons，再缺失时由 MonoIcon 生成", "Prefer the app's native themed icon, then Lawnicons, then MonoIcon generation")
        else text("优先使用应用自带的主题化图标；缺失时由 MonoIcon 生成", "Prefer the app's native themed icon, then MonoIcon generation")
        IconSourceStyle.NATIVE_GENERATED -> text("优先使用应用自带的主题化图标；缺失时直接由 MonoIcon 生成", "Prefer the app's native themed icon, then MonoIcon generation directly")
        IconSourceStyle.LAWNICONS -> text("优先使用 Lawnicons；缺失时依次使用应用原生主题化图标、MonoIcon 生成", "Prefer Lawnicons, then the app's native themed icon, then MonoIcon generation")
        IconSourceStyle.GENERATED -> text("始终由 MonoIcon 处理应用原始图标", "Always let MonoIcon process the app's original icon")
    }
}

@Composable
internal fun rememberIconPreferencesStrings(): IconPreferencesStrings {
    val strings = LocalStrings.current
    val chinese = strings == stringsFor(AppLanguage.CHINESE, false)
    return remember(chinese) { IconPreferencesStrings(chinese) }
}
