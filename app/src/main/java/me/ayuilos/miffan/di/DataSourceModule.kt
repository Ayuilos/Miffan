package me.ayuilos.miffan.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.data.ai.AIRequestInterceptor
import me.ayuilos.miffan.data.ai.RequestLoggingInterceptor
import me.ayuilos.miffan.data.ai.openai.OpenAICodexAuthService
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthService
import me.ayuilos.miffan.data.ai.transformers.AssistantTemplateLoader
import me.ayuilos.miffan.data.ai.GenerationHandler
import me.ayuilos.miffan.data.ai.TranslationHandler
import me.ayuilos.miffan.data.ai.transformers.TemplateTransformer
import me.ayuilos.miffan.data.api.MiffanAPI
import me.ayuilos.miffan.data.api.SponsorAPI
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.fts.MessageFtsManager
import me.ayuilos.miffan.data.db.fts.SimpleDictManager
import me.ayuilos.miffan.data.db.migrations.Migration_6_7
import me.ayuilos.miffan.data.db.migrations.Migration_11_12
import me.ayuilos.miffan.data.db.migrations.Migration_13_14
import me.ayuilos.miffan.data.db.migrations.Migration_14_15
import me.ayuilos.miffan.data.db.migrations.Migration_15_16
import me.ayuilos.miffan.data.db.migrations.Migration_24_25
import me.ayuilos.miffan.data.ai.mcp.McpManager
import me.ayuilos.miffan.data.network.SettingsProxyAuthenticator
import me.ayuilos.miffan.data.network.SettingsProxySelector
import me.ayuilos.miffan.data.network.SettingsSocks5Authenticator
import me.ayuilos.miffan.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import me.ayuilos.miffan.data.sync.S3Sync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_24_25,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                }
            })
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null
                        )
                    )
                    options
                }
            )))
            .build()
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get(), appEventBus = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            workspaceManager = get(),
        )
    }

    single {
        TranslationHandler(providerManager = get())
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
        val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
        val appliedProxySetting = AtomicReference(
            Triple(
                initialNetworkSetting.proxyUrl,
                initialNetworkSetting.proxyUsername,
                initialNetworkSetting.proxyPassword,
            )
        )
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val networkSetting = settingsStore.settingsFlow.value.networkSetting
                val currentProxySetting = Triple(
                    networkSetting.proxyUrl,
                    networkSetting.proxyUsername,
                    networkSetting.proxyPassword,
                )
                if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                    client.connectionPool.evictAll()
                }

                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    val userAgent = networkSetting.userAgent
                        .trim()
                        .ifEmpty { "Miffan-Android/${BuildConfig.VERSION_NAME}" }
                    requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent)
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                redactHeader("Authorization")
                redactHeader("Proxy-Authorization")
                redactHeader("X-Api-Key")
                redactHeader("Cookie")
                redactHeader("Set-Cookie")
                redactHeader("ChatGPT-Account-Id")
            })
            .build()
        client.also { SearchService.init(it, get()) }
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        OpenAICodexAuthService(
            httpClient = get(),
            settingsStore = get(),
        )
    }

    single {
        OpenRouterAuthService(
            context = get(),
            httpClient = get(),
            settingsStore = get(),
            appScope = get(),
        )
    }

    single {
        ProviderManager(
            client = get(),
            context = get(),
            openAICodexTokenProvider = get<OpenAICodexAuthService>(),
        )
    }

    single {
        WebDavSync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<MiffanAPI> {
        get<Retrofit>().create(MiffanAPI::class.java)
    }
}
