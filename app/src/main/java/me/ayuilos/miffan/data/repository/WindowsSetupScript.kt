package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager

/** Standalone, administrator-run Windows onboarding. Comments are never inserted into code. */
object WindowsSetupScript {
    private const val PLACEHOLDER = "__MIFFAN_PUBLIC_KEY__"
    private val keyPattern = Regex(
        """\A(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521))[ \t]+([A-Za-z0-9+/]+={0,2})(?:[ \t]+[^\r\n\u0000]*)?\z"""
    )

    fun render(template: String, publicKey: String): String {
        require(publicKey.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Public key must be one line" }
        val match = requireNotNull(keyPattern.matchEntire(publicKey.trim())) { "Unsupported public key format" }
        require(template.windowed(PLACEHOLDER.length).count { it == PLACEHOLDER } == 1 &&
            template.contains("'$PLACEHOLDER'")) { "Template must contain exactly one single-quoted public key placeholder" }
        return template.replace(PLACEHOLDER, "${match.groupValues[1]} ${match.groupValues[2]}")
    }

    /** Read on an IO dispatcher when called from UI. */
    fun fromAssets(assets: AssetManager, publicKey: String): String =
        assets.open("remote/windows-setup.ps1").bufferedReader(Charsets.UTF_8).use { render(it.readText(), publicKey) }
}
