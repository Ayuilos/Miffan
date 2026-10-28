plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.wire)
}

android {
    namespace = "me.rerere.cuaspace"
}

wire {
    // Messages only: the calls go through SpaceGrpc on our own OkHttp client, so Wire's gRPC client
    // (which brings a JVM-only OkHttp) is not used.
    kotlin {
        rpcRole = "none"
    }
    root(
        "cua.env.v1.SystemService",
        "cua.env.v1.ProcessService",
        "cua.env.v1.FilesystemService",
        "cua.env.v1.StreamService",
    )
}

dependencies {
    api(libs.wire.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
