package com.alexdiab.stadiatv.lineup

import com.alexdiab.stadiatv.lineup.Genre.Broadcast
import com.alexdiab.stadiatv.lineup.Genre.Business
import com.alexdiab.stadiatv.lineup.Genre.Culture
import com.alexdiab.stadiatv.lineup.Genre.Documentary
import com.alexdiab.stadiatv.lineup.Genre.Drama
import com.alexdiab.stadiatv.lineup.Genre.Entertainment
import com.alexdiab.stadiatv.lineup.Genre.Fast
import com.alexdiab.stadiatv.lineup.Genre.Faith
import com.alexdiab.stadiatv.lineup.Genre.Kids
import com.alexdiab.stadiatv.lineup.Genre.Lifestyle
import com.alexdiab.stadiatv.lineup.Genre.Movies
import com.alexdiab.stadiatv.lineup.Genre.Music
import com.alexdiab.stadiatv.lineup.Genre.News
import com.alexdiab.stadiatv.lineup.Genre.Premium
import com.alexdiab.stadiatv.lineup.Genre.Regional
import com.alexdiab.stadiatv.lineup.Genre.Shopping
import com.alexdiab.stadiatv.lineup.Genre.Sports
import com.alexdiab.stadiatv.lineup.Genre.Weather
import com.alexdiab.stadiatv.lineup.Tier.Essential as P0
import com.alexdiab.stadiatv.lineup.Tier.Optional as P2
import com.alexdiab.stadiatv.lineup.Tier.Recommended as P1

/**
 * The master channel lineup for Canada and the United States, grouped as in the product brief. Tiers: P0 essential,
 * P1 recommended, P2 optional. A provider's streams are matched to these entries by [CatalogMatcher]; anything it
 * carries beyond the catalogue still appears in the guide, just without catalogue metadata.
 *
 * Maintenance notes:
 *  - NBCSN returned as a 24-hour channel in November 2025; MSNBC is MS NOW since October 2025.
 *  - FanDuel Sports Network's parent-run service closed in September 2026, so the former FanDuel regional networks are
 *    not listed; clubs now use team-run, DAZN or free local broadcasts, which providers name individually.
 *  - US and Canadian channels with the same name are separate entries, because their schedules differ.
 */
object ChannelCatalog {
    /** A channel's place in the master lineup, so a guide can list essentials in the order the catalogue gives them. */
    val order: Map<String, Int> by lazy { all.withIndex().associate { (index, channel) -> channel.id to index } }

    val all: List<CatalogChannel> by lazy {
        CatalogBuilder().apply {
            broadcastNetworks()
            canadianSports()
            americanSports()
            regionalSportsNetworks()
            news()
            americanEntertainment()
            canadianEntertainment()
            documentaryAndLifestyle()
            premium()
            kidsAndFamily()
            frenchCanadian()
            spanishAndDigital()
        }.build()
    }

    // region 1. Major broadcast networks (matched to local stations by market)

    private fun CatalogBuilder.broadcastNetworks() {
        ca("CBC Television", P0, Broadcast, aliases = "cbc|cbc tv", locals = true, sports = true, short = "CBC")
        ca("CTV", P0, Broadcast, aliases = "ctv television", locals = true, sports = true)
        ca("CTV2", P0, Broadcast, aliases = "ctv 2|ctv two", locals = true, sports = true)
        ca("Global", P0, Broadcast, aliases = "global tv|global television", locals = true, sports = true)
        ca("Citytv", P0, Broadcast, aliases = "city|city tv|citytv|city television", locals = true, sports = true)
        ca("CHCH", P0, Broadcast, aliases = "chch tv|chch hamilton")
        ca("OMNI Television", P0, Broadcast, aliases = "omni|omni 1|omni tv", locals = true, short = "OMNI")
        ca("OMNI 2", P0, Broadcast, aliases = "omni two")
        ca("APTN", P0, Broadcast, aliases = "aptn east|aptn national|aboriginal peoples television network", locals = true)
        ca("ICI Radio-Canada Télé", P0, Broadcast, aliases = "ici|ici tele|ici radio canada|radio canada|radio canada tele|ici television|ici tele canada", fr = true, locals = true, sports = true, short = "ICI Télé")
        ca("TVA", P0, Broadcast, aliases = "tva network|tva quebec", fr = true, locals = true, sports = true)
        ca("Noovo", P0, Broadcast, aliases = "noovo tv|v tele", fr = true, locals = true)
        ca("Télé-Québec", P0, Broadcast, aliases = "tele quebec|telequebec", fr = true)

        us("ABC", P0, Broadcast, aliases = "abc network|abc tv|american broadcasting company", locals = true, sports = true, feeds = "CA")
        us("CBS", P0, Broadcast, aliases = "cbs network|cbs tv", locals = true, sports = true, feeds = "CA")
        us("NBC", P0, Broadcast, aliases = "nbc network|nbc tv", locals = true, sports = true, feeds = "CA")
        us("FOX", P0, Broadcast, aliases = "fox network|fox tv|fox broadcasting", locals = true, sports = true, feeds = "CA")
        us("PBS", P0, Broadcast, aliases = "pbs network|public broadcasting service", locals = true)
        us("The CW", P0, Broadcast, aliases = "cw|cw network|cw tv", locals = true, short = "CW")
        us("MyNetworkTV", P0, Broadcast, aliases = "my network tv|my network|mytv|my tv|mynet", locals = true)
        us("ION Television", P0, Broadcast, aliases = "ion|ion tv|ion network", locals = true, short = "ION")
        us("Univision", P0, Broadcast, aliases = "univision network|univision tv", es = true, locals = true)
        us("Telemundo", P0, Broadcast, aliases = "telemundo network|telemundo television network", es = true, locals = true)
        us("UniMás", P0, Broadcast, aliases = "unimas|uni mas|unimas network", es = true, locals = true)
    }

