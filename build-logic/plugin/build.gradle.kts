plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    alias(libs.plugins.ksp) // KSP
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

gradlePlugin {
    val build by plugins.creating {
        id = "com.grab.grazel.build.common"
        implementationClass = "com.grab.grazel.build.BuildLogicPlugin"
    }
}

dependencies {
    /* implementation(libs.coroutines)
     implementation(libs.coroutines.jvm)*/
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
