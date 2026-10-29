package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.ServerRequest
import org.junit.Test

class ApprovalTest {
    private fun request(params: String) = ServerRequest("r1", "approval", HermesJson.parseToJsonElement(params).jsonObject)

    @Test
    fun `a dangerous command keeps the command, its reason and every choice`() {
        val approval = approvalOf(
            request(
                """
                {"session_id":"s1","request_id":"a1","command":"rm -rf build","description":"recursive delete",
                 "pattern_key":"recursive_delete","pattern_keys":["recursive_delete"],
                 "allow_permanent":true,"allow_session":true,"choices":["once","session","always","deny"]}
                """,
            ),
        )

        assertThat(approval.kind).isEqualTo(ApprovalKind.Command)
        assertThat(approval.command).isEqualTo("rm -rf build")
        assertThat(approval.description).isEqualTo("recursive delete")
        assertThat(approval.choices).containsExactly("once", "session", "always", "deny").inOrder()
        assertThat(approval.summary).isEqualTo("rm -rf build\nrecursive delete")
    }

    @Test
    fun `a plugin rule names the tool and gives its reason instead of the allowlist label`() {
        val approval = approvalOf(
            request(
                """
                {"session_id":"s1","request_id":"a1","command":"<send_email> (plugin approval rule)",
                 "description":"Send an email to alice@example.com: Quarterly report",
                 "pattern_key":"plugin_rule:send_email","pattern_keys":["plugin_rule:send_email"],
                 "choices":["once","session","always","deny"]}
                """,
            ),
        )

        assertThat(approval.kind).isEqualTo(ApprovalKind.Tool("send_email"))
        assertThat(approval.command).isEmpty()
        assertThat(approval.choices).containsExactly("once", "session", "always", "deny").inOrder()
        assertThat(approval.summary).isEqualTo("Send an email to alice@example.com: Quarterly report")
    }

    @Test
    fun `a plugin rule without the label takes the tool from the rule key`() {
        val approval = approvalOf(request("""{"command":"","description":"why","pattern_key":"plugin_rule:home_lock"}"""))

        assertThat(approval.kind).isEqualTo(ApprovalKind.Tool("home_lock"))
    }

    @Test
    fun `an MCP question offers only yes or no and shows the server's words`() {
        val approval = approvalOf(
            request(
                """
                {"session_id":"s1","request_id":"a1",
                 "command":"MCP tool 'create_issue' on UNTRUSTED server 'github' wants to run.",
                 "description":"Server 'github' is configured 'trust: untrusted'.",
                 "pattern_key":"mcp_elicitation","pattern_keys":["mcp_elicitation"],
                 "choices":["once","session","always","deny"]}
                """,
            ),
        )

        assertThat(approval.kind).isEqualTo(ApprovalKind.Mcp)
        assertThat(approval.command).isEqualTo("MCP tool 'create_issue' on UNTRUSTED server 'github' wants to run.")
        assertThat(approval.choices).containsExactly("once", "deny").inOrder()
        assertThat(approval.summary)
            .isEqualTo("MCP tool 'create_issue' on UNTRUSTED server 'github' wants to run.\nServer 'github' is configured 'trust: untrusted'.")
    }

    @Test
    fun `an MCP question offers yes and no whatever the server lists`() {
        val approval = approvalOf(request("""{"command":"Confirm?","pattern_key":"mcp_elicitation","choices":["deny"]}"""))

        assertThat(approval.choices).containsExactly("once", "deny").inOrder()
    }

    @Test
    fun `an approval without a pattern key reads as a command with the default choices`() {
        val approval = approvalOf(request("""{"description":"","tool_name":"terminal"}"""))

        assertThat(approval.kind).isEqualTo(ApprovalKind.Command)
        assertThat(approval.description).isEmpty()
        assertThat(approval.choices).containsExactly("once", "deny").inOrder()
    }
}