    // endregion

    // region 2. Canadian sports channels

    private fun CatalogBuilder.canadianSports() {
        ca("TSN", 1..5, P0, Sports)
        ca("Sportsnet East", P0, Sports, aliases = "sportsnet atlantic|sn east")
        ca("Sportsnet Ontario", P0, Sports, aliases = "sn ontario")
        ca("Sportsnet West", P0, Sports, aliases = "sn west")
        ca("Sportsnet Pacific", P0, Sports, aliases = "sn pacific")
        ca("Sportsnet One", P0, Sports, aliases = "sn one|sportsnet 1|rogers tv sportsnet one")
        ca("Sportsnet 360", P0, Sports, aliases = "sn 360|sportsnet360")
        ca("Sportsnet World", P1, Sports, aliases = "sportsnet world extra|sn world|sportsnet world extra 1|sportsnet world extra 2|sportsnet world extra 3")
        ca("RDS", P0, Sports, aliases = "reseau des sports|rds 1", fr = true)
        ca("RDS2", P0, Sports, aliases = "rds 2|reseau des sports 2", fr = true)
        ca("RDS Info", P1, Sports, aliases = "rds i|reseau des sports info|rdsi", fr = true)
        ca("TVA Sports", P0, Sports, aliases = "tva sports 1|tvas", fr = true)
        ca("TVA Sports 2", P0, Sports, aliases = "tvas 2|tva sports2", fr = true)
        ca("NBA TV Canada", P1, Sports, aliases = "nba tv|nba tv canada")
        ca("Fight Network", P1, Sports, aliases = "the fight network|fightnetwork|fight net")
        ca("Game+", P1, Sports, aliases = "game plus|gameplus")
        ca("OneSoccer", P1, Sports, aliases = "one soccer|onesoccer")
        ca("NFL Network Canada", P1, Sports, aliases = "nfl network|nfl net")
        ca("MLB Network Canada", P1, Sports, aliases = "mlb network")
        ca("Golf Channel Canada", P1, Sports, aliases = "golf|golf channel")
        ca("DAZN Canada", P2, Sports, aliases = "dazn")
        ca("ATN Cricket Plus", P2, Sports, aliases = "atn cricket|cricket plus")
        ca("Sportsnet+", P2, Sports, aliases = "sportsnet plus|sn plus")
        ca("TSN+", P2, Sports, aliases = "tsn plus")
    }

    // endregion

    // region 3. American sports channels

    private fun CatalogBuilder.americanSports() {
        us("ESPN", P0, Sports, aliases = "espn 1|espn network|espn hd", feeds = "CA")
        us("ESPN2", P0, Sports, aliases = "espn 2|espn two", feeds = "CA")
        us("FS1", P0, Sports, aliases = "fox sports 1|foxsports1|fox sports one|fox sports")
        us("FS2", P0, Sports, aliases = "fox sports 2|foxsports2|fox sports two")
        us("NFL Network", P0, Sports, aliases = "nfl net|nfl network us", feeds = "CA")
        us("NFL RedZone", P0, Sports, aliases = "nfl red zone|redzone|red zone")
        us("MLB Network", P0, Sports, aliases = "mlb net|the mlb channel|mlb channel", feeds = "CA")
        us("NBA TV", P0, Sports, aliases = "nba television|nbatv", feeds = "CA")
        us("NHL Network", P0, Sports, aliases = "nhl net|nhl network us", feeds = "CA")
        us("TNT", P0, Sports, Entertainment, aliases = "tnt drama|turner network television", feeds = "CA")
        us("TBS", P0, Sports, Entertainment, aliases = "tbs network|tbs superstation", feeds = "CA")
        us("truTV", P0, Sports, Entertainment, aliases = "tru tv|trutv network")
        us("CBS Sports Network", P0, Sports, aliases = "cbs sports|cbssn|cbs sports net")
        us("NBCSN", P1, Sports, aliases = "nbc sports network|nbc sports|nbcsn network")
        us("Golf Channel", P1, Sports, aliases = "golf|golf channel us|nbc golf", feeds = "CA")
        us("Tennis Channel", P1, Sports, aliases = "tennis|tennis channel us", feeds = "CA")
        us("ESPNU", P1, Sports, aliases = "espn u|espn university|espnu college sports")
        us("ESPNews", P1, Sports, aliases = "espn news|espnews hd")
        us("Big Ten Network", P1, Sports, aliases = "btn|big ten|big 10 network|big10 network")
        us("SEC Network", P1, Sports, aliases = "sec|espn sec network|sec net")
        us("ACC Network", P1, Sports, aliases = "acc|espn acc network|acc net")
        us("FanDuel TV", P2, Sports, aliases = "fanduel|fan duel tv|fan duel")
        us("FOX Soccer Plus", P2, Sports, aliases = "fox soccer|fsp|fox soccer plus network")
        us("Willow TV", P2, Sports, aliases = "willow|willow cricket|willow sports")
        us("beIN Sports", P2, Sports, aliases = "bein sports|bein sports usa|bein")
        us("beIN Sports Xtra", P2, Sports, aliases = "bein sports xtra|bein xtra", feeds = "CA")
        us("ESPN Deportes", P1, Sports, es = true, aliases = "espn deportes hd")
        us("FOX Deportes", P1, Sports, es = true, aliases = "fox deportes hd")
        us("TUDN", P1, Sports, es = true, aliases = "tudn usa")
    }

