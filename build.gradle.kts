plugins {
    java
}

group = "me.kismeria"
version = "1.23.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.152-beta")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.24")
    // Netty — для автовхода лицензий (перехват входа), в сервере уже есть
    compileOnly("io.netty:netty-transport:4.2.16.Final")
    // NMS для поз из GSit (/lay, /crawl): сервер хостинга (Mojang-маппинги) + его библиотеки
    compileOnly(files("run/host/paper-26.3.jar"))
    compileOnly(fileTree("run/libraries") { include("**/*.jar") })
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
