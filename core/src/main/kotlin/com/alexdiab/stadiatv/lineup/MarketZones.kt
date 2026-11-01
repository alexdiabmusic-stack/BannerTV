package com.alexdiab.stadiatv.lineup

/**
 * The time zone a local market sits in, so a station with no schedule of its own can borrow a sister station's
 * network schedule from the same part of the country (Regina from Winnipeg, never from Halifax). Unknown markets
 * are treated as unknown, not guessed.
 */
enum class Zone { Atlantic, Eastern, Central, Mountain, Pacific }

object MarketZones {
    private val byCity: List<Pair<String, Zone>> = buildList {
        fun zone(zone: Zone, cities: String) = cities.split('|').forEach { add(IdentityText.compact(it) to zone) }
        zone(Zone.Atlantic, "halifax|moncton|saint john|fredericton|charlottetown|sydney|maritimes|new brunswick|nova scotia|prince edward island|pei|cape breton|antigonish|truro|st johns|newfoundland|labrador|miramichi")
        zone(
            Zone.Eastern,
            "toronto|ottawa|montreal|quebec|gatineau|kingston|london|windsor|kitchener|barrie|peterborough|sudbury|northern ontario|thunder bay|sault ste marie|oshawa|durham|hamilton|niagara|rimouski|saguenay|sherbrooke|trois rivieres|riviere du loup|abitibi|val d or|carleton|bas saint laurent|ontario|" +
                "new york|boston|philadelphia|washington|atlanta|miami|orlando|tampa|jacksonville|charlotte|raleigh|durham nc|detroit|cleveland|pittsburgh|buffalo|baltimore|hartford|new haven|providence|albany|syracuse|rochester|richmond|norfolk|columbus|cincinnati|indianapolis|louisville|west palm beach|fort myers|greenville|charleston|savannah|toledo|dayton|harrisburg|burlington|portland maine|manchester|springfield ma|west virginia|florida|georgia|ohio|michigan|virginia|carolina|new jersey|connecticut|massachusetts|pennsylvania|maine|vermont|new hampshire|bangor",
        )
        zone(
            Zone.Central,
            "winnipeg|manitoba|regina|saskatchewan|saskatoon|brandon|chicago|dallas|houston|san antonio|austin|minneapolis|st louis|saint louis|kansas city|milwaukee|new orleans|memphis|nashville|oklahoma city|tulsa|omaha|des moines|birmingham|little rock|madison|green bay|wichita|baton rouge|jackson|mobile|huntsville|lubbock|amarillo|corpus christi|san antonio|waco|shreveport|fort worth|texas|illinois|wisconsin|iowa|minnesota|missouri|oklahoma|alabama|mississippi|louisiana|arkansas|tennessee|kansas|nebraska",
        )
        zone(Zone.Mountain, "calgary|edmonton|alberta|lethbridge|medicine hat|red deer|grande prairie|lloydminster|denver|phoenix|salt lake city|albuquerque|boise|el paso|tucson|colorado springs|billings|cheyenne|colorado|utah|arizona|new mexico|idaho|montana|wyoming")
        zone(Zone.Pacific, "vancouver|victoria|british columbia|bc|kelowna|okanagan|kamloops|prince george|vernon|los angeles|san francisco|seattle|portland|san diego|sacramento|las vegas|fresno|spokane|bakersfield|oakland|san jose|reno|palm springs|santa barbara|california|nevada|oregon|washington state|hawaii|alaska")
    }.sortedByDescending { it.first.length }

    /** The cities whose station carries a network's main feed, so a borrowed schedule is the national one. */
    val FLAGSHIPS = setOf("new york", "los angeles", "toronto", "montreal", "vancouver", "chicago", "winnipeg", "denver", "calgary", "halifax")

    /** Callsigns of the stations that carry each network's main feed, so a borrowed schedule is the national one. */
    val FLAGSHIP_CALLSIGNS: Map<String, Zone> = mapOf(
        "WABC" to Zone.Eastern, "WCBS" to Zone.Eastern, "WNBC" to Zone.Eastern, "WNYW" to Zone.Eastern, "WPIX" to Zone.Eastern, "WWOR" to Zone.Eastern,
        "WNET" to Zone.Eastern, "WXTV" to Zone.Eastern, "WNJU" to Zone.Eastern, "WFUT" to Zone.Eastern, "WPXN" to Zone.Eastern,
        "KABC" to Zone.Pacific, "KCBS" to Zone.Pacific, "KNBC" to Zone.Pacific, "KTTV" to Zone.Pacific, "KTLA" to Zone.Pacific, "KCET" to Zone.Pacific,
        "KMEX" to Zone.Pacific, "KVEA" to Zone.Pacific, "KFTR" to Zone.Pacific,
        "CFTO" to Zone.Eastern, "CIII" to Zone.Eastern, "CBLT" to Zone.Eastern, "CITY" to Zone.Eastern, "CFTM" to Zone.Eastern, "CFJP" to Zone.Eastern, "CBFT" to Zone.Eastern,
        "CIVT" to Zone.Pacific, "CHAN" to Zone.Pacific, "CBUT" to Zone.Pacific, "CKVU" to Zone.Pacific,
    )

    fun of(market: String): Zone? {
        val key = IdentityText.compact(market)
        if (key.isEmpty()) return null
        return byCity.firstOrNull { (city, _) -> key.contains(city) }?.second
    }
}