    // endregion

    // region 4. American regional sports networks (verify availability before relying on any)

    private fun CatalogBuilder.regionalSportsNetworks() {
        us("YES Network", P1, Sports, Regional, aliases = "yes|yes network new york")
        us("MSG Networks", P1, Sports, Regional, aliases = "msg|msg network|madison square garden network|msg zone")
        us("SportsNet New York", P1, Sports, Regional, aliases = "sny|sportsnet ny|sportsnet new york|sportsnet hd new york")
        us("NESN", P1, Sports, Regional, aliases = "new england sports network|nesn hd")
        us("NESN Plus", P2, Sports, Regional, aliases = "nesn+|nesnplus")
        us("NBC Sports Boston", P1, Sports, Regional, aliases = "nbcsb|nbc sports bos")
        us("NBC Sports Philadelphia", P1, Sports, Regional, aliases = "nbcsp|nbc sports philly|nbc sports phl")
        us("Monumental Sports Network", P1, Sports, Regional, aliases = "monumental|msn")
        us("MASN", P1, Sports, Regional, aliases = "mid atlantic sports network|masn 1")
        us("MASN2", P2, Sports, Regional, aliases = "masn 2")
        us("Marquee Sports Network", P1, Sports, Regional, aliases = "marquee")
        us("Chicago Sports Network", P1, Sports, Regional, aliases = "chsn|chicago sports")
        us("SportsNet Pittsburgh", P1, Sports, Regional, aliases = "at and t sportsnet pittsburgh|att sportsnet pittsburgh|root sports pittsburgh")
        us("NBC Sports Bay Area", P1, Sports, Regional, aliases = "nbc sports bay area and california|nbcs bay area")
        us("NBC Sports California", P1, Sports, Regional, aliases = "nbcs california|nbc sports california plus")
        us("Spectrum SportsNet", P1, Sports, Regional, aliases = "spectrum sportsnet lakers|spectrum sports net|spectrum sportsnet la")
        us("SportsNet LA", P1, Sports, Regional, aliases = "spectrum sportsnet la dodgers|sportsnet la dodgers|sportsnet los angeles")
        us("Altitude Sports", P1, Sports, Regional, aliases = "altitude|altitude sports and entertainment")
        us("Space City Home Network", P1, Sports, Regional, aliases = "space city|space city home")
        us("Bally Sports", P2, Sports, Regional, aliases = "bally|fanduel sports network")
    }

    // endregion

    // region 5. News, business and weather

    private fun CatalogBuilder.news() {
        ca("CBC News Network", P0, News, aliases = "cbc news|cbc newsworld|cbc news hd")
        ca("CTV News Channel", P0, News, aliases = "ctv news|ctv news network|ctv news hd")
        ca("CP24", P0, News, aliases = "cp 24")
        ca("Global News BC 1", P1, News, aliases = "global news bc1|global bc 1|global national news|global national")
        ca("BNN Bloomberg", P1, News, Business, aliases = "bnn|bloomberg canada")
        ca("CPAC", P1, News, aliases = "cable public affairs channel")
        ca("CPAC French", P2, News, aliases = "cpac fr|cpac francais", fr = true)
        ca("ICI RDI", P0, News, aliases = "rdi|ici rdi hd|radio canada information", fr = true)
        ca("LCN", P0, News, aliases = "le canal nouvelles|lcn tva|le canal nouvelles tva", fr = true)
        ca("The Weather Network", P1, Weather, aliases = "weather network|twn")
        ca("MétéoMédia", P1, Weather, aliases = "meteomedia|meteo media|meteo media montreal", fr = true)

        us("CNN", P0, News, aliases = "cnn us|cnn usa|cable news network", feeds = "CA")
        us("Fox News", P0, News, aliases = "fox news channel|fnc|foxnews", feeds = "CA")
        us("MS NOW", P0, News, aliases = "msnow|msnbc|ms now")
        us("CNBC", P1, News, Business, aliases = "cnbc us", feeds = "CA")
        us("Fox Business", P1, News, Business, aliases = "fox business network|fbn|foxbusiness")
        us("NewsNation", P0, News, aliases = "news nation|newsnation hd")
        us("Newsmax", P0, News, aliases = "newsmax tv|newsmax hd")
        us("Bloomberg Television", P1, News, Business, aliases = "bloomberg|bloomberg tv|bloomberg hd")
        us("BBC News", P0, News, aliases = "bbc world news|bbc news channel|bbc news hd", feeds = "CA")
        us("C-SPAN", P1, News, aliases = "cspan|c span|c-span 1|cspan 1")
        us("C-SPAN2", P1, News, aliases = "cspan2|c span 2|c-span 2|cspan 2")
        us("C-SPAN3", P1, News, aliases = "cspan3|c span 3|c-span 3|cspan 3")
        us("The Weather Channel", P1, Weather, aliases = "weather channel|twc|weather channel hd")
        us("AccuWeather Network", P1, Weather, aliases = "accuweather|accu weather|accu weather network")
        us("FOX Weather", P1, Weather, aliases = "fox weather channel|foxweather")
        us("ABC News Live", P1, News, aliases = "abc news|abc news now|abc news live hd")
        us("CBS News 24/7", P1, News, aliases = "cbs news 24 7|cbs news|cbs news streaming|cbs news 247")
        us("NBC News NOW", P1, News, aliases = "nbc news now|nbc news|nbcnewsnow")
        us("CNN International", P2, News, aliases = "cnn int|cnn intl", feeds = "CA")
        us("HLN", P2, News, aliases = "headline news|hln hd", feeds = "CA")
        us("CNBC World", P2, News, Business, aliases = "cnbc world hd")
        us("USA Today News", P2, News, aliases = "usa today|usa today news hd")
        us("Newsy", P2, News, aliases = "newsy hd")
        us("CNN en Español", P1, News, es = true, aliases = "cnn espanol|cnn en espanol")
    }

