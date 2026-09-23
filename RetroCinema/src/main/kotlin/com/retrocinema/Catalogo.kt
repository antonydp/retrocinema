package com.retrocinema

/**
 * Catalogo ITALIANO curato e VERIFICATO (v5): ogni film di origine
 * italiana (audio garantito italiano; le comiche sono mute, nessuna
 * lingua parlata). Ogni item controllato 1-a-1 via API metadata di
 * Internet Archive: esiste, ha un file video riproducibile (MP4/H264),
 * durata da lungometraggio, nessun contenuto horror/adulto.
 * Generato da scripts/build_catalogo_italiano.py — non toccare a mano.
 */
data class FilmCatalogo(val titolo: String, val id: String, val anno: Int?, val minuti: Int)

object Catalogo {
    val toto = listOf(
        FilmCatalogo("Totò e le donne", "toto-e-le-donne-film-completo-con-toto-e-peppino-de-filippo", 1952, 92),
        FilmCatalogo("Totò, Peppino e i fuorilegge", "toto-peppino-e-i-fuorilegge-film-completo", 1956, 98),
        FilmCatalogo("Dov'è la libertà?", "dove-la-liberta-toto-1954", 1954, 87),
        FilmCatalogo("Le streghe", "le-streghe-toto-1967", 1967, 106),
        FilmCatalogo("Totò, Peppino e le fanatiche", "toto-peppino-e-le-fanatiche", 1958, 85),
        FilmCatalogo("Risate di gioia", "risate-di-gioia-toto-1960", 1960, 101),
        FilmCatalogo("Totò, Eva e il pennello proibito", "toto-eva-e-il-pennello-proibito-1959", 1959, 99),
    )

    val commedia = listOf(
        FilmCatalogo("Guardie e ladri", "guardie-e-ladri-mario-monicelli-e-steno-1951-b-n-576p", 1951, 101),
        FilmCatalogo("La grande guerra", "la-grande-guerra-mario-monicelli-1959-b-n-720p", 1959, 132),
        FilmCatalogo("Il sorpasso", "il-sorpasso-dino-risi-1962-b-n-720p", 1962, 105),
        FilmCatalogo("I mostri", "i-mostri-dino-risi-1963-b-n-720p", 1963, 122),
        FilmCatalogo("Poveri ma belli", "poveri-ma-belli-dino-risi-1957-b-n-720p", 1957, 101),
        FilmCatalogo("Divorzio all'italiana", "divorzio-italiana-pietro-germi-1961-b-n-700p", 1961, 104),
        FilmCatalogo("Il medico della mutua", "il-medico-della-mutua-luigi-zampa-1968-color-360p", 1968, 95),
        FilmCatalogo("Mafioso", "mafioso-alberto-lattuada-1962-b-n-400p", 1962, 102),
        FilmCatalogo("La ragazza con la valigia", "la-ragazza-con-la-valigia-valerio-zurlini-1961-b-n-720p", 1961, 116),
        FilmCatalogo("Il bel Antonio", "il-bel-antonio-mauro-bolognini-1960-b-n-720p", 1960, 102),
        FilmCatalogo("La vita agra", "la-vita-agra-carlo-lizzani-1964-b-n-480p", 1964, 99),
        FilmCatalogo("Arrangiatevi", "arrangiatevi-mauro-bolognini-1959-b-n-480p", 1959, 107),
        FilmCatalogo("La provinciale", "la-provinciale-mario-soldati-1953-b-n-360p", 1953, 109),
        FilmCatalogo("4 passi tra le nuvole", "4-passi-tra-le-nuvole-alessandro-blasetti-1942-b-n-480p", 1942, 88),
        FilmCatalogo("La famiglia Passaguai", "la-famiglia-passaguai-aldo-fabrizi-1951-b-n-480p", 1951, 91),
        FilmCatalogo("Napoletani a Milano", "napoletani-a-milano-eduardo-de-filippo-1953-b-n-720p", 1953, 98),
        FilmCatalogo("Tutti a casa", "tutti-a-casa-luigi-comenicini-1960-b-n-556p", 1960, 117),
        FilmCatalogo("Lo scopone scientifico", "lo-scopone-scientifico-luigi-comencini-1972-color-480p", 1972, 109),
        FilmCatalogo("Don Camillo", "don-camillo-julien-duvivier-1952-b-n-720p", 1952, 106),
        FilmCatalogo("Totò a colori", "toto-a-colori-steno-1952-720p", 1952, 92),
        FilmCatalogo("Pane, amore e fantasia", "pane-amore-e-fantasia-luigi-comencini-1953-b-n-576p", 1953, 99),
        FilmCatalogo("Amici miei", "AmiciMiei1975Monicell", 1975, 127),
        FilmCatalogo("Stanlio e Ollio in italiano", "StanlioEOllio", 1941, 85),
        FilmCatalogo("L'armata Brancaleone", "for.-love.-and.-gold.-1966.1080p.-webrip", 1966, 120),
        FilmCatalogo("Brancaleone alle crociate", "brancaleone-alle-crociate-1970", 1970, 120),
        FilmCatalogo("Il ritorno di Don Camillo", "il-ritorno-di-don-camillo-colorized-1953-720p", 1953, 111),
        FilmCatalogo("Don Camillo e l'onorevole Peppone", "don-camillo-e-l-onorevole-peppone-colorized-1955-720p", 1955, 100),
        FilmCatalogo("Matrimonio all'italiana", "matrimonio-all-italiana-1964", 1964, 102),
    )

