package io.github.artcraftandroid.wordcraft.mcp

import android.content.Context
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The DoxAi agent server: the open document as an MCP server over Streamable HTTP, on loopback
 * only (nothing off the device can reach it). A computer reaches it with
 * `adb reverse tcp:8636 tcp:8636`. No authentication: the endpoint lives on the device itself.
 */
object DoxAiMcpServer {
    const val PORT = 8636
    const val URL = "http://127.0.0.1:$PORT/mcp"

    @Volatile
    private var host: EmbeddedServer<*, *>? = null

    fun start(context: Context) {
        if (host != null) return
        host = embeddedServer(CIO, host = "127.0.0.1", port = PORT) {
            routing {
                get("/health") { call.respondText("doxai-mcp") }
            }
            mcpStreamableHttp { build() }
        }.start(wait = false)
    }

    fun stop() {
        host?.stop(500, 1000)
        host = null
    }

    private fun text(result: String) = CallToolResult(content = listOf(TextContent(result)))

    private fun failure(message: String) =
        CallToolResult(content = listOf(TextContent(message)), isError = true)

    private suspend fun toolResult(block: suspend (McpBridge.Provider) -> CallToolResult): CallToolResult {
        val p = McpBridge.provider
        if (p == null || !p.hasDocument) return failure("No document is open in DoxAi.")
        return runCatching { block(p) }.getOrElse { failure(it.message ?: it.toString()) }
    }

    private fun str(description: String = "") = buildJsonObject {
        put("type", "string")
        if (description.isNotEmpty()) put("description", description)
    }

    private fun int(description: String = "") = buildJsonObject {
        put("type", "integer")
        if (description.isNotEmpty()) put("description", description)
    }

