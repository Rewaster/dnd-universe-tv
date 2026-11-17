// AGP 9 compiles Kotlin itself (built-in Kotlin); the Kotlin plugin is only put on the
// classpath here to pin the Kotlin compiler version.
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
