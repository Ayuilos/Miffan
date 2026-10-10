plugins { id("rikkahub.android.library") }
android {
    namespace = "me.rerere.stream"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

val prepareStreamSources by tasks.registering(Exec::class) {
    inputs.files(rootProject.file(".deps/p6a/common-c.tar.gz"), rootProject.file(".deps/p6a/common-c/enet.tar.gz"),
        rootProject.file(".deps/p6a/common-c/nanors.tar.gz"))
    inputs.dir("scripts/patches")
    inputs.file("scripts/prepare.py")
    outputs.dir(layout.buildDirectory.dir("sources"))
    commandLine("python3", project.file("scripts/prepare.py"))
}
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(prepareStreamSources)
}
