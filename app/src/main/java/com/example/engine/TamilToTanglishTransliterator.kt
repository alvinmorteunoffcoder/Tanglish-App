package com.example.engine

import android.util.Log

object TamilToTanglishTransliterator {

    private val mapIndependentVowel = mapOf(
        'அ' to "a", 'ஆ' to "aa", 'இ' to "i", 'ஈ' to "ee",
        'உ' to "u", 'ஊ' to "oo", 'எ' to "e", 'ஏ' to "ae",
        'ஐ' to "ai", 'ஒ' to "o", 'ஓ' to "oe", 'ஔ' to "au",
        'ஃ' to "k"
    )

    private val mapVowelSign = mapOf(
        'ா' to "aa", 'ி' to "i", 'ீ' to "ee", 'ு' to "u",
        'ூ' to "oo", 'ெ' to "e", 'ே' to "ae", 'ை' to "ai",
        'ொ' to "o", 'ோ' to "oe", 'ௌ' to "au"
    )

    private val mapBaseConsonant = mapOf(
        'க' to "k", 'ங' to "ng", 'ச' to "ch", 'ஞ' to "nj",
        'ட' to "t", 'ண' to "n", 'த' to "th", 'ந' to "n",
        'ப' to "p", 'ம' to "m", 'ய' to "y", 'ர' to "r",
        'ல' to "l", 'வ' to "v", 'ழ' to "zh", 'ள' to "l",
        'ற' to "r", 'ன' to "n", 'ஜ' to "j", 'ஷ' to "sh",
        'ஸ' to "s", 'ஹ' to "h"
    )

    private val englishPreservationMap = mapOf(
        "poen" to "phone",
        "pone" to "phone",
        "peon" to "phone",
        "kaal" to "call",
        "kaals" to "calls",
        "ofis" to "office",
        "aapis" to "office",
        "escool" to "school",
        "skool" to "school",
        "daaktar" to "doctor",
        "haaspital" to "hospital",
        "kaar" to "car",
        "paik" to "bike",
        "tiivi" to "TV",
        "vaatsap" to "WhatsApp",
        "maesaej" to "message",
        "maesaeg" to "message",
        "chaat" to "chat",
        "kaapi" to "coffee",
        "kapi" to "coffee",
        "laaptap" to "laptop",
        "paas" to "bus",
        "pas" to "bus",
        "baji" to "bus",
        "claas" to "class",
        "peecchu" to "beach",
        "peekku" to "beach",
        "peeckku" to "beach",
        "peeccu" to "beach",
        "peecku" to "beach",
        "peech" to "beach",
        "peechu" to "beach",
        "pichu" to "beach",
        "picchu" to "beach",
        "pikku" to "beach"
    )

    fun isTamilWord(word: String): Boolean {
        return word.any { it in '\u0b80'..'\u0bff' }
    }

    private class TamilSyllable(
        val type: SyllableType,
        val base: Char,
        val sign: Char? = null
    )

    private enum class SyllableType {
        VOWEL, CONSONANT, OTHER
    }

