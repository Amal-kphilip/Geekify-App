package com.geekify.android.domain.listening

import com.geekify.android.data.local.ListeningSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MIN_MEANINGFUL_LISTEN_MS = 10_000L
private const val STREAK_DAY_LISTEN_MS = 10 * 60_000L
private const val DAY_MS = 24 * 60 * 60_000L

/**
 * Estimates a track's language without a language field in the source metadata.
 * Distinct scripts and explicit title tags take priority; romanized titles are only
 * assigned when the title contains high-confidence language clues.
 */
object ListeningLanguageClassifier {
    private data class ScriptRule(val label: String, val range: CharRange)

    private val scriptRules = listOf(
        ScriptRule("Malayalam", '\u0D00'..'\u0D7F'),
        ScriptRule("Tamil", '\u0B80'..'\u0BFF'),
        ScriptRule("Telugu", '\u0C00'..'\u0C7F'),
        ScriptRule("Kannada", '\u0C80'..'\u0CFF'),
        ScriptRule("Bengali", '\u0980'..'\u09FF'),
        ScriptRule("Punjabi (Gurmukhi script)", '\u0A00'..'\u0A7F'),
        ScriptRule("Gujarati", '\u0A80'..'\u0AFF'),
        ScriptRule("Odia", '\u0B00'..'\u0B7F'),
        ScriptRule("Devanagari (Hindi/Marathi etc.)", '\u0900'..'\u097F'),
        ScriptRule("Arabic script (Urdu/Arabic etc.)", '\u0600'..'\u06FF'),
        ScriptRule("Korean", '\uAC00'..'\uD7AF'),
        ScriptRule("Japanese", '\u3040'..'\u30FF'),
        ScriptRule("Chinese/Han script", '\u4E00'..'\u9FFF'),
        ScriptRule("Cyrillic (Russian etc.)", '\u0400'..'\u04FF'),
        ScriptRule("Greek", '\u0370'..'\u03FF')
    )

    private data class RomanizedRule(
        val language: String,
        val anchors: Set<String> = emptySet(),
        val strongWords: Set<String> = emptySet(),
        val supportingWords: Set<String> = emptySet(),
        val anchorPhrases: Set<String> = emptySet(),
        val morphSuffixes: Set<String> = emptySet(),
        val morphSubstrings: Set<String> = emptySet()
    )