    // endregion

    // region 6. American entertainment channels

    private fun CatalogBuilder.americanEntertainment() {
        us("USA Network", P0, Entertainment, aliases = "usa|usa net|usanetwork")
        us("Bravo", P0, Entertainment, aliases = "bravo tv|bravo us")
        us("E!", P0, Entertainment, aliases = "e entertainment|e entertainment television|e network")
        us("A&E", P0, Entertainment, aliases = "a and e|ae|a e network|a&e network")
        us("Paramount Network", P0, Entertainment, aliases = "paramount|spike|paramount tv")
        us("MTV", P0, Entertainment, Music, aliases = "mtv network|music television|mtv us")
        us("VH1", P1, Entertainment, Music, aliases = "vh 1|vh1 hd")
        us("BET", P1, Entertainment, aliases = "black entertainment television|bet network|bet hd")
        us("Comedy Central", P0, Entertainment, aliases = "comedy central us|comedy central hd")
        us("TV Land", P1, Entertainment, aliases = "tvland|tv land hd")
        us("OWN", P1, Entertainment, aliases = "oprah winfrey network|own network|oprah winfrey")
        us("WE tv", P1, Entertainment, aliases = "we|we tv|womens entertainment|we network")
        us("CMT", P1, Entertainment, Music, aliases = "country music television|cmt hd")
        us("Game Show Network", P1, Entertainment, aliases = "gsn|game show net|gsn hd")
        us("FX", P0, Entertainment, Drama, aliases = "fx network|fx hd")
        us("FXX", P0, Entertainment, Drama, aliases = "fxx network|fxx hd")
        us("AMC", P0, Entertainment, Drama, aliases = "amc network|american movie classics|amc hd")
        us("SYFY", P0, Entertainment, Drama, aliases = "sy fy|syfy network|sci fi channel|scifi")
        us("IFC", P1, Entertainment, Drama, aliases = "independent film channel|ifc hd")
        us("SundanceTV", P1, Entertainment, Drama, aliases = "sundance tv|sundance|sundance channel")
        us("Lifetime", P0, Entertainment, Drama, aliases = "lifetime network|lifetime hd|lifetime television")
        us("Lifetime Movie Network", P1, Entertainment, Movies, aliases = "lmn|lifetime movies|lmn hd")
        us("Hallmark Channel", P0, Entertainment, Drama, aliases = "hallmark|hallmark hd|hallmark channel hd")
        us("Hallmark Mystery", P1, Entertainment, Drama, aliases = "hallmark movies and mysteries|hallmark movies mysteries|hallmark mystery channel")
        us("Hallmark Family", P1, Entertainment, Drama, aliases = "hallmark drama|hallmark family hd")
        us("Great American Family", P1, Entertainment, Drama, aliases = "great american country|gac family|gac|great american family hd")
        us("BBC America", P1, Entertainment, Drama, aliases = "bbc america hd|bbca")
        us("Reelz", P1, Entertainment, aliases = "reelz channel|reelz hd")
        us("Ovation", P1, Entertainment, Culture, aliases = "ovation tv|ovation hd")
        us("Freeform", P1, Entertainment, aliases = "abc family|freeform hd")
        us("TCM", P1, Entertainment, Movies, aliases = "turner classic movies|tcm hd", feeds = "CA")
        us("QVC", P2, Shopping, aliases = "qvc hd")
        us("HSN", P2, Shopping, aliases = "home shopping network|hsn hd")
    }

    // endregion

    // region 7. Canadian entertainment channels

