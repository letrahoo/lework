package dev.lework.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Official Brand Colors & Verified Official SVG Vector Geometries
// ---------------------------------------------------------------------------

internal val ClaudeBrandTerracotta = Color(0xFFD97757)
internal val OpenAiBrandLight = Color(0xFFECEAE4)
internal val DeepSeekBrandBlue = Color(0xFF4D6BFE)
internal val CursorBrandLight = Color(0xFFEDECEC)
internal val TraeBrandGreen = Color(0xFF32F08C)

private const val CLAUDE_STARBURST_PATH =
    "m4.7144 15.9555 l4.7174 -2.6471 l.079 -.2307 l-.079 -.1275 h-.2307 l-.7893 -.0486 " +
        "l-2.6956 -.0729 l-2.3375 -.0971 l-2.2646 -.1214 l-.5707 -.1215 l-.5343 -.7042 " +
        "l.0546 -.3522 l.4797 -.3218 l.686 .0608 l1.5179 .1032 l2.2767 .1578 l1.6514 .0972 " +
        "l2.4468 .255 h.3886 l.0546 -.1579 l-.1336 -.0971 l-.1032 -.0972 L6.973 9.8356 " +
        "l-2.55 -1.6879 l-1.3356 -.9714 l-.7225 -.4918 l-.3643 -.4614 l-.1578 -1.0078 " +
        "l.6557 -.7225 l.8803 .0607 l.2246 .0607 l.8925 .686 l1.9064 1.4754 l2.4893 1.8336 " +
        "l.3643 .3035 l.1457 -.1032 l.0182 -.0728 l-.164 -.2733 l-1.3539 -2.4467 " +
        "l-1.445 -2.4893 l-.6435 -1.032 l-.17 -.6194 c-.0607 -.255 -.1032 -.4674 -.1032 -.7285 " +
        "L6.287 .1335 L6.6997 0 l.9957 .1336 l.419 .3642 l.6192 1.4147 l1.0018 2.2282 " +
        "l1.5543 3.0296 l.4553 .8985 l.2429 .8318 l.091 .255 h.1579 v-.1457 l.1275 -1.706 " +
        "l.2368 -2.0947 l.2307 -2.6957 l.0789 -.7589 l.3764 -.9107 l.7468 -.4918 l.5828 .2793 " +
        "l.4797 .686 l-.0668 .4433 l-.2853 1.8517 l-.5586 2.9021 l-.3643 1.9429 h.2125 " +
        "l.2429 -.2429 l.9835 -1.3053 l1.6514 -2.0643 l.7286 -.8196 l.85 -.9046 l.5464 -.4311 " +
        "h1.0321 l.759 1.1293 l-.34 1.1657 l-1.0625 1.3478 l-.8804 1.1414 l-1.2628 1.7 " +
        "l-.7893 1.36 l.0729 .1093 l.1882 -.0183 l2.8535 -.607 l1.5421 -.2794 l1.8396 -.3157 " +
        "l.8318 .3886 l.091 .3946 l-.3278 .8075 l-1.967 .4857 l-2.3072 .4614 l-3.4364 .8136 " +
        "l-.0425 .0304 l.0486 .0607 l1.5482 .1457 l.6618 .0364 h1.621 l3.0175 .2247 " +
        "l.7892 .522 l.4736 .6376 l-.079 .4857 l-1.2142 .6193 l-1.6393 -.3886 l-3.825 -.9107 " +
        "l-1.3113 -.3279 h-.1822 v.1093 l1.0929 1.0686 l2.0035 1.8092 l2.5075 2.3314 " +
        "l.1275 .5768 l-.3218 .4554 l-.34 -.0486 l-2.2039 -1.6575 l-.85 -.7468 l-1.9246 -1.621 " +
        "h-.1275 v.17 l.4432 .6496 l2.3436 3.5214 l.1214 1.0807 l-.17 .3521 l-.6071 .2125 " +
        "l-.6679 -.1214 l-1.3721 -1.9246 L14.38 17.959 l-1.1414 -1.9428 l-.1397 .079 " +
        "l-.674 7.2552 l-.3156 .3703 l-.7286 .2793 l-.6071 -.4614 l-.3218 -.7468 " +
        "l.3218 -1.4753 l.3886 -1.9246 l.3157 -1.53 l.2853 -1.9004 l.17 -.6314 l-.0121 -.0425 " +
        "l-.1397 .0182 l-1.4328 1.9672 l-2.1796 2.9446 l-1.7243 1.8456 l-.4128 .164 " +
        "l-.7164 -.3704 l.0667 -.6618 l.4008 -.5889 l2.386 -3.0357 l1.4389 -1.882 " +
        "l.929 -1.0868 l-.0062 -.1579 h-.0546 l-6.3385 4.1164 l-1.1293 .1457 l-.4857 -.4554 " +
        "l.0608 -.7467 l.2307 -.2429 l1.9064 -1.3114 Z"

