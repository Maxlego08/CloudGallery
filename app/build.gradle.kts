import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// --- OneDrive (MSAL) ---------------------------------------------------------------------
// Identifiant d'application Entra et empreinte de signature : lus depuis local.properties
// (non versionné) ou, en priorité, depuis une propriété Gradle (-Pmsal.clientId=…,
// ~/.gradle/gradle.properties). Jamais écrits en dur dans le dépôt. Valeurs absentes ou
// invalides : le build réussit et la fonction OneDrive est masquée dans l'appli.
val localProps = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { load(StringReader(it)) }
}

fun msalProp(key: String): String =
    (providers.gradleProperty(key).orNull ?: localProps.getProperty(key)).orEmpty().trim()

val msalClientId: String = msalProp("msal.clientId")
    .takeIf { Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$").matches(it) }
    .orEmpty()
val msalSignatureHash: String = msalProp("msal.signatureHash")
    .takeIf { Regex("^[A-Za-z0-9+/]{27}=$").matches(it) }
    .orEmpty()
if (msalClientId.isEmpty() || msalSignatureHash.isEmpty()) {
    logger.warn("MSAL not configured (msal.clientId / msal.signatureHash): OneDrive disabled in this build")
}

// --- Signature release -------------------------------------------------------------------
// Keystore et mots de passe lus hors du dépôt (cloudgallery.release.* dans
// ~/.gradle/gradle.properties ou local.properties). Absents : release signé avec la clé debug.
val releaseStoreFile: File? = msalProp("cloudgallery.release.storeFile")
    .takeIf { it.isNotEmpty() }?.let(::file)?.takeIf { it.isFile }
// Empreinte de la clé release, enregistrée en plus de celle de debug dans Entra.
val msalReleaseSignatureHash: String = msalProp("msal.releaseSignatureHash")
    .takeIf { releaseStoreFile != null && Regex("^[A-Za-z0-9+/]{27}=$").matches(it) }
    ?: msalSignatureHash
if (releaseStoreFile == null) {
    logger.warn("No release keystore (cloudgallery.release.storeFile): release signed with the debug key")
}

android {
    namespace = "fr.eva.cloudgallery"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "fr.eva.cloudgallery"
        minSdk = 36
        targetSdk = 37
        versionCode = 4
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MSAL_CLIENT_ID", "\"$msalClientId\"")
        buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalSignatureHash\"")
        // Toujours défini (sinon la fusion du manifeste échoue) ; chemin factice si non configuré,
        // sans effet puisque la fonction est alors désactivée.
        manifestPlaceholders["msalSignatureHash"] = msalSignatureHash.ifEmpty { "msal-not-configured" }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = msalProp("cloudgallery.release.storePassword")
                keyAlias = msalProp("cloudgallery.release.keyAlias")
                keyPassword = msalProp("cloudgallery.release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalReleaseSignatureHash\"")
            manifestPlaceholders["msalSignatureHash"] = msalReleaseSignatureHash.ifEmpty { "msal-not-configured" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Schémas Room exportés, lus par MigrationTestHelper dans les tests instrumentés.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.microsoft.msal)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.paging.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