    /*
     * Layer 3: Expanded Romanized vocabulary + phonetic/morphological rules.
     * Anchor terms/phrases are distinctive enough to classify 1-2 word titles on their own;
     * morphological suffixes/roots and broader words combine to reach the threshold.
     */
    private val romanizedRules = listOf(
        RomanizedRule(
            "Malayalam",
            anchors = setOf(
                "mazha", "pavizha", "aaromale", "chembakame", "khalbile", "mizhiyil", "pulari", "nilave",
                "malare", "uyiril", "thodum", "kinavu", "kanavu", "pranayam", "sneham", "ormakal",
                "azhake", "nenjil", "koode", "neela", "parayathe", "ariyathe", "kuttanadan", "chempoove",
                "alliyambal", "harimuraleeravam", "entammede", "jimikki", "kammal", "kalakkatha",
                "darshana", "puthiyoru", "aavesham", "illuminati", "armadham", "jaada", "kuthanthram",
                "nebulakal", "cherathukal", "kumbalangi", "premam", "manjummel", "parava", "uyare",
                "bheeshma", "romancham", "aadujeevitham", "periyone", "maharani", "pookkal", "vennilave",
                "minungum", "mizhikal", "hridayam", "onakka", "munthiri", "kizhakku", "pookkale",
                "enthu", "parayamo", "kanmani", "mizhi", "ormayil", "premalu", "mini", "lokam"
            ),
            strongWords = setOf(
                "njan", "ente", "ninte", "mazhayil", "manass", "engane", "evide", "cheyyum", "raave",
                "ninakku", "kiliye", "ullil", "thoomani", "poove", "raavu", "pularumo", "mizhikalil",
                "kanavukal", "pranayame", "snehame", "nenjile", "parayu", "ariyum", "kaattu", "melle",
                "innale", "innu", "naale", "karale", "ponne", "muthe", "vave", "kunjade", "thottu",
                "koodu", "theeram", "doore", "chaare", "raavil"
            ),
            supportingWords = setOf(
                "alle", "aane", "aanu", "nammal", "ningal", "chechi", "chetta", "pinne", "ivide",
                "enthe", "oru", "njaan", "ini", "ippo", "appo"
            ),
            anchorPhrases = setOf(
                "pavizha mazha", "ente nenjil", "malayalam song", "neela nilave", "onakka munthiri",
                "jimikki kammal", "kuttanadan punchayile"
            ),
            morphSuffixes = setOf("kkal", "ukal", "athe", "azha", "uzha", "inte", "allo", "umbol"),
            morphSubstrings = setOf("khal", "mizh", "pular", "kanav", "pranay", "sneh", "ullil", "nenj", "koode", "paray", "thoom", "neel", "poov", "raav", "mazh")
        ),
        RomanizedRule(
            "Tamil",
            anchors = setOf(
                "kuthu", "kuththu", "thalaiva", "thalapathy", "tamizh", "ennadi", "azhagiya", "nenjukkul",
                "peidhidum", "vaazhkai", "uyire", "kadhale", "kaadhale", "thendral", "minnale", "vaazhga",
                "paravai", "mutham", "hukum", "vaathi", "kaavaalaa", "kolaveri", "vikram", "pathala",
                "ponniyin", "selvan", "mallipoo", "rakita", "chellamma", "mayakkama", "maruvaarthai",
                "kurumugil", "thaensudare", "achacho", "katchi", "verithanam", "aaluma", "doluma",
                "sodakku", "dippam", "dappam", "usure", "pogudhey", "munbe", "nagaram", "kannana",
                "kanne", "ennai", "konjam", "maattram", "senjitaley", "rowdy", "kutty", "jailer"
            ),
            strongWords = setOf(
                "enakku", "unakku", "kadhal", "kaadhal", "vizhiyil", "vaadi", "sollu", "machi", "machan",
                "ponnu", "thamizha", "poda", "podi", "thirudi", "anbe", "nenje", "vizhi", "azhagu",
                "azhage", "kanna", "vaanam", "megam", "mazhai", "iravu", "nilavu", "urave", "uyir",
                "kaatru", "idhayam", "nenjam"
            ),
            supportingWords = setOf("naan", "nee", "enna", "paattu", "amma", "appa", "indha", "andha", "idhu", "adhu", "innum"),
            anchorPhrases = setOf("arabic kuthu", "why this kolaveri", "tamil song", "vaathi coming", "katchi sera", "kannana kanne"),
            morphSuffixes = setOf("gal", "inai", "aiye", "aal", "ukkul", "adhey", "athey"),
            morphSubstrings = setOf("uyir", "kadh", "kaadh", "vizh", "kanman", "thendr", "vaazh", "azhag", "mutham")
        ),
        RomanizedRule(
            "Telugu",
            anchors = setOf(
                "nuvvu", "nuvve", "naatu", "vellipomaakey", "choopultho", "baagundi", "kannulalo",
                "srivalli", "buttabomma", "samajavaragamana", "ramuloo", "ramulaa", "antava", "saami",
                "inkem", "kaavaale", "vachinde", "priyathama", "manohari", "kumkumala", "kurchi",
                "madathapetti", "chuttamalle", "devara", "pushpa", "pillaga", "ammayi", "abbayi",
                "chandamama", "preminchana", "mindblock", "ulavacharu", "bujji", "peniviti"
            ),
            strongWords = setOf(
                "neeku", "naaku", "ninnu", "vachave", "andham", "manasu", "telugu", "yedalo", "prema",
                "pilla", "chala", "nenu", "ammaye", "kannulu", "chiru", "navvu", "velugu", "gunde", "cheliya"
            ),
            supportingWords = setOf("naa", "oka", "inka", "ela", "enti"),
            anchorPhrases = setOf("naatu naatu", "telugu song", "oo antava", "saami saami", "kurchi madathapetti"),
            morphSuffixes = setOf("vuga", "lalo", "thoti"),
            morphSubstrings = setOf("nuvv", "neek", "naak", "manas", "andh", "kannul", "preminch", "ammay", "chinni", "butta", "pillag")
        ),
        RomanizedRule(
            "Kannada",
            anchors = setOf(
                "jotheyali", "belageddu", "kannadathi", "hudugi", "preethi", "maduve", "mungaru",
                "helutaithe", "singara", "siriye", "kantara", "tagaru", "raajakumara", "karabuu",
                "bekagide", "usire", "geleya", "gelathi", "bombe"
            ),
            strongWords = setOf("kannada", "ninna", "naanu", "neenu", "banni", "illi", "yenagali", "magale", "hoovu", "hrudaya", "kanasu"),
            supportingWords = setOf("nanna", "ondu", "yenu", "nodu", "maatu", "maadu", "jeeva"),
            anchorPhrases = setOf("kannada song", "singara siriye", "bombe helutaithe")
        ),
        RomanizedRule(
            "Hindi",
            anchors = setOf(
                "pyaar", "pyar", "ishq", "zindagi", "chahiye", "raabta", "dhadkan", "aashiqui",
                "bekhayali", "kesariya", "humsafar", "bepanah", "sajde", "khuda", "mohabbat",
                "deewana", "deewani", "channa", "mereya", "shayad", "raataan", "lambiyan",
                "pasoori", "chaleya", "satranga", "heeriye", "kabira", "ilahi", "gerua",
                "hawayein", "kalank", "khairiyat", "kamleya", "baarishein", "fitoor", "mehrama",
                "qaafirana", "naina", "husn", "bulleya", "ae", "dil", "mushkil", "zaalima",
                "mast", "magan", "subhanallah", "agar", "saath", "tum", "hi", "ho"
            ),
            strongWords = setOf(
                "tumse", "tumhara", "tumhari", "jaane", "jeena", "jeene", "sanam", "sajna", "piya",
                "khamosh", "duniya", "khwab", "khwaab", "nain", "saans", "saansein", "baat", "baatein",
                "raat", "raatein", "yaad", "yaadein", "rab", "rabba", "tere", "mere", "tumhe",
                "mera", "meri", "teri", "suhana", "safar", "manzil", "sitare", "chaand", "subah",
                "shaam", "lamhe", "apna"
            ),
            supportingWords = setOf(
                "hai", "hain", "hoon", "nahi", "nahin", "kya", "kyun", "jaan", "rang",
                "aur", "ek", "hum", "toh", "woh", "yeh", "mein"
            ),
            anchorPhrases = setOf(
                "tum hi ho", "tere liye", "dil se", "channa mereya", "hindi song", "apna bana le",
                "raataan lambiyan", "tere vaaste", "kal ho naa ho", "kal ho na ho", "agar tum saath ho"
            ),
            morphSuffixes = setOf("iyan", "iyaan"),
            morphSubstrings = setOf("khw", "pyaar", "ishq", "mohabb", "zindag", "dhadk", "aashiq", "humsaf", "sajd", "deewan", "chann")
        ),
        RomanizedRule(
            "Punjabi",
            anchors = setOf(
                "gabru", "kudi", "kudiye", "munde", "mundeya", "mitran", "sohni", "sohneya", "patola",
                "launglaachi", "bhangra", "gidda", "jatt", "jatta", "vail", "vailpuna", "punjabi",
                "chobbar", "pind", "lehenga", "koka", "galwakdi", "qismat", "lalkara", "bandook",
                "velly", "sardaar", "sardari", "akhiyan", "phulkari", "paranda", "kangna", "jhanjar"
            ),
            strongWords = setOf("sardar", "jind", "maahi", "sohnea", "chakde", "naal", "yaaran", "yaari", "mahiya", "dholna", "beliya", "ranjha"),
            supportingWords = setOf("ve", "ni", "balle", "sajna", "gora", "oye", "tenu", "mainu", "assi", "tussi", "sadda", "vich"),
            anchorPhrases = setOf("punjabi song", "laung laachi", "brown munde", "suit punjabi"),
            morphSubstrings = setOf("jatt", "mund", "kudi", "gabr", "vail", "chobb", "patol", "bhangr")
        ),
        RomanizedRule(
            "Bengali",
            anchors = setOf("bhalobasha", "bhalobashi", "brishti", "shudhu", "bojhena", "kothay", "bangla", "bengali", "chokh", "prithibi", "tomake", "amake"),
            strongWords = setOf("tumi", "ami", "amar", "tomar", "bhalo", "kemon", "jibon", "moner", "priyo", "shona", "ekla"),
            supportingWords = setOf("mon", "toke", "batashe"),
            anchorPhrases = setOf("bengali song", "bangla gaan")
        ),
        RomanizedRule(
            "Marathi",
            anchors = setOf("majha", "majhi", "majhya", "marathi", "tuza", "tuzi", "manat", "manala", "zingaat", "apsara"),
            strongWords = setOf("tula", "mala", "aahe", "ahe", "gaani", "sundara", "saajani"),
            supportingWords = setOf("mi", "kay", "rani"),
            anchorPhrases = setOf("marathi song")
        ),
        RomanizedRule(
            "Gujarati",
            anchors = setOf("gujarati", "kemcho", "dikri", "chokri", "garba", "gujju", "khalasi", "vhalam"),
            strongWords = setOf("tame", "mane", "tari", "maru", "chhe"),
            supportingWords = setOf("tara", "prem"),
            anchorPhrases = setOf("kem cho", "gujarati song")
        ),
        RomanizedRule(
            "Odia",
            anchors = setOf("odia", "odiya", "kemiti", "pakhare"),
            strongWords = setOf("mora", "tora", "tume", "bhala"),
            supportingWords = setOf("mo", "mu"),
            anchorPhrases = setOf("odia song")
        ),
        RomanizedRule(
            "Korean",
            anchors = setOf("saranghae", "annyeong", "gomawo", "eotteoke", "mianhae", "gwiyeowo", "jinjja", "bangtan", "hallyu", "daebak"),
            strongWords = setOf("neomu", "chingu", "unnie", "oppa", "sarang"),
            supportingWords = setOf("uri", "bogo", "geurae"),
            anchorPhrases = setOf("korean song", "k pop")
        ),
        RomanizedRule(
            "Japanese",
            anchors = setOf("arigatou", "arigato", "sayonara", "daisuki", "yoroshiku", "watashi", "shinunoga", "wa", "kaikai", "kitan", "gurenge", "unravel"),
            strongWords = setOf("kokoro", "kawaii", "sekai", "yume", "hikari", "yoru", "kaze"),
            supportingWords = setOf("kimi", "boku", "sakura", "aoi"),
            anchorPhrases = setOf("japanese song", "shinunoga e wa")
        ),
        RomanizedRule(
            "Spanish",
            anchors = setOf("despacito", "bailando", "besame", "corazon", "gasolina", "dembow", "perreo", "mamacita", "regueton", "reggaeton"),
            strongWords = setOf("cancion", "bailar", "quiero", "amor", "noche", "vida", "caliente", "corazon", "bonita", "loco", "fuego"),
            supportingWords = setOf("para", "con", "yo", "te", "como", "pero", "mas", "todo"),
            anchorPhrases = setOf("te quiero", "mi vida", "corazon partio", "spanish song", "besame mucho")
        ),
        RomanizedRule(
            "English",
            strongWords = setOf("because", "without", "tonight", "tomorrow", "goodbye", "somebody", "everybody", "nothing", "everything", "nobody", "anywhere", "somewhere", "believe", "dancing", "lonely", "broken", "heaven", "angel", "perfect", "closer", "faded", "attention", "paradise", "memories", "heartbreak", "heartache", "sunshine", "midnight", "moonlight", "beautiful", "forever", "wonderland", "starlight", "yesterday"),
            supportingWords = setOf("the", "you", "your", "with", "from", "are", "we", "me", "my", "what", "where", "when", "why", "how", "never", "can", "not", "dont", "cant", "ill", "im", "its", "in", "on", "of", "to", "for", "and", "but", "if", "like", "this", "that", "all", "one", "more"),
            anchorPhrases = setOf("stand by me", "let it be", "shape of you", "love me like you do", "someone like you", "what makes you beautiful", "take me home", "we are the champions", "english song")
        )
    )