private const val OPENAI_BLOSSOM_PATH =
    "M252.794 108.802 C289.191 99.0484 326.265 110.305 351.148 135.135 " +
        "C385.113 126.072 422.85 134.862 449.492 161.505 C476.136 188.149 484.925 225.888 475.862 259.85 " +
        "V259.854 C500.696 284.735 511.95 321.81 502.198 358.207 C492.447 394.602 464.161 421.084 430.215 430.217 " +
        "C421.083 464.162 394.603 492.448 358.206 502.199 C321.812 511.951 284.734 500.693 259.852 475.864 " +
        "C225.887 484.927 188.15 476.137 161.507 449.495 C134.864 422.851 126.073 385.111 135.136 351.149 " +
        "C110.304 326.266 99.0496 289.192 108.801 252.795 C118.552 216.4 146.84 189.918 180.784 180.785 " +
        "C189.917 146.841 216.396 118.553 252.794 108.802 Z " +
        "M374.292 407.145 C374.292 411.271 372.092 415.086 368.517 417.148 L283.723 466.102 " +
        "C302.487 480.585 327.555 486.459 352.217 479.852 C386.997 470.532 410.068 439.312 410.555 405.006 " +
        "V317.717 C410.555 315.08 409.125 312.621 406.843 311.303 L374.292 292.509 V407.145 Z " +
        "M251.868 415.897 C248.296 417.959 243.893 417.959 240.317 415.897 L155.526 366.942 " +
        "C152.366 390.436 159.811 415.08 177.866 433.136 H177.863 C203.325 458.594 241.896 462.962 271.85 446.232 " +
        "L347.449 402.586 C349.735 401.268 351.148 398.8 351.148 396.163 V358.579 L251.868 415.897 Z " +
        "M368.602 220.628 C366.319 219.309 363.474 219.318 361.191 220.637 L328.641 239.431 L427.921 296.749 " +
        "C431.496 298.811 433.697 302.627 433.697 306.752 V404.661 C455.622 395.654 473.244 376.881 479.851 352.218 " +
        "C489.169 317.442 473.668 281.85 444.201 264.274 L368.602 220.628 Z " +
        "M177.303 206.34 C155.377 215.348 137.756 234.122 131.148 258.783 " +
        "C121.832 293.561 137.331 329.153 166.799 346.727 L242.398 390.373 " +
        "C244.68 391.692 247.525 391.684 249.807 390.366 L282.357 371.572 L183.078 314.253 " +
        "C179.504 312.189 177.303 308.375 177.303 304.251 V206.34 Z " +
        "M259.849 279.145 V331.858 L305.5 358.213 L351.15 331.858 V279.145 L305.5 252.789 L259.849 279.145 Z " +
        "M327.276 144.9 C308.512 130.418 283.445 124.543 258.782 131.15 " +
        "C224.002 140.471 200.931 171.691 200.445 205.995 V293.286 C200.445 295.923 201.875 298.381 204.158 299.7 " +
        "L236.707 318.493 V203.856 C236.707 199.731 238.909 195.916 242.483 193.853 L327.276 144.9 Z " +
        "M433.137 177.867 C407.675 152.407 369.103 148.038 339.149 164.769 L263.55 208.415 " +
        "C261.265 209.734 259.852 212.202 259.852 214.838 V252.423 L359.132 195.105 " +
        "C362.703 193.041 367.108 193.041 370.682 195.105 L455.473 244.06 " +
        "C458.635 220.567 451.189 195.922 433.135 177.867 H433.137 Z"