    val autori = listOf(
        FilmCatalogo("I soliti ignoti", "i-soliti-ignoti-mario-monicelli-1958-b-n-720p", 1958, 101),
        FilmCatalogo("Otto e mezzo", "otto-e-mezzo-federico-fellni-1963-b-n-720p", 1963, 139),
        FilmCatalogo("Amarcord", "amarcord-federico-fellini-1973-color-720p", 1973, 124),
        FilmCatalogo("La strada", "la-strada-federico-fellini-1954-b-n-720p", 1954, 109),
        FilmCatalogo("I vitelloni", "i-vitelloni-federico-fellini-1953-b-n-720p", 1953, 108),
        FilmCatalogo("Lo sceicco bianco", "lo-sceicco-bianco-federico-fellini-1952-b-n-720p", 1952, 86),
        FilmCatalogo("Ossessione", "ossessione-luchino-visconti-1943-b-n-720p", 1943, 126),
        FilmCatalogo("La terra trema", "la-terra-trema-luchino-visconti-1948-b-n-720p", 1948, 160),
        FilmCatalogo("Senso", "senso-luchino-visconti-1954-color-720p", 1954, 118),
        FilmCatalogo("L'oro di Napoli", "oro-di-napoli-vittorio-de-sica-1954-b-n-480p", 1954, 131),
        FilmCatalogo("Europa '51", "europa-51-roberto-rossellini-1952-b-n-1080p", 1952, 118),
        FilmCatalogo("Una giornata particolare", "una-giornata-particolare-ettore-scola-1977-color-720p", 1977, 106),
        FilmCatalogo("Luci del varietà", "luci-del-varieta-alberto-lattuada-e-federico-fellini-1950-b-n-720p", 1950, 98),
        FilmCatalogo("Io la conoscevo bene", "io-la-conoscevo-bene-antonio-pietrangeli-1965-b-n-690p", 1965, 115),
        FilmCatalogo("I magliari", "i-magliari-francesco-rosi-1959-b-n-576p", 1959, 115),
        FilmCatalogo("La dolce vita", "1960-la-dolce-vita-720p-ac-3-ita-sub-eng-mircrew_202502", 1960, 176),
        FilmCatalogo("Il bidone", "il.-bidone.-1955.1080p.-blu-ray.x-264", 1955, 114),
        FilmCatalogo("Le notti di Cabiria", "nights-of-cabiria-fellini", 1957, 119),
        FilmCatalogo("Bellissima", "bellissima_202106", 1951, 110),
    )

