import xyz.jpenilla.resourcefactory.fabric.Environment

plugins {
    id("xyz.jpenilla.quiet-fabric-loom")
    alias(libs.plugins.indra)
    alias(libs.plugins.indraGit)
    alias(libs.plugins.resourceFactoryFabricConvention)
}

fun lastCommitHash(): String = indraGit.commit().map { it.name.substring(0, 7) }
    .orNull ?: error("Could not determine git commit")

version = "${version}+fabric.${lastCommitHash()}"

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(fabricApiLibs.command.api.v2)
}

indra {
    javaVersions {
        target(25)
    }
}

fabricModJson {
    name = "GenArea"
    description = "Adds a command to generate an area"
    author("Spottedleaf")
    license("GPL-3.0-only")
    icon("assets/genarea/icon.png")
    environment = Environment.ANY
    accessWidener = "genarea.accesswidener"
    depends("fabricloader", ">=${libs.versions.fabric.loader.get()}")
    depends("minecraft", ">=${libs.versions.minecraft.get()}")
    depends("fabric-api")
    entrypoint("main", "ca.spottedleaf.genarea.GenAreaMod")
}

loom {
    accessWidenerPath = file("src/main/resources/genarea.accesswidener")
}

tasks.jar {
    val name = project.name
    inputs.property("projectName", name)

    from("LICENSE") {
        rename { "${name}_LICENSE" }
    }
}