private const val ANTIGRAVITY_ARCH_PATH =
    "M14.0777 13.984 C14.945 14.6345 16.2458 14.2008 15.0533 13.0084 " +
        "C11.476 9.53949 12.2349 0 7.79033 0 " +
        "C3.34579 0 4.10461 9.53949 0.527295 13.0084 " +
        "C-0.773543 14.3092 0.635692 14.6345 1.50293 13.984 " +
        "C4.86344 11.7076 4.64663 7.69664 7.79033 7.69664 " +
        "C10.934 7.69664 10.7172 11.7076 14.0777 13.984 Z"

private const val PI_CORAL_PATH = "M165.29 165.29 H517.36 V400 H400 V282.65 H165.29 Z"
private const val PI_BLUE_PATH = "M165.29 282.65 H282.65 V400 H400 V517.36 H282.65 V634.72 H165.29 Z"
private const val PI_GOLD_PATH = "M517.36 400 H634.72 V634.72 H517.36 Z"

private const val DEEPSEEK_WHALE_PATH =
    "M23.748 4.482 c-.254 -.124 -.364 .113 -.512 .234 c-.051 .039 -.094 .09 -.137 .136 " +
        "c-.372 .397 -.806 .657 -1.373 .626 c-.829 -.046 -1.537 .214 -2.163 .848 " +
        "c-.133 -.782 -.575 -1.248 -1.247 -1.548 c-.352 -.156 -.708 -.311 -.955 -.65 " +
        "c-.172 -.241 -.219 -.51 -.305 -.774 c-.055 -.16 -.11 -.323 -.293 -.35 " +
        "c-.2 -.031 -.278 .136 -.356 .276 c-.313 .572 -.434 1.202 -.422 1.84 " +
        "c.027 1.436 .633 2.58 1.838 3.393 c.137 .093 .172 .187 .129 .323 " +
        "c-.082 .28 -.18 .552 -.266 .833 c-.055 .179 -.137 .217 -.329 .14 " +
        "a5.526 5.526 0 0 1 -1.736 -1.18 c-.857 -.828 -1.631 -1.742 -2.597 -2.458 " +
        "a11.365 11.365 0 0 0 -.689 -.471 c-.985 -.957 .13 -1.743 .388 -1.836 " +
        "c.27 -.098 .093 -.432 -.779 -.428 c-.872 .004 -1.67 .295 -2.687 .684 " +
        "a3.055 3.055 0 0 1 -.465 .137 a9.597 9.597 0 0 0 -2.883 -.102 " +
        "c-1.885 .21 -3.39 1.102 -4.497 2.623 C.082 8.606 -.231 10.684 .152 12.85 " +
        "c.403 2.284 1.569 4.175 3.36 5.653 c1.858 1.533 3.997 2.284 6.438 2.14 " +
        "c1.482 -.085 3.133 -.284 4.994 -1.86 c.47 .234 .962 .327 1.78 .397 " +
        "c.63 .059 1.236 -.03 1.705 -.128 c.735 -.156 .684 -.837 .419 -.961 " +
        "c-2.155 -1.004 -1.682 -.595 -2.113 -.926 c1.096 -1.296 2.746 -2.642 3.392 -7.003 " +
        "c.05 -.347 .007 -.565 0 -.845 c-.004 -.17 .035 -.237 .23 -.256 " +
        "a4.173 4.173 0 0 0 1.545 -.475 c1.396 -.763 1.96 -2.015 2.093 -3.517 " +
        "c.02 -.23 -.004 -.467 -.247 -.588 z " +
        "M11.581 18 c-2.089 -1.642 -3.102 -2.183 -3.52 -2.16 c-.392 .024 -.321 .471 -.235 .763 " +
        "c.09 .288 .207 .486 .371 .739 c.114 .167 .192 .416 -.113 .603 " +
        "c-.673 .416 -1.842 -.14 -1.897 -.167 c-1.361 -.802 -2.5 -1.86 -3.301 -3.307 " +
        "c-.774 -1.393 -1.224 -2.887 -1.298 -4.482 c-.02 -.386 .093 -.522 .477 -.592 " +
        "a4.696 4.696 0 0 1 1.529 -.039 c2.132 .312 3.946 1.265 5.468 2.774 " +
        "c.868 .86 1.525 1.887 2.202 2.891 c.72 1.066 1.494 2.082 2.48 2.914 " +
        "c.348 .292 .625 .514 .891 .677 c-.802 .09 -2.14 .11 -3.054 -.614 z " +
        "m1 -6.44 a.306 .306 0 0 1 .415 -.287 a.302 .302 0 0 1 .2 .288 " +
        "a.306 .306 0 0 1 -.31 .307 a.303 .303 0 0 1 -.304 -.308 z " +
        "m3.11 1.596 c-.2 .081 -.399 .151 -.59 .16 a1.245 1.245 0 0 1 -.798 -.254 " +
        "c-.274 -.23 -.47 -.358 -.552 -.758 a1.73 1.73 0 0 1 .016 -.588 " +
        "c.07 -.327 -.008 -.537 -.239 -.727 c-.187 -.156 -.426 -.199 -.688 -.199 " +
        "a.559 .559 0 0 1 -.254 -.078 a.253 .253 0 0 1 -.114 -.358 " +
        "c.028 -.054 .16 -.186 .192 -.21 c.356 -.202 .767 -.136 1.146 .016 " +
        "c.352 .144 .618 .408 1.001 .782 c.391 .451 .462 .576 .685 .914 " +
        "c.176 .265 .336 .537 .445 .848 c.067 .195 -.019 .354 -.25 .452 z"