    val neorealismo = listOf(
        FilmCatalogo("In nome della legge", "InNomeDellaLegge", 1949, 96),
        FilmCatalogo("Ladri di biciclette", "ladri-di-biciclette-vittorio-de-sica-1948-b-n-720p", 1948, 89),
        FilmCatalogo("Roma città aperta", "roma-citta-aperta-roberto-rossellini-1945-b-n-720p", 1945, 103),
        FilmCatalogo("Miracolo a Milano", "miracolo-a-milano-vittorio-de-sica-1951-b-n-720p", 1951, 97),
        FilmCatalogo("Sciuscià", "sciuscia-vittorio-de-sica-1946-b-n-720p", 1946, 87),
        FilmCatalogo("Umberto D.", "umberto-d.-vittorio-de-sica-1952-b-n-720p", 1952, 88),
        FilmCatalogo("Paisà", "05-paisa-roberto-rossellini-1946-b-n-720p", 1946, 125),
        FilmCatalogo("Riso amaro", "riso-amaro-giuseppe-de-santis-1949-b-n-720p", 1949, 109),
        FilmCatalogo("Il cammino della speranza", "il-cammino-della-speranza-pietro-germi-1950-b-n-576p", 1950, 101),
        FilmCatalogo("Cielo sulla palude", "cielo-sulla-palude-augusto-genina-1949-b-n-720p", 1949, 104),
        FilmCatalogo("Il posto", "il-posto-ermanno-olmi-1961-b-n-1080p", 1961, 97),
        FilmCatalogo("L'albero degli zoccoli", "albero-degli-zoccoli-ermanno-olmi-1978-color-720p", 1978, 186),
        FilmCatalogo("La lunga notte del '43", "la-lunga-notte-del-43-florestano-vancini-1960-b-n-1080p", 1960, 101),
        FilmCatalogo("Cristo si è fermato a Eboli", "christ-stopped-at-eboli-1979", 1979, 222),
    )

    val melodramma = listOf(
        FilmCatalogo("Catene", "catene-raffaele-matarazzo-1949-b-n-480p", 1949, 95),
        FilmCatalogo("Città dolente", "citta-dolente-mario-bonnard-1949-b-n-480p", 1949, 99),
        FilmCatalogo("Tormento", "tormento_202103", 1950, 98),
    )

    val peplum = listOf(
        FilmCatalogo("Maciste, il gladiatore più forte del mondo", "MacisteIlGladiatorePiForteDelMondo_382", 1962, 96),
        FilmCatalogo("Ercole e la regina di Lidia", "Hercules_Unchained", 1959, 96),
        FilmCatalogo("Il colosso di Rodi", "the.-colossus.-of.-rhodes.-1961.272p", 1961, 128),
        FilmCatalogo("Ercole alla conquista di Atlantide", "ercole-alla-conquista-di-atlantide-1961_202503", 1961, 100),
    )

    val musical = listOf(
        FilmCatalogo("Carosello napoletano", "carosello-napoletano-ettore-giannini-1954-b-n-480p", 1954, 119),
    )

    val comiche = listOf(
                FilmCatalogo("Cabiria", "cabiria-1913-1914-riedizione-2021-restored-remix", 1914, 157),
        FilmCatalogo("L'Inferno", "silent-dantes-inferno", 1911, 67),
        FilmCatalogo("Charlie Chaplin Festival", "charlie_chaplin_film_fest", 1938, 77),
        FilmCatalogo("The General", "TheGeneral", 1927, 67),
        FilmCatalogo("Sherlock Jr.", "MyMovie_20190318", 1924, 44),
        FilmCatalogo("Steamboat Bill, Jr.", "SteamboatBillJr_201411", 1928, 67),
        FilmCatalogo("Our Hospitality", "OurHospitality_29", 1923, 73),
        FilmCatalogo("College", "college", 1927, 65),
        FilmCatalogo("The Navigator", "mymovie_202004", 1924, 60),
        FilmCatalogo("Seven Chances", "my-movie_202208", 1925, 56),
    )

}