# UniFFI's Kotlin bindings reach the Rust library through JNA, which finds the bindings' native
# functions and structures by reflection: keep both.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class io.github.artcraftandroid.wordcraft.core.** { *; }
-dontwarn java.awt.**
# Ktor's optional IntelliJ debugger detector references this JVM-only API. Android does not ship it.
-dontwarn java.lang.management.**
