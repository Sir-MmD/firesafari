plugins {
    id("com.android.application") version "9.4.1" apply false
    // Not applied: AGP compiles Kotlin itself. Listed only to pin the Kotlin version,
    // because GeckoView is built with Kotlin 2.4.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
