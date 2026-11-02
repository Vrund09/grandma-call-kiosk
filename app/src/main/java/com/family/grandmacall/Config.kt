package com.family.grandmacall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Contact(val name: String, val number: String, val photo: String)

data class Config(
    val contacts: List<Contact>,
    val speakerphone: Boolean,
    val blockStatusBar: Boolean,
    /** Packages hidden while Grandma Call is device owner. */
    val hideApps: List<String>,
    /** Testing aid: taps are only logged ("would call …"), nothing is dialled. */
    val dryRun: Boolean,
    /** Who gets the power-cut / low-battery / power-back SMS. Null = no alerts. */
    val alertNumber: String?,
    /** How long the charger must be without power before the power-cut SMS is sent. */
    val powerCutAlertSeconds: Int,
) {
    companion object {
        @Volatile private var cached: Config? = null

        fun get(context: Context): Config =
            cached ?: load(context).also { cached = it }

        private fun load(context: Context): Config {
            val text = context.assets.open("config.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val contacts = root.getJSONArray("contacts").objects().map {
                Contact(it.getString("name"), it.getString("number"), it.getString("photo"))
            }
            return Config(
                contacts = contacts,
                speakerphone = root.optBoolean("speakerphone", true),
                blockStatusBar = root.optBoolean("blockStatusBar", true),
                dryRun = root.optBoolean("dryRun", false),
                alertNumber = root.optString("alertNumber", "").ifBlank { null },
                powerCutAlertSeconds = root.optInt("powerCutAlertSeconds", 120),
                hideApps = root.optJSONArray("hideApps")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    }
}
