package me.ayuilos.miffan.data.repository

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WindowsSetupScriptTest {
    private val template = "& { \$key = '__MIFFAN_PUBLIC_KEY__' }"

    @Test fun acceptsSupportedTypesAndDropsUntrustedComments() {
        for (type in listOf("ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521")) {
            assertEquals("& { \$key = '$type AAAA+/==' }", WindowsSetupScript.render(template,
                "$type\tAAAA+/== someone'; Write-Host \$(evil) `whoami`"))
        }
    }

    @Test fun rejectsInvalidKeysAndAmbiguousTemplates() {
        for (key in listOf("ssh-dss AAAA", "ssh-ed25519", "ssh-ed25519 A;bad", "ssh-rsa AA=A",
            "ssh-rsa AAA===", "ssh-rsa AA\n", "ssh-rsa AA\rcomment", "ssh-rsa AA\u0000", "ssh-rsa 'AAAA'")) {
            assertTrue(key, runCatching { WindowsSetupScript.render(template, key) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (bad in listOf("", template + template, "\"__MIFFAN_PUBLIC_KEY__\"")) {
            assertTrue(runCatching { WindowsSetupScript.render(bad, "ssh-rsa AAAA") }.isFailure)
        }
    }

    @Test fun realAssetHasOneQuotedPlaceholderAndRendersOnce() {
        val asset = File("src/main/assets/remote/windows-setup.ps1").readText()
        val rendered = WindowsSetupScript.render(asset, "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMiffan test")
        assertFalse(rendered.contains("__MIFFAN_PUBLIC_KEY__"))
        assertEquals(1, Regex("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMiffan").findAll(rendered).count())
        assertTrue(rendered.startsWith("& {\n"))
        assertTrue(rendered.trimEnd().endsWith("}"))
    }
}
