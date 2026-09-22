<p align="center">
  <img src="docs/banner.png" alt="RetroCinema" width="720"/>
</p>

# RetroCinema 🎞️

**Il cinema di una volta, in una sola app.** Una repository di estensioni [CloudStream](https://github.com/recloudstream/cloudstream) curata per chi ama i film classici: commedie all'italiana, musical, western, film noir e capolavori del dominio pubblico — **tutto gratuito, senza pubblicità e semplice da usare**.

Sviluppata per essere **veloce, completa e alla portata di tutti**: le righe della home sono pronte all'uso, come le file di poster di Netflix, senza dover cercare niente.

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

### Passo 4 — Installa i plugin
Nell'elenco che compare, tocca **Installa** su tutti e tre:

| Plugin | Cosa contiene |
|---|---|
| 🎞️ **Internet Archive** | Migliaia di film classici in dominio pubblico: muto, commedie, musical, western e film noir, divisi per decennio |
| 🇮🇹 **RaiPlay** | Commedia all'italiana (Totò, Sordi, Gassman), Grandi Classici di Hollywood, Stanlio e Ollio, Teche Rai — tutto legale e in italiano |
| 🍿 **StreamingCommunity** | Film e serie più recenti (se un giorno smette di funzionare, si aggiorna da qui) |

### Passo 5 — Torna alla Home
Premi il tasto **None** (o il pulsante di category) in basso a destra nella home, scegli **Film** e il gioco è fatto: le file di film d'epoca scorrono come su Netflix. 🍿

---

## 🏠 Cosa trovi in Home

| Riga | Contenuto |
|---|---|
| **I più visti del momento** | I film classici più scaricati su Internet Archive |
| **Commedia all'italiana** | I soliti ignoti, Pane amore e fantasia, Totò, Divorzio all'italiana, Il Gattopardo… |
| **Classici di Hollywood** | Gilda, La signora del venerdì, Funny Girl, Da qui all'eternità… |
| **Stanlio e Ollio** | 26 film della coppia più amata del cinema comico |
| **Musical** | Fred Astaire, Judy Garland e i grandi musical del dominio pubblico |
| **Film Noir** | I detective, le femme fatale e l'ombra degli anni '40–'50 |
| **Western** | John Wayne e i duelli all'alba |
| **Classici italiani** | Il neorealismo e il cinema italiano del dominio pubblico |
| **Anni '30 / '40 / '50 / '60** | Un viaggio per decennio, uno scorrimento per anno |
| **Film del momento / Le Teche Rai** | Novità e tesori d'archivio di RaiPlay |

---

## 🆘 Problemi comuni

**"Non riesco ad aggiungere la repository"** — Alcuni gestori telefonici italiani bloccano gli indirizzi `raw.githubusercontent.com`. Attiva una **VPN qualsiasi** sul telefono solo per il momento in cui aggiungi la repository, poi la puoi disattivare.

**"Un film non parte"** — Alcuni titoli di RaiPlay o StreamingCommunity vanno e vengono (i diritti scadono e ritornano). Prova un altro film della stessa riga: i cataloghi sono pieni.

**"Il plugin StreamingCommunity non funziona più"** — Quei siti cambiano indirizzo di frequente. Controlla qui su GitHub se c'è una versione nuova del plugin, poi nell'app: *Impostazioni → Estensioni → aggiorna*.

**"I plugin non si aggiornano"** — Nell'app: *Impostazioni → Estensioni → (tocco lungo sul plugin) → Aggiorna*. Gli aggiornamenti sono automatici di default.

---

## 🛠️ Per sviluppatori

### Struttura (template ufficiale [recloudstream/TestPlugins](https://github.com/recloudstream/TestPlugins))

```
retrocinema/
├── build.gradle.kts            ← configura tutti i moduli (JitPack: com.github.recloudstream:gradle)
├── settings.gradle.kts         ← include automaticamente ogni cartella-plugin
├── repo.json                   ← il "catalogo" che l'app legge
├── .github/workflows/build.yml ← compilazione automatica a ogni push
├── InternetArchive/            ← un modulo = un plugin .cs3
├── RaiPlay/
└── StreamingCommunity/         ← adattato da doGior/doGiorsHadEnough
```

### Build automatica
Ogni push su `main`/`master` avvia **GitHub Actions**: il workflow esegue `./gradlew make makePluginsJson`, copia i `.cs3` e il `plugins.json` generato nel branch **`builds`**. Il `repo.json` punta lì: l'app scarica sempre l'ultima build, con **aggiornamento automatico** dei plugin (basta incrementare `version` nel `build.gradle.kts` del modulo).

### Installazione per provare la tua build
```
https://raw.githubusercontent.com/antonydp/retrocinema/builds/plugins.json
```

### Come curare i contenuti
- **RaiPlay**: le righe "Commedia all'italiana" e "Classici di Hollywood" sono liste di slug verificati in `RaiPlayProvider.kt` (`COMEDIE_ITALIANE`, `CLASSICI_HOLLYWOOD`). I titoli rimossi da RaiPlay vengono saltati automaticamente.
- **Internet Archive**: ogni riga è una query Lucene su `collection:(feature_films)` ordinata per `downloads desc`.
- **StreamingCommunity**: gestisce da solo il cambio di dominio (codice di [doGior](https://github.com/doGior/doGiorsHadEnough)).

### Note tecniche verificate sul campo (settembre 2026)
- **RaiPlay**: `/programmi/<slug>.json` → `first_item_path` → `/video/…json` → `video.content_url` → append `&output=71` = **playlist HLS m3u8**.
- **Internet Archive**: `advancedsearch.php` (query Lucene) → `metadata/<id>` → link fisici `https://<server><dir>/<file>` (solo MPEG4/H.264/Matroska/DivX; Ogg Video escluso perché ExoPlayer non riproduce Theora).
- La ricerca RaiPlay sfrutta la pagina SSR `ricerca.html?q=` (selettori `div.card-item[data-info-url]`).

### Crediti e licenza
- Provider **StreamingCommunity** adattato dal codice di **doGior** ([doGiorsHadEnough](https://github.com/doGior/doGiorsHadEnough)) — grazie alla convenzione di condivisione della community.
- Pattern del provider Internet Archive ispirato al provider ufficiale di **Luna712** ([recloudstream/extensions](https://github.com/recloudstream/extensions)).
- Progetto rilasciato con **licenza GPL-3.0** (vedi `LICENSE`).

### Nota legale
Le estensioni funzionano come un normale browser: non ospitano alcun contenuto, si limitano a collegarsi a fonti che pubblicano i film legittimamente (Internet Archive e RaiPlay sono fonti pubbliche e gratuite). Lo StreamingCommunity è incluso su richiesta dell'utente; per un uso sereno e familiare si consigliano le fonti legali dei primi due plugin.