    /*
     * Layer 4: Curated Artist, Composer & Label Language Taxonomy.
     * Primary anchors (composers, music directors, monolingual artists, regional labels) take precedence
     * over polyglot playback singers (e.g., Shreya Ghoshal, Sid Sriram, Chithra, Yesudas, SPB, Haricharan)
     * when both appear on a collaboration credit.
     */
    private val primaryArtistTaxonomy: Map<String, String> = buildMap {
        fun register(language: String, vararg names: String) {
            names.forEach { put(it, language) }
        }
        register(
            "Malayalam",
            "sushin shyam", "jakes bejoy", "gopi sundar", "shaan rahman", "rex vijayan", "vidyasagar",
            "vineeth sreenivasan", "sithara krishnakumar", "kapil kapilan", "dabzee", "fejo", "vedan",
            "job kurian", "hesham abdul wahab", "govind vasantha", "thaikkudam bridge", "neha nair",
            "bijibal", "ouseppachan", "m jayachandran", "ks harisankar", "k s harisankar", "najim arshad",
            "sooraj santhosh", "sachin warrier", "shahabaz aman", "masala coffee", "avial", "agam",
            "muzik247", "think music malayalam", "manorama music", "satyam audios", "goodwill entertainments",
            "christo xavier", "vishnu vijay", "justin varghese", "kailas menon", "rahul raj", "deepak dev",
            "alphons joseph", "m g sreekumar", "mg sreekumar", "sujatha mohan", "madhu balakrishnan",
            "anne amie", "gouri lakshmi", "hanan shaah"
        )
        register(
            "Tamil",
            "anirudh ravichander", "anirudh", "a r rahman", "ar rahman", "ilaiyaraaja", "ilayaraja",
            "yuvan shankar raja", "harris jayaraj", "santhosh narayanan", "g v prakash kumar", "gv prakash",
            "sean roldan", "d imman", "hiphop tamizha", "nivas k prasanna", "sam c s", "sam cs",
            "ghibran", "leon james", "darbuka siva", "vivek mervin", "chinmayi", "dhanush",
            "silambarasan tr", "sony music south", "think music india", "vijay antony", "srikanth deva",
            "harish raghavendra", "naresh iyer", "saindhavi", "shakthisree gopalan", "dhee", "arivu",
            "paal dabba", "asal kolar", "ofro"
        )
        register(
            "Hindi",
            "arijit singh", "pritam", "vishal shekhar", "amit trivedi", "atif aslam", "kk",
            "jubin nautiyal", "badshah", "sachin jigar", "mithoon", "vishal mishra", "darshan raval",
            "anuv jain", "prateek kuhad", "shankar ehsaan loy", "salim sulaiman", "mohit chauhan",
            "sonu nigam", "neha kakkar", "sunidhi chauhan", "armaan malik", "amaal mallik",
            "tanishk bagchi", "rochak kohli", "b praak", "jaani", "yo yo honey singh", "honey singh",
            "raftaar", "krsna", "seedhe maut", "divine", "t series", "zee music company",
            "sony music india", "saregama", "alka yagnik", "udit narayan", "kumar sanu", "kishore kumar",
            "lata mangeshkar", "asha bhosle", "mohammed rafi", "javed ali", "shaan", "himesh reshammiya",
            "ankit tiwari", "jeet gannguli", "papon", "rekha bhardwaj", "shilpa rao", "asees kaur",
            "jasleen royal", "sachet tandon", "parampara tandon", "sachet parampara", "the local train",
            "when chai met toast", "varun jain"
        )
        register(
            "Telugu",
            "devi sri prasad", "dsp", "thaman s", "s thaman", "m m keeravani", "mm keeravani",
            "mickey j meyer", "mani sharma", "ram miriyala", "mangli", "anurag kulkarni",
            "kaala bhairava", "rahul sipligunj", "sri krishna", "geetha madhuri", "aditya music",
            "lahari music", "chaitan bharadwaj", "bheems ceciroleo"
        )
        register(
            "Punjabi",
            "diljit dosanjh", "ap dhillon", "karan aujla", "sidhu moose wala", "shubh", "gurinder gill",
            "harrdy sandhu", "amrinder gill", "gurdas maan", "satinder sartaaj", "ammy virk",
            "jassie gill", "parmish verma", "mankirt aulakh", "sharry mann", "speed records",
            "white hill music", "jass manak", "gurnam bhullar", "nimrat khaira", "sunanda sharma",
            "baani sandhu", "prem dhillon", "arjan dhillon", "talwiinder", "ikky", "tarsem jassar",
            "kulwinder billa", "ranjit bawa", "babbu maan", "bohemia", "imran khan", "prophec",
            "pav dharia", "gippy grewal", "garry sandhu", "jasmine sandlas"
        )
        register(
            "Kannada",
            "arjun janya", "b ajaneesh loknath", "ajaneesh loknath", "charan raj", "raghu dixit",
            "sanjith hegde", "vasuki vaibhav", "chandan shetty", "anand audio", "prk audio",
            "v harikrishna", "gurukiran", "hamsalekha", "rajesh krishnan", "vijay prakash"
        )
        register(
            "Bengali",
            "anupam roy", "arnob", "fossils", "rupam islam", "nachiketa", "anjan dutt", "svf music"
        )
        register(
            "Korean",
            "bts", "bangtan boys", "blackpink", "newjeans", "twice", "stray kids", "seventeen", "exo",
            "iu", "jung kook", "jungkook", "le sserafim", "aespa", "ive", "itzy", "tomorrow x together",
            "enhypen", "ateez", "nct", "red velvet", "bigbang", "psy", "babymonster", "illit",
            "kiss of life", "mamamoo", "monsta x", "got7", "shinee", "super junior"
        )
        register(
            "Japanese",
            "yoasobi", "kenshi yonezu", "fujii kaze", "radwimps", "ado", "aimer", "vaundy",
            "official hige dandism", "king gnu", "mrs green apple", "one ok rock", "creepy nuts",
            "yorushika", "zutomayo", "imase", "tatsuya kitani", "hikaru utada"
        )
        register(
            "Spanish",
            "bad bunny", "karol g", "peso pluma", "feid", "daddy yankee", "j balvin", "rosalia",
            "shakira", "maluma", "ozuna", "rauw alejandro", "anuel aa", "myke towers", "bizarrap",
            "quevedo", "nicky jam", "farruko", "romeo santos", "aventura", "marc anthony",
            "enrique iglesias", "luis fonsi", "sebastian yatra", "camilo", "becky g", "kali uchis",
            "fuerza regida", "grupo frontera", "junior h", "natanael cano", "luis miguel"
        )
    }

