package com.jerrey.monoicon.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * MonoIcon's navigation artwork. All four silhouettes use a 24dp canvas, rounded
 * corners and a solid fill, matching the visual weight of KernelSU's navigation.
 * Cutouts stay transparent so the icons work with dynamic colors and glass bars.
 */
internal object MonoIcons {
    val Home: ImageVector by lazy {
        filledIcon(
            "MonoIcon.Home",
            """
                M3.24 10.18 L10.58 3.84 Q12 2.61 13.42 3.84 L20.76 10.18
                Q21.38 10.72 21.11 11.47 Q20.86 12.2 20.04 12.2 H19.2 V18.4
                Q19.2 20.5 17.1 20.5 H14.2 V15.05 Q14.2 14 13.15 14 H10.85
                Q9.8 14 9.8 15.05 V20.5 H6.9 Q4.8 20.5 4.8 18.4 V12.2 H3.96
                Q3.14 12.2 2.89 11.47 Q2.62 10.72 3.24 10.18 Z
            """,
        )
    }

    val IconStyle: ImageVector by lazy {
        filledIcon(
            "MonoIcon.IconStyle",
            """
                M5.15 3.25 H8.35 Q10.25 3.25 10.25 5.15 V8.35
                Q10.25 10.25 8.35 10.25 H5.15 Q3.25 10.25 3.25 8.35 V5.15
                Q3.25 3.25 5.15 3.25 Z
                M16.35 3.14 Q17 2.49 17.65 3.14 L20.86 6.35
                Q21.51 7 20.86 7.65 L17.65 10.86 Q17 11.51 16.35 10.86
                L13.14 7.65 Q12.49 7 13.14 6.35 Z
                M10.5 17 A3.75 3.75 0 1 1 3 17 A3.75 3.75 0 1 1 10.5 17 Z
                M15.65 13.75 H18.85 Q20.75 13.75 20.75 15.65 V18.85
                Q20.75 20.75 18.85 20.75 H15.65 Q13.75 20.75 13.75 18.85 V15.65
                Q13.75 13.75 15.65 13.75 Z
            """,
        )
    }

    val Configuration: ImageVector by lazy {
        filledIcon(
            "MonoIcon.Configuration",
            """
                M4.9 3.8 H9.3 Q10.05 3.8 10.6 4.35 L12.25 6 H19.1
                Q21 6 21 7.9 V18.6 Q21 20.5 19.1 20.5 H4.9
                Q3 20.5 3 18.6 V5.7 Q3 3.8 4.9 3.8 Z
                M6.75 10.25 H9 V9.6 Q9 8.8 9.8 8.8 Q10.6 8.8 10.6 9.6 V10.25
                H17.25 Q18 10.25 18 11 Q18 11.75 17.25 11.75 H10.6 V12.4
                Q10.6 13.2 9.8 13.2 Q9 13.2 9 12.4 V11.75 H6.75
                Q6 11.75 6 11 Q6 10.25 6.75 10.25 Z
                M6.75 15.25 H13.4 V14.6 Q13.4 13.8 14.2 13.8 Q15 13.8 15 14.6
                V15.25 H17.25 Q18 15.25 18 16 Q18 16.75 17.25 16.75 H15 V17.4
                Q15 18.2 14.2 18.2 Q13.4 18.2 13.4 17.4 V16.75 H6.75
                Q6 16.75 6 16 Q6 15.25 6.75 15.25 Z
            """,
        )
    }

    val Settings: ImageVector by lazy {
        filledIcon(
            "MonoIcon.Settings",
            """
                M9.39 4.69 Q9.85 4.5 10.04 3.97 L10.33 3.14 Q10.51 2.62 11.35 2.62
                L12.65 2.62 Q13.49 2.62 13.67 3.14 L13.96 3.97 Q14.15 4.5 14.61 4.69
                L15.32 4.99 Q15.78 5.18 16.29 4.94 L17.08 4.56 Q17.58 4.31 18.17 4.9
                L19.1 5.83 Q19.69 6.42 19.44 6.92 L19.06 7.71 Q18.82 8.22 19.01 8.68
                L19.31 9.39 Q19.5 9.85 20.03 10.04 L20.86 10.33 Q21.38 10.51 21.38 11.35
                L21.38 12.65 Q21.38 13.49 20.86 13.67 L20.03 13.96 Q19.5 14.15 19.31 14.61
                L19.01 15.32 Q18.82 15.78 19.06 16.29 L19.44 17.08 Q19.69 17.58 19.1 18.17
                L18.17 19.1 Q17.58 19.69 17.08 19.44 L16.29 19.06 Q15.78 18.82 15.32 19.01
                L14.61 19.31 Q14.15 19.5 13.96 20.03 L13.67 20.86 Q13.49 21.38 12.65 21.38
                L11.35 21.38 Q10.51 21.38 10.33 20.86 L10.04 20.03 Q9.85 19.5 9.39 19.31
                L8.68 19.01 Q8.22 18.82 7.71 19.06 L6.92 19.44 Q6.42 19.69 5.83 19.1
                L4.9 18.17 Q4.31 17.58 4.56 17.08 L4.94 16.29 Q5.18 15.78 4.99 15.32
                L4.69 14.61 Q4.5 14.15 3.97 13.96 L3.14 13.67 Q2.62 13.49 2.62 12.65
                L2.62 11.35 Q2.62 10.51 3.14 10.33 L3.97 10.04 Q4.5 9.85 4.69 9.39
                L4.99 8.68 Q5.18 8.22 4.94 7.71 L4.56 6.92 Q4.31 6.42 4.9 5.83
                L5.83 4.9 Q6.42 4.31 6.92 4.56 L7.71 4.94 Q8.22 5.18 8.68 4.99 Z
                M15.4 12 A3.4 3.4 0 1 1 8.6 12 A3.4 3.4 0 1 1 15.4 12 Z
            """,
        )
    }

    private fun filledIcon(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(path.trimIndent()).toNodes(),
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.EvenOdd,
    ).build()
}