    private fun schema(vararg fields: Pair<String, JsonObject>) =
        ToolSchema(properties = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } })

    private fun arg(request: CallToolRequest, key: String): String? =
        request.arguments?.get(key)?.jsonPrimitive?.let { if (it.isString) it.content else it.toString() }

    private fun argInt(request: CallToolRequest, key: String): Int? =
        request.arguments?.get(key)?.jsonPrimitive?.intOrNull

    private fun build(): Server {
        val server = Server(
            serverInfo = Implementation(name = "doxai", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        server.addTool(
            name = "get_document_info",
            description = "The name and engine-reported info of the document open in DoxAi.",
        ) { _ ->
            toolResult { p ->
                text("""{"name": ${JsonPrimitive(p.documentName ?: "")}, "info": ${p.command("file.info", "") ?: "null"}}""")
            }
        }

        server.addTool(
            name = "get_selection",
            description = "Where the caret is and what it covers, with the formatting in force there.",
        ) { _ ->
            toolResult { p ->
                text("""{"selection": ${p.selection() ?: "null"}, "format": ${p.command("format.state", "") ?: "null"}}""")
            }
        }

        server.addTool(
            name = "type_text",
            description = "Type text at the caret, as if the user typed it.",
            inputSchema = schema("text" to str("The text to insert at the caret.")),
        ) { request ->
            toolResult { p ->
                val t = arg(request, "text") ?: return@toolResult failure("text is required.")
                if (p.typeText(t)) text("Typed ${t.length} characters.") else failure("The engine refused the text.")
            }
        }

        server.addTool(
            name = "insert_table",
            description = "Insert a table at the caret.",
            inputSchema = schema(
                "rows" to int("Row count, at least 1."),
                "cols" to int("Column count, at least 1."),
            ),
        ) { request ->
            toolResult { p ->
                val rows = argInt(request, "rows") ?: return@toolResult failure("rows is required.")
                val cols = argInt(request, "cols") ?: return@toolResult failure("cols is required.")
                if (rows < 1 || cols < 1) return@toolResult failure("rows and cols must be at least 1.")
                val params = buildJsonObject { put("rows", rows); put("cols", cols) }.toString()
                p.command("insert.table", params)?.let { text("Inserted a $rows x $cols table.") }
                    ?: failure("The engine refused insert.table.")
            }
        }

        server.addTool(
            name = "insert_picture",
            description = "Insert a picture at the caret, given as base64 (PNG, JPEG or similar).",
            inputSchema = schema("data" to str("The picture's bytes, base64-encoded.")),
        ) { request ->
            toolResult { p ->
                val data = arg(request, "data") ?: return@toolResult failure("data is required.")
                p.command("insert.picture", buildJsonObject { put("data", data) }.toString())
                    ?.let { text("Inserted the picture.") } ?: failure("The engine refused insert.picture.")
            }
        }

        server.addTool(
            name = "insert_link",
            description = "Turn the selection into a hyperlink, or insert linked text.",
            inputSchema = schema(
                "url" to str("The link's address."),
                "text" to str("Optional text to insert and link; the selection is used when left out."),
            ),
        ) { request ->
            toolResult { p ->
                val url = arg(request, "url") ?: return@toolResult failure("url is required.")
                val params = buildJsonObject {
                    put("url", url)
                    arg(request, "text")?.let { put("text", it) }
                }.toString()
                p.command("insert.link", params)?.let { text("Inserted the link.") }
                    ?: failure("The engine refused insert.link.")
            }
        }

        server.addTool(
            name = "add_comment",
            description = "Comment on the selection.",
            inputSchema = schema("text" to str("The comment's text.")),
        ) { request ->
            toolResult { p ->
                val t = arg(request, "text") ?: return@toolResult failure("text is required.")
                p.command("review.newComment", buildJsonObject { put("text", t) }.toString())
                    ?.let { text("Added the comment.") } ?: failure("The engine refused review.newComment.")
            }
        }

        server.addTool(
            name = "save_document",
            description = "Save the document over the file it came from.",
        ) { _ ->
            toolResult { p ->
                if (p.save()) text("Saved.")
                else failure("Not saved: the document has no writable file; use Save As in the app.")
            }
        }

        server.addTool(
            name = "export_pdf",
            description = "Lay the document out as PDF and return its bytes, base64-encoded.",
        ) { _ ->
            toolResult { p ->
                val bytes = p.exportPdf() ?: return@toolResult failure("The engine refused the export.")
                text(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }
        }

        server.addTool(
            name = "run_command",
            description =
                "Run any engine command the app's own UI can, by id, with a JSON params object. " +
                    "Call list_commands for the ids and their params.",
            inputSchema = schema(
                "id" to str("The command's id, as list_commands reports it."),
                "params" to str("JSON params object; {} when the command takes none."),
            ),
        ) { request ->
            toolResult { p ->
                val id = arg(request, "id") ?: return@toolResult failure("id is required.")
                val params = arg(request, "params") ?: "{}"
                p.command(id, params)?.let { text("Ran $id: $it") } ?: failure("The engine refused $id.")
            }
        }

        server.addTool(
            name = "list_commands",
            description = "The engine commands DoxAi exposes, with what each takes.",
        ) { _ ->
            toolResult { _ -> text(COMMANDS.joinToString("\n") { "${it.first}: ${it.second}" }) }
        }

        return server
    }

    /** The commands [run_command] accepts, with their params. */
    private val COMMANDS = listOf(
        "format.bold" to "{}",
        "format.italic" to "{}",
        "format.underline" to "{}",
        "format.strikethrough" to "{}",
        "format.clear" to "{}",
        "format.subscript" to "{}",
        "format.superscript" to "{}",
        "format.font" to """{"family": "Calibri"}""",
        "format.size" to """{"size": 12}""",
        "format.highlight" to """{"color": "yellow"}""",
        "format.color" to """{"color": "FF0000"}""",
        "para.alignLeft" to "{}",
        "para.alignCenter" to "{}",
        "para.alignRight" to "{}",
        "para.justify" to "{}",
        "para.indent" to "{}",
        "para.outdent" to "{}",
        "para.bullets" to "{}",
        "para.numbering" to "{}",
        "para.style" to """{"style": "Heading 1"}""",
        "para.spacing" to "{}",
        "insert.pageBreak" to "{}",
        "insert.pageNumber" to "{}",
        "insert.header" to "{}",
        "insert.footer" to "{}",
        "insert.shape" to """{"preset": "rect"}""",
        "insert.equation" to "{}",
        "table.insertRowBelow" to "{}",
        "table.insertColumnRight" to "{}",
        "table.deleteRow" to "{}",
        "table.deleteColumn" to "{}",
        "table.merge" to "{}",
        "review.trackChanges" to "{}",
        "review.acceptAll" to "{}",
        "review.rejectAll" to "{}",
        "review.wordCount" to "{}",
        "layout.margins" to """{"top": 1, "bottom": 1, "left": 1, "right": 1}""",
        "layout.orientation" to """{"value": "portrait"}""",
        "layout.size" to "{}",
        "layout.columns" to "{}",
        "edit.undo" to "{}",
        "edit.redo" to "{}",
        "edit.copy" to "{}",
        "edit.cut" to "{}",
        "edit.paste" to "{}",
        "file.save" to "{}",
        "file.saveAs" to "{}",
    )
}