    private val polyglotPlaybackSingers: Map<String, String> = mapOf(
        "shreya ghoshal" to "Hindi",
        "sid sriram" to "Tamil",
        "k s chithra" to "Malayalam",
        "ks chithra" to "Malayalam",
        "chithra" to "Malayalam",
        "k j yesudas" to "Malayalam",
        "kj yesudas" to "Malayalam",
        "yesudas" to "Malayalam",
        "s p balasubrahmanyam" to "Tamil",
        "sp balasubrahmanyam" to "Tamil",
        "spb" to "Tamil",
        "vijay yesudas" to "Malayalam",
        "karthik" to "Tamil",
        "haricharan" to "Tamil",
        "haricharan seshadri" to "Tamil",
        "shweta mohan" to "Malayalam",
        "jonita gandhi" to "Hindi",
        "benny dayal" to "Hindi",
        "nakash aziz" to "Hindi",
        "pradeep kumar" to "Tamil",
        "shankar mahadevan" to "Hindi",
        "hariharan" to "Hindi",
        "kailash kher" to "Hindi"
    )

    private val languageAliases = linkedMapOf(
        "Malayalam" to listOf("malayalam", "malayali"),
        "Tamil" to listOf("tamil", "tamizh"),
        "Telugu" to listOf("telugu"),
        "Kannada" to listOf("kannada"),
        "Hindi" to listOf("hindi"),
        "Punjabi" to listOf("punjabi"),
        "Bengali" to listOf("bengali", "bangla"),
        "Marathi" to listOf("marathi"),
        "Gujarati" to listOf("gujarati"),
        "Odia" to listOf("odia", "odiya", "oriya"),
        "Urdu" to listOf("urdu"),
        "Arabic" to listOf("arabic"),
        "Korean" to listOf("korean", "k-pop"),
        "Japanese" to listOf("japanese", "j-pop"),
        "Chinese" to listOf("chinese", "mandarin"),
        "English" to listOf("english"),
        "Spanish" to listOf("spanish"),
        "French" to listOf("french"),
        "Portuguese" to listOf("portuguese"),
        "German" to listOf("german")
    )

