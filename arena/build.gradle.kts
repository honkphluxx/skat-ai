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
    // Only here, never in :jskat-ai. That module is the adapter that seats
    // JSkat's players, and the algorithmic ones need nothing but jskat-base.jar;
    // ONNX Runtime is hundreds of megabytes of native libraries across the
    // platforms it ships for, and only the two ML players ever touch it. Declaring
    // it one level up keeps the adapter a plain-jar dependency for anyone who
    // wants the algorithmic baselines and not the transformer.
    //
    // The shipped belief runs through dev.skatklar.demo.belief.BeliefNet, which is
    // plain Java; this runtime is the fallback and the second opinion.
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
        // skat.* is the arena's own namespace: skat.probe turns on the honesty
        // control for the outside engines (docs/external-bots.md).
        if (name.startsWith("jskat.") || name.startsWith("onnxruntime.")
                || name.startsWith("belief.") || name.startsWith("skat.")) {
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

/**
 * Why a player that can declare Null never does, and what lifting the 23-point
 * ceiling could buy. See NullAuditMain; the finding it exists for is in
 * arena/README.md under 2026-09-09.
 *
 * ./gradlew :arena:nullAudit --args="--player=belief-32 --boards=2000 --threads=8"
 */
/** ./gradlew :arena:calibration --args="--player=belief-32 --boards=600" */
tasks.register<JavaExec>("calibration") {
    group = "verification"
    description = "Measures what the bidder's probability is worth against what happens"
    mainClass.set("dev.skatklar.training.arena.CalibrationMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
}

tasks.register<JavaExec>("nullAudit") {
    group = "verification"
    description = "Asks every seat how high it would bid and what it would announce"
    mainClass.set("dev.skatklar.training.arena.NullAuditMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
}

/**
 * Where, card by card, our declarer loses the games par wins with the same ten
 * cards. See DeclaringAuditMain and tools/declaring-audit.sh.
 *
 * ./gradlew :arena:declaringAudit --args="--seeds=11,12,13 --boards=200 --threads=16 --out=arena-logs/par-audit"
 */
tasks.register<JavaExec>("declaringAudit") {
    group = "verification"
    description = "Replays the par matches' declarer games and solves the true position before every card"
    mainClass.set("dev.skatklar.training.arena.DeclaringAuditMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
}

/**
 * Where the solver's nodes lie by tricks left, and how often a position near the
 * end recurs across worlds, decisions and games -- the numbers a global endgame
 * cache would pay on. Runs the Java search: the native engine is not
 * instrumented. See EndgameProbeMain and tools/endgame-probe.sh.
 *
 * ./gradlew :arena:endgameProbe --args="--seed=14 --boards=60 --threads=16"
 */
tasks.register<JavaExec>("endgameProbe") {
    group = "verification"
    description = "Measures where the solver's work lies and how much of it an endgame cache would save"
    mainClass.set("dev.skatklar.training.arena.EndgameProbeMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
    systemProperty("skatklar.solver", "java")
}

/**
 * The same declarer against two defences: our defenders, and another's, on the
 * same boards, every card solved. See DefendingAuditMain and tools/defending-audit.sh.
 *
 * ./gradlew :arena:defendingAudit --args="--seeds=14,15,16 --boards=200 --defenders=skatzero --threads=16 --out=arena-logs/defending-audit/skatzero"
 */
tasks.register<JavaExec>("defendingAudit") {
    group = "verification"
    description = "Plays our declarer against our defenders and another's, and solves the true position before every card"
    mainClass.set("dev.skatklar.training.arena.DefendingAuditMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
}

/**
 * Does the vote rank the declarer's cards the way the table does? Plays every
 * legal card out against the real defenders from recorded positions.
 *
 * ./gradlew :arena:rolloutAudit --args="--seeds=14,15,16 --boards=200 --rollouts=8 --per-band=12 --threads=16 --out=arena-logs/rollout"
 */
tasks.register<JavaExec>("rolloutAudit") {
    group = "verification"
    description = "Rolls every legal card out from recorded declarer positions and compares the order with the vote's"
    mainClass.set("dev.skatklar.training.arena.RolloutAuditMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
}

/**
 * The rollout audit's decisions again, every legal card played out in the
 * worlds the player sampled for it, so the vote and a rollout's pick are
 * compared on the same information. See WorldRolloutMain and tools/world-rollout.sh.
 *
 * ./gradlew :arena:worldRollout --args="--seeds=14,15,16 --from=arena-logs/rollout --threads=16 --out=arena-logs/rollout/worlds"
 */
tasks.register<JavaExec>("worldRollout") {
    group = "verification"
    description = "Plays the audit's decisions out in the player's own sampled worlds and scores the pick against the vote's"
    mainClass.set("dev.skatklar.training.arena.WorldRolloutMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
}

/**
 * Phase B2 step 1: how often the worlds a player samples are the world on the
 * table, by trick. See BeliefShareMain and tools/belief-share.sh.
 *
 * ./gradlew :arena:beliefShare --args="--seeds=11,12,13 --boards=200 --threads=16"
 */
tasks.register<JavaExec>("beliefShare") {
    group = "verification"
    description = "Reads the sampled worlds off real games and scores them against the deal"
    mainClass.set("dev.skatklar.training.arena.BeliefShareMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
}

/**
 * The teacher's decisions for the student to imitate (plan 2.10, I1). See
 * TeacherExportMain and tools/teacher-export.sh.
 *
 * ./gradlew :arena:exportTeacher --args="--boards=50000 --seed=101 --threads=16 --out=teacher-data"
 */
tasks.register<JavaExec>("exportTeacher") {
    group = "verification"
    description = "Records the teacher's votes, world by world, at every card decision of self-played games"
    mainClass.set("dev.skatklar.training.data.TeacherExportMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = runRoot
    forwardProperties()
    maxHeapSize = "2g"
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
