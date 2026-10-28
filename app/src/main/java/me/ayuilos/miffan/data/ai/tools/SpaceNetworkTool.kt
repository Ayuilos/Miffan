package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot

const val SPACE_NETWORK_FIX_TOOL = "request_network_fix"

/** The only command the network card runs on the host; the partner never chooses what runs there. */
const val SPACE_NETWORK_FIX_COMMAND = "sudo ufw route allow in on docker0"

/**
 * Asks the owner to let the partner's own computer reach the internet. The chat shows a card that runs
 * [SPACE_NETWORK_FIX_COMMAND] on [computerName] from the phone (the owner types sudo's password there);
 * approving it means the fix ran, after which this checks the network again and reports the result.
 */
fun createSpaceNetworkTool(
    workspaceId: String,
    computerName: String,
    snapshot: WorkspaceToolTargetSnapshot?,
    spaces: PartnerSpaces,
): Tool = Tool(
    name = SPACE_NETWORK_FIX_TOOL,
    description = "Call when your own computer cannot reach the internet (DNS lookups or downloads fail, e.g. apt or " +
        "pip time out). The owner sees a card in this chat that fixes it on $computerName with one command they run " +
        "from their phone; this returns whether your computer is online afterwards. Do not ask for passwords or try " +
        "to change $computerName's firewall yourself.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("reason") {
                    put("type", "string")
                    put("description", "One short sentence for the owner: what failed and what you were trying to do.")
                }
            },
            required = listOf("reason"),
        )
    },
    needsApproval = { true },
    workspaceTarget = snapshot,
    execute = {
        val check = runCatching { spaces.netcheck(workspaceId) }
        listOf(UIMessagePart.Text(check.fold(
            onSuccess = { result ->
                buildJsonObject {
                    put("online", result.online)
                    put("dns", result.dns)
                    put("https", result.https)
                    result.detail?.let { put("failed", it) }
                    if (!result.online) put("next", "Still offline. Tell the owner what failed; they can retry from your " +
                        "computer's page (partner profile › Computer).")
                }
            },
            onFailure = { error ->
                buildJsonObject {
                    put("online", false)
                    put("error", error.message ?: "The network check could not run")
                }
            },
        ).toString()))
    },
)
