package me.ayuilos.miffan.data.appsettings

import me.rerere.search.SearchServiceOptions
import kotlin.uuid.Uuid

/**
 * Search services that take a single API key and always send requests to the same, fixed address, so a secure card
 * can say exactly where the key goes. Services whose address the owner can edit (Jina, Grok, SearXNG, scripts) are
 * left to their settings page.
 */
enum class SearchKeyService(val id: String, val origin: String, val keyUrl: String?, val create: () -> SearchServiceOptions) {
    TAVILY("tavily", "api.tavily.com", "https://app.tavily.com/home", { SearchServiceOptions.TavilyOptions() }),
    EXA("exa", "api.exa.ai", "https://dashboard.exa.ai/api-keys", { SearchServiceOptions.ExaOptions() }),
    ZHIPU("zhipu", "open.bigmodel.cn", "https://bigmodel.cn/usercenter/proj-mgmt/apikeys", { SearchServiceOptions.ZhipuOptions() }),
    DOUBAO("doubao", "open.feedcoopapi.com", "https://console.volcengine.com/search-infinity/api-key", { SearchServiceOptions.DoubaoOptions() }),
    LINKUP("linkup", "api.linkup.so", "https://www.linkup.so/", { SearchServiceOptions.LinkUpOptions() }),
    BRAVE("brave", "api.search.brave.com", "https://api.search.brave.com/", { SearchServiceOptions.BraveOptions() }),
    METASO("metaso", "metaso.cn", "https://metaso.cn/", { SearchServiceOptions.MetasoOptions() }),
    OLLAMA("ollama", "ollama.com", "https://ollama.com/settings/keys", { SearchServiceOptions.OllamaOptions() }),
    PERPLEXITY("perplexity", "api.perplexity.ai", "https://www.perplexity.ai/settings/api", { SearchServiceOptions.PerplexityOptions() }),
    FIRECRAWL("firecrawl", "api.firecrawl.dev", null, { SearchServiceOptions.FirecrawlOptions() }),
    BOCHA("bocha", "api.bochaai.com", "https://open.bochaai.com/", { SearchServiceOptions.BochaOptions() }),
    TINYFISH("tinyfish", "*.tinyfish.ai", "https://agent.tinyfish.ai/api-keys", { SearchServiceOptions.TinyfishOptions() }),
    SERPER("serper", "google.serper.dev", "https://serper.dev/api-keys", { SearchServiceOptions.SerperOptions() });

    companion object {
        fun of(id: String?): SearchKeyService? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun of(options: SearchServiceOptions): SearchKeyService? = when (options) {
            is SearchServiceOptions.TavilyOptions -> TAVILY
            is SearchServiceOptions.ExaOptions -> EXA
            is SearchServiceOptions.ZhipuOptions -> ZHIPU
            is SearchServiceOptions.DoubaoOptions -> DOUBAO
            is SearchServiceOptions.LinkUpOptions -> LINKUP
            is SearchServiceOptions.BraveOptions -> BRAVE
            is SearchServiceOptions.MetasoOptions -> METASO
            is SearchServiceOptions.OllamaOptions -> OLLAMA
            is SearchServiceOptions.PerplexityOptions -> PERPLEXITY
            is SearchServiceOptions.FirecrawlOptions -> FIRECRAWL
            is SearchServiceOptions.BochaOptions -> BOCHA
            is SearchServiceOptions.TinyfishOptions -> TINYFISH
            is SearchServiceOptions.SerperOptions -> SERPER
            else -> null
        }
    }
}

/** The API key of a search service, or null for services that have none. */
fun SearchServiceOptions.searchApiKey(): String? = when (this) {
    is SearchServiceOptions.ZhipuOptions -> apiKey
    is SearchServiceOptions.DoubaoOptions -> apiKey
    is SearchServiceOptions.TavilyOptions -> apiKey
    is SearchServiceOptions.ExaOptions -> apiKey
    is SearchServiceOptions.LinkUpOptions -> apiKey
    is SearchServiceOptions.BraveOptions -> apiKey
    is SearchServiceOptions.MetasoOptions -> apiKey
    is SearchServiceOptions.OllamaOptions -> apiKey
    is SearchServiceOptions.PerplexityOptions -> apiKey
    is SearchServiceOptions.FirecrawlOptions -> apiKey
    is SearchServiceOptions.JinaOptions -> apiKey
    is SearchServiceOptions.BochaOptions -> apiKey
    is SearchServiceOptions.GrokOptions -> apiKey
    is SearchServiceOptions.TinyfishOptions -> apiKey
    is SearchServiceOptions.SerperOptions -> apiKey
    is SearchServiceOptions.BingLocalOptions, is SearchServiceOptions.SearXNGOptions, is SearchServiceOptions.CustomJsOptions -> null
}

/** [this] with every credential it holds passed through [transform] (API key, or SearXNG's password). */
fun SearchServiceOptions.transformSearchSecrets(transform: (String) -> String): SearchServiceOptions = when (this) {
    is SearchServiceOptions.ZhipuOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.DoubaoOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.TavilyOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.ExaOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.LinkUpOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.BraveOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.MetasoOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.OllamaOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.PerplexityOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.FirecrawlOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.JinaOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.BochaOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.GrokOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.TinyfishOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.SerperOptions -> copy(apiKey = transform(apiKey))
    is SearchServiceOptions.SearXNGOptions -> copy(password = transform(password))
    is SearchServiceOptions.BingLocalOptions, is SearchServiceOptions.CustomJsOptions -> this
}

/** [this] with [key] as its API key; services without one stay as they are. */
fun SearchServiceOptions.withSearchApiKey(key: String): SearchServiceOptions =
    // Only services with an API key: SearXNG's password must not be replaced by a key.
    if (searchApiKey() == null) this else transformSearchSecrets { key }

/** A new search service of [service] with [key], under [id]. */
fun SearchKeyService.newOptions(id: Uuid, key: String): SearchServiceOptions = create().withSearchApiKey(key).let { options ->
    when (options) {
        is SearchServiceOptions.TavilyOptions -> options.copy(id = id)
        is SearchServiceOptions.ExaOptions -> options.copy(id = id)
        is SearchServiceOptions.ZhipuOptions -> options.copy(id = id)
        is SearchServiceOptions.DoubaoOptions -> options.copy(id = id)
        is SearchServiceOptions.LinkUpOptions -> options.copy(id = id)
        is SearchServiceOptions.BraveOptions -> options.copy(id = id)
        is SearchServiceOptions.MetasoOptions -> options.copy(id = id)
        is SearchServiceOptions.OllamaOptions -> options.copy(id = id)
        is SearchServiceOptions.PerplexityOptions -> options.copy(id = id)
        is SearchServiceOptions.FirecrawlOptions -> options.copy(id = id)
        is SearchServiceOptions.BochaOptions -> options.copy(id = id)
        is SearchServiceOptions.TinyfishOptions -> options.copy(id = id)
        is SearchServiceOptions.SerperOptions -> options.copy(id = id)
        else -> options
    }
}
