package fr.eva.cloudgallery.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * FontFamily.Default = police système : sur un Galaxy, c'est la police One UI choisie par
 * l'utilisateur. Aucune police Samsung n'est embarquée dans l'APK.
 */
private val Base = TextStyle(fontFamily = FontFamily.Default)

@Immutable
data class OneUiTypography(
    /** Grand titre étendu des écrans principaux (« Photos », « Albums »). */
    val largeTitle: TextStyle = Base.copy(fontSize = 32.sp, fontWeight = FontWeight.Normal, lineHeight = 38.sp),
    val largeSubtitle: TextStyle = Base.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    /** Titre de barre d'application réduite. */
    val appBarTitle: TextStyle = Base.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
    /** En-têtes de jour / mois dans la grille. */
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
