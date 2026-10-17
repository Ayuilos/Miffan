import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

class RemoteHelperPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val assemble = tasks.register<AssembleRemoteHelper>("assembleRemoteHelper") {
            sourceDirectory.set(rootProject.layout.projectDirectory.dir("remote-helper/src"))
            outputDirectory.set(layout.buildDirectory.dir("generated/remoteHelper"))
        }
        extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(assemble, AssembleRemoteHelper::outputDirectory)
        }
        tasks.withType<Test>().configureEach {
            dependsOn(assemble)
            inputs.dir(assemble.flatMap { it.outputDirectory }).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.dir(rootProject.layout.projectDirectory.dir("remote-helper/fixtures")).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.file(rootProject.layout.projectDirectory.file("remote-helper/versions.lock")).withPathSensitivity(PathSensitivity.RELATIVE)
            systemProperty("remoteHelper.assets", assemble.get().outputDirectory.dir("remote").get().asFile.absolutePath)
            systemProperty("remoteHelper.fixtures", rootProject.layout.projectDirectory.dir("remote-helper/fixtures").asFile.absolutePath)
            systemProperty("remoteHelper.versions", rootProject.layout.projectDirectory.file("remote-helper/versions.lock").asFile.absolutePath)
            systemProperty("remoteHelper.windowsTests", rootProject.layout.projectDirectory.dir("remote-helper/tests/windows").asFile.absolutePath)
        }
    }
}