    private fun CatalogBuilder.canadianEntertainment() {
        ca("Showcase", P0, Entertainment, Drama, aliases = "showcase east|showcase west|showcase canada")
        ca("W Network", P0, Entertainment, aliases = "w|w network east|w network west|w network canada")
        ca("Slice", P0, Entertainment, Lifestyle, aliases = "slice tv|slice canada")
        ca("CTV Drama Channel", P0, Entertainment, Drama, aliases = "ctv drama|bravo canada drama|ctv drama channel hd")
        ca("CTV Comedy Channel", P0, Entertainment, aliases = "ctv comedy|comedy network|the comedy network")
        ca("CTV Sci-Fi Channel", P0, Entertainment, Drama, aliases = "ctv sci fi|ctv scifi|space|space channel|ctv sci-fi")
        ca("USA Network Canada", P0, Entertainment, aliases = "usa network|usa canada|usa")
        ca("Bravo Canada", P0, Entertainment, aliases = "bravo|bravo tv canada")
        ca("FX Canada", P1, Entertainment, Drama, aliases = "fx|fx canada hd")
        ca("FXX Canada", P1, Entertainment, Drama, aliases = "fxx")
        ca("CTV Life Channel", P1, Entertainment, Lifestyle, aliases = "ctv life|life network|the life network")
        ca("E! Canada", P1, Entertainment, aliases = "e!|e|e entertainment|e canada")
        ca("Much", P1, Entertainment, Music, aliases = "muchmusic|much music|much canada")
        ca("Adult Swim Canada", P1, Entertainment, aliases = "adult swim|adultswim")
        ca("Oxygen True Crime", P1, Entertainment, Documentary, aliases = "oxygen|oxygen canada")
        ca("Lifetime Canada", P1, Entertainment, Drama, aliases = "lifetime")
        ca("DTOUR", P1, Entertainment, Lifestyle, aliases = "d tour|dtour hd")
        ca("DejaView", P2, Entertainment, aliases = "deja view|dejaview hd|dejaview canada")
        ca("MovieTime", P2, Movies, aliases = "movie time|movietime hd")
        ca("GameTV", P2, Entertainment, aliases = "game tv|gametv canada|game tv canada")
        ca("AMC Canada", P1, Entertainment, Drama, aliases = "amc")
        ca("A&E Canada", P1, Entertainment, aliases = "a and e|ae|a&e")
        ca("Paramount Network Canada", P2, Entertainment, aliases = "paramount network|paramount")
        ca("Comedy Gold", P2, Entertainment, aliases = "comedy gold canada")
        ca("ABC Spark", P2, Entertainment, aliases = "abc spark canada")
        us("MTV2", P2, Entertainment, Music, aliases = "mtv 2|mtv2 hd")
        us("Circle", P2, Entertainment, Music, aliases = "circle hd|circle country")
        ca("Makeful", P2, Lifestyle, aliases = "makeful tv")
        ca("OUTtv", P2, Entertainment, aliases = "out tv")
        ca("Vision TV", P2, Faith, aliases = "visiontv|vision")
        ca("YES TV", P2, Faith, aliases = "yestv")
        ca("Crossroads Television", P2, Faith, aliases = "crossroads|ctsn")
        ca("TLN", P2, Entertainment, aliases = "tln tv|telelatino")
        ca("TeleLatino", P2, Entertainment, aliases = "telelatino network|tele latino")
        ca("Fairchild TV", P2, Entertainment, aliases = "fairchild|fairchild 1|fairchild tv 1")
        ca("Fairchild TV 2", P2, Entertainment, aliases = "fairchild 2")
        ca("TV Asia", P2, Entertainment, aliases = "tv asia canada")
        ca("Today's Shopping Choice", P2, Shopping, aliases = "tsc|todays shopping choice|tsc shopping")
        ca("The Shopping Channel", P2, Shopping, aliases = "shopping|the shopping channel canada|shopping channel")
        ca("TVO", P1, Documentary, Culture, aliases = "tvo ontario|tvontario|tv ontario|tvo hd")
        ca("Knowledge Network", P2, Documentary, Culture, aliases = "knowledge|knowledge bc")
        ca("CHEK", P2, Broadcast, aliases = "chek tv|chek news|chek victoria")
        ca("NTV", P2, Broadcast, aliases = "ntv newfoundland|ntv st johns|cjon")
        ca("Rogers TV", P2, Broadcast, aliases = "rogers tv durham|rogers tv local|rogers television")
        ca("CTV Nature", P1, Documentary, aliases = "ctv nature channel|nature channel")
        ca("CTV Wild", P1, Documentary, aliases = "ctv wild channel|wild channel|ctv wild hd")
        ca("CTV Speed", P1, Documentary, Sports, aliases = "ctv speed channel|speed channel|speed")
    }

    // endregion

    // region 8. Documentaries, reality and lifestyle

