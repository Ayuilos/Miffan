package me.ayuilos.miffan.data.appsettings

const val REDACTED_CREDENTIAL = "[已隐藏一段像密钥的内容]"

private val CREDENTIAL_PATTERNS = listOf(
    // OpenAI, Anthropic, DeepSeek, Moonshot and most compatible services.
    Regex("""\bsk-[A-Za-z0-9_\-]{12,}"""),
    // Google API keys.
    Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""),
    // Prefixed tokens of common services (Groq, xAI, GitHub, Hugging Face, Replicate, OpenRouter, Perplexity, Tavily).
    Regex("""\b(?:gsk_|xai-|ghp_|gho_|github_pat_|hf_|r8_|sk-or-|pplx-|tvly-)[A-Za-z0-9_\-]{12,}"""),
    Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/\-]{12,}=*"""),
    // Long random tokens: upper and lower case letters and digits mixed (commit hashes and plain words do not match).
    Regex("""(?<![A-Za-z0-9_\-])(?=[A-Za-z0-9_\-]*[a-z])(?=[A-Za-z0-9_\-]*[A-Z])(?=[A-Za-z0-9_\-]*[0-9])[A-Za-z0-9_\-]{32,}(?![A-Za-z0-9_\-])"""),
)

/** True when [text] holds something that looks like an API key or access token. */
fun looksLikeCredential(text: String): Boolean = CREDENTIAL_PATTERNS.any { it.containsMatchIn(text) }

/** [text] with every key-looking token replaced by [REDACTED_CREDENTIAL]. */
fun redactCredentials(text: String): String = CREDENTIAL_PATTERNS.fold(text) { acc, pattern -> pattern.replace(acc, REDACTED_CREDENTIAL) }
