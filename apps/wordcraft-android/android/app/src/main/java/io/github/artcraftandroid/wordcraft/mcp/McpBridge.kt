package io.github.artcraftandroid.wordcraft.mcp

/**
 * The seam between the app's open document and the agent (MCP) server: [AppViewModel] attaches a
 * provider when it is created, and the server's tools call it. Every call runs on the engine's
 * command thread, in order with the user's own edits, so agent and hand edits share one history.
 */
object McpBridge {
    interface Provider {
        /** A document is open for the agent to work on. */
        val hasDocument: Boolean

        /** The open document's file name. */
        val documentName: String?

        /**
         * Run an engine command [id] with [params] (JSON), as the app's own UI does; returns the
         * engine's JSON result, or null when the engine isn't ready or the command failed.
         */
        suspend fun command(id: String, params: String): String?

        /** The selection and the text around it, as the engine reports it. */
        suspend fun selection(): String?

        /** Type [text] at the caret. */
        suspend fun typeText(text: String): Boolean

        /** Save over the file the document came from; false when there is none or it is read-only. */
        suspend fun save(): Boolean

        /** The document laid out as PDF pages. */
        suspend fun exportPdf(): ByteArray?
    }

    @Volatile
    var provider: Provider? = null
        private set

    fun attach(p: Provider) {
        provider = p
    }

    fun detach(p: Provider) {
        if (provider === p) provider = null
    }
}