    private fun CatalogBuilder.documentaryAndLifestyle() {
        ca("Discovery Canada", P1, Documentary, aliases = "discovery|discovery channel canada|discovery hd")
        ca("HGTV Canada", P1, Lifestyle, aliases = "hgtv|home and garden television|hgtv canada hd")
        ca("Food Network Canada", P1, Lifestyle, aliases = "food network|food|food network canada hd")
        ca("Magnolia Network Canada", P1, Lifestyle, aliases = "magnolia network|magnolia")
        ca("Investigation Discovery Canada", P1, Documentary, aliases = "investigation discovery|discovery id|id|id canada|discovery id canada")
        ca("History Canada", P1, Documentary, aliases = "history|history channel|history hd|history television")
        ca("History2 Canada", P1, Documentary, aliases = "history2|history 2|h2|history two")
        ca("National Geographic Canada", P1, Documentary, aliases = "national geographic|nat geo|natgeo|nat geo canada")
        ca("Nat Geo Wild Canada", P1, Documentary, aliases = "nat geo wild|national geographic wild|natgeo wild")
        ca("Home Network", P1, Lifestyle, aliases = "home|corus home|w network home")
        ca("Flavour Network", P1, Lifestyle, aliases = "flavour|flavor network|corus flavour")
        ca("Love Nature", P1, Documentary, aliases = "love nature canada|lovenature")
        ca("BBC Earth Canada", P1, Documentary, aliases = "bbc earth")
        ca("Cottage Life", P1, Lifestyle, aliases = "cottage life tv|cottagelife")
        ca("Crime + Investigation", P1, Documentary, aliases = "crime and investigation|crime investigation|crime plus investigation|ci")
        ca("Travel + Escape", P2, Lifestyle, aliases = "travel and escape|travel escape|travelxp")
        ca("Wild TV", P2, Documentary, aliases = "wildtv")
        ca("Animal Planet Canada", P1, Documentary, aliases = "animal planet")
        ca("TLC Canada", P1, Lifestyle, aliases = "tlc")
        ca("Science Canada", P2, Documentary, aliases = "discovery science|science channel|science")
        ca("CBC Gem", P2, Fast, aliases = "gem|cbc gem live")

        us("Discovery Channel", P1, Documentary, aliases = "discovery|discovery us|discovery hd")
        us("HGTV", P1, Lifestyle, aliases = "home and garden television|hgtv hd")
        us("Food Network", P1, Lifestyle, aliases = "food|food network hd")
        us("Magnolia Network", P1, Lifestyle, aliases = "magnolia|magnolia network hd")
        us("Investigation Discovery", P1, Documentary, aliases = "id|discovery id|investigation discovery hd|id investigation discovery")
        us("History Channel", P1, Documentary, aliases = "history|history hd|the history channel|history channel hd")
        us("History2", P1, Documentary, aliases = "history 2|h2|history two")
        us("National Geographic", P1, Documentary, aliases = "nat geo|natgeo|national geographic channel|nat geo hd", feeds = "CA")
        us("Nat Geo Wild", P1, Documentary, aliases = "national geographic wild|natgeo wild|nat geo wild hd")
        us("Animal Planet", P1, Documentary, aliases = "animal planet hd|animalplanet", feeds = "CA")
        us("TLC", P1, Lifestyle, aliases = "the learning channel|tlc hd", feeds = "CA")
        us("Science Channel", P1, Documentary, aliases = "science|discovery science|science hd")
        us("Travel Channel", P1, Lifestyle, aliases = "travel|travel hd")
        us("Cooking Channel", P1, Lifestyle, aliases = "cooking|cooking hd", feeds = "CA")
        us("Smithsonian Channel", P2, Documentary, aliases = "smithsonian|smithsonian hd")
        us("American Heroes Channel", P2, Documentary, aliases = "ahc|american heroes|military channel")
        us("Destination America", P2, Documentary, aliases = "destination america hd|dam")
        us("Oxygen", P1, Entertainment, Documentary, aliases = "oxygen true crime|oxygen hd")
        us("Discovery Familia", P2, Documentary, es = true, aliases = "discovery familia hd")
        us("Discovery en Español", P2, Documentary, es = true, aliases = "discovery espanol|discovery en espanol")
        us("History en Español", P2, Documentary, es = true, aliases = "history espanol|history en espanol|h en espanol")
        us("Motor Trend", P2, Lifestyle, aliases = "motortrend|motor trend network")
    }

    // endregion

    // region 9. Premium movies and television (shown only when the user's provider carries them)

    private fun CatalogBuilder.premium() {
        us("HBO", P1, Premium, Movies, aliases = "hbo east|hbo hd|hbo us|hbo 1")
        us("HBO2", P1, Premium, Movies, aliases = "hbo 2|hbo two")
        us("HBO Signature", P1, Premium, Movies, aliases = "hbo sig|hbo signature hd")
        us("HBO Family", P1, Premium, Movies, Kids, aliases = "hbo family hd")
        us("HBO Comedy", P1, Premium, Movies, aliases = "hbo comedy hd")
        us("HBO Zone", P1, Premium, Movies, aliases = "hbo zone hd")
        us("HBO Latino", P1, Premium, Movies, es = true, aliases = "hbo latino hd")
        us("Cinemax", P1, Premium, Movies, aliases = "cinemax east|cinemax hd|max")
        us("MoreMax", P2, Premium, Movies, aliases = "more max|moremax hd")
        us("ActionMax", P2, Premium, Movies, aliases = "action max|actionmax hd")
        us("ThrillerMax", P2, Premium, Movies, aliases = "thriller max|thrillermax hd")
        us("Paramount+ with SHOWTIME", P1, Premium, Movies, aliases = "showtime|showtime east|showtime hd|showtime 1|paramount plus with showtime|sho")
        us("Showtime 2", P1, Premium, Movies, aliases = "showtime2|sho 2|showtime two")
        us("Showtime Extreme", P1, Premium, Movies, aliases = "showtime xtreme|sho extreme|showtime extreme hd")
        us("Showtime Showcase", P1, Premium, Movies, aliases = "sho showcase|showtime showcase hd")
        us("Showtime Family Zone", P2, Premium, Movies, Kids, aliases = "showtime family|sho family zone")
        us("STARZ", P1, Premium, Movies, aliases = "starz east|starz hd|starz 1")
        us("STARZ Edge", P2, Premium, Movies, aliases = "starz edge hd")
        us("STARZ Cinema", P2, Premium, Movies, aliases = "starz cinema hd")
        us("STARZ Comedy", P2, Premium, Movies, aliases = "starz comedy hd")
        us("STARZ Kids & Family", P2, Premium, Movies, Kids, aliases = "starz kids and family|starz kids family|starz kids")
        us("STARZ Encore", P2, Premium, Movies, aliases = "encore|starz encore hd|encore east")
        us("MGM+", P1, Premium, Movies, aliases = "mgm plus|mgm|epix|mgm hd")
        us("MGM+ Hits", P2, Premium, Movies, aliases = "mgm plus hits|epix hits|mgm hits")
        us("MGM+ Marquee", P2, Premium, Movies, aliases = "mgm plus marquee|epix 2|mgm marquee")
        us("MGM+ Drive-In", P2, Premium, Movies, aliases = "mgm plus drive in|epix drive in|mgm drive in")
        us("The Movie Channel", P2, Premium, Movies, aliases = "tmc|movie channel|the movie channel hd")
        us("FLIX", P2, Premium, Movies, aliases = "flix hd|flix channel")
        us("Cinemania", P2, Movies, aliases = "cinemania hd")

        ca("Crave", 1..4, P0, Premium, Movies, aliases = "crave tv|crave|tmn|the movie network")
        ca("HBO Canada", 1..2, P1, Premium, Movies, aliases = "hbo")
        ca("STARZ Canada", 1..2, P1, Premium, Movies, aliases = "starz")
        ca("Super Channel Fuse", P2, Premium, Movies, aliases = "super channel fuse hd|superchannel fuse|fuse")
        ca("Super Channel Heart & Home", P2, Premium, Movies, aliases = "super channel heart and home|superchannel heart home|heart and home|heart home|super channel heartandhome")
        ca("Super Channel Vault", P2, Premium, Movies, aliases = "super channel vault hd|superchannel vault|vault|super channel ginx")
        ca("Super Channel Quest", P2, Premium, Movies, aliases = "quest|super channel quest hd|superchannel quest")
        ca("Hollywood Suite 70s+", P2, Premium, Movies, aliases = "hollywood suite 70s|hollywood suite 70s plus|hs 70s|hollywood 70s")
        ca("Hollywood Suite 80s & 90s", P2, Premium, Movies, aliases = "hollywood suite 80s and 90s|hollywood suite 80s|hollywood 80s 90s|hs 80s")
        ca("Hollywood Suite 2000s", P2, Premium, Movies, aliases = "hollywood suite 2000|hs 2000s|hollywood 2000s")
        ca("Hollywood Suite 2010s+", P2, Premium, Movies, aliases = "hollywood suite 2010s|hollywood suite 2010s plus|hs 2010s|hollywood 2010s")
        ca("Super Écran", 1..4, P1, Premium, Movies, aliases = "super ecran|superecran|super ecran east", fr = true)
        ca("Cinépop", P1, Premium, Movies, aliases = "cinepop|cine pop", fr = true)
        ca("Silver Screen Classics", P2, Movies, aliases = "silver screen|ssc")
    }

