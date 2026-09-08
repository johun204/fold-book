package com.foldbook.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** material-icons-extended 대신, 필요한 아이콘 몇 개만 표준 Material 경로로 직접 정의 (APK 용량 절약). */
object AppIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()

    val Folder = icon(
        "Folder",
        "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z",
    )
    val FolderOpen = icon(
        "FolderOpen",
        "M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z",
    )
    val Image = icon(
        "Image",
        "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z",
    )
    val Computer = icon(
        "Computer",
        "M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H3V4h18v12z",
    )
    val Cloud = icon(
        "Cloud",
        "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z",
    )
    val PhoneAndroid = icon(
        "PhoneAndroid",
        "M16 1H8C6.34 1 5 2.34 5 4v16c0 1.66 1.34 3 3 3h8c1.66 0 3-1.34 3-3V4c0-1.66-1.34-3-3-3zm-2 20h-4v-1h4v1zm3.25-3H6.75V4h10.5v14z",
    )
    val MenuBook = icon(
        "MenuBook",
        "M21 5c-1.11-.35-2.33-.5-3.5-.5-1.95 0-4.05.4-5.5 1.5-1.45-1.1-3.55-1.5-5.5-1.5S2.45 4.9 1 6v14.65c0 .25.25.5.5.5.1 0 .15-.05.25-.05C3.1 20.45 5.05 20 6.5 20c1.95 0 4.05.4 5.5 1.5 1.35-.85 3.8-1.5 5.5-1.5 1.65 0 3.35.3 4.75 1.05.1.05.15.05.25.05.25 0 .5-.25.5-.5V6c-.6-.45-1.25-.75-2-1zm0 13.5c-1.1-.35-2.3-.5-3.5-.5-1.7 0-4.15.65-5.5 1.5V8c1.35-.85 3.8-1.5 5.5-1.5 1.2 0 2.4.15 3.5.5v11.5z",
    )
    val Download = icon(
        "Download",
        "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
    )
    val DeleteSweep = icon(
        "DeleteSweep",
        "M15 16h4v2h-4zM15 8h7v2h-7zM15 12h6v2h-6zM3 18c0 1.1 0.9 2 2 2h6c1.1 0 2 -0.9 2 -2V8H3zM14 5h-3l-1 -1H6L5 5H2v2h12z",
    )
    val Visibility = icon(
        "Visibility",
        "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s10.27 -3.11 12 -7.5c-1.73 -4.39 -6 -7.5 -11 -7.5zM12 17c-2.76 0 -5 -2.24 -5 -5s2.24 -5 5 -5 5 2.24 5 5 -2.24 5 -5 5zM12 9c-1.66 0 -3 1.34 -3 3s1.34 3 3 3 3 -1.34 3 -3 -1.34 -3 -3 -3z",
    )
    val VisibilityOff = icon(
        "VisibilityOff",
        "M12 7c2.76 0 5 2.24 5 5 0 0.65 -0.13 1.26 -0.36 1.83l2.92 2.92c1.51 -1.26 2.7 -2.89 3.43 -4.75 -1.73 -4.39 -6 -7.5 -11 -7.5 -1.4 0 -2.74 0.25 -3.98 0.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28 0.46 0.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03 -0.3 4.38 -0.84l0.42 0.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-0.05 0.21 -0.08 0.43 -0.08 0.65 0 1.66 1.34 3 3 3 0.22 0 0.44 -0.03 0.65 -0.08l1.55 1.55c-0.67 0.33 -1.41 0.53 -2.2 0.53 -2.76 0 -5 -2.24 -5 -5 0 -0.79 0.2 -1.53 0.53 -2.2zM11.84 9.02l3.15 3.15 0.02 -0.16c0 -1.66 -1.34 -3 -3 -3l-0.19 0.01z",
    )
}
