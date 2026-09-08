// Shared configuration for the three modules, and the one build step that is not
// Gradle's: compiling the vendored JSkat.
//
// JSkat is a git submodule under third_party/jskat and is the only outside
// opponent this project can measure against. It has its own Gradle build, so it
// is invoked as a build rather than consumed as an artifact; there is no
// published jskat-base on Maven Central at the revision the arena pins.
//
// A clone without `--recurse-submodules` has an empty third_party/jskat. That is
// a supported state: everything except the JSkat contestants builds and runs,
// because PlayerRegistry discovers them reflectively and a missing adapter costs
// the arena a contestant rather than a run.

val jskatRoot = layout.projectDirectory.dir("third_party/jskat")
val jskatJar = jskatRoot.file("jskat-base/build/libs/jskat-base.jar")

val jskatPresent = jskatRoot.file("settings.gradle.kts").asFile.exists()

tasks.register<Exec>("buildJskatBase") {
    group = "build"
    description = "Compiles the JSkat submodule the arena measures against"
    onlyIf {
        if (!jskatPresent) {
            logger.lifecycle("third_party/jskat is empty -- run "
                    + "`git submodule update --init third_party/jskat` to measure "
                    + "against JSkat. Building without it.")
        }
        jskatPresent
    }
    workingDir(jskatRoot)
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    if (isWindows) {
        commandLine("cmd", "/c", "gradlew.bat", ":jskat-base:jar", "-x", "test")
    } else {
        // Through sh, so that an archive or a Windows checkout that lost the
        // nested wrapper's executable bit still works.
        commandLine("sh", "gradlew", ":jskat-base:jar", "-x", "test")
    }
    environment("JAVA_HOME", System.getProperty("java.home"))
    if (jskatPresent) {
        inputs.dir(jskatRoot.dir("jskat-base/src/main"))
        inputs.dir(jskatRoot.dir("build-logic/src/main"))
        inputs.file(jskatRoot.file("jskat-base/build.gradle.kts"))
        inputs.file(jskatRoot.file("settings.gradle.kts"))
        outputs.file(jskatJar)
    }
}

// The two outside engines that run as helper processes. Deliberately not wired
// into anything: a missing engine, a missing compiler or a failed build must
// cost the arena a contestant and never a run, and the arena finds the binaries
// for itself. Run it by name -- `./gradlew :skat-ai:buildExternalBots` -- after
// putting XSkat and go-skat under third_party/. See docs/external-bots.md.
tasks.register<Exec>("buildExternalBots") {
    group = "build"
    description = "Builds the XSkat and go-skat helper binaries the arena can seat"
    workingDir(layout.projectDirectory)
    val script = "tools/build-external-bots.sh"
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    if (isWindows) {
        // Git for Windows ships sh; if it is not on PATH the task says so
        // rather than failing the build.
        commandLine("sh", script)
    } else {
        commandLine("sh", script)
    }
    isIgnoreExitValue = true
}

tasks.register("checkEverything") {
    group = "verification"
    description = "Compiles and tests every module of the training ground"
    dependsOn(":engine:test", ":arena:test")
}