    fun classify(title: String?, artist: String?): String {
        val trackTitle = title.orEmpty().trim()
        val trackArtist = artist.orEmpty().trim()

        // Layer 1: Explicit title / metadata language tags
        explicitLanguageTag(trackTitle)?.let { return it }

        // Layer 2: Native Unicode script detection (title first, then artist)
        detectScript(trackTitle)?.let { return it }
        detectScript(trackArtist)?.let { return it }

        val artistTaxonomyLang = resolveArtistLanguage(trackArtist)

        // Layer 3: Upgraded Romanized lexical + phonetic morphology scoring
        val normalizedTitle = normalizeLatinText(trackTitle)
        if (normalizedTitle.isNotBlank()) {
            val tokens = Regex("[a-z0-9]+(?:'[a-z0-9]+)?").findAll(normalizedTitle)
                .map { it.value.trim('\'') }
                .filter { it.isNotBlank() }
                .toSet()

            val ranked = romanizedRules.map { rule ->
                var score = 0.0
                score += rule.anchors.count { it in tokens } * 4.0
                score += rule.strongWords.count { it in tokens } * 2.0
                score += rule.supportingWords.count { it in tokens } * 1.0
                score += rule.anchorPhrases.count { phrase ->
                    Regex("(?:^|\\s)${Regex.escape(phrase)}(?:$|\\s)").containsMatchIn(normalizedTitle)
                } * 4.0

                val unmatchedTokens = tokens.filter { it.length >= 5 && it !in rule.anchors }
                val morphHits = unmatchedTokens.count { token ->
                    rule.morphSuffixes.any { suffix -> token.length > suffix.length + 2 && token.endsWith(suffix) } ||
                        rule.morphSubstrings.any { sub -> token.contains(sub) }
                }
                score += morphHits * 2.0

                if (artistTaxonomyLang != null && rule.language == artistTaxonomyLang && score >= 1.5) {
                    score += 2.0
                }
                rule.language to score
            }.sortedByDescending { it.second }

            val best = ranked.firstOrNull()
            val runnerUp = ranked.getOrNull(1)?.second ?: 0.0
            if (best != null && best.second >= 3.5 && best.second - runnerUp >= 1.5) {
                return best.first
            }

            // Layer 4: Curated Artist, Composer & Label Taxonomy fallback when title is not strongly another language
            if (artistTaxonomyLang != null) {
                return artistTaxonomyLang
            }

            return "Latin script (language unclear)"
        }

        if (artistTaxonomyLang != null) return artistTaxonomyLang
        return if (trackArtist.any(Char::isLetter)) "Latin script (language unclear)" else "Unknown"
    }

