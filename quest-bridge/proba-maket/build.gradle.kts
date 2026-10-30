// Стенд макета кадра эфира (2.5.0): настоящий код плашек бриджа рисует через android.graphics поверх Skia.
// Запуск — zapusk.sh (android.jar API 34 — только для компиляции бриджа; Vosk — заглушки).
plugins { kotlin("jvm") version "2.0.21" }
repositories { mavenCentral() }
val androidJar = (findProperty("androidJar") as String?) ?: System.getenv("ANDROID_JAR")
    ?: error("нужен android.jar: -PandroidJar=… или ANDROID_JAR")
sourceSets { main { kotlin.srcDir("../app/src/main/kotlin"); kotlin.srcDir("stubs") } }
val maket by sourceSets.creating {
    kotlin.srcDir("src/maket/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
dependencies {
    compileOnly(files(androidJar))
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    "maketImplementation"("org.jetbrains.skiko:skiko-awt:0.9.4")
    "maketImplementation"("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.9.4")
    "maketImplementation"("io.github.jaredmdobson:concentus:1.0.2")
}
kotlin { jvmToolchain(21) }
tasks.register<JavaExec>("maket") {
    classpath = maket.runtimeClasspath
    mainClass.set("zapusk.Zapusk")
    jvmArgs("-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote", "-XX:-BytecodeVerificationLocal")
    systemProperty("assets", file("../app/src/main/assets").absolutePath)
    systemProperty("shrift", (findProperty("shrift") as String?) ?: "shrift")
    args = listOf((findProperty("maketArgs") as String?) ?: "maket-out")
}