private const val CURSOR_CUBE_PATH =
    "M457.43 125.94 L244.42 2.96 c-6.84 -3.95 -15.28 -3.95 -22.12 0 L9.3 125.94 " +
        "c-5.75 3.32 -9.3 9.46 -9.3 16.11 v247.99 c0 6.65 3.55 12.79 9.3 16.11 l213.01 122.98 " +
        "c6.84 3.95 15.28 3.95 22.12 0 l213.01 -122.98 c5.75 -3.32 9.3 -9.46 9.3 -16.11 v-247.99 " +
        "c0 -6.65 -3.55 -12.79 -9.3 -16.11 h-.01 Z " +
        "M444.05 151.99 l-205.63 356.16 c-1.39 2.4 -5.06 1.42 -5.06 -1.36 v-233.21 " +
        "c0 -4.66 -2.49 -8.97 -6.53 -11.31 L24.87 145.67 c-2.4 -1.39 -1.42 -5.06 1.36 -5.06 h411.26 " +
        "c5.84 0 9.49 6.33 6.57 11.39 h-.01 Z"

private const val ACP_REGISTRY_PATH =
    "M568.003 115.821 L517.278 27.9661 C507.183 10.4816 489.084 0.0227367 468.894 0.0227367 " +
        "C448.727 0.0227367 430.674 10.4361 420.556 27.8752 L343.251 161.749 H242.755 " +
        "C236.23 161.749 230.386 158.384 227.135 152.745 C223.861 147.106 223.861 140.376 227.135 134.715 " +
        "L277.861 46.8603 C281.112 41.2216 286.955 37.8338 293.481 37.8338 " +
        "C300.006 37.8338 305.827 41.1988 309.101 46.8603 L312.125 52.0897 " +
        "C313.353 54.2042 315.604 55.5002 318.036 55.5002 C320.469 55.5002 322.743 54.1815 323.948 52.067 " +
        "L337.385 28.5118 C338.795 26.0335 338.522 22.9413 336.703 20.7586 C325.699 7.57131 309.874 0 293.322 0 " +
        "C292.662 0 292.003 0 291.321 0.0454733 C272.04 0.75031 254.76 11.1864 245.074 27.9434 " +
        "L200.215 105.657 L155.81 29.1484 C145.465 11.2092 126.594 0.0227367 106.608 0.0227367 " +
        "C105.949 0.0227367 105.289 0.0227367 104.607 0.06821 C85.3265 0.773047 68.0467 11.2092 58.3608 27.9661 " +
        "L7.65806 115.821 C-6.25678 139.899 -0.868187 168.82 21.05 187.759 " +
        "C29.8945 195.422 41.6039 199.628 54.0181 199.628 H148.648 " +
        "C151.081 199.628 153.332 198.332 154.56 196.217 L168.52 172.026 " +
        "C169.748 169.911 169.748 167.319 168.52 165.205 C167.292 163.09 165.041 161.794 162.608 161.794 " +
        "H56.0417 C49.5163 161.794 43.6729 158.429 40.4216 152.79 C37.1475 147.152 37.1475 140.422 40.4216 134.76 " +
        "L91.1471 46.9057 C94.3985 41.2671 100.242 37.8793 106.767 37.8793 " +
        "C113.293 37.8793 119.113 41.2443 122.387 46.9057 L194.826 172.526 " +
        "C195.031 172.89 195.258 173.208 195.531 173.526 C198.76 178.665 202.83 183.485 207.786 187.782 " +
        "C216.631 195.444 228.34 199.651 240.754 199.651 H321.424 L315.581 209.769 " +
        "C314.353 211.883 314.353 214.475 315.581 216.59 C316.809 218.704 319.059 220 321.492 220 H349.436 " +
        "C351.868 220 354.119 218.704 355.347 216.59 L364.396 200.901 L367.17 196.468 " +
        "C367.17 196.468 367.261 196.331 367.284 196.263 L453.274 46.9285 " +
        "C456.525 41.2898 462.369 37.902 468.894 37.902 C475.42 37.902 481.263 41.2671 484.514 46.9285 " +
        "L535.24 134.783 C538.491 140.422 538.514 147.174 535.24 152.813 " +
        "C531.988 158.452 526.145 161.84 519.62 161.84 H418.669 " +
        "C416.236 161.84 413.985 163.136 412.757 165.25 L398.774 189.442 " +
        "C397.546 191.556 397.546 194.148 398.774 196.263 C400.002 198.377 402.253 199.673 404.686 199.673 " +
        "H518.21 C539.81 199.673 559.295 188.237 569.026 169.843 C578.053 152.79 577.666 132.623 567.981 115.843 " +
        "L568.003 115.821 Z"