    /**
     * Resolves a track's language from artist/composer/label credits, giving precedence to
     * primary language anchors (composers, music directors, monolingual acts, labels) over
     * polyglot playback singers on multi-artist collaborations.
     */
    fun resolveArtistLanguage(artist: String?): String? {
        if (artist.isNullOrBlank()) return null
        val credits = artist
            .split(Regex("(?i)\\s*(?:,|&|/|•|\\||;|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bx\\b|\\bwith\\b)\\s*"))
            .map { normalizeLatinText(it) }
            .filter { it.isNotBlank() }

        // Pass 1: Primary composer / artist / label anchors win on collaborations
        for (credit in credits) {
            primaryArtistTaxonomy[credit]?.let { return it }
        }
        val normalizedFull = normalizeLatinText(artist)
        for ((knownName, lang) in primaryArtistTaxonomy) {
            if (knownName.length >= 4 && Regex("(?:^|\\s)${Regex.escape(knownName)}(?:$|\\s)").containsMatchIn(normalizedFull)) {
                return lang
            }
        }

        // Pass 2: Polyglot playback singers (only when no primary anchor is present)
        for (credit in credits) {
            polyglotPlaybackSingers[credit]?.let { return it }
        }
        for ((singerName, lang) in polyglotPlaybackSingers) {
            if (singerName.length >= 5 && Regex("(?:^|\\s)${Regex.escape(singerName)}(?:$|\\s)").containsMatchIn(normalizedFull)) {
                return lang
            }
        }
        return null
    }

