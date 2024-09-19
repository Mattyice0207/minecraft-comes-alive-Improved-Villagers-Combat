import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar
import org.jetbrains.gradle.ext.compiler
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings

plugins {
    id("java")
    id("java-library")
    id("maven-publish")
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.8"
    id("eclipse")
    id("com.gtnewhorizons.retrofuturagradle") version "1.4.0"
    id("com.matthewprenger.cursegradle") version "1.4.0"
}


val mcVersion: String by project
val modVersion: String by project
val mavenGroup: String by project
val modName: String by project
val modId: String by project
val archiveBase: String by project

version = modVersion + (System.getenv("CI_SHA_SHORT") ?: "")
group = mavenGroup

// Set the toolchain version to decouple the Java we run Gradle with from the Java used to compile and run the mod
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
    // Generate sources and javadocs jars when building and publishing
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.isFork = true
    options.isIncremental = true
}

val embed = configurations.create("embed")
configurations {
    "implementation" {
        extendsFrom(embed)
    }
}

configure<BasePluginExtension> {
    archivesName.set("$archiveBase-$mcVersion")
}

minecraft {
    mcVersion = "1.12.2"

    // MCP Mappings
    mcpMappingChannel = "stable"
    mcpMappingVersion = "39"

    // Set username here, the UUID will be looked up automatically
    username = "Developer"

    // Add any additional tweaker classes here
    // extraTweakClasses.add("org.spongepowered.asm.launch.MixinTweaker")

    // Add various JVM arguments here for runtime
    val args = mutableListOf("-ea:${project.group}")
    if (projectProperty("useCoreMod")) {
        val coreModPluginClassName: String by project
        args += "-Dfml.coreMods.load=$coreModPluginClassName"
    }
    if (projectProperty("useMixins")) {
        args += "-Dmixin.hotSwap=true"
        args += "-Dmixin.checks.interfaces=true"
        args += "-Dmixin.debug.export=true"
    }
    extraRunJvmArguments.addAll(args)

    // Include and use dependencies Access Transformer files
    useDependencyAccessTransformers = false

    // Add any properties you want to swap out for a dynamic value at build time here
    // Any properties here will be added to a class at build time, the name can be configured below
    // Example:
    injectedTags.put("VERSION", project.version)
    injectedTags.put("MOD_ID", modId)
    injectedTags.put("NAME", modName)
}

// Generate a group.archives_base_name.Tags class
tasks.injectTags.configure {
    // Change Tags class' name here:
    outputClassName.set("${project.group}.Reference")
}

repositories {
    maven {
        name = "CleanroomMC Maven"
        url = uri("https://maven.cleanroommc.com")
    }
    maven {
        name = "SpongePowered Maven"
        url = uri("https://repo.spongepowered.org/maven")
    }
    maven {
        name = "CurseMaven"
        url = uri("https://cursemaven.com")
        content {
            includeGroup("curse.maven")
        }
    }
    mavenLocal() // Must be last for caching to work
}

