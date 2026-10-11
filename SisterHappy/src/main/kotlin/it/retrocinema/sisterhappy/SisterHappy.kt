package it.retrocinema.sisterhappy

import android.graphics.BitmapFactory
import android.widget.Toast
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.InetAddress
import java.net.URI

/** User-Agent realistico da browser desktop. */
const val SH_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * ------------------------------------------------------------------
 * TOAST DIAGNOSTICI (v9)
 * ------------------------------------------------------------------
 * Breve messaggio a schermo (gli stessi toast dell'app) per capire DOVE
 * muore la catena dei link, senza collegare il telefono al PC:
 * - "SH A ..."      : avvio, numero di link, esito del DNS del telefono
 * - "SH B1/B2/..."  : esito di ogni singolo link con la sua traccia
 * - "SH FINE x/n"   : quanti link sono stati emessi davvero
 * Ogni toast rimpiazza il precedente: gli ultimi a schermo sono i piu'
 * significativi. Il dettaglio completo resta nei log TAG "SisterHappy".
 */
const val SH_DEBUG_TOASTS = true

private val shTraceLock = Any()
private var shTraceList: List<StringBuilder> = emptyList()

/** Nota nella traccia del link [idx] (primo link = 0). */
fun shNote(idx: Int, msg: String) {
    synchronized(shTraceLock) {
        shTraceList.getOrNull(idx)?.append(msg)?.append(' ')
    }
}

/** Traccia del link [idx] (parte finale: i toast hanno spazio limitato). */
fun shTraceOf(idx: Int, max: Int = 90): String = synchronized(shTraceLock) {
    shTraceList.getOrNull(idx)?.toString()?.trim()?.takeLast(max) ?: ""
}

/** Prepara una traccia vuota per ognuno dei [n] link da risolvere. */
fun shTraceReset(n: Int) {
    synchronized(shTraceLock) {
        shTraceList = List(n.coerceAtLeast(1)) { StringBuilder() }
    }
}

/** Toast a schermo; silenzioso se SH_DEBUG_TOASTS e' false. */
fun shToast(msg: String) {
    if (!SH_DEBUG_TOASTS) return
    runCatching { CommonActivity.showToast("SH $msg", Toast.LENGTH_LONG) }
        .onFailure { Log.e("SisterHappy", "toast fallito: ${it.message}") }
}

/** Etichetta corta di un URL: host + ultimo segmento (per i toast). */
fun shTag(url: String): String {
    val clean = url.substringAfter("://").removePrefix("www.")
    val host = clean.substringBefore('/')
    val tail = clean.trimEnd('/').substringAfterLast('/')
    return if (tail.isNotEmpty() && tail != host) "$host/$tail" else host
}

/** Un link di un episodio: url del protettore (o diretto) + etichetta dell'host letta dalla pagina. */
data class ShLink(
    val url: String = "",
    val host: String = ""
)

/** Dati di un episodio salvati nel campo data: stagione, nome e lista dei link disponibili. */
data class ShEpisodeData(
    val season: Int = 0,
    val name: String = "",
    val links: List<ShLink> = emptyList()
)

/**
 * SisterHappy - estensione dedicata a UNA sola serie TV italiana.
 *
 * La pagina del sito e una classica pagina "serie" WordPress:
 * - sinossi dentro div.entry-content (parte nascosta compresa)
 * - "Link disponibili:" seguito da div.su-accordion con un div.su-spoiler per stagione
 * - dentro ogni stagione, righe di testo separate da <br>:
 *     "15x01 Audizioni 1 - <a href=protettore>MaxStream</a> - <a>DeltaBit</a>"
 * - i link passano da protettori (uprot.net, clicka.cc) che nascondono gli host video
 *
 * Punti chiave della v9 (diagnostica a schermo):
 * - TOAST DIAGNOSTICI: ad ogni apertura episodio compaiono brevi messaggi a
 *   schermo: A = avvio con esito DNS, B1/B2/.. = esito di ogni link con la
 *   sua traccia, FINE = riepilogo. Dicono esattamente dove muore la catena
 *   senza collegare il telefono al PC (i log completi restano nel TAG
 *   "SisterHappy" del visualizzatore log dell'app).
 * Punti chiave della v11 (captcha safego risolto in locale, niente WebView):
 * - CANALE PRINCIPALE = clicka (DeltaBit/MixDrop): la catena redirect+POST
 *   della v10 arriva alla landing safego ma riceve un CAPTCHA DI CIFRE
 *   (traccia "c capt"). MammaMia lo risolve in automatico (tesseract): qui
 *   c'e' l'equivalente in puro Kotlin (ShOcr): GET della landing -> prima
 *   <img> data URI -> OCR cifre locale -> POST captch5=<cifre> -> il primo
 *   anchor e' l'host video. Fino a 3 tentativi con captcha freschi e soglia
 *   di fiducia progressiva; i cookie di sessione risolti restano in cache
 *   per gli episodi successivi (come il cookie.txt di MammaMia).
 * - UPROT/MaxStream ULTIMO: l'interstiziale Cloudflare (traccia "u chal") e'
 *   un muro su TLS/impronta del client HTTP che le richieste pure non
 *   superano (MammaMia stesso rinuncia e chiede il captcha manuale): resta
 *   in coda nel caso il sito lo disattivi.
 * Punti chiave della v10 (flussi MammaMia aggiornati a ott. 2026):
 * - UPROT con header browser COMPLETI: Origin https://uprot.net/ + Referer del
 *   link msf (stesso sito) + Sec-Fetch/sec-ch-ua, come fa MammaMia (che con
 *   questo flusso funziona ancora oggi). La v9 con header minimi riceveva una
 *   pagina 200 SENZA il link Continue (traccia "u noCONT u noCONT"). Traccia
 *   nuova: "u chal" (interstiziale Cloudflare), "u capt" (pagina captcha).
 * - CLICKA: eliminati gli AJAX linkEmbedView/linkView (ora rispondono 403:
 *   traccia v9 "cA 403 cA 403"); al loro posto il flusso MammaMia
 *   real_page: redirect a mano clicka->safego con cookie raccolti a ogni
 *   salto + Range, poi POST della landing con cookie/Origin/Referer e
 *   lettura del PRIMO anchor (= link dell'host video). Traccia "c capt" se
 *   il protettore esige il captcha (MammaMia lo risolve con OCR).
 * - DELTABIT: flusso completo MammaMia deltabit.py: redirect a mano, GET
 *   player con Referer safego, TUTTI i campi input nascosti, attesa 2,5 s,
 *   POST imhuman -> sources:["...m3u8"] (prima solo regex sulla pagina).
 * Punti chiave della v8 (diagnostica sul campo):
 * - RICERCA MONO-SERIE: il plugin risponde solo con la sua serie, mai risultati
 *   estranei del sito (il tester interno dell'app prova il PRIMO risultato di
 *   ricerca e il PRIMO episodio: con risultati estranei testava pagine sbagliate).
 * - EPISODI IN ORDINE NUOVA-STAGIONE-PRIMA: il primo episodio caricato e' quello
 *   della stagione corrente, che ha i link uprot -> MaxStream (il canale
 *   affidabile); le righe vecchie con soli link clicka finiscono in fondo.
 * - COOKIE A MANO: il client HTTP dell'app NON ha un cookie jar (verificato nel
 *   sorgente CloudStream/nicehttp): i cookie ricevuti da ogni pagina vengono
 *   raccolti e rinviati esplicitamente alle richieste successive, come fa il
 *   browser. Senza questo la POST AJAX dei protettori parte "a mani vuote".
 * - REDIRECT A MANO nell'estrattore MaxStream: ogni salto raccoglie i cookie.
 * - ESTRAZIONE GENERICA: se la catena arriva su una pagina sconosciuta, il
 *   plugin la scarica e ricava l'm3u8/mp4 da solo (regex in cascata).
 * - Niente WebView: solo richieste HTTP pure con tetto temporale globale.
 */
class SisterHappy : MainAPI() {
    override var name = "SisterHappy"
    override var mainUrl = "https://eurostream.pics"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    companion object {
        /** Pagina unica della serie gestita da questo plugin. */
        const val SHOW_PATH = "/x-factor-53/"

        /** Host video finali riconosciuti come "foglia" della catena. */
        private val LEAF_HOSTS = listOf("maxstream", "deltabit", "mixdrop", "mxdrop", "turbovid")

        /** Tempo massimo dell'intera risoluzione di un episodio. */
        private const val DEADLINE_MS = 45_000L

        /** Timeout di ogni singola richiesta HTTP, in SECONDI (l'unita' di nicehttp). */
        private const val HTTP_TIMEOUT_S = 8L

        /**
         * Cookie di sessione del protettore safego: dopo il PRIMO captcha
         * risolto restano validi ancora per un po' (lo stesso trucco del
         * cookie.txt di MammaMia): gli episodi successivi passano senza OCR.
         */
        private val shSafegoJar = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** URL verso un host video noto, in qualunque parte del codice HTML. */
        private val KNOWN_HOST_RE = Regex(
            "https?://[\\w.-]*(?:maxstream|deltabit|mixdrop|mxdrop|turbovid)[\\w.-]*/[^\\s\"'<>]+",
            RegexOption.IGNORE_CASE
        )

        /** File video diretto (m3u8/mp4/mkv) in qualunque parte del codice HTML. */
        private val MEDIA_RE = Regex(
            "https?://[^\\s\"'<>]+\\.(?:m3u8|mp4|mkv)[^\\s\"'<>]*",
            RegexOption.IGNORE_CASE
        )

        /**
         * Anchor "Continue" delle pagine uprot: gestito via jsoup in parseUprotPage
         * (testo anche spaziato "C O N T I N U E"). Nessun DOT_MATCHES_ALL.
         */
        private val CONTINUE_RE = Regex("""<a[^>]+href="([^"]+)".*?Continue""", RegexOption.IGNORE_CASE)

        private val META_REFRESH_RE = Regex(
            "http-equiv\\s*=\\s*[\"']refresh[\"'][^>]*content\\s*=\\s*[\"'][^\"']*url=([^\"'>]+)",
            RegexOption.IGNORE_CASE
        )
        private val JS_LOCATION_RE = Regex(
            "(?:window\\.)?location(?:\\.href|\\.replace\\(\\s*)?\\s*[=(]\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )

        /** Tag per i log diagnostici (visibili nel visualizzatore log dell'app). */
        private const val TAG = "SisterHappy"
    }

    /**
     * Header di navigazione: SET COMPLETO da browser Chrome (shFullHeaders),
     * non piu' il minimo della v9: i protettori filtrano le richieste povere
     * di header servendo pagine senza i link (traccia v9 "u noCONT").
     */
    private fun navHeaders(referer: String? = null): Map<String, String> =
        shFullHeaders(referer ?: "")

    /** GET con header da browser, cookie opzionale, header extra e timeout corretto; mai lancia, al piu' null. */
    private suspend fun shGet(
        url: String,
        referer: String? = null,
        cookie: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = runCatching {
        var headers = if (cookie != null) navHeaders(referer) + mapOf("Cookie" to cookie)
        else navHeaders(referer)
        if (extra.isNotEmpty()) headers = headers + extra
        app.get(url, headers = headers, timeout = HTTP_TIMEOUT_S)
    }.getOrNull()

    private fun isLeafHost(url: String): Boolean = LEAF_HOSTS.any { url.contains(it, ignoreCase = true) }

    // ------------------------------------------------------------------
    //  HOME: una sola riga con la scheda della serie
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val card = loadShowCard(mainUrl + SHOW_PATH)
        return newHomePageResponse(
            HomePageList("In evidenza", listOf(card)),
        )
    }

    // ------------------------------------------------------------------
    //  RICERCA: solo la serie gestita dal plugin
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        // Plugin mono-serie: risponde SOLO con la sua pagina, e solo se la
        // query e' coerente col titolo (solo le LETTERE contano, cifre e spazi
        // esclusi: cosi' "titolo 20" torna = slug della pagina). Mai risultati
        // estranei del sito: il tester dell'app prova il primo risultato trovato.
        val q = query.lowercase().filter { it.isLetter() }.replace(" ", "")
        val slug = SHOW_PATH.filter { it.isLetter() }
        if (q.isEmpty() || !slug.contains(q)) return emptyList()
        return runCatching { listOf(loadShowCard(mainUrl + SHOW_PATH)) }
            .getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------
    //  LOAD: la scheda della serie con stagioni ed episodi
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        return loadShow(url)
    }

    private suspend fun loadShow(url: String): com.lagradost.cloudstream3.TvSeriesLoadResponse {
        val doc = app.get(url, headers = navHeaders()).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Serie"

        val content = doc.selectFirst("div.entry-content") ?: doc.body()

        // Poster: prima immagine dentro il contenuto
        val poster = content.select("img").firstOrNull()?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }

        // Sinossi: tutto il testo del contenuto PRIMA di "Link disponibili",
        // senza l'accordion degli episodi (parte nascosta della sinossi compresa)
        val plot = extractPlot(content)

        // Stagione piu' recente per prima: e' quella che si guarda adesso e
        // contiene i link uprot -> MaxStream (canale affidabile). Il tester
        // dell'app prova il primo episodio caricato: cosi' prova il migliore.
        val episodes = parseEpisodes(content)
            .sortedWith(compareByDescending<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.showStatus = ShowStatus.Ongoing
        }
    }

    /** Scheda leggera per la home e la ricerca: solo titolo e poster. */
    private suspend fun loadShowCard(url: String): SearchResponse {
        val doc = app.get(url, headers = navHeaders()).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: name
        val poster = doc.selectFirst("div.entry-content img")?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    /** Testo della sinossi: copia del contenuto senza accordion/pulsanti, troncato a "Link disponibili". */
    private fun extractPlot(content: Element): String {
        val clone = content.clone()
        clone.select("div.su-accordion, div.su-spoiler, button, script, style").remove()
        var text = clone.wholeText()
        val cut = text.indexOf("Link disponibili")
        if (cut >= 0) text = text.substring(0, cut)
        return text.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
    }

    // ------------------------------------------------------------------
    //  EPISODI: parsing di su-spoiler (una stagione ciascuno)
    // ------------------------------------------------------------------

    private fun parseEpisodes(content: Element): List<Episode> {
        val episodes = mutableListOf<Episode>()
        content.select("div.su-spoiler").forEach { spoiler ->
            val seasonTitle = spoiler.selectFirst(".su-spoiler-title")?.text() ?: ""
            val seasonHint = Regex("stagione\\s+(\\d+)", RegexOption.IGNORE_CASE)
                .find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull()
            val body = spoiler.selectFirst(".su-spoiler-content") ?: return@forEach

            // Divide il contenuto della stagione in righe sugli <br>
            val lines = mutableListOf<List<org.jsoup.nodes.Node>>()
            var current = mutableListOf<org.jsoup.nodes.Node>()
            body.childNodes().forEach { node ->
                if (node is Element && node.tagName() == "br") {
                    lines.add(current)
                    current = mutableListOf()
                } else {
                    current.add(node)
                }
            }
            lines.add(current)

            var fallbackEp = 0
            lines.forEach { nodes ->
                val label = StringBuilder()
                val links = mutableListOf<ShLink>()
                var seenAnchor = false
                nodes.forEach { node ->
                    when (node) {
                        is Element -> {
                            if (node.tagName() == "a") {
                                val href = node.attr("abs:href")
                                if (href.isNotBlank()) {
                                    links.add(ShLink(href, node.text().trim()))
                                    seenAnchor = true
                                }
                            } else if (!seenAnchor) {
                                label.append(node.text())
                            }
                        }
                        is TextNode -> if (!seenAnchor) label.append(node.text())
                        else -> {}
                    }
                }
                // Righe senza link = episodi non disponibili sul sito: saltate
                if (links.isNotEmpty()) {
                    var labelStr = label.toString()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .trimStart('-', '\u2013', '\u2014')
                        .trim()
                    var season = 0
                    var epNum = 0
                    val match = Regex("^(\\d+)\\s*[x\u00d7]\\s*(\\d+)\\s*").find(labelStr)
                    if (match != null) {
                        season = match.groupValues[1].toIntOrNull() ?: 0
                        epNum = match.groupValues[2].toIntOrNull() ?: 0
                        labelStr = labelStr.substring(match.range.last + 1).trim()
                            .trimStart('-', '\u2013', '\u2014')
                            .trim()
                    } else {
                        season = seasonHint ?: 0
                        fallbackEp++
                        epNum = fallbackEp
                    }
                    if (labelStr.isBlank()) labelStr = "Episodio $epNum"
                    episodes.add(
                        newEpisode(ShEpisodeData(season, labelStr, links).toJson()) {
                            this.name = labelStr
                            this.season = season
                            this.episode = epNum
                        }
                    )
                }
            }
        }
        return episodes
    }

    // ------------------------------------------------------------------
    //  LINK: risoluzione lineare protettori -> host video (niente WebView)
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = tryParseJson<ShEpisodeData>(data)
        if (episode == null) {
            shToast("A dati episodio illeggibili")
            return false
        }
        if (episode.links.isEmpty()) {
            shToast("A 0 link nell'episodio")
            return false
        }

        // Canale affidabile = clicka -> DeltaBit/MixDrop: il captcha della
        // landing safego e' risolto in locale dall'OCR (v11). uprot ->
        // MaxStream va per ultimo: l'interstiziale Cloudflare blocca le
        // richieste pure (traccia "u chal") e solo un sito meno protetto
        // potrebbe farlo tornare utile.
        val ordered = episode.links.sortedBy { link ->
            when {
                link.url.contains("clicka.cc/delta", ignoreCase = true) -> 0
                link.url.contains("clicka.cc/mix", ignoreCase = true) -> 1
                link.url.contains("uprot.net", ignoreCase = true) -> 3
                else -> 2
            }
        }
        shTraceReset(ordered.size)

        // Diagnostica v9: il DNS del telefono risolve uprot.net? (il browser
        // puo' usare un DNS diverso da quello dell'app: se qui esce BLOCCATO
        // il problema e' di rete, non di scraping)
        val dns = runCatching { InetAddress.getByName("uprot.net").hostAddress }
            .getOrNull()?.take(16)
        shToast("A ${ordered.size} link DNS=${dns ?: "BLOCCATO"}")

        val deadline = System.currentTimeMillis() + DEADLINE_MS

        // I link vengono risolti in parallelo: ogni catena e' lineare e corta,
        // quindi il tempo totale resta sotto il tetto.
        val results = ordered.mapIndexed { i, link -> i to link }.amap { (i, link) ->
            if (System.currentTimeMillis() >= deadline || link.url.isBlank()) false
            else runCatching {
                shNote(i, "da:" + shTag(link.url))
                resolveAndExtract(i, link.url, deadline, subtitleCallback, callback)
            }.onFailure {
                Log.e(TAG, "errore su ${link.url}: ${it.message}")
                shNote(i, "ERR:${it.message?.take(20)}")
            }.getOrDefault(false).also { r ->
                shToast("B${i + 1} ${if (r) "OK" else "KO"} ${shTraceOf(i)}")
            }
        }
        val okCount = results.count { it }
        Log.i(TAG, "loadLinks finito: ${okCount > 0} su ${ordered.size} link")
        // Riepilogo compatto: per ogni link l'ultima nota della traccia, cosi'
        // l'ultimo toast dice in UNA riga dove sono morte le catene
        val summary = (0 until ordered.size).joinToString(" ") { i ->
            "B${i + 1}:" + shTraceOf(i).trim().substringAfterLast(' ', "-").take(14)
        }
        shToast("FINE $okCount/${ordered.size} $summary".take(220))
        return okCount > 0
    }

    /** Risolve l'eventuale protettore con una catena lineare e delega all'estrattore finale. */
    private suspend fun resolveAndExtract(
        idx: Int,
        url: String,
        deadline: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false
        val wrapped: (ExtractorLink) -> Unit = { l ->
            emitted = true
            callback(l)
        }

        val target = when {
            isLeafHost(url) -> url
            url.contains("uprot.net", ignoreCase = true) -> resolveUprot(idx, url, deadline)
            url.contains("clicka.cc", ignoreCase = true) ||
                url.contains("safego.cc", ignoreCase = true) -> resolveClicka(idx, url, deadline)
            else -> url // host diretto non noto: si prova comunque l'estrazione
        } ?: return false

        shNote(idx, "→" + shTag(target))
        Log.i(TAG, "host finale raggiunto: $target")
        extractFinal(idx, target, deadline, subtitleCallback, wrapped)
        return emitted
    }

    /**
     * uprot.net: GET della pagina e lettura dell'anchor "Continue" verso l'host video.
     * Prima la variante /mse/ (senza captcha, trucco noto), poi l'originale /msf/.
     * Header ESATTI MammaMia (uprot.py, ott. 2026): Origin https://uprot.net/ e
     * Referer del LINK STESSO (pagina uprot, non eurostream!) sopra il set browser
     * completo. La v9 inviava header minimi + referer eurostream e riceveva una
     * pagina 200 senza Continue ("u noCONT u noCONT"). Cookie raccolti e rinviati;
     * se il Continue rimanda a un'altra pagina uprot si segue per max 3 salti.
     */
    private suspend fun resolveUprot(idx: Int, url: String, deadline: Long): String? {
        var current = url
        for (hop in 0 until 3) {
            if (System.currentTimeMillis() >= deadline) {
                shNote(idx, "u deadline")
                return null
            }
            val candidates = if (current.contains("/msf/", ignoreCase = true))
                listOf(current.replace("/msf/", "/mse/", ignoreCase = true), current)
            else listOf(current)

            var next: String? = null
            var jar: Map<String, String> = emptyMap()
            for (candidate in candidates) {
                // Flusso MammaMia: Origin su uprot e Referer = link msf (stesso sito)
                val resp = shGet(
                    candidate,
                    referer = current,
                    cookie = shCookieHeader(jar),
                    extra = mapOf("Origin" to "https://uprot.net/"),
                )
                if (resp == null) {
                    Log.i(TAG, "uprot: richiesta fallita (DNS/rete/timeout) $candidate")
                    shNote(idx, "u RETE")
                    continue
                }
                Log.i(TAG, "uprot: HTTP ${resp.code} su $candidate")
                if (resp.code == 403) {
                    Log.i(TAG, "uprot: blocco 403 (WAF/bot) su $candidate")
                    shNote(idx, "u 403WAF")
                    continue
                }
                if (resp.code != 200) shNote(idx, "u ${resp.code}")
                jar = jar + resp.cookies
                next = parseUprotPage(resp.text, resp.document)
                if (next == null) {
                    Log.i(TAG, "uprot: nessun Continue/host trovato nella pagina $candidate")
                    shNote(idx, uprotFailNote(resp.text))
                    continue
                }
                break
            }
            if (next == null) return null
            // Si accetta qualunque destinazione http (anche dominio nuovo):
            // l'estrazione finale generica la sa gestire. Se e' ancora uprot si continua.
            if (!next.contains("uprot.net", ignoreCase = true)) {
                shNote(idx, "u→" + shTag(next))
                return next
            }
            current = next
        }
        shNote(idx, "u loop")
        return null
    }

    /**
     * Classifica la pagina uprot che NON contiene il Continue: serve a capire
     * a schermo se il problema e' Cloudflare (interstiziale), un captcha del
     * protettore, o una pagina semplicemente senza link.
     */
    private fun uprotFailNote(html: String): String {
        val low = html.lowercase()
        return when {
            low.contains("cf-chl") || low.contains("challenge-platform") ||
                low.contains("just a moment") || low.contains("checking your browser") ||
                low.contains("attention required") -> "u chal"
            low.contains("captcha") -> "u capt"
            else -> "u noCONT"
        }
    }

    /**
     * Parsing della pagina uprot. Prima via jsoup: anchor con testo "Continue"
     * (anche con lettere spaziate "C O N T I N U E", come su MammaMia) — vale
     * l'ULTIMO anchor Continue, come fa MammaMia. Poi file video diretto nel
     * sorgente, regex grezza, URL host noto, anchor generici, redirect JS/meta.
     */
    private fun parseUprotPage(html: String, doc: Element): String? {
        var continueHref: String? = null
        for (a in doc.select("a[href]")) {
            val text = a.text().replace(Regex("\\s+"), "").uppercase()
            if (text.contains("CONTINUE")) continueHref = a.attr("abs:href")
        }
        continueHref?.let { href ->
            if (href.startsWith("http")) {
                Log.i(TAG, "uprot: anchor Continue -> $href")
                return href
            }
        }
        // Ricava il video da solo: m3u8/mp4 gia' presenti nel sorgente pagina
        MEDIA_RE.find(html)?.value?.let {
            Log.i(TAG, "uprot: file video diretto nel sorgente")
            return it
        }
        // Riserva: regex grezza sulla stessa riga (page con markup semplice)
        CONTINUE_RE.findAll(html).forEach { m ->
            val href = m.groupValues[1].trim()
            if (href.startsWith("http")) return href
        }
        KNOWN_HOST_RE.find(html)?.value?.let {
            Log.i(TAG, "uprot: URL host noto nel sorgente -> $it")
            return it
        }
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href")
            if (href.startsWith("http") && isLeafHost(href)) {
                Log.i(TAG, "uprot: anchor host noto -> $href")
                return href
            }
        }
        return htmlRedirect(html)
    }

    /**
     * clicka.cc (DeltaBit/MixDrop): flusso MammaMia eurostreaming.py (ott. 2026),
     * che e' quello che funziona ancora oggi nel browser -- i vecchi AJAX
     * linkEmbedView/linkView della v8/v9 ora rispondono 403 e sono rimossi:
     * 1) GET del link clicka con redirect A MANO (cookie raccolti a ogni salto,
     *    header Range per un corpo minimo, come fa MammaMia) fino alla landing
     *    reale (di norma safego.cc, a volte direttamente l'host video);
     * 2) POST della landing con i cookie + Origin/Referer same-site e
     *    User-Agent Firefox (scelta MammaMia): la risposta ha come PRIMO anchor
     *    il link dell'host video;
     * 3) se invece della pagina col link arriva un'immagine captcha (data URI),
     *    il protettore esige l'OCR: si passa a solveClickaCaptcha che lo
     *    risolve in locale (v11, flusso MammaMia captch5 con OCR cifre).
     */
    private suspend fun resolveClicka(idx: Int, url: String, deadline: Long): String? {
        // 1) catena di redirect a mano clicka -> landing reale, cookie a ogni salto
        var current = url
        val jar = mutableMapOf<String, String>()
        // Cookie di sessione gia' risolti in passato: con questi la PRIMA POST
        // della landing puo' gia' restituire l'anchor, senza toccare l'OCR
        if (shSafegoJar.isNotEmpty()) jar.putAll(shSafegoJar)
        var reached = false
        for (hop in 0 until 5) {
            if (System.currentTimeMillis() >= deadline) {
                shNote(idx, "c deadline")
                return null
            }
            val headers = navHeaders(mainUrl + SHOW_PATH).toMutableMap()
            headers["Range"] = "bytes=0-0"
            shCookieHeader(jar)?.let { headers["Cookie"] = it }
            val resp = runCatching {
                app.get(current, headers = headers, timeout = HTTP_TIMEOUT_S, allowRedirects = false)
            }.getOrNull()
            if (resp == null) {
                Log.i(TAG, "clicka: GET fallito (rete/timeout) $current")
                shNote(idx, "c RETE")
                return null
            }
            jar.putAll(resp.cookies)
            when {
                resp.code in 300..399 -> {
                    val loc = resp.headers.values("location").firstOrNull()?.trim()
                    if (loc.isNullOrEmpty()) {
                        shNote(idx, "c noLoc")
                        return null
                    }
                    current = runCatching { URI(current).resolve(loc).toString() }
                        .getOrNull() ?: run { shNote(idx, "c badLoc"); return null }
                }
                resp.code == 200 -> {
                    reached = true
                }
                else -> {
                    Log.i(TAG, "clicka: GET HTTP ${resp.code} su $current")
                    shNote(idx, "c ${resp.code}")
                    return null
                }
            }
            if (reached) break
        }

        // Atterrati direttamente su un host video? Nessun POST necessario.
        val landingHost = runCatching { URI(current).host ?: "" }.getOrDefault("")
        if (landingHost.contains("safego", ignoreCase = true) ||
            landingHost.contains("clicka", ignoreCase = true)
        ) {
            shNote(idx, "c\u2192" + shTag(current))
        } else {
            shNote(idx, "c\u2192" + shTag(current))
            return current
        }

        // 2) POST della landing safego/clicka (flusso real_page MammaMia):
        //    Firefox + Origin/Referer same-site + cookie della GET
        for (postHop in 0 until 2) {
            if (System.currentTimeMillis() >= deadline) {
                shNote(idx, "c deadline")
                return null
            }
            val postHeaders = mutableMapOf(
                "User-Agent" to SH_FF_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to "https://$landingHost",
                "Referer" to current,
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
            )
            shCookieHeader(jar)?.let { postHeaders["Cookie"] = it }
            val resp = runCatching {
                app.post(
                    current,
                    headers = postHeaders,
                    requestBody = "".toRequestBody(
                        "application/x-www-form-urlencoded".toMediaTypeOrNull()
                    ),
                    timeout = HTTP_TIMEOUT_S,
                )
            }.getOrNull()
            if (resp == null) {
                shNote(idx, "cP RETE")
                return null
            }
            Log.i(TAG, "clicka: POST HTTP ${resp.code} su $current")
            shNote(idx, "cP ${resp.code}")
            if (resp.code != 200) return null
            jar.putAll(resp.cookies)
            val doc = resp.document

            // PRIMO anchor della pagina = link host video (esattamente MammaMia)
            val first = doc.selectFirst("a[href]")?.attr("abs:href")
            if (!first.isNullOrBlank() && first.startsWith("http")) {
                if (first.contains("safego", ignoreCase = true) ||
                    first.contains("clicka", ignoreCase = true)
                ) {
                    // ulteriore pagina protettore: si riposta sulla nuova landing
                    if (postHop == 0) {
                        current = first
                        continue
                    }
                } else {
                    shNote(idx, "c\u2192" + shTag(first))
                    shSafegoJar.putAll(jar)
                    return first
                }
            }

            // Captcha del protettore (immagine data URI): si risolve in locale
            // con l'OCR delle cifre (flusso MammaMia captch5, vedi sotto)
            if (doc.selectFirst("img[src^=data:image]") != null ||
                resp.text.contains("captch5", ignoreCase = true)
            ) {
                Log.i(TAG, "clicka: captcha del protettore -> OCR cifre locale")
                val solved = solveClickaCaptcha(idx, current, jar, deadline)
                if (solved != null) return solved
                return null
            }
            // Pagina 404 del protettore (cookie/sessione non valida)?
            if (resp.text.contains("The requested URL was not found", ignoreCase = true)) {
                shNote(idx, "c 404p")
                return null
            }
            // Redirect dentro l'HTML: si riposta sulla nuova destinazione
            if (postHop == 0) {
                val redir = htmlRedirect(resp.text)
                if (redir != null && redir.startsWith("http")) {
                    current = redir
                    continue
                }
            }
            shNote(idx, "c noURL")
            return null
        }
        shNote(idx, "c noURL")
        return null
    }

    /** Redirect dentro l'HTML: meta refresh o location.href/location.replace. */
    private fun htmlRedirect(html: String): String? {
        META_REFRESH_RE.find(html)?.groupValues?.get(1)?.trim()?.let { if (it.startsWith("http")) return it }
        JS_LOCATION_RE.find(html)?.groupValues?.get(1)?.trim()?.let { if (it.startsWith("http")) return it }
        return null
    }

    /**
     * Risolve il captcha del protettore safego/clicka in LOCALE, replicando il
     * flusso MammaMia (get_numbers + convert_numbers + POST captch5) senza
     * tesseract e senza WebView:
     * 1) GET della landing con User-Agent Firefox (senza Range): il
     *    protettore genera un captcha fresco legato ai cookie di sessione;
     * 2) la prima <img> con src data:image e' l'immagine del captcha;
     * 3) OCR cifre in puro Kotlin (ShOcr, template multi-font 12x16);
     * 4) POST captch5=<cifre> con cookie + Origin/Referer same-site:
     *    la risposta ha come PRIMO anchor il link dell'host video.
     * Fino a 3 tentativi (a ogni GET un captcha nuovo); la soglia di fiducia
     * dell'OCR si allarga a ogni giro (0.05 / 0.02 / 0.0): non si spediscono
     * risposte azzardate quando si puo' riprovare con un codice fresco.
     */
    private suspend fun solveClickaCaptcha(
        idx: Int,
        url: String,
        jar: MutableMap<String, String>,
        deadline: Long,
    ): String? {
        val gates = doubleArrayOf(0.05, 0.02, 0.0)
        var current = url
        for (attempt in gates.indices) {
            if (System.currentTimeMillis() >= deadline) {
                shNote(idx, "c deadline")
                return null
            }
            // 1) GET della landing: captcha fresco + cookie di sessione
            val getHeaders = mutableMapOf(
                "User-Agent" to SH_FF_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Upgrade-Insecure-Requests" to "1",
            )
            shCookieHeader(jar)?.let { getHeaders["Cookie"] = it }
            val getResp = runCatching {
                app.get(current, headers = getHeaders, timeout = HTTP_TIMEOUT_S)
            }.getOrNull()
            if (getResp == null) {
                shNote(idx, "cO RETE")
                return null
            }
            jar.putAll(getResp.cookies)
            if (getResp.code != 200) {
                shNote(idx, "cO ${getResp.code}")
                return null
            }
            // 2) immagine captcha: prima <img> con src data:image (formato MammaMia)
            val dataUri = getResp.document.selectFirst("img[src^=data:image]")?.attr("src")
            if (dataUri.isNullOrBlank() || !dataUri.contains(",")) {
                shNote(idx, "cO noimg")
                return null
            }
            // 3) OCR cifre locale (nessuna libreria esterna, nessun servizio)
            val ocr = runCatching { shOcrFromDataUri(dataUri) }.getOrNull()
            if (ocr == null || ocr.margin < gates[attempt]) {
                Log.i(TAG, "clicka captcha: lettura non sicura al tentativo ${attempt + 1}")
                shNote(idx, "cO?")
                continue
            }
            shNote(idx, "cO=${ocr.text}")
            // 4) POST captch5 (header del flusso real_page)
            val host = runCatching { URI(current).host ?: "" }.getOrDefault("")
            val postHeaders = mutableMapOf(
                "User-Agent" to SH_FF_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to "https://$host",
                "Referer" to current,
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
            )
            shCookieHeader(jar)?.let { postHeaders["Cookie"] = it }
            val resp = runCatching {
                app.post(
                    current,
                    headers = postHeaders,
                    requestBody = "captch5=${ocr.text}".toRequestBody(
                        "application/x-www-form-urlencoded".toMediaTypeOrNull()
                    ),
                    timeout = HTTP_TIMEOUT_S,
                )
            }.getOrNull()
            if (resp == null) {
                shNote(idx, "cP RETE")
                return null
            }
            jar.putAll(resp.cookies)
            Log.i(TAG, "clicka captcha: POST HTTP ${resp.code} (tentativo ${attempt + 1}, codice ${ocr.text})")
            shNote(idx, "cP ${resp.code}")
            if (resp.code != 200) return null
            val first = resp.document.selectFirst("a[href]")?.attr("abs:href")
            if (!first.isNullOrBlank() && first.startsWith("http")) {
                if (first.contains("safego", ignoreCase = true) ||
                    first.contains("clicka", ignoreCase = true)
                ) {
                    // ancora una landing del protettore: si riparte col GET
                    current = first
                    continue
                }
                shNote(idx, "c\u2192" + shTag(first))
                shSafegoJar.putAll(jar)
                return first
            }
            // risposta senza anchor: captcha sbagliato o scaduto, si riprova
        }
        shNote(idx, "cO KO")
        return null
    }

    /**
     * Da un data URI (immagine del captcha) all'OCR: base64 -> Bitmap
     * Android -> luminanza intera -> ShOcr.read (template 12x16).
     */
    private fun shOcrFromDataUri(dataUri: String): ShOcr.Res? {
        val b64 = dataUri.substringAfter(',', "").filter { !it.isWhitespace() }
        if (b64.isEmpty()) return null
        val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(b64) }
            .getOrNull() ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0 || w > 2000 || h > 2000) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return ShOcr.read(gray, w, h)
    }

    /** Manda l'URL finale all'estrattore dell'host giusto (o all'estrazione generica). */
    private suspend fun extractFinal(
        idx: Int,
        url: String,
        deadline: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // File video diretto: emesso subito, senza estrattore
        if (emitDirectLink(idx, url, callback)) return
        when {
            url.contains("maxstream", ignoreCase = true) -> {
                shNote(idx, "ex:ms")
                MaxStreamExtractor(deadline) { s -> shNote(idx, s) }
                    .getUrl(url, mainUrl, subtitleCallback, callback)
            }
            url.contains("mixdrop", ignoreCase = true) ||
                url.contains("mxdrop", ignoreCase = true) -> {
                shNote(idx, "ex:md")
                MixDropExtractor { s -> shNote(idx, s) }
                    .getUrl(url, mainUrl, subtitleCallback, callback)
            }
            url.contains("deltabit", ignoreCase = true) ||
                url.contains("turbovid", ignoreCase = true) -> {
                shNote(idx, "ex:db")
                DeltaBitExtractor(deadline) { s -> shNote(idx, s) }
                    .getUrl(url, mainUrl, subtitleCallback, callback)
            }
            else -> {
                // Host non noto: prova gli estrattori registrati dall'app, poi l'estrazione generica
                shNote(idx, "ex:app")
                val handled = runCatching {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }.getOrDefault(false)
                if (!handled) {
                    shNote(idx, "ex:gen")
                    selfExtract(idx, url, callback)
                }
            }
        }
    }

    /**
     * Estrazione generica ("ricava i m3u8 da solo"): scarica la pagina finale e
     * cerca al suo interno il file video con le stesse regex a cascata degli
     * estrattori dedicati. Copre i domini nuovi o cambiati senza toccare il codice.
     */
    private suspend fun selfExtract(idx: Int, url: String, callback: (ExtractorLink) -> Unit) {
        val resp = shGet(url, referer = mainUrl + SHOW_PATH)
        if (resp == null) {
            Log.i(TAG, "selfExtract: richiesta fallita $url")
            shNote(idx, "sx RETE")
            return
        }
        Log.i(TAG, "selfExtract: HTTP ${resp.code} su $url")
        shNote(idx, "sx ${resp.code}")
        if (resp.code != 200) return
        val body = resp.text
        shFindM3u8(body)?.let {
            Log.i(TAG, "selfExtract: m3u8 ricavato dalla pagina")
            emitDirectLink(idx, it, callback)
            return
        }
        MEDIA_RE.find(body)?.value?.let {
            shNote(idx, "sx media")
            emitDirectLink(idx, it, callback)
        }
    }

    /** Se l'URL e' un file video diretto lo emette senza estrattore; dice se l'ha fatto. */
    private suspend fun emitDirectLink(
        idx: Int,
        url: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val lower = url.substringBefore('#').substringBefore('?').lowercase()
        val type = when {
            lower.endsWith(".m3u8") -> com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
            lower.endsWith(".mp4") || lower.endsWith(".mkv") ->
                com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
            else -> return false
        }
        shNote(idx, "EMIT")
        callback(
            com.lagradost.cloudstream3.utils.newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = type,
            ) {
                this.referer = ""
                this.quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
            }
        )
        return true
    }
}