private fun buildSinglePathVector(
    name: String,
    viewportWidth: Float,
    viewportHeight: Float,
    pathData: String,
    fillType: PathFillType = PathFillType.NonZero,
    translationX: Float = 0f,
    translationY: Float = 0f,
): ImageVector {
    val nodes = PathParser().parsePathString(pathData).toNodes()
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    ).apply {
        if (translationX != 0f || translationY != 0f) {
            group(translationX = translationX, translationY = translationY) {
                addPath(
                    pathData = nodes,
                    pathFillType = fillType,
                    fill = SolidColor(Color.White),
                )
            }
        } else {
            addPath(
                pathData = nodes,
                pathFillType = fillType,
                fill = SolidColor(Color.White),
            )
        }
    }.build()
}

internal val OfficialClaudeIcon: ImageVector by lazy {
    buildSinglePathVector(
        name = "OfficialClaude",
        viewportWidth = 24f,
        viewportHeight = 24f,
        pathData = CLAUDE_STARBURST_PATH,
    )
}

internal val OfficialOpenAiCodexIcon: ImageVector by lazy {
    buildSinglePathVector(
        name = "OfficialOpenAiCodex",
        viewportWidth = 411f,
        viewportHeight = 411f,
        pathData = OPENAI_BLOSSOM_PATH,
        fillType = PathFillType.EvenOdd,
        translationX = -100f,
        translationY = -100f,
    )
}

internal val OfficialAntigravityMonoIcon: ImageVector by lazy {
    buildSinglePathVector(
        name = "OfficialAntigravityMono",
        viewportWidth = 16.4f,
        viewportHeight = 16.4f,
        pathData = ANTIGRAVITY_ARCH_PATH,
        translationX = 0.4f,
        translationY = 1.0f,
    )
}

internal val OfficialPiColorIcon: ImageVector by lazy {
    val coral = PathParser().parsePathString(PI_CORAL_PATH).toNodes()
    val blue = PathParser().parsePathString(PI_BLUE_PATH).toNodes()
    val gold = PathParser().parsePathString(PI_GOLD_PATH).toNodes()
    ImageVector.Builder(
        name = "OfficialPiColor",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 560f,
        viewportHeight = 560f,
    ).apply {
        group(translationX = -120f, translationY = -120f) {
            addPath(pathData = coral, fill = SolidColor(Color(0xFFF09082)))
            addPath(pathData = blue, fill = SolidColor(Color(0xFF4D9ABF)))
            addPath(pathData = gold, fill = SolidColor(Color(0xFFF1BE58)))
        }
    }.build()
}

