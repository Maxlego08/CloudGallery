package fr.eva.cloudgallery.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * FontFamily.Default = system font: on a Galaxy, this is the One UI font chosen by
 * the user. No Samsung font is bundled in the APK.
 */
private val Base = TextStyle(fontFamily = FontFamily.Default)

@Immutable
data class OneUiTypography(
    /** Large expanded title of the main screens ("Photos", "Albums"). */
    val largeTitle: TextStyle = Base.copy(fontSize = 32.sp, fontWeight = FontWeight.Normal, lineHeight = 38.sp),
    val largeSubtitle: TextStyle = Base.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    /** Title of a collapsed app bar. */
    val appBarTitle: TextStyle = Base.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
    /** Day / month headers in the grid. */
    val sectionHeader: TextStyle = Base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    val listTitle: TextStyle = Base.copy(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    val listSubtitle: TextStyle = Base.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    val categoryHeader: TextStyle = Base.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp),
    val albumName: TextStyle = Base.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    val albumCount: TextStyle = Base.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    val tabLabel: TextStyle = Base.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    val tabLabelSelected: TextStyle = Base.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp),
    val actionLabel: TextStyle = Base.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 14.sp),
    val badge: TextStyle = Base.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 13.sp),
    val button: TextStyle = Base.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp),
    val body: TextStyle = Base.copy(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
    val dialogTitle: TextStyle = Base.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
)

val Typography = Typography(
    bodyLarge = Base.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = Base.copy(fontSize = 14.sp, lineHeight = 19.sp),
    titleLarge = Base.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
    titleMedium = Base.copy(fontSize = 17.sp, lineHeight = 22.sp),
    labelLarge = Base.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp),
)
