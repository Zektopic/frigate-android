package com.zektopic.frigate.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.ui.theme.FrigateTheme

// The path from web/src/components/Logo.tsx, viewBox 0 0 512 512.
private const val LOGO_PATH =
    "M130 446.5C131.6 459.3 145 468 137 470C129 472 94 406.5 86 378.5C78 350.5 73.5 319 75.5 301" +
        "C77.4999 283 181 255 181 247.5C181 240 147.5 247 146 241C144.5 235 171.3 238.6 178.5 229" +
        "C189.75 214 204 216.5 213 208.5C222 200.5 233 170 235 157C237 144 215 129 209 119" +
        "C203 109 222 102 268 83C314 64 460 22 462 27C464 32 414 53 379 66C344 79 287 104 287 111" +
        "C287 118 290 123.5 288 139.5C286 155.5 285.76 162.971 282 173.5C279.5 180.5 277 197 282 212" +
        "C286 224 299 233 305 235C310 235.333 323.8 235.8 339 235C358 234 385 236 385 241" +
        "C385 246 344 243 344 250C344 257 386 249 385 256C384 263 350 260 332 260" +
        "C317.6 260 296.333 259.333 287 256L285 263C281.667 263 274.7 265 267.5 265" +
        "C258.5 265 258 268 241.5 268C225 268 230 267 215 266C200 265 144 308 134 322" +
        "C124 336 130 370 130 385.5C130 399.428 128 430.5 130 446.5Z"

/** The Frigate bird, drawn in the current text colour like the web UI's `fill-current`. */
@Composable
fun FrigateLogo(modifier: Modifier = Modifier, tint: Color = FrigateTheme.colors.primary) {
    val vector = remember(tint) {
        ImageVector.Builder(
            name = "FrigateLogo",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 512f,
            viewportHeight = 512f
        ).addPath(pathData = addPathNodes(LOGO_PATH), fill = SolidColor(tint)).build()
    }
    Image(imageVector = vector, contentDescription = "Frigate", modifier = modifier)
}
