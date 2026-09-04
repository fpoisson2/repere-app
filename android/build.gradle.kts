plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

tasks.register("playBundles") {
    group = "distribution"
    description = "Builds the signed mobile and Wear OS bundles for Play Console."
    if (!rootProject.file("keystore.properties").exists()) {
        throw GradleException("android/keystore.properties est requis pour produire les bundles Play signés.")
    }
    dependsOn(":mobile:bundleRelease", ":wear:bundleRelease")
}
