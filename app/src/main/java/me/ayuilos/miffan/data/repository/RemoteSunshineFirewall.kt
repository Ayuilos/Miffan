package me.ayuilos.miffan.data.repository

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val firewallJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Exact three-field stdin contract; no profile, TCP, SSH or RDP modifications. */
@Serializable
data class RemoteSunshineFirewallPlan(
    @SerialName("disable_rules") val disableRules: List<String>,
    @SerialName("allowed_sources") val allowedSources: List<String>,
    @SerialName("add_rule") val addRule: AddRule?,
) {
    @Serializable
    data class AddRule(val id: String, val program: String, val protocol: String,
        val ports: List<String>, val sources: List<String>, val replace: Boolean)

    /** Call only after displaying this exact plan and receiving the user's confirmation. */
    fun approve(): ApprovedSunshineFirewallPlan {
        require(disableRules.isNotEmpty() || addRule != null) { "Empty firewall plan" }
        require(allowedSources.isNotEmpty()) { "Source subnets required" }
        addRule?.let {
            require(it.id == "Miffan-Sunshine-UDP-LAN" && it.protocol == "UDP" &&
                it.ports == listOf("47998-48000") && it.sources == allowedSources)
        }
        // Freeze the reviewed values; later edits to a backing collection cannot change approval.
        return ApprovedSunshineFirewallPlan(firewallJson.encodeToString(serializer(), this))
    }
}
class ApprovedSunshineFirewallPlan internal constructor(internal val json: String)

@Serializable
data class RemoteSunshineFirewallCheck(
    val success: Boolean,
    val detail: String,
    @SerialName("firewall_model_ok") val firewallModelOk: Boolean = false,
    @SerialName("udp_rule_present") val udpRulePresent: Boolean = false,
    val rules: List<JsonObject> = emptyList(),
    val profiles: List<JsonObject> = emptyList(),
    @SerialName("unsafe_rules") val unsafeRules: List<JsonObject> = emptyList(),
    @SerialName("unmanageable_rules") val unmanageableRules: List<String> = emptyList(),
    @SerialName("allowed_sources") val allowedSources: List<String> = emptyList(),
    @SerialName("suggested_plan") val suggestedPlan: RemoteSunshineFirewallPlan? = null,
    @SerialName("plan_detail") val planDetail: String? = null,
)
@Serializable
data class RemoteSunshineFirewallResult(
    val success: Boolean, val detail: String,
    val changed: Boolean = false, val backup: String? = null,
    @SerialName("firewall_model_ok") val firewallModelOk: Boolean = false,
    @SerialName("applied_changes") val appliedChanges: RemoteSunshineFirewallPlan? = null,
)
internal fun parseSunshineFirewallCheck(output: String): RemoteSunshineFirewallCheck =
    firewallJson.decodeFromString(output.trim().lineSequence().last())
internal fun parseSunshineFirewallResult(output: String): RemoteSunshineFirewallResult =
    firewallJson.decodeFromString(output.trim().lineSequence().last())

/** Inspection never grants approval and never invokes apply. Fail closed on unknown inspection. */
internal fun requireSunshineFirewall(check: RemoteSunshineFirewallCheck) {
    if (!check.success) throw RemoteScreenUnavailableException(RemoteScreenProblem.FIREWALL_CHECK_FAILED,
        check.detail, firewallCheck = check)
    if (!check.firewallModelOk) throw RemoteScreenUnavailableException(RemoteScreenProblem.FIREWALL_REVIEW_NEEDED,
        check.detail, firewallCheck = check)
}
