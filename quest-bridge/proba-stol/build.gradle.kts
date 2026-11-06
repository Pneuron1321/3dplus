// Бридж целиком на обычной JVM (без Android Gradle Plugin): компиляция против android.jar API 34, JVM-тесты
// приложения, стенд гостей (StolGosti для proba-gosti). Запуск — sobrat.sh. Vosk — заглушки (proba-maket/stubs).
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
plugins { kotlin("jvm") version "2.0.21" }
repositories { mavenCentral() }
val androidJar = (findProperty("androidJar") as String?) ?: System.getenv("ANDROID_JAR")
    ?: error("нужен android.jar API 34: -PandroidJar=… или ANDROID_JAR (github.com/Sable/android-platforms, android-34)")
sourceSets {
    main { kotlin.srcDir("../app/src/main/kotlin"); kotlin.srcDir("../proba-maket/stubs") }
    test { kotlin.srcDir("../app/src/test/kotlin"); kotlin.srcDir("../proba-gosti") }
}
dependencies {
    compileOnly(files(androidJar))
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    testImplementation("junit:junit:4.13.2")
    // как в CI (Android): тесты видят java.* только из android.jar — ловит JDK-only API (java.net.http…)
    if (project.hasProperty("bezJdk")) testCompileOnly(files(androidJar))
}
kotlin { jvmToolchain(21) }
if (project.hasProperty("bezJdk")) tasks.named<KotlinCompile>("compileTestKotlin") { compilerOptions.freeCompilerArgs.add("-no-jdk") }
tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("assets", file("../app/src/main/assets").absolutePath)
}
tasks.register<JavaExec>("stol") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.questclaude.bridge.StolGostiKt")
    args = ((project.findProperty("stolArgs") as String?) ?: "").split(" ")
}
