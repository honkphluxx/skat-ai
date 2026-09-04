// The adapter that lets JSkat's players sit at this engine's table.
//
// Its own module because JSkat is optional: the arena declares it as a
// dependency, and a checkout whose submodule is empty simply has no JSkat
// contestants. Keeping the adapter apart from the arena is also what keeps
// jskat-base off any classpath that does not want it.

plugins {
    `java-library`
}

// `parent` is this directory in both arrangements: the root project when
// skat-ai is built on its own, and :skat-ai when it is a subproject of the
// SkatKlar build. Saying `rootProject` here would work in exactly one of them.
val ai = project.parent!!
val jskatJar = ai.layout.projectDirectory.file(
        "third_party/jskat/jskat-base/build/libs/jskat-base.jar")
val jskatBase = files(jskatJar).builtBy(ai.tasks.named("buildJskatBase"))

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    dependsOn(ai.tasks.named("buildJskatBase"))
}

// ":engine" standalone, ":skat-ai:engine" inside the SkatKlar build.
val enginePath = if (ai.path == ":") ":engine" else "${ai.path}:engine"

dependencies {
    api(project(enginePath))
    implementation(jskatBase)
    implementation("org.slf4j:slf4j-api:2.0.17")
}
