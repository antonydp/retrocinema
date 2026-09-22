<p align="center">
  <img src="docs/banner.png" alt="RetroCinema" width="720"/>
</p>

# RetroCinema 🎞️

**Il cinema di una volta, in UN SOLO plugin.** Estensione [CloudStream](https://github.com/recloudstream/cloudstream) curata per chi ama i film classici: commedie all'italiana, musical, western, film noir e capolavori — **tutto gratuito, legale, senza pubblicità e semplice da usare**.

Un solo plugin da installare, una sola home piena di film: le righe scorrono come le file di poster di Netflix, senza dover cercare niente.

> ✅ **Contenuti selezionati a mano** — solo lungometraggi adatti a tutta la famiglia.
> ❌ **Niente horror, niente serie TV, niente contenuti adulti.**

---

## 📲 Installazione (3 minuti, una volta sola)

> Serve prima l'app **CloudStream** (versione *pre-release* consigliata): scaricala da [recloudstream.cloud](https://recloudstream.cloud) o dalle release GitHub ufficiali.

### Passo 1 — Apri le impostazioni
Nell'app tocca in alto a destra l'icona dell'**ingranaggio** ⚙️ (Impostazioni).

### Passo 2 — Vai su Estensioni
Tocca la voce **Estensioni** (o *Extensions*).

### Passo 3 — Aggiungi la repository
Tocca **Aggiungi repository** e incolla questo indirizzo esatto:

```
https://raw.githubusercontent.com/antonydp/retrocinema/main/repo.json
```

poi premi **Aggiungi repository** e attendi qualche secondo.

### Passo 4 — Installa RetroCinema
Nell'elenco compare **un solo plugin: RetroCinema**. Tocca **Installa** e poi **OK**.

### Passo 5 — Torna alla Home
Tocca il nome del fornitore in alto sulla home (o il pulsante delle categorie), scegli **RetroCinema**: le file di film d'epoca scorrono come su Netflix. 🍿

---

## 🏠 Cosa trovi in Home (14 righe curate + scroll infinito)

| Riga | Contenuto |
|---|---|
| **Da vedere assolutamente** | I 16 migliori film del catalogo verificato: Amarcord, La ciociara, Boccaccio '70, La grande guerra… |
| **Grandi film italiani** | 30 capolavori verificati: Fellini, De Sica, Visconti, Monicelli, Sordi, Totò, Gassman |
| **Grandi Classici di Hollywood** | Su RaiPlay: Gilda, La signora del venerdì, Da qui all'eternità, Funny Girl… |
| **Stanlio e Ollio restaurati** | 26 film della coppia più amata, restaurati e in italiano |
| **Comiche: Chaplin, Keaton e Stanlio e Ollio** | I capolavori del cinema muto che fanno ridere tutti |
| **Western** | 30 film verificati: John Wayne, duelli all'alba e grandi spazi |
| **Film Noir e Gangster** | 30 film verificati: detective, femme fatale e gangster anni '40–'50 |
| **Musical** | 30 film verificati: Astaire, Garland, Gene Kelly, Bing Crosby |
| **Avventura e Grande Storia** | Tarzan, Il libro della giungla, avventure d'altri tempi |
| **Il grande cinema su RaiPlay** | Il Gattopardo, La piscina, Gruppo di famiglia in un interno… |
| **Anni '40 / '50 / '60** | Un viaggio per decennio — **scrolla fino in fondo: arrivano altri film** |
| **Scopri film sempre nuovi** | Il resto del catalogo, ordinato per popolarità — **scroll infinito** |

Oltre **180 film verificati uno a uno** (esistono, sono davvero film, e hanno un file video riproducibile) + infinite pagine di scoperta via scroll.

La **ricerca** cerca contemporaneamente su RaiPlay e Internet Archive e **continua a caricare risultati mentre scorri**.

---

## 🆘 Problemi comuni

**"Non riesco ad aggiungere la repository"** — Alcuni gestori telefonici italiani bloccano gli indirizzi `raw.githubusercontent.com`. Attiva una **VPN qualsiasi** sul telefono solo per il momento in cui aggiungi la repository, poi la puoi disattivare.

**"Un film non parte"** — I titoli di RaiPlay vanno e vengono (i diritti scadono e ritornano). Prova un altro film della stessa riga: i cataloghi sono pieni. Su Internet Archive, se un film non parte prova il link con qualità più bassa (es. 480p).

**"Il plugin non si aggiorna"** — Nell'app: *Impostazioni → Estensioni → (tocco lungo su RetroCinema) → Aggiorna*. Gli aggiornamenti sono automatici di default.

---

## 🛠️ Per sviluppatori

### Struttura (template ufficiale [recloudstream/TestPlugins](https://github.com/recloudstream/TestPlugins))

```
retrocinema/
├── build.gradle.kts            ← configura i moduli (JitPack: com.github.recloudstream:gradle)
├── settings.gradle.kts         ← include automaticamente ogni cartella-plugin
├── repo.json                   ← il "catalogo" che l'app legge
├── .github/workflows/build.yml ← compilazione automatica a ogni push
└── RetroCinema/                ← UN solo modulo = UN solo plugin .cs3
    └── src/main/kotlin/com/retrocinema/
        ├── RetroCinemaProvider.kt   ← tutta la logica (home, ricerca, load, link)
        ├── Catalogo.kt              ← catalogo verificato generato da script
        └── RetroCinemaPlugin.kt     ← registrazione del provider
```

### Build automatica
Ogni push su `main`/`master` avvia **GitHub Actions**: il workflow esegue `./gradlew make makePluginsJson`, copia i `.cs3` e il `plugins.json` generato nel branch **`builds`**. Il `repo.json` punta lì: l'app scarica sempre l'ultima build, con **aggiornamento automatico** (basta incrementare `version` nel `build.gradle.kts` del modulo).

### Come sono scelti i contenuti
- **Catalogo verificato (181 film)**: ogni film è stato controllato automaticamente via API `metadata` di Internet Archive prima di entrare nel catalogo — deve esistere, essere un film (mediatype movies), avere un file MP4/MKV riproducibile da ExoPlayer e superare i filtri anti-horror. Titoli e anni vengono puliti dallo spam degli uploader.
- **RaiPlay** (legale, ufficiale, tutto in italiano): le righe usano le collezioni editoriali verificate `grandiclassicidihollywood`, `stanlioeollio-edizionirestaurate`, `ilgrandecinema` — si aggiornano da sole quando Rai cambia il catalogo. Gli item con genere *horror/erotico* vengono filtrati nel codice.
- **Internet Archive dinamico**: le righe decenni e "Scopri film sempre nuovi" sono query Lucene su `collection:(feature_films)` con `NOT subject:(horror)`, ordinate per `downloads desc`, con **paginazione nativa (scroll infinito, fino a 8 pagine)**.

### Note tecniche verificate sul campo (settembre 2026)
- **RaiPlay**: le collezioni hanno due forme (`contents[].contents[]` e `blocks[].sets[].path_id` → ContentSet JSON): il parser le attraversa entrambe in modo ricorsivo.
- **RaiPlay stream**: `/programmi/<slug>.json` → `first_item_path` → `/video/…json` → `video.content_url` (relinker) → `&output=71` = **playlist HLS m3u8**, con `Referer: https://www.raiplay.it` + **sottotitoli SRT italiani** da `subtitleList`.
- **Internet Archive stream**: NON si usa l'estrattore interno `archive.org/details` di CloudStream (ha un selettore CSS malformato e lascia il player in caricamento infinito). Il plugin chiama `metadata/<id>` e costruisce **link diretti** `https://<server><dir>/<file>` (solo MPEG4/H.264/Matroska/DivX, nomi URL-encoded, niente Ogg/Theora né duplicati "IA"), con fallback su `/download/` e **sottotitoli .vtt** dell'item passati via `subtitleCallback`.
- **Paginazione**: `getMainPage(page, request)` con `hasNext=true` per le righe dinamiche IA; `search(query, page)` sovrascritto con `SearchResponseList` per la ricerca a scroll infinito.
- **Ricerca RaiPlay**: pagina SSR `ricerca.html?q=` (card con `data-info-url`/`data-video-json` + `aria-label`).

### Crediti e licenza
- Pattern del provider Internet Archive dal provider ufficiale di **Luna712** ([recloudstream/extensions](https://github.com/recloudstream/extensions)).
- Progetto rilasciato con **licenza GPL-3.0** (vedi `LICENSE`).

### Nota legale
Le estensioni funzionano come un normale browser: non ospitano alcun contenuto, si limitano a collegarsi a fonti che pubblicano i film legittimamente. **Internet Archive** (dominio pubblico) e **RaiPlay** (servizio pubblico Rai) sono fonti pubbliche, legali e gratuite.
