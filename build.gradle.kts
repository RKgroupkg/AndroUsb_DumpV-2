import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    // Access version catalog
    val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    
    id("io.gitlab.arturbosch.detekt").version(libs.findVersion("detekt").get().toString())
    id("com.github.ben-manes.versions").version(libs.findVersion("benmanesversion").get().toString())
    id("org.jetbrains.kotlin.plugin.compose").version(libs.findVersion("kotlin").get().toString())
}

allprojects {
    group = PUBLISHING_GROUP
}

val detektFormatting = libs.detekt.formatting

subprojects {
    apply {
        plugin("io.gitlab.arturbosch.detekt")
    }

    detekt {
        config.from(rootProject.files("config/detekt/detekt.yml"))
    }

    dependencies {
        detektPlugins(detektFormatting)
    }
}

tasks {
    withType<DependencyUpdatesTask>().configureEach {
        rejectVersionIf {
            candidate.version.isStableVersion().not()
        }
    }
}
