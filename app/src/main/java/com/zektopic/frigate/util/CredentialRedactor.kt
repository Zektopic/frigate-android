package com.zektopic.frigate.util

/**
 * Masks camera credentials wherever a stream URL leaves the app: the HTTP API, logs,
 * diagnostics and on-screen labels.
 *
 * Ported from Frigate's `clean_camera_user_pass`, using the broader patterns the fork
 * introduced. The original patterns only matched usernames made of `[a-zA-Z0-9_-]`,
 * so `rtsp://john.doe:secret@cam`, `rtsp://:secret@cam` and
 * `user=john.doe&password=...` all passed through in clear text.
 */
object CredentialRedactor {

    private val URL_USER_PASS = Regex("""://[^\s:/@]*:\S+@""")
    private val HTTP_USER_PASS = Regex("""user=[^\s&]*&password=\S+""")
    private const val URL_MASK = "://*:*@"
    private const val HTTP_MASK = "user=*&password=*"

    /** [text] with every embedded username and password replaced by `*`. */
    fun redact(text: String): String =
        HTTP_USER_PASS.replace(URL_USER_PASS.replace(text, URL_MASK), HTTP_MASK)

    /**
     * Undo [redact] on text that came back from a client.
     *
     * The API hands out a redacted config; if that text were saved as-is, every
     * password would be replaced by a literal `*`. Each whitespace-delimited token in
     * [edited] that still carries the mask is swapped for the token in [original]
     * that redacts to it. A token the client changed (a new host, a typed password)
     * matches nothing and is kept exactly as sent.
     */
    fun restore(edited: String, original: String): String {
        if (!edited.contains(URL_MASK) && !edited.contains(HTTP_MASK)) return edited
        val secrets = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        for (token in TOKEN.findAll(original)) {
            val masked = redact(token.value)
            if (masked == token.value) continue
            val previous = secrets.put(masked, token.value)
            // Two different secrets behind one mask: guessing would save the wrong one.
            if (previous != null && previous != token.value) ambiguous += masked
        }
        return TOKEN.replace(edited) { match ->
            val value = match.value
            if (value in ambiguous) value else secrets[value] ?: value
        }
    }

    /** True when [text] still contains a mask, i.e. a credential that could not be restored. */
    fun containsMask(text: String): Boolean = text.contains(URL_MASK) || text.contains(HTTP_MASK)

    private val TOKEN = Regex("""\S+""")
}
