plugins {
    java
    alias(libs.plugins.shadow)
}

base {
    archivesName = "UltimateDonutSmp"
}

java {
    // skinsrestorer-api ships Java 25 class files, so javac must be 25+ even though the plugin targets Java 21.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    // Same reason: let a Java 21 build depend on that provided API. It is supplied by the SkinsRestorer plugin at runtime.
    disableAutoTargetJvm()
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
    maven("https://repo.codemc.org/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
    maven("https://repo.lunarclient.dev")
    maven("https://repo.opencollab.dev/main/")
    maven("https://maven.maxhenkel.de/repository/public")
}

// Server-provided APIs: needed to compile and test, never bundled into the jar.
val provided = configurations.create("provided")
configurations.compileOnly { extendsFrom(provided) }
configurations.testImplementation { extendsFrom(provided) }

dependencies {
    val serverApi = findProperty("serverApi")?.toString()
    if (serverApi != null) provided(serverApi) else provided(libs.spigot.api)
    provided(libs.protocollib)
    provided(libs.skinsrestorer.api)
    provided(libs.placeholderapi)
    provided(libs.apollo.api)
    provided(libs.vault.api)
    provided(libs.floodgate.api)
    provided(libs.luckperms.api)
    provided(libs.voicechat.api)
    // Spigot and Paper ship both JDBC drivers, and the plugin class loader prefers the server's copy anyway.
    provided(libs.sqlite.jdbc)
    provided(libs.mysql.connector.j)

    implementation(libs.hikaricp)
    implementation(libs.mongodb.driver.sync)
    implementation(libs.jedis)
    implementation(libs.adventure.text.minimessage)
    implementation(libs.adventure.text.serializer.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        filter { it.replace("\${project.version}", version) }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    // Only the shaded jar is a usable plugin.
    enabled = false
}

tasks.shadowJar {
    archiveClassifier = ""
    relocate("org.xerial", "com.bx.ultimateDonutSmp.libs.xerial")
    relocate("redis.clients", "com.bx.ultimateDonutSmp.libs.redis.clients")
    relocate("org.apache.commons.pool2", "com.bx.ultimateDonutSmp.libs.commons.pool2")
    relocate("com.zaxxer.hikari", "com.bx.ultimateDonutSmp.libs.hikari")
    exclude("META-INF/versions/**")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
