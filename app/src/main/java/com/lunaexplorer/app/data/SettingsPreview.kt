package com.lunaexplorer.app.data

import kotlinx.serialization.json.JsonElement

enum class TransferVerdict(val label: String) {
    /** The device has no value for the setting. */
    NEW("New"),
    REPLACES("Replaces"),
    IDENTICAL("Unchanged"),
    UNREADABLE("Unreadable"),
}

data class TransferRow(
    val unit: TransferUnit,
    val incoming: JsonElement,
    val verdict: TransferVerdict,
    val current: String = "",
    val offered: String = "",
    val refusal: String = "",
    /** Rows within a list-valued setting. */
    val items: List<TransferItem> = emptyList(),
) {
    val page: String get() = unit.page
    val id: String get() = unit.id
    val missing: List<TransferItem> get() = items.filter { it.missing }
    val usable: Boolean get() = verdict != TransferVerdict.UNREADABLE
}

data class TransferPreview(
    val document: TransferDocument,
    val rows: List<TransferRow>,
    /** Ids in the file this build has no setting for. */
    val unknown: List<String> = emptyList(),
) {
    val changing: List<TransferRow> get() = rows.filter {
        it.verdict == TransferVerdict.NEW || it.verdict == TransferVerdict.REPLACES
    }
    /** Rows ticked by default. */
    val suggested: Set<String> get() = changing.mapTo(HashSet()) { it.id }
}

object SettingsPreviewer {
    fun of(document: TransferDocument, source: TransferSource): TransferPreview {
        val rows = document.values.mapNotNull { (id, incoming) ->
            val unit = SettingsRegistry.unit(id) ?: return@mapNotNull null
            val current = unit.read(source)
            when (val attempt = unit.write(TransferValue(incoming), source)) {
                is TransferWrite.Refused -> TransferRow(
                    unit, incoming, TransferVerdict.UNREADABLE,
                    current = describe(current), offered = describe(incoming), refusal = attempt.why,
                    items = unit.items?.invoke(incoming, source).orEmpty(),
                )
                is TransferWrite.Applied -> {
                    // Compare the value as it reads back after the write, not the raw JSON, since
                    // writers normalise what they accept.
                    val after = unit.read(attempt.source)
                    val verdict = when {
                        current == null -> TransferVerdict.NEW
                        after == current -> TransferVerdict.IDENTICAL
                        else -> TransferVerdict.REPLACES
                    }
                    TransferRow(
                        unit, incoming, verdict,
                        current = describe(current), offered = describe(incoming),
                        items = unit.items?.invoke(incoming, source).orEmpty(),
                    )
                }
            }
        }
        return TransferPreview(document, rows.sortedBy { row ->
            SettingsRegistry.pages.indexOf(row.page).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }, document.unknownIds())
    }

    /** A refused row is skipped and reported in the result; the remaining rows still apply. */
    fun apply(
        preview: TransferPreview,
        source: TransferSource,
        chosen: Set<String>,
        items: Map<String, Set<String>> = emptyMap(),
    ): TransferResult {
        var building = source
        val applied = mutableListOf<String>()
        val refused = mutableListOf<Pair<String, String>>()
        preview.rows.filter { it.id in chosen }.forEach { row ->
            val value = TransferValue(row.incoming, items[row.id])
            when (val write = row.unit.write(value, building)) {
                is TransferWrite.Applied -> { building = write.source; applied += row.unit.label }
                is TransferWrite.Refused -> refused += row.unit.label to write.why
            }
        }
        return TransferResult(building, applied, refused)
    }
}

data class TransferResult(
    val source: TransferSource,
    val applied: List<String>,
    val refused: List<Pair<String, String>>,
)

private fun describe(value: JsonElement?): String {
    if (value == null) return ""
    val text = value.toString()
    return when {
        text.startsWith("[") || text.startsWith("{") -> ""
        else -> text.trim('"')
    }
}