internal val OfficialPiMonoIcon: ImageVector by lazy {
    val coral = PathParser().parsePathString(PI_CORAL_PATH).toNodes()
    val blue = PathParser().parsePathString(PI_BLUE_PATH).toNodes()
    val gold = PathParser().parsePathString(PI_GOLD_PATH).toNodes()
    ImageVector.Builder(
        name = "OfficialPiMono",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 560f,
        viewportHeight = 560f,
    ).apply {
        group(translationX = -120f, translationY = -120f) {
            addPath(pathData = coral, fill = SolidColor(Color.White))
            addPath(pathData = blue, fill = SolidColor(Color.White))
            addPath(pathData = gold, fill = SolidColor(Color.White))
        }
    }.build()
}

internal val OfficialDeepSeekIcon: ImageVector by lazy {
    buildSinglePathVector(
        name = "OfficialDeepSeek",
        viewportWidth = 24f,
        viewportHeight = 24f,
        pathData = DEEPSEEK_WHALE_PATH,
    )
}

internal val OfficialCursorIcon: ImageVector by lazy {
    buildSinglePathVector(
        name = "OfficialCursor",
        viewportWidth = 532.09f,
        viewportHeight = 532.09f,
        pathData = CURSOR_CUBE_PATH,
        translationX = 32.68f,
    )
}

internal val OfficialAcpIcon: ImageVector by lazy {
    buildSinglePathVector(
        name = "OfficialAcp",
        viewportWidth = 576f,
        viewportHeight = 576f,
        pathData = ACP_REGISTRY_PATH,
        translationY = 178f,
    )
}

internal val OfficialTraeIcon: ImageVector by lazy {
    buildSinglePathVector(
        name = "OfficialTrae",
        viewportWidth = 24f,
        viewportHeight = 24f,
        pathData = "M1 4 H4 V18 H1 Z M4 18 H22 V21 H4 Z M4 4 H22 V7 H4 Z M19 7 H22 V18 H19 Z " +
            "M11 10 L13 12 L11 14 L9 12 Z M16 10 L18 12 L16 14 L14 12 Z",
    )
}

private val AntigravityArchNativePath: Path by lazy {
    PathParser().parsePathString(ANTIGRAVITY_ARCH_PATH).toPath()
}

internal fun officialEngineMonoVector(engineId: String): ImageVector {
    val key = engineId.trim().lowercase()
    return when {
        key == "claude" || key.contains("claude") -> OfficialClaudeIcon
        key == "codex" || key.contains("codex") || key.contains("openai") -> OfficialOpenAiCodexIcon
        key == "agy" || key.contains("antigravity") -> OfficialAntigravityMonoIcon
        key == "pi" -> OfficialPiMonoIcon
        key == "dsh" || key.contains("deepseek") -> OfficialDeepSeekIcon
        key.contains("cursor") -> OfficialCursorIcon
        key.contains("trae") -> OfficialTraeIcon
        key.startsWith("acp") -> OfficialAcpIcon
        else -> Icons.Rounded.Terminal
    }
}

/**
 * Renders the authentic official brand mark for each coding engine across Desktop and Mobile.
 *
 * When [tintOverride] is `null`, icons render in their official brand colors (including the
 * 4-color Google Antigravity gradient and 3-color Pi emblem). Passing a non-null [tintOverride]
 * (e.g. dimmed grey for unavailable engines) renders the official monochrome silhouette cleanly.
 */
