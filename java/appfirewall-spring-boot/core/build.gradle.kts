// Pure-Java core. No Spring deps so this jar is reusable from a hypothetical
// plain-Java or Micronaut SDK later. See spec §10.

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
