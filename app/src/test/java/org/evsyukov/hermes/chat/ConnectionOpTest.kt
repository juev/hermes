package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.evsyukov.hermes.data.remote.HermesJson
import org.junit.Test

class ConnectionOpTest {
    private fun json(text: String): JsonObject = HermesJson.parseToJsonElement(text).jsonObject

    private val request = json(
        """
        {"op_id":"op1","seq":1,"deadline_at":1800000000.5,"timeout_seconds":600,"tool_call_id":"call_1",
         "targets":[
           {"name":"gmail","kind":"connector","action":"connect","state":"initiated","alias":"work",
            "connect_url":"https://connect.example/gmail","connection_id":"ca_1","tools":["send_email"]},
           {"name":"github","kind":"mcp","action":"install","state":"pending",
            "required_env":[{"name":"GITHUB_TOKEN","required":true,"secret":true,"default":"","prompt":null}]},
           {"name":"notes","kind":"plugin","action":"install","state":"pending","display":"Notes","tier":"verified",
            "repo":"acme/notes","sha":"abc123","scan":{"status":"warnings","summary":"network access"},
            "approved":{"force":false,"enable":true,"ref":null}}
         ]}
        """,
    )

    @Test
    fun `a request becomes a card with every kind of row`() {
        val connection = connectionOf(request)!!

        assertThat(connection.opId).isEqualTo("op1")
        assertThat(connection.toolCallId).isEqualTo("call_1")
        assertThat(connection.deadlineAt).isEqualTo(1800000000.5)
        assertThat(connection.catalog).isTrue()
        assertThat(connection.awaitsBrowser).isTrue()
        val (gmail, github, notes) = connection.targets
        assertThat(gmail.alias).isEqualTo("work")
        assertThat(gmail.connectUrl).isEqualTo("https://connect.example/gmail")
        assertThat(gmail.state).isEqualTo(TargetState.INITIATED)
        assertThat(github.requiredEnv).containsExactly(EnvField("GITHUB_TOKEN", required = true, secret = true, default = "", prompt = null))
        assertThat(notes.catalog!!.display).isEqualTo("Notes")
        assertThat(notes.catalog!!.scan).isEqualTo("warnings" to "network access")
        assertThat(notes.catalog!!.approved).isEqualTo(Approved(force = false, enable = true, ref = null))
    }

    @Test
    fun `a request without an operation, a call or anything to show is no card`() {
        assertThat(connectionOf(json("""{"seq":1,"deadline_at":1,"tool_call_id":"c","targets":[{"name":"x","kind":"mcp","action":"install","state":"pending"}]}"""))).isNull()
        assertThat(connectionOf(json("""{"op_id":"o","seq":1,"deadline_at":1,"targets":[{"name":"x","kind":"mcp","action":"install","state":"pending"}]}"""))).isNull()
        assertThat(connectionOf(json("""{"op_id":"o","seq":1,"deadline_at":1,"tool_call_id":"c","targets":[]}"""))).isNull()
    }

    @Test
    fun `a directory card has suggestions and no rows`() {
        val connection = connectionOf(json("""{"op_id":"o","seq":1,"deadline_at":1,"tool_call_id":"c","targets":[],"display":{"suggested":["gmail","slack"]}}"""))!!

        assertThat(connection.targets).isEmpty()
        assertThat(connection.suggested).containsExactly("gmail", "slack").inOrder()
    }

    @Test
    fun `an unknown kind shows as an MCP row`() {
        val connection = connectionOf(json("""{"op_id":"o","seq":1,"deadline_at":1,"tool_call_id":"c","targets":[{"name":"x","kind":"robot","action":"install","state":"pending"}]}"""))!!

        assertThat(connection.targets.single().kind).isEqualTo("mcp")
    }

    @Test
    fun `an update moves a row and keeps the link the server minted before`() {
        val connection = connectionOf(request)!!.applying(
            json(
                """
                {"op_id":"op1","seq":2,"deadline_at":1800000100,"settled":false,"target":"gmail","from":"initiated","to":"connected",
                 "targets":[{"name":"gmail","kind":"connector","action":"connect","state":"connected","alias":"work"},
                            {"name":"github","kind":"mcp","action":"install","state":"pending"},
                            {"name":"notes","kind":"plugin","action":"install","state":"pending"}]}
                """,
            ),
        )

        val gmail = connection.targets[0]
        assertThat(connection.seq).isEqualTo(2)
        assertThat(connection.deadlineAt).isEqualTo(1800000100.0)
        assertThat(gmail.state).isEqualTo(TargetState.CONNECTED)
        assertThat(gmail.connectUrl).isEqualTo("https://connect.example/gmail")
        assertThat(gmail.connectionId).isEqualTo("ca_1")
        assertThat(gmail.tools).containsExactly("send_email")
        assertThat(connection.targets[1].requiredEnv).hasSize(1)
        assertThat(connection.awaitsBrowser).isFalse()
    }

    @Test
    fun `an older frame, a frame of another operation and a frame after the settlement change nothing`() {
        val card = connectionOf(request)!!.applying(json("""{"op_id":"op1","seq":5,"deadline_at":1,"settled":false,"targets":[]}"""))
        val older = """{"op_id":"op1","seq":4,"deadline_at":1,"settled":true,"targets":[{"name":"gmail","kind":"connector","action":"connect","state":"failed"}]}"""
        val other = """{"op_id":"op2","seq":9,"deadline_at":1,"settled":true,"targets":[]}"""

        assertThat(card.applying(json(older))).isSameInstanceAs(card)
        assertThat(card.applying(json(other))).isSameInstanceAs(card)

        val settled = card.applying(json("""{"op_id":"op1","seq":6,"deadline_at":1,"settled":true,"settled_by":"continue","targets":[]}"""))
        assertThat(settled.settled).isTrue()
        assertThat(settled.settledBy).isEqualTo("continue")
        assertThat(settled.applying(json("""{"op_id":"op1","seq":7,"deadline_at":1,"settled":false,"targets":[]}"""))).isSameInstanceAs(settled)
    }
}
