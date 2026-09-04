// The rules, the solver and the players. No third-party runtime dependency at
// all, which is not an accident: this is what ends up inside an Android APK, and
// it is what the arena measures. Anything that could not be shipped to a phone
// does not belong here.

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
