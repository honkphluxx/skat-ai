// The measuring instrument: duplicate-deal matches, the belief trainer's Java
// side, and the players that contest them.

plugins {
    `java-library`
    application
}

val ai = project.parent!!
val enginePath = if (ai.path == ":") ":engine" else "${ai.path}:engine"
val jskatAiPath = if (ai.path == ":") ":jskat-ai" else "${ai.path}:jskat-ai"

// Where every run reads and writes: arena-logs/, belief-model/, belief-data/.
// The skat-ai directory itself, in both arrangements -- not the root of whatever
// build is running. A measurement that lands in a different place depending on
// which build invoked it is a measurement whose "log already exists" resume
// silently stops working, and this arena resumes by exactly that check.
val runRoot = ai.layout.projectDirectory.asFile

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    dependsOn(ai.tasks.named("buildJskatBase"))
}

dependencies {
    api(project(enginePath))
    // The JSkat baselines are discovered reflectively, so the arena still builds
    // and runs when the submodule is absent. The adapter itself is a compile
    // dependency because JSkatMlPlayers constructs providers directly.
    implementation(project(jskatAiPath))
    implementation(files(ai.layout.projectDirectory.file(
            "third_party/jskat/jskat-base/build/libs/jskat-base.jar")))
    // Only here, never in :jskat-ai: that module is on the Android app's compile
    // path in the SkatKlar build, and ONNX Runtime would pull its native
    // libraries into the APK for a player the app does not use. The shipped
    // belief runs through dev.skatklar.demo.belief.BeliefNet, which is plain
    // Java; this runtime is the fallback and the second opinion.
    //
    // Not the 1.28.0 jskat-base pins: that release's native library fails to load
    // on Windows with "DLL initialization routine failed", while 1.19.2 and 1.17.3
    // load fine on the same machine. A stock Windows ships its own
    // C:\Windows\System32\onnxruntime.dll, which is the likely conflict.
    // Override to bisect further:  -PonnxVersion=1.28.0
    // jskat-base's models need 1.17 or newer.
    implementation("com.microsoft.onnxruntime:onnxruntime:"
            + providers.gradleProperty("onnxVersion").getOrElse("1.19.2"))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("dev.skatklar.training.arena.ArenaMain")
}

tasks.test {
    useJUnit()
}

/**
 * Forwards the -D namespaces that matter into a forked JVM.
 *
 * A -D on the Gradle command line reaches the daemon, not the process a JavaExec
 * starts, so overriding would silently do nothing without this.
 */
fun JavaExec.forwardProperties() {
    // The ML players look for .jskat/models relative to the working directory,
    // but the JSkat build writes them inside the submodule.
    systemProperty("jskat.models.dir", ai.layout.projectDirectory
            .dir("third_party/jskat/.jskat/models").asFile.absolutePath)
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("jskat.") || name.startsWith("onnxruntime.")
                || name.startsWith("belief.")) {
            systemProperty(name, value.toString())
        }
    }
}

/** ./gradlew :arena:arena --args="--a=belief --b=search --boards=300" */
tasks.register<JavaExec>("arena") {
    group = "verification"
    description = "Runs a duplicate-deal match between two AI implementations"
    mainClass.set("dev.skatklar.training.arena.ArenaMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
}

/** ./gradlew :arena:play --args="--level=club" */
tasks.register<JavaExec>("play") {
    group = "application"
    description = "Deals a hand and plays it against the measured AI, on this terminal"
    mainClass.set("dev.skatklar.training.play.PlayMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    standardInput = System.`in`
    forwardProperties()
}

/** ./gradlew :arena:export --args="--boards=50000 --threads=4" */
tasks.register<JavaExec>("export") {
    group = "verification"
    description = "Generates labelled belief-model training data by playing games"
    mainClass.set("dev.skatklar.training.data.ExportMain")
    classpath = sourceSets["main"].runtimeClasspath
    // Shards land beside the build root rather than inside this module, because
    // a night of them is gigabytes and nobody wants that under source control.
    workingDir = runRoot
    forwardProperties()
    maxHeapSize = "2g"
}
