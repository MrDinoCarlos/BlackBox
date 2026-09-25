plugins {
    id("java")
}

group = "es.mrdino"
version = "0.6.0"

val paper263CompileClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    add(paper263CompileClasspath.name, "io.papermc.paper:paper-api:26.3.build.34-alpha")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

val compilePaper263Java by tasks.registering(JavaCompile::class) {
    description = "Comprueba que el codigo principal compila con la API de Paper 26.3"
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    source(sourceSets.main.get().java)
    classpath = paper263CompileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/java/paper263Compatibility"))
    val paper263Java = providers.gradleProperty("paper263Java").map(String::toInt).orElse(25)
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(paper263Java.get()))
    })
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/DEPENDENCIES")
}

tasks.test {
    useJUnitPlatform()
}

tasks.check {
    dependsOn(compilePaper263Java)
}
