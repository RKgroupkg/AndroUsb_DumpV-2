buildscript {
    val kotlinVersion = "1.9.0"
    val agpVersion = "8.0.2"
    
    dependencies {
        classpath("com.android.tools.build:gradle:$agpVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
