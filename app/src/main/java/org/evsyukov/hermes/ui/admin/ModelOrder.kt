package org.evsyukov.hermes.ui.admin

import org.evsyukov.hermes.data.remote.ModelChoices
import org.evsyukov.hermes.data.remote.ModelProvider

/**
 * The providers and models in an order that does not change when the server's answer comes back
 * differently ordered: the provider in use first, then by name, and each provider's models by name.
 *
 * The dashboard sorts the providers it does not know canonically (custom endpoints, user providers)
 * by how many models each one has, and reads the catalogs cache-first while warming them, so a
 * refresh can move them although the user changed nothing. The models of a live catalog can come
 * back in another order as well. Sorting here, rather than trusting that order, keeps a refresh
 * from reshuffling the list under the user's finger.
 */
internal fun ModelChoices.stableOrder(): ModelChoices =
    copy(
        providers = providers.sortedWith(PROVIDER_ORDER).map { provider ->
            provider.copy(models = provider.models.sortedWith(NAME_ORDER))
        },
    )

private val NAME_ORDER = String.CASE_INSENSITIVE_ORDER

private val PROVIDER_ORDER: Comparator<ModelProvider> =
    compareByDescending<ModelProvider> { it.current }
        .thenBy(NAME_ORDER) { it.name }
        .thenBy(NAME_ORDER) { it.slug }
