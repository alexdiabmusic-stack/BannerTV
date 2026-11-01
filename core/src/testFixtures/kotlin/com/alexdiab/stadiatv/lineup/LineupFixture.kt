package com.alexdiab.stadiatv.lineup

import com.alexdiab.stadiatv.live.Channel

/**
 * A real provider's lineup (names, categories and guide ids) saved as test data: names exactly as the provider
 * writes them, including the decoration, duplicates and wrong guide ids that matching has to cope with.
 */
object LineupFixture {
    val channels: List<Channel> by lazy {
        resource("streams.tsv").lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
            val parts = line.split('\t')
            channel(index, parts[0], parts.getOrElse(1) { "" }, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }
    }

    val listings: List<GuideListing> by lazy {
        resource("guide.tsv").lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t')
            GuideListing(parts[0], parts[1].split('|').filter { it.isNotBlank() }, parts[2].toInt())
        }
    }

    val matcher = CatalogMatcher()
    val builder = LineupBuilder(matcher)
    val directory: GuideDirectory by lazy { GuideDirectory.build(matcher, listings) }

    fun channel(id: Int, name: String, group: String = "", epg: String? = null) = Channel(
        id = id.toString(), name = name, streamUrl = "http://example.test/$id.m3u8", logoUrl = null,
        group = group, playlistId = "p", playlistName = "Provider", tvgId = epg,
    )

    fun lineup(channels: List<Channel> = this.channels, directory: GuideDirectory? = this.directory): Lineup =
        builder.build(builder.analyse(channels), directory)

    private fun resource(name: String): String =
        checkNotNull(LineupFixture::class.java.getResource("/lineup/$name")) { "missing test resource $name" }.readText()
}