@Composable
internal fun EngineGlyphIcon(
    engineId: String,
    size: Dp = 15.dp,
    tintOverride: Color? = null,
    modifier: Modifier = Modifier,
) {
    val key = engineId.trim().lowercase()
    when {
        key == "claude" || key.contains("claude") -> {
            Icon(
                imageVector = OfficialClaudeIcon,
                contentDescription = null,
                tint = tintOverride ?: ClaudeBrandTerracotta,
                modifier = modifier.size(size),
            )
        }
        key == "codex" || key.contains("codex") || key.contains("openai") -> {
            Icon(
                imageVector = OfficialOpenAiCodexIcon,
                contentDescription = null,
                tint = tintOverride ?: OpenAiBrandLight,
                modifier = modifier.size(size),
            )
        }
        key == "agy" || key.contains("antigravity") -> {
            if (tintOverride != null) {
                Icon(
                    imageVector = OfficialAntigravityMonoIcon,
                    contentDescription = null,
                    tint = tintOverride,
                    modifier = modifier.size(size),
                )
            } else {
                OfficialAntigravityColorMark(size = size, modifier = modifier)
            }
        }
        key == "pi" -> {
            if (tintOverride != null) {
                Icon(
                    imageVector = OfficialPiMonoIcon,
                    contentDescription = null,
                    tint = tintOverride,
                    modifier = modifier.size(size),
                )
            } else {
                Icon(
                    imageVector = OfficialPiColorIcon,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = modifier.size(size),
                )
            }
        }
        key == "dsh" || key.contains("deepseek") -> {
            Icon(
                imageVector = OfficialDeepSeekIcon,
                contentDescription = null,
                tint = tintOverride ?: DeepSeekBrandBlue,
                modifier = modifier.size(size),
            )
        }
        key.contains("cursor") -> {
            Icon(
                imageVector = OfficialCursorIcon,
                contentDescription = null,
                tint = tintOverride ?: CursorBrandLight,
                modifier = modifier.size(size),
            )
        }
        key.contains("trae") -> {
            Icon(
                imageVector = OfficialTraeIcon,
                contentDescription = null,
                tint = tintOverride ?: TraeBrandGreen,
                modifier = modifier.size(size),
            )
        }
        key.startsWith("acp") -> {
            Icon(
                imageVector = OfficialAcpIcon,
                contentDescription = null,
                tint = tintOverride ?: Everforest.BoldFg,
                modifier = modifier.size(size),
            )
        }
        else -> {
            Icon(
                imageVector = Icons.Rounded.Terminal,
                contentDescription = null,
                tint = tintOverride ?: engineAccentColor(engineId),
                modifier = modifier.size(size),
            )
        }
    }
}

/**
 * Pure-vector Google Antigravity official 4-color gradient mark, clipped to the exact official
 * arch silhouette (`16×15` SVG viewBox) with radial color fields matching the official asset.
 */
@Composable
private fun OfficialAntigravityColorMark(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = minOf(w, h)
        val scale = s / 16.4f
        val ox = (w - 15.6f * scale) * 0.5f
        val oy = (h - 14.4f * scale) * 0.5f

        withTransform({
            translate(left = ox, top = oy)
            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
        }) {
            clipPath(AntigravityArchNativePath) {
                // Base Google Blue across both legs and central bridge
                drawRect(
                    color = Color(0xFF3186FF),
                    topLeft = Offset(-2f, -2f),
                    size = Size(20f, 19f),
                )
                // Emerald Green field along the upper-mid left slope
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFF00B95C),
                            0.55f to Color(0xCC00B95C),
                            1.0f to Color(0x0000B95C),
                        ),
                        center = Offset(1.5f, 5.4f),
                        radius = 7.2f,
                    ),
                    radius = 7.2f,
                    center = Offset(1.5f, 5.4f),
                )
                // Warm Google Yellow crown at upper-left apex
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFFBBC04),
                            0.50f to Color(0xD8FBBC04),
                            1.0f to Color(0x00FBBC04),
                        ),
                        center = Offset(5.6f, 0.3f),
                        radius = 6.2f,
                    ),
                    radius = 6.2f,
                    center = Offset(5.6f, 0.3f),
                )
                // Vivid Google Red across top-right crown and upper-right slope
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFFC413D),
                            0.52f to Color(0xE0FC413D),
                            1.0f to Color(0x00FC413D),
                        ),
                        center = Offset(11.4f, 2.7f),
                        radius = 6.8f,
                    ),
                    radius = 6.8f,
                    center = Offset(11.4f, 2.7f),
                )
                // Luminous Google Blue center bridge anchor
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xF03186FF),
                            0.55f to Color(0x883186FF),
                            1.0f to Color(0x003186FF),
                        ),
                        center = Offset(7.8f, 9.6f),
                        radius = 5.6f,
                    ),
                    radius = 5.6f,
                    center = Offset(7.8f, 9.6f),
                )
            }
        }
    }
}