    /**
     * Maps the 5-layer classifier output into canonical recommender language tags.
     * Generic English, Han-only, or uncertain Latin tracks return null so they are never
     * treated as a regional language preference signal.
     */
    fun classifyForRecommender(title: String?, artist: String?): String? {
        return normalizeRecommenderLanguage(classify(title, artist))
    }

    fun normalizeRecommenderLanguage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when (raw.trim()) {
            "Malayalam" -> "Malayalam"
            "Tamil" -> "Tamil"
            "Telugu" -> "Telugu"
            "Kannada" -> "Kannada"
            "Bengali" -> "Bengali"
            "Gujarati" -> "Gujarati"
            "Odia" -> "Odia"
            "Punjabi", "Punjabi (Gurmukhi script)" -> "Punjabi"
            "Hindi", "Devanagari (Hindi/Marathi etc.)" -> "Hindi"
            "Marathi" -> "Marathi"
            "Korean", "K-pop" -> "K-pop"
            "Japanese" -> "Japanese"
            "Spanish" -> "Spanish"
            else -> null
        }
    }

    private fun explicitLanguageTag(title: String): String? {
        if (title.isBlank()) return null
        languageAliases.forEach { (language, aliases) ->
            aliases.forEach { alias ->
                val escaped = Regex.escape(alias)
                val tagged = Regex(
                    "(?i)(?:\\[\\s*$escaped\\s*]|\\(\\s*$escaped\\s*\\)|" +
                        "\\b(?:language|lyrics?|audio|version|song)\\s*(?:in|[:=-])?\\s+$escaped\\b|" +
                        "\\b$escaped\\s+(?:song|version|audio|lyrics?|language)\\b|" +
                        "(?:^|[-–—|•])\\s*$escaped\\s*$)"
                )
                if (tagged.containsMatchIn(title) || normalizeLatinText(title) == normalizeLatinText(alias)) {
                    return language
                }
            }
        }
        return null
    }

    private fun detectScript(text: String): String? {
        if (text.isBlank()) return null
        val counts = scriptRules.mapNotNull { rule ->
            val count = text.count { it in rule.range && it.isLetter() }
            if (count > 0) rule to count else null
        }
        if (counts.isEmpty()) return null
        // Prefer Japanese when kana appears; Han-only text remains ambiguous.
        if (text.any { it in '\u3040'..'\u30FF' }) return "Japanese"
        return counts.maxByOrNull { it.second }?.first?.label
    }

    private fun normalizeLatinText(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return normalized
            .filter { java.lang.Character.getType(it) != java.lang.Character.NON_SPACING_MARK.toInt() }
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9']+"), " ")
            .trim()
    }
}
data class TrackListeningTotal(
    val videoId: String,
    val title: String,
    val artist: String,
    val listenedMs: Long,
    val sessions: Int
)

data class ArtistListeningTotal(
    val id: String,
    val name: String,
    val listenedMs: Long,
    val sessions: Int
)

data class LanguageListeningTotal(val name: String, val listenedMs: Long)
data class DailyListeningTotal(val label: String, val listenedMs: Long)
data class DiscoveredArtist(val id: String, val name: String, val firstHeardAt: Long)

data class ListeningStatsSnapshot(
    val last7DaysMs: Long = 0L,
    val last30DaysMs: Long = 0L,
    val dailyListening: List<DailyListeningTotal> = emptyList(),
    val topTracks: List<TrackListeningTotal> = emptyList(),
    val topArtists: List<ArtistListeningTotal> = emptyList(),
    val languages: List<LanguageListeningTotal> = emptyList(),
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val recentlyDiscoveredArtists: List<DiscoveredArtist> = emptyList()
) {
    val hasData: Boolean get() = last30DaysMs > 0L || topTracks.isNotEmpty() || longestStreakDays > 0
}

