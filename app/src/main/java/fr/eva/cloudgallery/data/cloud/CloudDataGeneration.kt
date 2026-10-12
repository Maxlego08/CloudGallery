package fr.eva.cloudgallery.data.cloud

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Numéro de vidage des données OneDrive, incrémenté par la purge (déconnexion, purge des données
 * restantes, changement de compte) AVANT sa transaction. Tout ce qui écrit un contenu OneDrive
 * (miniature, original, copie hors ligne) relève ce numéro au début du téléchargement et le
 * compare au moment d'enregistrer : un contenu obtenu avant une purge n'est jamais conservé.
 */
@Singleton
class CloudDataGeneration @Inject constructor() {

    private val counter = AtomicLong(0)

    val value: Long get() = counter.get()

    fun bump() {
        counter.incrementAndGet()
    }
}
