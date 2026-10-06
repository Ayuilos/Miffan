package me.ayuilos.miffan.data.repository

import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.provider.Modality
import me.rerere.ai.registry.ModelCatalog
import me.rerere.ai.registry.ModelRegistry

fun Settings.repairModelCapabilities(catalog: ModelCatalog): Settings {
    val textOnly = listOf(Modality.TEXT)
    val repairedProviders = providers.map { provider ->
        val models = provider.models.map modelMap@{ model ->
            if (model.capabilitiesEdited || model.abilities.isNotEmpty() ||
                model.inputModalities != textOnly || model.outputModalities != textOnly
            ) {
                return@modelMap model
            }
            val inferred = ModelRegistry.resolveCapabilities(model.copy(discoveredCapabilities = null), catalog)
            if (inferred.abilities.isEmpty() && inferred.inputModalities == textOnly &&
                inferred.outputModalities == textOnly
            ) {
                model
            } else {
                model.copy(
                    abilities = inferred.abilities,
                    inputModalities = inferred.inputModalities,
                    outputModalities = inferred.outputModalities,
                )
            }
        }
        if (models == provider.models) provider else provider.copyProvider(models = models)
    }
    return if (repairedProviders == providers) this else copy(providers = repairedProviders)
}
