package com.mura.mesh.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Mura's own line icon set (24 px grid, 2 px stroke). Tinted by Icon() like any vector. */
object MIcons {
    private fun line(name: String, vararg d: String) = build(name, false, *d)
    private fun solid(name: String, vararg d: String) = build(name, true, *d)

    private fun build(name: String, filled: Boolean, vararg d: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            for (p in d) {
                addPath(
                    pathData = addPathNodes(p),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = if (filled) null else SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    val Back = line("back", "M15 6l-6 6 6 6")
    val Search = line("search", circle(11f, 11f, 7f), "M20 20l-4-4")
    val Plus = line("plus", "M12 5v14M5 12h14")
    val Send = line("send", "M21 3L10 14", "M21 3l-7 18-4-8-8-4z")
    val Mic = line("mic", "M9 5a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val MicOff = line("micoff", "M9 5a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0z", "M5 11a7 7 0 0 0 14 0", "M12 18v3", "M3 3l18 18")
    val Clip = line("clip", "M21 11l-8.5 8.5a5 5 0 0 1-7-7L14 4a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L15 7")
    val Image = line("image", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", circle(9f, 9f, 1.5f), "M21 15l-5-5L5 20")
    val Video = line("video", "M4 6h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M17 10l5-3v10l-5-3")
    val Camera = line("camera", "M4 7h3l2-3h6l2 3h3a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", circle(12f, 13f, 4f))
    val File = line("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5")
    val Pin = line("pin", "M12 21s-7-6.5-7-12a7 7 0 0 1 14 0c0 5.5-7 12-7 12z", circle(12f, 9f, 2.5f))
    val Phone = line("phone", "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2")
    val Hangup = line("hangup", "M3 15c5-5 13-5 18 0l-2.5 2.5-3-1.5v-2.5a10 10 0 0 0-7 0V16l-3 1.5z")
    val Speaker = line("speaker", "M4 9v6h4l5 4V5L8 9z", "M16.5 8.5a5 5 0 0 1 0 7", "M19 6a8.5 8.5 0 0 1 0 12")
    val Lock = line("lock", "M6 11h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V7a4 4 0 0 1 8 0v4")
    val Check = line("check", "M5 12l5 5L20 7")
    val Checks = line("checks", "M1.5 12.5l4.5 4.5 10-10", "M10.5 15.5l1.5 1.5 10-10")
    val Clock = line("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Mesh = line("mesh", circle(12f, 12f, 2.5f), circle(5f, 6f, 2f), circle(19f, 6f, 2f), circle(12f, 20f, 2f),
        "M10.2 10.5L6.5 7.4M13.8 10.5l3.7-3.1M12 14.5V18")
    val Qr = line("qr", "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h2v2h-2z", "M18 14h2", "M14 18v2h2", "M18 18h2v2h-2z")
    val Scan = line("scan", "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", "M7 12h10")
    val Settings = line("settings", "M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1", circle(15f, 6f, 2f), circle(9f, 12f, 2f), circle(17f, 18f, 2f))
    val More = solid("more", circle(12f, 5f, 1.6f), circle(12f, 12f, 1.6f), circle(12f, 19f, 1.6f))
    val Play = solid("play", "M7 4v16l13-8z")
    val Pause = solid("pause", "M6 4h4v16H6z", "M14 4h4v16h-4z")
    val Eye = line("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    val Once = line("once", circle(12f, 12f, 9f), "M10.5 9l2-1.5V16")
    val Close = line("close", "M6 6l12 12M18 6L6 18")
    val Download = line("download", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
    val Warning = line("warning", "M12 3l10 18H2z", "M12 10v5", "M12 18v0.5")
    val User = line("user", circle(12f, 8f, 4f), "M4 21c0-4 4-6 8-6s8 2 8 6")
    val Shield = line("shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", "M9 12l2 2 4-4")
    val Trash = line("trash", "M4 7h16", "M10 11v6M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val Copy = line("copy", "M9 9h11v11H9z", "M5 15H4V4h11v1")
    val Share = line("share", "M12 3v12", "M7 8l5-5 5 5", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6")
    val Bluetooth = line("bluetooth", "M7 7l10 10-5 5V2l5 5L7 17")
    val Battery = line("battery", "M3 8h15a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M22 11v2", "M5 11v2")
    val Ban = line("ban", circle(12f, 12f, 9f), "M5.6 5.6l12.8 12.8")
    val Key = line("key", circle(7.5f, 15.5f, 3.5f), "M10 13l9-9", "M16 7l2.5 2.5", "M19 4l2 2")
    val Edit = line("edit", "M4 20h4L20 8l-4-4L4 16z")
    val Group = line("group", circle(9f, 8f, 3.5f), circle(17f, 9f, 2.5f), "M3 19c0-3 3-5 6-5s6 2 6 5", "M15 14c3 0 6 1.5 6 4.5")
    val Smile = line("smile", circle(12f, 12f, 9f), "M8 14s1.5 2 4 2 4-2 4-2", "M9 9.5v0.5M15 9.5v0.5")
    val Record = solid("record", circle(12f, 12f, 6f))
    val Status = line("status", circle(12f, 12f, 9f), circle(12f, 12f, 4f))
    val Link = line("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val Cloud = line("cloud", "M7 18h10a4 4 0 0 0 0.5-8A6 6 0 0 0 6 9a4.5 4.5 0 0 0 1 9z")
    val Pdf = line("pdf", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M8 14h2a1.5 1.5 0 0 0 0-3H8v6")
    val Pin2 = line("pinchat", "M9 4h6l-1 6 3 3H7l3-3z", "M12 13v7")
    val Mute = line("mute", "M6 16V11a6 6 0 0 1 9-5.2", "M18 11v5l2 2H8", "M10 21h4", "M3 3l18 18")
    val Exit = line("exit", "M15 4h4v16h-4", "M10 8l-4 4 4 4", "M6 12h11")
    val Crown = line("crown", "M4 18h16", "M4 8l4 4 4-7 4 7 4-4-2 10H6z")
    val Bell = line("bell", "M6 16V11a6 6 0 0 1 12 0v5l2 2H4z", "M10 21h4")
    val Forward = line("forward", "M15 7l5 5-5 5", "M20 12H10a6 6 0 0 0-6 6v1")
    val Pinned = line("pinned", "M9 4h6l-1 6 3 3H7l3-3z", "M12 13v7")
    val Up = line("up", "M6 15l6-6 6 6")
    val Down = line("down", "M6 9l6 6 6-6")
    val Poll = line("poll", "M5 20V10", "M12 20V4", "M19 20v-7")
    val ListIcon = line("list", "M9 6h11M9 12h11M9 18h11", "M4 6h.01M4 12h.01M4 18h.01")
    val Undo = line("undo", "M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3")
    val Star = line("star", "M12 3l2.8 5.8 6.2.9-4.5 4.4 1 6.3L12 17.5 6.5 20.4l1-6.3L3 9.7l6.2-.9z")
    val StarFilled = solid("starfilled", "M12 3l2.8 5.8 6.2.9-4.5 4.4 1 6.3L12 17.5 6.5 20.4l1-6.3L3 9.7l6.2-.9z")
    val Brush = line("brush", "M18 3l3 3-9 9-3-3z", "M9 12c-3 0-5 2-5 5 0 1.5-1 2.5-2 3 3 1 7 0 8-3l1-2")
    val Reply = line("reply", "M9 7L4 12l5 5", "M4 12h10a6 6 0 0 1 6 6v1")
    val Timer = line("timer", circle(12f, 13f, 8f), "M12 9v4l2.5 2", "M9 2h6")
    val Flame = line("flame", "M12 22a7 7 0 0 0 7-7c0-4-3-6.5-4-9.5-1.5 2-2 3.5-2.5 5.5C11 9 9.5 7.5 9 5c-2 2.5-4 5.5-4 10a7 7 0 0 0 7 7z")
    val Fingerprint = line("fingerprint", "M12 11v3a8 8 0 0 1-1.5 4.5", "M8.5 9.5A4 4 0 0 1 16 11v2a12 12 0 0 1-1 5",
        "M5.5 7.5A8 8 0 0 1 20 11v1.5", "M4.5 12v1a10 10 0 0 0 1 4.5", "M8 12.5v.5a7 7 0 0 1-1.3 4.2")
    val Clipboard = line("clipboard", "M9 4h6v3H9z", "M15 5h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h3")
    val Keyboard = line("keyboard", "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M6 10h.01M10 10h.01M14 10h.01M18 10h.01", "M7 14h10")
    // 2.3
    val Translate = line("translate", "M4 5h8", "M8 3v2", "M10.5 5c-1 4-3.5 7-6.5 8.5", "M6 9c1 2 3 3.5 5 4.5", "M12 21l4-9 4 9", "M13.3 18h5.4")
    val Palette = line("palette", "M12 3a9 9 0 0 0 0 18c1.2 0 1.8-.9 1.8-1.8 0-1.2-1-1.5-1-2.6 0-1 .8-1.6 1.8-1.6H17a4 4 0 0 0 4-4C21 6.5 17 3 12 3z",
        "M7.5 11h.01M10 7.5h.01M14.5 7.5h.01M17 11h.01")
    val Globe = line("globe", circle(12f, 12f, 9f), "M3 12h18", "M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9s1-6.5 3.5-9z")
    val Refresh = line("refresh", "M20 11a8 8 0 0 0-14.8-4.2L4 8", "M4 4v4h4", "M4 13a8 8 0 0 0 14.8 4.2L20 16", "M20 20v-4h-4")
    val Archive = line("archive", "M3 4h18v4H3z", "M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8", "M10 12h4")
    val CallIn = line("callin", "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2", "M21 3l-6 6", "M15 4v5h5")
    val CallOut = line("callout", "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2", "M15 9l6-6", "M16 3h5v5")
}
