package com.alexdiab.stadiatv.data.news

data class RssItem(
    val title: String,
    val link: String?,
    val guid: String?,
    val description: String,
    val publishedMillis: Long?,
    val imageUrl: String?,
    val author: String?,
    val categories: List<String>,
)


fun interface RssDecoder { fun decode(xml: String): List<RssItem> }