/** Aggregations are pure and deterministic so time-window, streak and ranking behavior can be unit-tested. */
object ListeningStatsCalculator {
    fun calculate(
        sessions: List<ListeningSessionEntity>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        languageOverrides: Map<String, String> = emptyMap()
    ): ListeningStatsSnapshot {
        val meaningful = sessions.filter { it.listenedMs >= MIN_MEANINGFUL_LISTEN_MS }
        val monthSince = now - 30L * DAY_MS
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val weekStartDate = today.minusDays(6)
        val weekStartMs = weekStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekly = meaningful.filter { it.startedAt in weekStartMs..now }
        val monthly = meaningful.filter { it.startedAt in monthSince..now }
        val weeklyByDate = weekly.groupBy { localDate(it.startedAt, zone) }
        val dailyListening = (0L..6L).map { offset ->
            val date = weekStartDate.plusDays(offset)
            DailyListeningTotal(
                label = date.format(DateTimeFormatter.ofPattern("EE", Locale.getDefault())),
                listenedMs = weeklyByDate[date].orEmpty().sumOf { it.listenedMs }
            )
        }

        val topTracks = monthly.groupBy { it.videoId }.map { (id, rows) ->
            val newest = rows.maxByOrNull { it.startedAt } ?: return@map null
            TrackListeningTotal(
                videoId = id,
                title = newest.title,
                artist = newest.artist,
                listenedMs = rows.sumOf { it.listenedMs },
                sessions = rows.size
            )
        }.filterNotNull().sortedWith(compareByDescending<TrackListeningTotal> { it.listenedMs }.thenBy { it.title.lowercase(Locale.ROOT) }).take(5)

        data class MutableArtist(var name: String, var listenedMs: Long = 0L, var sessions: Int = 0)
        val artistTotals = linkedMapOf<String, MutableArtist>()
        monthly.forEach { session ->
            artistEntries(session.artist, session.artists.map { it.id to it.name }).forEach { (id, name) ->
                val total = artistTotals.getOrPut(id) { MutableArtist(name) }
                total.name = name
                total.listenedMs += session.listenedMs
                total.sessions += 1
            }
        }
        val topArtists = artistTotals.map { (id, v) -> ArtistListeningTotal(id, v.name, v.listenedMs, v.sessions) }
            .sortedWith(compareByDescending<ArtistListeningTotal> { it.listenedMs }.thenBy { it.name.lowercase(Locale.ROOT) })
            .take(5)

        // A source-provided language (or language inferred from lyrics script) is more
        // reliable than a title-only estimate. Keep the existing classifier as fallback.
        val languageTotals = monthly.groupBy { session ->
            languageOverrides[session.videoId]
                ?: ListeningLanguageClassifier.classify(session.title, session.artist)
        }
            .map { (language, rows) -> LanguageListeningTotal(language, rows.sumOf { it.listenedMs }) }
            .sortedByDescending { it.listenedMs }
            .take(6)

        val dailyMs = meaningful.groupBy { localDate(it.startedAt, zone) }
            .mapValues { (_, rows) -> rows.sumOf { it.listenedMs } }
        val qualifyingDays = dailyMs.filterValues { it >= STREAK_DAY_LISTEN_MS }.keys
        val currentStart = when {
            today in qualifyingDays -> today
            today.minusDays(1) in qualifyingDays -> today.minusDays(1)
            else -> null
        }
        var currentStreak = 0
        if (currentStart != null) {
            var date: LocalDate = currentStart
            while (date in qualifyingDays) {
                currentStreak++
                date = date.minusDays(1)
            }
        }
        val sortedDays = qualifyingDays.sorted()
        var longestStreak = 0
        var runLength = 0
        var previous: LocalDate? = null
        sortedDays.forEach { date ->
            runLength = if (previous != null && previous.plusDays(1) == date) runLength + 1 else 1
            longestStreak = maxOf(longestStreak, runLength)
            previous = date
        }

        // An artist is “discovered” only if the first meaningful local listen is within the last 30 days.
        data class FirstArtist(val name: String, val at: Long)
        val firstByArtist = linkedMapOf<String, FirstArtist>()
        meaningful.forEach { session ->
            artistEntries(session.artist, session.artists.map { it.id to it.name }).forEach { (id, name) ->
                val current = firstByArtist[id]
                if (current == null || session.startedAt < current.at) {
                    firstByArtist[id] = FirstArtist(name, session.startedAt)
                }
            }
        }
        val discovered = firstByArtist.mapNotNull { (id, first) ->
            if (first.at in monthSince..now) DiscoveredArtist(id, first.name, first.at) else null
        }.sortedByDescending { it.firstHeardAt }.take(5)

        return ListeningStatsSnapshot(
            last7DaysMs = weekly.sumOf { it.listenedMs },
            last30DaysMs = monthly.sumOf { it.listenedMs },
            dailyListening = dailyListening,
            topTracks = topTracks,
            topArtists = topArtists,
            languages = languageTotals,
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            recentlyDiscoveredArtists = discovered
        )
    }

    private fun localDate(timestamp: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    private fun artistEntries(
        fallbackArtist: String,
        artists: List<Pair<String?, String>>
    ): List<Pair<String, String>> {
        val valid = artists.mapNotNull { (id, name) ->
            name.trim().takeIf { it.isNotEmpty() }?.let { (id?.takeIf(String::isNotBlank)?.let { key -> "id:$key" } ?: "name:${it.lowercase(Locale.ROOT)}") to it }
        }
        if (valid.isNotEmpty()) return valid.distinctBy { it.first }
        return fallbackArtist.trim().takeIf { it.isNotEmpty() }
            ?.let { listOf(("name:" + it.lowercase(Locale.ROOT)) to it) }.orEmpty()
    }

    fun formatDuration(durationMs: Long): String {
        val minutes = (durationMs.coerceAtLeast(0L) / 60_000L).toInt()
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            durationMs <= 0L -> "0m"
            else -> "<1m"
        }
    }

    fun formatDate(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(timestamp).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
}
