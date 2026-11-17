package ir.jrweb.drjrweb

data class ThreatItem(
    val title: String,
    val description: String,
    val isApp: Boolean,
    val packageName: String? = null,
    val filePath: String? = null
)