dependencies {
    compileOnly (libs.lombok)
    annotationProcessor( libs.lombok)

    testCompileOnly (libs.lombok)
    testAnnotationProcessor(libs.lombok)

    implementation(libs.curse.hei)

    val useMixins: Boolean = projectProperty("useMixins")
    if (useMixins) {
        implementation(libs.mixinBooter)
    }

    val useSpark: Boolean = projectProperty("useSpark")
    if (useSpark) {
        // for profiling
        runtimeOnly(libs.curse.spark)
    }

    if (useMixins) {
        // Change your mixin refmap name here:
        val archiveBaseName: String by project
        val mixin: String =
            modUtils.enableMixins(
                libs.mixinBooter.get().toString(),
                "mixins.${modId}.refmap.json"
            ).toString()
        api(mixin) {
            isTransitive = false
        }
        annotationProcessor("org.ow2.asm:asm-debug-all:5.2")
        annotationProcessor("com.google.guava:guava:24.1.1-jre")
        annotationProcessor("com.google.code.gson:gson:2.8.6")
        annotationProcessor(mixin) {
            isTransitive = false
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Adds Access Transformer files to tasks
if (projectProperty("useAccessTransformer")) {
    sourceSets.main.get().resources.files.forEach {
        if (it.name.lowercase().endsWith("_at.cfg")) {
            tasks.deobfuscateMergedJarToSrg.get().accessTransformerFiles.from(it)
            tasks.srgifyBinpatchedJar.get().accessTransformerFiles.from(it)
        }
    }
}

tasks.withType<ProcessResources> {
    // This will ensure that this task is redone when the versions change
    inputs.property("modversion", project.version)
    inputs.property("mcversion", project.minecraft.mcVersion)

    // Replace various properties in mcmod.info and pack.mcmeta if applicable
    filesMatching(listOf("mcmod.info", "pack.mcmeta")) {
        // Replace version and mcversion
        expand(
            "modversion" to project.version,
            "mcversion" to project.minecraft.mcVersion
        )
    }

    if (projectProperty("useAccessTransformer")) {
        rename("(.+_at.cfg)", "META-INF/$1") // Make sure Access Transformer files are in META-INF folder
    }
}

tasks.withType<Jar> {
    manifest {
        val attributes = mutableMapOf<String, Any>()
        if (projectProperty("useCoreMod")) {
            val coreModPluginClassName: String by project
            attributes["FMLCorePlugin"] = coreModPluginClassName
            if (projectProperty("includeMod")) {
                attributes["FMLCorePluginContainsFMLMod"] = true
                attributes["ForceLoadAsMod"] = project.gradle.startParameter.taskNames[0] == "build"
            }
        }
        if (projectProperty("useAccessTransformer")) {
            attributes["FMLAT"] = modId + "_at.cfg"
        }
        attributes(attributes)
    }
    // Add all embedded dependencies into the jar
    from(provider { embed.map { if (it.isDirectory()) it else zipTree(it) } })
}

idea {
    module {
        inheritOutputDirs = true
    }
    project {
        settings {
            runConfigurations {
                create("1. Run Client", org.jetbrains.gradle.ext.Gradle::class) {
                    taskNames = listOf("runClient")
                }
                create("2. Run Server", org.jetbrains.gradle.ext.Gradle::class) {
                    taskNames = listOf("runServer")
                }
                create("3. Run Obfuscated Client", org.jetbrains.gradle.ext.Gradle::class) {
                    taskNames = listOf("runObfClient")
                }
                create("4. Run Obfuscated Server", org.jetbrains.gradle.ext.Gradle::class) {
                    taskNames = listOf("runObfServer")
                }
            }

            compiler.javac {
                afterEvaluate {
                    javacAdditionalOptions = "-encoding utf8"
                    tasks.withType<JavaCompile> {
                        val args = options.compilerArgs.joinToString(separator = " ") { "\"$it\"" }
                        moduleJavacAdditionalOptions = mapOf(
                            project.name + ".main" to args
                        )
                    }
                }
            }
        }
    }
}

tasks.named("processIdeaSettings").configure {
    dependsOn("injectTags")
}

tasks.named<Jar>("jar") {
    enabled = true
    finalizedBy(tasks.reobfJar)
}

tasks.named<ReobfuscatedJar>("reobfJar") {
    inputJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
}

inline fun <reified T : Any> projectProperty(propertyKey: String): T {
    val value = project.properties[propertyKey].let { it.toString() }
    return when (T::class) {
        Boolean::class -> value.toBoolean() as T
        else -> throw IllegalArgumentException()
    }
}

//buildscript {
//    repositories {
//        mavenCentral()
//        jcenter()
//        maven { url = "http://files.minecraftforge.net/maven" }
//    }
//    dependencies {
//        classpath 'net.minecraftforge.gradle:ForgeGradle:2.3-SNAPSHOT'
//    }
//}
//apply plugin: 'net.minecraftforge.gradle.forge'
////Only edit below this line, the above code adds and enables the necessary things for Forge to be setup.
//
//version = "6.0.1"
//group = "com.minecraftcomesalive" // http://maven.apache.org/guides/mini/guide-naming-conventions.html
//archivesBaseName = "MCA-1.12.2"
//
//sourceCompatibility = targetCompatibility = '1.8' // Need this here so eclipse task generates correctly.
//compileJava {
//    sourceCompatibility = targetCompatibility = '1.8'
//}
//
//minecraft {
//    version = "1.12.2-14.23.5.2768"
//    runDir = "run"
//
//    // the mappings can be changed at any time, and must be in the following format.
//    // snapshot_YYYYMMDD   snapshot are built nightly.
//    // stable_#            stables are built at the discretion of the MCP team.
//    // Use non-default mappings at your own risk. they may not always work.
//    // simply re-run your setup task after changing the mappings to update your workspace.
//    mappings = "snapshot_20171003"
//    // makeObfSourceJar = false // an Srg named sources jar is made by default. uncomment this to disable.
//}
//
//dependencies {
//    provided 'org.projectlombok:lombok:1.16.4'
//    // you may put jars on which you depend on in ./libs
//    // or you may define them like so..
//    //compile "some.group:artifact:version:classifier"
//    //compile "some.group:artifact:version"
//
//    // real examples
//    //compile 'com.mod-buildcraft:buildcraft:6.0.8:dev'  // adds buildcraft to the dev env
//    //compile 'com.googlecode.efficient-java-matrix-library:ejml:0.24' // adds ejml to the dev env
//
//    // the 'provided' configuration is for optional dependencies that exist at compile-time but might not at runtime.
//    //provided 'com.mod-buildcraft:buildcraft:6.0.8:dev'
//
//    // the deobf configurations:  'deobfCompile' and 'deobfProvided' are the same as the normal compile and provided,
//    // except that these dependencies get remapped to your current MCP mappings
//    //deobfCompile 'com.mod-buildcraft:buildcraft:6.0.8:dev'
//    //deobfProvided 'com.mod-buildcraft:buildcraft:6.0.8:dev'
//
//    // for more info...
//    // http://www.gradle.org/docs/current/userguide/artifact_dependencies_tutorial.html
//    // http://www.gradle.org/docs/current/userguide/dependency_management.html
//
//}
//
//processResources {
//    // this will ensure that this task is redone when the versions change.
//    inputs.property "version", project.version
//    inputs.property "mcversion", project.minecraft.version
//
//    // replace stuff in mcmod.info, nothing else
//    from(sourceSets.main.resources.srcDirs) {
//        include 'mcmod.info'
//
//        // replace version and mcversion
//        expand 'version':project.version, 'mcversion':project.minecraft.version
//    }
//
//    // copy everything else except the mcmod.info
//    from(sourceSets.main.resources.srcDirs) {
//        exclude 'mcmod.info'
//    }
//}
//
//jar {
//    classifier = 'universal'
//}
