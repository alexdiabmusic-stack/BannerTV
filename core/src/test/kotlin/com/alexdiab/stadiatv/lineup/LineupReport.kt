package com.alexdiab.stadiatv.lineup

/** A readable account of how a lineup came out, for tuning the catalogue against a real provider. */
object LineupReport {
    fun write(lineup: Lineup, streams: List<ResolvedStream>): String = buildString {
        val live = streams.filterNot { it.isEvent }
        appendLine("streams ${streams.size}  event/PPV ${streams.size - live.size}  entries ${lineup.entries.size}")
        val catalogued = lineup.entries.filter { it.catalog != null }
        appendLine("catalogue entries ${catalogued.size}  distinct catalogue channels ${catalogued.mapNotNull { it.catalog?.id }.toSet().size} of ${ChannelCatalog.all.size}")
        val prefs = LineupPrefs()
        fun guided(e: LineupEntry) = e.pick(prefs).guideId != null
        val sources = lineup.entries.groupingBy { it.pick(prefs).guideSource }.eachCount()
        appendLine("guide source of the stream that plays: $sources")
        val real = lineup.entries.filter { !it.isFast && (it.country == "CA" || it.country == "US") }
        appendLine("CA/US channels (no free streaming) ${real.size}: with guide ${real.count(::guided)}, without ${real.count { !guided(it) }}; free streaming entries ${lineup.entries.count { it.isFast }}")
        appendLine("HD pick: ${lineup.entries.count { it.pick(LineupPrefs()).stream.quality.rank >= Quality.Hd.rank }} entries play HD+ by default; SD-only ${lineup.entries.count { e -> e.feeds.all { f -> f.streams.all { it.quality.rank < Quality.Hd.rank } } }}")

        appendLine("\n== catalogue channels the lineup does not carry (tier, name)")
        val carried = catalogued.mapNotNull { it.catalog?.id }.toSet()
        for (tier in Tier.entries) {
            val missing = ChannelCatalog.all.filter { it.tier == tier && it.id !in carried }
            appendLine("-- $tier: ${missing.size} missing of ${ChannelCatalog.all.count { it.tier == tier }}")
            if (tier != Tier.Optional) missing.forEach { appendLine("   ${it.id}  ${it.name}") }
        }

        appendLine("\n== catalogued entries without a guide")
        catalogued.filterNot(::guided).sortedBy { it.tier }.forEach { e ->
            appendLine("   [${e.tier}] ${e.name}  (${e.streamCount} streams; e.g. ${e.feeds.first().streams.first().channel.name.trim()} id=${e.feeds.first().streams.first().channel.tvgId})")
        }

        appendLine("\n== CA/US entries without a guide that the catalogue doesn't know")
        lineup.entries.filter { it.catalog == null && !it.isFast && (it.country == "CA" || it.country == "US") && !guided(it) }.forEach { e ->
            appendLine("   ${e.country} ${e.name}  (e.g. ${e.feeds.first().streams.first().channel.name.trim()} id=${e.feeds.first().streams.first().channel.tvgId})")
        }

        appendLine("\n== CA/US uncatalogued entries that do have a guide")
        lineup.entries.filter { it.catalog == null && !it.isFast && (it.country == "CA" || it.country == "US") && guided(it) }.forEach { e ->
            appendLine("   ${e.country} ${e.name}  -> ${e.feeds.firstNotNullOf { it.guideId }}")
        }

        appendLine("\n== every catalogued entry: name -> guide (source) <- provider names")
        catalogued.sortedBy { it.name }.forEach { e ->
            val pick = e.pick(prefs)
            val names = e.feeds.flatMap { f -> f.streams.map { it.channel.name.trim() } }.take(3).joinToString(" / ")
            val shift = if (pick.guideOffsetMinutes != 0) " ${pick.guideOffsetMinutes}min" else ""
            appendLine("   ${e.name} [${e.country}] -> ${pick.guideId} (${pick.guideSource}$shift)  plays ${pick.channel.name.trim()}  <- $names")
        }

        appendLine("\n== catalogued entries by catalogue channel (markets collapsed)")
        catalogued.groupBy { it.catalog!!.id }.toSortedMap().forEach { (id, entries) ->
            val withGuide = entries.count(::guided)
            appendLine("   $id: ${entries.size} entries ($withGuide with guide)" + if (entries.size <= 3) "  " + entries.joinToString(", ") { it.name } else "")
        }
    }
}