    // endregion

    // region 10. Kids and family

    private fun CatalogBuilder.kidsAndFamily() {
        ca("Family Jr.", P1, Kids, aliases = "family jr|family junior|family jr canada")
        ca("YTV", P1, Kids, aliases = "ytv hd|ytv east|ytv west|ytv pacific")
        ca("Treehouse", P1, Kids, aliases = "treehouse tv|treehouse east|treehouse west")
        ca("Cartoon Network Canada", P1, Kids, aliases = "cartoon network|cartoon network canada hd")
        ca("Disney Channel Canada", P1, Kids, aliases = "disney channel|disney canada")
        ca("Disney Junior Canada", P1, Kids, aliases = "disney junior|disney jr|disney jr canada")
        ca("Disney XD Canada", P1, Kids, aliases = "disney xd")
        ca("Boomerang Canada", P2, Kids, aliases = "boomerang")
        ca("TFO", P1, Kids, Culture, aliases = "tfo hd|groupe media tfo", fr = true)
        ca("Télétoon", P2, Kids, aliases = "teletoon|teletoon francais|teletoon la nuit|teletoon fr", fr = true)
        ca("Télémagino", P2, Kids, aliases = "telemagino|tele magino", fr = true)
        ca("Nickelodeon Canada", P2, Kids, aliases = "nickelodeon|nick canada")
        ca("Nick Jr. Canada", P2, Kids, aliases = "nick jr|nickjr|nick jr canada")
        ca("Teletoon Canada", P2, Kids, aliases = "teletoon english|cartoon network teletoon")

        us("Nickelodeon", P1, Kids, aliases = "nick|nickelodeon east|nickelodeon west|nick hd")
        us("Nick Jr.", P1, Kids, aliases = "nick jr|nickjr|nick jr east|nick jr west")
        us("Nicktoons", P1, Kids, aliases = "nick toons|nicktoons hd")
        us("TeenNick", P1, Kids, aliases = "teen nick|teennick hd")
        us("Disney Channel", P1, Kids, aliases = "disney|disney channel east|disney channel west", feeds = "CA")
        us("Disney Junior", P1, Kids, aliases = "disney jr|disney junior east|disney junior west", feeds = "CA")
        us("Disney XD", P1, Kids, aliases = "disney xd east|disney xd west", feeds = "CA")
        us("Cartoon Network", P1, Kids, aliases = "cartoon network east|cartoon network west|cn", feeds = "CA")
        us("Boomerang", P1, Kids, aliases = "boomerang hd|boomerang tv")
        us("PBS Kids", P1, Kids, aliases = "pbs kids hd|pbs kids channel|pbskids")
        us("BabyFirst", P1, Kids, aliases = "baby first|baby first tv|babyfirst tv|baby tv|babytv")
        us("Universal Kids", P2, Kids, aliases = "universal kids hd|sprout")
        us("Cartoonito", P2, Kids, aliases = "cartoonito hd")
    }

    // endregion

    // region 11. French-language Canadian channels