    /**
     * Translates a single Tamil script word into natural Tanglish matching day-to-day phonetic spelling
     */
    fun transliterate(word: String): String {
        // Clean symbols around the word
        val prefixSymbols = StringBuilder()
        val suffixSymbols = StringBuilder()
        var startIdx = 0
        while (startIdx < word.length && !isTamilWord(word[startIdx].toString()) && !word[startIdx].isLetterOrDigit()) {
            prefixSymbols.append(word[startIdx])
            startIdx++
        }
        var endIdx = word.length - 1
        while (endIdx >= startIdx && !isTamilWord(word[endIdx].toString()) && !word[endIdx].isLetterOrDigit()) {
            suffixSymbols.insert(0, word[endIdx])
            endIdx--
        }

        if (startIdx > endIdx) return word // Word is all symbols
        val coreWord = word.substring(startIdx, endIdx + 1)

        if (!isTamilWord(coreWord)) return word

        // Parse into syllables
        val syllables = mutableListOf<TamilSyllable>()
        var idx = 0
        while (idx < coreWord.length) {
            val c = coreWord[idx]
            if (c in '\u0b85'..'\u0b94' || c == 'ஃ') { // Independent vowel
                syllables.add(TamilSyllable(SyllableType.VOWEL, c))
                idx++
            } else if (c in '\u0b95'..'\u0bb9') { // Consonant
                var sign: Char? = null
                if (idx + 1 < coreWord.length && (coreWord[idx + 1] in '\u0bbe'..'\u0bcd')) {
                    sign = coreWord[idx + 1]
                    idx += 2
                } else {
                    idx++
                }
                syllables.add(TamilSyllable(SyllableType.CONSONANT, c, sign))
            } else {
                syllables.add(TamilSyllable(SyllableType.OTHER, c))
                idx++
            }
        }

        val result = StringBuilder()
        for (j in syllables.indices) {
            val syl = syllables[j]
            if (syl.type == SyllableType.OTHER) {
                result.append(syl.base)
                continue
            }

            if (syl.type == SyllableType.VOWEL) {
                result.append(mapIndependentVowel[syl.base] ?: "")
                continue
            }

            // Consonant processing with contextual day-to-day rules
            val base = syl.base
            val sign = syl.sign

            val consonantSound = when (base) {
                'க' -> {
                    val prev = if (j > 0) syllables[j - 1] else null
                    when {
                        j == 0 -> "k"
                        prev != null && prev.base == 'க' && prev.sign == '்' -> "k"
                        prev != null && prev.base == 'ங' && prev.sign == '்' -> "g"
                        else -> "g" // intervocalic or middle soft "g"
                    }
                }
                'ச' -> {
                    val prev = if (j > 0) syllables[j - 1] else null
                    when {
                        j == 0 -> "s"
                        prev != null && prev.base == 'ச' && prev.sign == '்' -> "ch"
                        prev != null && prev.base == 'ஞ' && prev.sign == '்' -> "j" // nja
                        else -> "s"
                    }
                }
                'ட' -> {
                    val prev = if (j > 0) syllables[j - 1] else null
                    when {
                        j == 0 -> "t"
                        prev != null && prev.base == 'ட' && prev.sign == '்' -> "t" // tta
                        prev != null && prev.base == 'ண' && prev.sign == '்' -> "d" // nda
                        else -> "d" // intervocalic
                    }
                }
                'த' -> "th"
                'ப' -> {
                    val prev = if (j > 0) syllables[j - 1] else null
                    when {
                        j == 0 -> "p"
                        prev != null && prev.base == 'ப' && prev.sign == '்' -> "p" // ppa
                        prev != null && prev.base == 'ம' && prev.sign == '்' -> "b" // mba (romba)
                        else -> "p"
                    }
                }
                'ற' -> {
                    val prev = if (j > 0) syllables[j - 1] else null
                    when {
                        prev != null && prev.base == 'ற' && prev.sign == '்' -> "tr" // tra/tri/tru (kaatril, vetri)
                        else -> "r"
                    }
                }
                'ள' -> {
                    val prev = if (j > 0) syllables[j - 1] else null
                    val next = if (j + 1 < syllables.size) syllables[j + 1] else null
                    if ((prev != null && prev.base == 'ள' && prev.sign == '்') || (next != null && next.base == 'ள' && next.sign == '்')) {
                        "ll"
                    } else {
                        "l"
                    }
                }
                'ண' -> {
                    val prev = if (j > 0) syllables[j - 1] else null
                    val next = if (j + 1 < syllables.size) syllables[j + 1] else null
                    if ((prev != null && prev.base == 'ண' && prev.sign == '்') || (next != null && next.base == 'ண' && next.sign == '்')) {
                        "nn"
                    } else {
                        "n"
                    }
                }
                else -> mapBaseConsonant[base] ?: ""
            }

            // Determine vowel sound for this consonant syllable
            val vowelSound = if (sign != null) {
                if (sign == '்') {
                    "" // explicitly silent with pull
                } else {
                    mapVowelSign[sign] ?: ""
                }
            } else {
                "a" // inherent "a" sound
            }

            if (sign == '்') {
                // Return silent consonant sound variant
                val silentConsonantSound = when (base) {
                    'க' -> "k"
                    'ச' -> "c"
                    'ட' -> "t"
                    'த' -> "th"
                    'ப' -> "p"
                    'ற' -> "t"
                    'ங' -> "ng"
                    'ஞ' -> "n"
                    'ந' -> "n"
                    'ம' -> "m"
                    'ண' -> "n"
                    'ள' -> "l"
                    'ழ' -> "zh"
                    else -> mapBaseConsonant[base] ?: ""
                }
                result.append(silentConsonantSound)
            } else {
                result.append(consonantSound).append(vowelSound)
            }
        }

        val rawTransliterated = prefixSymbols.toString() + result.toString() + suffixSymbols.toString()
        val coreResult = result.toString()
        val lowercaseCore = coreResult.lowercase()
        var preservedCore = englishPreservationMap[lowercaseCore]
        
        if (preservedCore == null) {
            val phoneticMappings = listOf(
                // Tech, Mobile & Social Apps
                "teligiraam" to "telegram", "teligram" to "telegram", "deligiram" to "telegram",
                "vaatsap" to "WhatsApp", "vaatsappu" to "WhatsApp", "vatsap" to "WhatsApp", "vaatsapu" to "WhatsApp",
                "paespuk" to "Facebook", "baespuk" to "Facebook", "facebook" to "Facebook",
                "yoadiyoop" to "YouTube", "yutiyup" to "YouTube", "yoottiyoop" to "YouTube", "yootiyup" to "YouTube", "yudiyub" to "YouTube",
                "instaagiraam" to "Instagram", "instagram" to "Instagram", "indagiram" to "Instagram", "insta" to "Instagram",
                "googul" to "Google", "koogul" to "Google", "koogil" to "Google", "googil" to "Google",
                "pilaesttoar" to "Play Store", "plaestore" to "Play Store", "playstore" to "Play Store",
                "aapsttoar" to "App Store", "appstore" to "App Store",
                "aap" to "app", "aappu" to "app", "aapu" to "app",
                "vebsite" to "website", "vebsait" to "website",
                "tavunloatu" to "download", "davunloatu" to "download", "tavunloat" to "download", "daunlot" to "download", "daunloatu" to "download",
                "aploatu" to "upload", "aploat" to "upload", "aploattu" to "upload",
                "instaal" to "install", "instal" to "install",
                "apttaet" to "update", "apdeit" to "update", "apdaet" to "update", "apdeyttu" to "update",
                "sarch" to "search", "saarch" to "search",
                "saat" to "chat", "chaat" to "chat",
                "mesaej" to "message", "maesaej" to "message", "maesaeg" to "message", "meseg" to "message", "mesag" to "message", "maeseg" to "message", "message" to "message",
                "kaal" to "call", "caal" to "call", "call" to "call",
                "poen" to "phone", "pone" to "phone", "peon" to "phone", "foan" to "phone", "phone" to "phone",
                "mopail" to "mobile", "mobile" to "mobile", "mobe" to "mobile", "moba" to "mobile",
                "laepttaap" to "laptop", "laptap" to "laptop", "laaptap" to "laptop", "laptop" to "laptop",
                "kanpootttar" to "computer", "kanpoot" to "computer", "kanput" to "computer", "compoot" to "computer", "computer" to "computer",
                "sikirtoen" to "screen", "skireen" to "screen", "sirtoen" to "screen", "sekreen" to "screen", "screen" to "screen",
                "tispiplae" to "display", "tisplay" to "display", "displae" to "display", "display" to "display",
                "keepoarttu" to "keyboard", "keyboard" to "keyboard",
                "saarjar" to "charger", "charjar" to "charger", "charger" to "charger",
                "paettari" to "battery", "batteri" to "battery", "battery" to "battery",
                "veetiyo" to "video", "veeteyo" to "video", "video" to "video",
                "aatiyo" to "audio", "audio" to "audio",
                "poettoo" to "photo", "poto" to "photo", "photo" to "photo",
                "kaemara" to "camera", "camera" to "camera",
                "pail" to "file", "file" to "file",
                "taetttaa" to "data", "taettaa" to "data", "data" to "data",
                "indarnt" to "internet", "indarnet" to "internet", "internet" to "internet",
                "vaipai" to "WiFi", "wifi" to "WiFi",
                "netvork" to "network", "netvark" to "network", "network" to "network",
                "sim" to "SIM", "simu" to "SIM",
                "nambar" to "number", "nampar" to "number", "number" to "number",
                "kuroop" to "group", "kurup" to "group", "group" to "group",
                "sttaettas" to "status", "staettas" to "status", "status" to "status",
                "akkavund" to "account", "akavund" to "account", "account" to "account",
                "paasvaert" to "password", "password" to "password",
                "laakin" to "login", "login" to "login",
                "laakavut" to "logout", "logout" to "logout",
                "eemayil" to "email", "email" to "email",
                "aanlain" to "online", "online" to "online",
                "aaplain" to "offline", "offline" to "offline",
                "kaem" to "game", "game" to "game",
                "sarvar" to "server", "server" to "server",
                "erar" to "error", "error" to "error",
                "piks" to "fix", "fiks" to "fix", "fix" to "fix",
                "pak" to "bug", "bug" to "bug",
                "koatu" to "code", "code" to "code",
                "lingk" to "link", "link" to "link",
                "pitiaep" to "PDF", "pdf" to "PDF",
                "tekst" to "text", "text" to "text",
                "vaays" to "voice", "voice" to "voice",
                "maik" to "mic", "mic" to "mic",
                "sepeekkar" to "speaker", "speaker" to "speaker",
                "rekkaart" to "record", "record" to "record",
                "tevalappar" to "developer", "developer" to "developer",
                "maat" to "mod", "mod" to "mod", "cheat" to "cheat",

                // Job, Money & Banking
                "aapees" to "office", "ofis" to "office", "aapis" to "office", "aabis" to "office", "oapis" to "office", "offis" to "office", "office" to "office",
                "ork" to "work", "vaark" to "work", "vark" to "work", "work" to "work",
                "jaap" to "job", "job" to "job",
                "tiyootti" to "duty", "duty" to "duty",
                "paas" to "boss", "boss" to "boss",
                "stttaap" to "staff", "staff" to "staff",
                "saelari" to "salary", "salary" to "salary",
                "mani" to "money", "money" to "money",
                "kaesh" to "cash", "cash" to "cash",
                "jipae" to "GPay", "gpay" to "GPay",
                "paetiam" to "Paytm", "paytm" to "Paytm",
                "paengk" to "bank", "bank" to "bank",
                "paemand" to "payment", "payment" to "payment",

                // Daily Expressions
                "thaengks" to "thanks", "thanks" to "thanks",
                "thaengkyoo" to "thank you", "thankyou" to "thank you",
                "saari" to "sorry", "sorry" to "sorry",
                "pilees" to "please", "pleas" to "please", "please" to "please",
                "velkam" to "welcome", "welcome" to "welcome",
                "kiraats" to "congrats", "congrats" to "congrats",
                "haay" to "hi", "hi" to "hi",
                "haloo" to "hello", "hello" to "hello",
                "paay" to "bye", "bye" to "bye",
                "oakae" to "ok", "okay" to "ok", "ok" to "ok",
                "es" to "yes", "yes" to "yes",
                "noa" to "no", "no" to "no",
                "riyali" to "really", "really" to "really",
                "seeriyas" to "seriously", "seriously" to "seriously",
                "veyit" to "wait", "vait" to "wait", "wait" to "wait",
                "sttaap" to "stop", "staap" to "stop", "stop" to "stop",
                "sttaart" to "start", "staart" to "start", "start" to "start",
                "koa" to "go", "go" to "go",
                "kam" to "come", "come" to "come",
                "paek" to "back", "back" to "back",
                "karakt" to "correct", "correct" to "correct",
                "raang" to "wrong", "wrong" to "wrong",
                "eesi" to "easy", "easy" to "easy",
                "haart" to "hard", "hard" to "hard",
                "simpil" to "simple", "simple" to "simple",
                "pesatt" to "best", "best" to "best",
                "kut" to "good", "good" to "good",
                "paet" to "bad", "bad" to "bad",
                "nais" to "nice", "nice" to "nice",
                "soopper" to "super", "super" to "super",
                "aasat" to "awesome", "awesome" to "awesome",
                "kool" to "cool", "cool" to "cool",
                "kiraet" to "great", "great" to "great",
                "haeppi" to "happy", "happy" to "happy",
                "saet" to "sad", "sad" to "sad",
                "aangiri" to "angry", "angry" to "angry",
                "tayart" to "tired", "tired" to "tired",
                "pisi" to "busy", "busy" to "busy",
                "piree" to "free", "free" to "free",
                "reti" to "ready", "ready" to "ready",
                "laet" to "late", "late" to "late",
                "taim" to "time", "daim" to "time", "time" to "time",
                "avar" to "hour", "hour" to "hour",
                "minit" to "minute", "minute" to "minute",
                "sekand" to "second", "second" to "second",
                "nav" to "now", "now" to "now",
                "then" to "then",
                "aaptar" to "after", "after" to "after",
                "pipoar" to "before", "before" to "before",

                // Education & Work Environment
                "sekool" to "school", "skool" to "school", "ischool" to "school", "iskoo" to "school", "school" to "school",
                "kaalaej" to "college", "college" to "college",
                "kilaas" to "class", "claas" to "class", "klaas" to "class", "clahs" to "class", "klahs" to "class", "class" to "class",
                "koars" to "course", "course" to "course",
                "eksaam" to "exam", "exam" to "exam",
                "tesatt" to "test", "test" to "test",
                "risalt" to "result", "result" to "result",
                "pees" to "fees", "fees" to "fees",
                "puraajekt" to "project", "project" to "project",
                "meetting" to "meeting", "meeting" to "meeting",
                "kilaiyand" to "client", "client" to "client",
                "kastamar" to "customer", "customer" to "customer",
                "sappoart" to "support", "support" to "support",
                "koleek" to "colleague", "colleague" to "colleague",
                "teem" to "team", "team" to "team",
                "indarviyoo" to "interview", "interview" to "interview",

                // Travel & Vehicles
                "tikket" to "ticket", "tikk" to "ticket", "ticket" to "ticket",
                "pas" to "bus", "bas" to "bus", "baji" to "bus", "bus" to "bus",
                "tirein" to "train", "trein" to "train", "terain" to "train", "train" to "train",
                "pilait" to "flight", "plait" to "flight", "pilaet" to "flight", "flight" to "flight",
                "pilaen" to "plane", "plane" to "plane",
                "aerpoart" to "airport", "airport" to "airport",
                "staesan" to "station", "staeshan" to "station", "estaes" to "station", "staes" to "station", "stesh" to "station", "shn" to "station", "station" to "station",
                "mettaro" to "metro", "metro" to "metro",
                "aattoo" to "auto", "auto" to "auto",
                "taaksi" to "taxi", "taxi" to "taxi",
                "kaep" to "cab", "cab" to "cab",
                "kaar" to "car", "car" to "car",
                "paik" to "bike", "bike" to "bike",
                "saikkil" to "cycle", "saikl" to "cycle", "saikli" to "cycle", "cycle" to "cycle",
                "roatu" to "road", "road" to "road", "roat" to "road",
                "tiraapik" to "traffic", "traffic" to "traffic",
                "aksidand" to "accident", "aaksidand" to "accident", "aaksident" to "accident", "aksident" to "accident", "accident" to "accident",
                "polees" to "police", "polis" to "police", "police" to "police",
                "koart" to "court", "court" to "court",
                "kaes" to "case", "case" to "case",

                // Relations
                "pirathar" to "brother", "brother" to "brother",
                "sistar" to "sister", "sister" to "sister",
                "mathar" to "mother", "mother" to "mother",
                "paathar" to "father", "father" to "father",
                "pirand" to "friend", "prend" to "friend", "perand" to "friend", "friend" to "friend",
                "paemili" to "family", "family" to "family",
                "kasin" to "cousin", "cousin" to "cousin",
                "angkil" to "uncle", "uncle" to "uncle",
                "aandti" to "aunty", "aunty" to "aunty",
                "taati" to "daddy", "daddy" to "daddy",
                "paepi" to "baby", "baby" to "baby",
                "kit" to "kid", "kid" to "kid",
                "paay" to "boy", "boy" to "boy",
                "kaerl" to "girl", "girl" to "girl",
                "maen" to "man", "man" to "man",
                "vuman" to "woman", "woman" to "woman",
                "kaay" to "guy", "guy" to "guy",
                "lav" to "love", "luv" to "love", "love" to "love",
                "kiras" to "crush", "crush" to "crush",
                "kappil" to "couple", "couple" to "couple",

                // Shopping, Venues & Places
                "haaspittal" to "hospital", "aspat" to "hospital", "haaspidal" to "hospital", "haaspital" to "hospital", "haaspat" to "hospital", "haspat" to "hospital", "hospital" to "hospital",
                "taaktar" to "doctor", "daaktar" to "doctor", "daactar" to "doctor", "daktar" to "doctor", "doctor" to "doctor",
                "aanbulans" to "ambulance", "ambulance" to "ambulance",
                "metisin" to "medicine", "medicine" to "medicine",
                "taepplatt" to "tablet", "tablet" to "tablet",
                "kilinik" to "clinic", "clinic" to "clinic",
                "paesand" to "patient", "patient" to "patient",
                "nars" to "nurse", "nurse" to "nurse",
                "peech" to "beach", "picchu" to "beach", "peecchu" to "beach", "peekku" to "beach", "peeckku" to "beach", "peeccu" to "beach", "peecku" to "beach", "peechu" to "beach", "pichu" to "beach", "pikku" to "beach", "beach" to "beach",
                "paark" to "park", "park" to "park",
                "maal" to "mall", "mall" to "mall",
                "saap" to "shop", "shaap" to "shop", "shop" to "shop",
                "hoattal" to "hotel", "oattal" to "hotel", "ottal" to "hotel", "aattal" to "hotel", "hotel" to "hotel",
                "tiyaettar" to "theatre", "theatre" to "theatre",
                "sinima" to "cinema", "cinema" to "cinema",
                "moovi" to "movie", "movie" to "movie",
                "saang" to "song", "song" to "song",
                "seet" to "seat", "seat" to "seat",
                "pukking" to "booking", "booking" to "booking",

                // Food, Drink & Daily Utility
                "tee" to "tea", "tea" to "tea",
                "kaapi" to "coffee", "kapi" to "coffee", "coffee" to "coffee",
                "vaattar" to "water", "water" to "water",
                "put" to "food", "food" to "food",
                "rais" to "rice", "rice" to "rice",
                "joos" to "juice", "juice" to "juice",
                "milk" to "milk",
                "saaklaett" to "chocolate", "chocolate" to "chocolate",
                "sinaak" to "snack", "snack" to "snack",
                "pil" to "bill", "bill" to "bill",
                "tiras" to "dress", "dress" to "dress",
                "sart" to "shirt", "shirt" to "shirt",
                "paand" to "pant", "pant" to "pant",
                "soos" to "shoes", "shoes" to "shoes",
                "paek" to "bag", "bag" to "bag",
                "vaatch" to "watch", "watch" to "watch",
                "koalt" to "gold", "gold" to "gold",
                "kipt" to "gift", "gift" to "gift",
                "sarparais" to "surprise", "surprise" to "surprise",
                "atras" to "address", "address" to "address",
                "lokaesan" to "location", "lokeshan" to "location", "location" to "location",
                "maep" to "map", "map" to "map",
                "sttreet" to "street", "street" to "street",
                "havus" to "house", "house" to "house",
                "pilaat" to "flat", "flat" to "flat",
                "pilding" to "building", "building" to "building",
                "room" to "room",
                "pet" to "bed", "bed" to "bed",
                "paen" to "fan", "fan" to "fan",
                "lait" to "light", "light" to "light",
                "tiivi" to "TV",
                "pirij" to "fridge", "fridge" to "fridge",
                "eesi" to "AC"
            ).sortedByDescending { it.first.length }

            val match = phoneticMappings.firstOrNull { lowercaseCore.startsWith(it.first) }
            if (match != null) {
                val prefix = match.first
                val engWord = match.second
                val suffix = lowercaseCore.substring(prefix.length)
                val tamilCaseSuffixes = listOf("la", "ku", "um", "oda", "yoda", "ukku", "ilirundhu", "ai", "ga", "nga", "me", "a", "pa")
                preservedCore = when {
                    suffix.isEmpty() -> engWord
                    suffix in tamilCaseSuffixes -> "$engWord-$suffix"
                    suffix == "u" || suffix == "e" || suffix == "i" -> engWord
                    else -> "$engWord$suffix"
                }
            }
        }

        return if (preservedCore != null) {
            val finalCore = if (coreResult.isNotEmpty() && coreResult[0].isUpperCase()) {
                preservedCore.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
            } else {
                preservedCore
            }
            prefixSymbols.toString() + finalCore + suffixSymbols.toString()
        } else {
            rawTransliterated
        }
    }

    /**
     * Transliterates sentences or multiple words, leaving English tokens intact
     */
    fun transliterateSentence(sentence: String, userDictionary: Map<String, String>): String {
        val tokens = sentence.split("\\s+".toRegex())
        val mappedTokens = tokens.map { token ->
            val cleanTokenForLookup = token.filter { it in '\u0b80'..'\u0bff' || it.isLetter() }
            val customRepl = userDictionary[cleanTokenForLookup] ?: userDictionary[token]
            if (customRepl != null) {
                token.replace(cleanTokenForLookup, customRepl)
            } else {
                transliterate(token)
            }
        }
        return mappedTokens.joinToString(" ")
    }
}