    private fun CatalogBuilder.frenchCanadian() {
        ca("TV5", P1, Culture, aliases = "tv5 quebec|tv5 quebec canada|tv5monde|tv 5 quebec|tv5 canada|tv5 monde", fr = true)
        ca("Unis TV", P1, Culture, aliases = "unis|unis tv|unistv", fr = true)
        ca("ICI ARTV", P1, Culture, aliases = "artv|ici artv hd|ici arts|artv canada", fr = true)
        ca("ICI Explora", P1, Documentary, Culture, aliases = "explora|ici explora hd", fr = true)
        ca("Séries+", P1, Entertainment, Drama, aliases = "series plus|series+|seriesplus|series", fr = true)
        ca("ADDIK", P1, Entertainment, aliases = "addik tv|addiktv|addik hd", fr = true)
        ca("Z", P1, Entertainment, aliases = "z tele|z tv|ztele|z hd", fr = true)
        ca("Prise 2", P1, Entertainment, Movies, aliases = "prise2|prise 2 hd", fr = true)
        ca("Canal Vie", P1, Lifestyle, aliases = "canalvie|canal vie hd", fr = true)
        ca("Canal D", P1, Documentary, aliases = "canald|canal d hd", fr = true)
        ca("CASA", P1, Lifestyle, aliases = "casa tv|casa hd", fr = true)
        ca("Évasion", P1, Lifestyle, aliases = "evasion|canal evasion|evasion hd", fr = true)
        ca("Zeste", P1, Lifestyle, aliases = "zeste tv|zeste hd", fr = true)
        ca("Témoin", P2, Documentary, aliases = "temoin|temoin hd", fr = true)
        ca("Investigation", P1, Documentary, aliases = "investigation hd|investigation canada", fr = true)
        ca("QUB Radio", P2, News, aliases = "qub|qub radio tv", fr = true)
        ca("Historia", P2, Documentary, aliases = "historia hd|historio|history fr", fr = true)
        ca("Planète+", P2, Documentary, aliases = "planete plus|planete+|planete", fr = true)
        ca("ELLE Fictions", P2, Entertainment, Drama, aliases = "elle fictions hd|elle fiction", fr = true)
        ca("Frissons TV", P2, Entertainment, aliases = "frissons|frissons tv hd", fr = true)
        ca("AMI-télé", P2, Entertainment, aliases = "ami tele|ami-tele|amitele|ami tv", fr = true)
        ca("MAX", P2, Entertainment, aliases = "max hd|max canada|max fr", fr = true)
        ca("Savoir média", P2, Culture, aliases = "savoir media|savoir media hd|savoirmedia", fr = true)
        ca("Euronews", P2, News, aliases = "euro news|euronews hd|euronews francais", fr = true)
        ca("France 24", P2, News, aliases = "france 24 hd|france24 francais|france 24 french", fr = true)
        ca("Assemblée nationale du Québec", P2, News, aliases = "assemblee nationale|assemblee nationale quebec|canal de l assemblee nationale|assemblee nationale du quebec", fr = true)
        ca("Legislative Assembly of Ontario", P2, News, aliases = "ontario legislative assembly|legislative assembly ontario")
        ca("EMCI TV", P2, Faith, aliases = "emci|emcitv", fr = true)
    }

    // endregion

    // region 12. Spanish-language and digital broadcast networks, plus FAST

    private fun CatalogBuilder.spanishAndDigital() {
        us("Galavisión", P1, Entertainment, es = true, aliases = "galavision|galavision hd")
        us("Estrella TV", P1, Entertainment, es = true, aliases = "estrella|estrellatv|estrella tv hd")
        us("Universo", P1, Entertainment, es = true, aliases = "nbc universo|universo hd")
        us("beIN Sports en Español", P1, Sports, es = true, aliases = "bein sports en espanol|bein sports espanol|bein espanol")
        us("Discovery Kids en Español", P2, Kids, es = true, aliases = "discovery kids espanol")
        us("ESPN3", P2, Sports, aliases = "espn 3")

        us("MeTV", P1, Entertainment, aliases = "me tv|metv network|metv hd")
        us("MeTV+", P2, Entertainment, aliases = "metv plus|me tv plus|metv+ hd")
        us("MeTV Toons", P2, Entertainment, Kids, aliases = "me tv toons|metv toons hd")
        us("Cozi TV", P1, Entertainment, aliases = "cozi|cozitv|cozi tv hd")
        us("Antenna TV", P1, Entertainment, aliases = "antenna|antennatv|antenna tv hd")
        us("Bounce TV", P1, Entertainment, aliases = "bounce|bouncetv")
        us("Grit", P1, Entertainment, aliases = "grit tv|grit network")
        us("Laff", P1, Entertainment, aliases = "laff tv|laff network")
        us("H&I", P1, Entertainment, aliases = "heroes and icons|heroes icons|h and i|hi tv")
        us("Start TV", P2, Entertainment, aliases = "starttv|start tv hd")
        us("Court TV", P1, Entertainment, aliases = "courttv|court tv hd")
        us("ION Mystery", P2, Entertainment, aliases = "ion mystery hd|ionmystery")
        us("Comet", P2, Entertainment, aliases = "comet tv|comettv")
        us("Charge!", P2, Entertainment, aliases = "charge|charge tv")
        us("Movies!", P2, Movies, aliases = "movies|movies tv|movie tv")
        us("Catchy Comedy", P2, Entertainment, aliases = "catchy|catchy comedy tv")
        us("getTV", P2, Entertainment, aliases = "get tv|gettv hd")
        us("Pluto TV", P2, Fast, aliases = "pluto|plutotv")
        us("Tubi", P2, Fast, aliases = "tubi tv|tubitv")
        us("The Roku Channel", P2, Fast, aliases = "roku channel|roku|roku tv")
    }

    // endregion
}
