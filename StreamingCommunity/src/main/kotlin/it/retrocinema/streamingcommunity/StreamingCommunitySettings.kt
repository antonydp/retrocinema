package it.retrocinema.streamingcommunity

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Menu impostazioni del plugin: riorganizza e disattiva le sezioni della home.
 *
 * Si apre dal tasto impostazioni del provider in home (HomeFragment chiama
 * Plugin.openSettings). UI costruita via codice, senza risorse XML.
 *
 * - Le sezioni attive sono in alto, NELL'ORDINE SCELTRO TRASCINANDOLE
 *   (tieni premuto la riga o trascina la maniglia ☰; vicino ai bordi
 *   l'elenco scorre da solo)
 * - Deseleziona una casella per nascondere la sezione
 * - I preset riempiono subito l'elenco (poi puoi rifinire a mano)
 * - Salva scrive la lista in DataStore e invalida la cache del provider
 *
 * Il trascinamento usa il Drag&Drop di sistema (View.startDrag + OnDragListener):
 * compatibile con tutte le API supportate dall'app e senza librerie esterne.
 */
fun showScSectionsDialog(context: Context) {
    // Stato editabile: attive (in ordine) e disattivate (ordine del catalogo)
    val rows = ScSections.loadOrder(context).toMutableList()

    val listHost = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    val scroll = ScrollView(context).apply {
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        addView(listHost)
    }

    // ------------------------------------------------------------------
    //  Auto-scroll dell'elenco mentre si trascina vicino ai bordi:
    //  con 24 sezioni molte righe non sono visibili, senza scroll durante
    //  il drag una sezione in fondo non potrebbe mai raggiungere la cima.
    //  Gli eventi arrivano qui dalle righe (che ritornano false per
    //  ACTION_DRAG_LOCATION e lasciano "salire" l'evento al ScrollView).
    // ------------------------------------------------------------------
    scroll.setOnDragListener { v, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.label == SC_DRAG_LABEL
            DragEvent.ACTION_DRAG_LOCATION -> {
                val h = v.height
                if (h > 0) {
                    val edge = (h / 5).coerceAtLeast(24)
                    val y = event.getY().toInt()
                    when {
                        y < edge -> scroll.smoothScrollBy(0, -(edge - y).coerceAtLeast(14))
                        y > h - edge -> scroll.smoothScrollBy(0, (y - (h - edge)).coerceAtLeast(14))
                    }
                }
                true
            }
            // Un drop su area vuota non fa nulla, ma chiude il drag
            DragEvent.ACTION_DROP -> true
            else -> false
        }
    }

    /** Sposta la sezione trascinata nella posizione della riga di destinazione. */
    fun moveSection(draggedId: String, targetId: String) {
        if (draggedId == targetId) return
        val from = rows.indexOfFirst { it.id == draggedId }
        val to = rows.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0) return
        val item = rows.removeAt(from)
        // dopo la rimozione gli indici sotto "from" slittano di uno
        rows.add(if (from < to) to - 1 else to, item)
    }

    fun rebuild() {
        listHost.removeAllViews()

        listHost.addView(TextView(context).apply {
            text = "Preset rapidi:"
            setPadding(0, context.dp(4), 0, context.dp(4))
        })

        val presets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Standard" to ScSections.presetStandard(),
            "Famiglia" to ScSections.presetFamiglia(),
            "Solo film" to ScSections.presetSoloFilm(),
            "Solo serie" to ScSections.presetSoloSerie(),
        ).forEach { (label, preset) ->
            presets.addView(Button(context).apply {
                text = label
                setOnClickListener {
                    rows.clear()
                    rows.addAll(preset)
                    rebuild()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        presets.setPadding(0, 0, 0, context.dp(6))
        listHost.addView(presets)

        listHost.addView(TextView(context).apply {
            text = "Sezioni attive — trascina ☰ (o tieni premuta la riga) per riordinare, deseleziona per nascondere:"
            setPadding(0, context.dp(8), 0, context.dp(4))
        })

        rows.forEach { section ->
            listHost.addView(dragSectionRow(
                context = context,
                section = section,
                onDropOn = { draggedId ->
                    moveSection(draggedId, section.id)
                    rebuild()
                },
                onHide = {
                    rows.removeAll { it.id == section.id }
                    rebuild()
                },
            ))
        }

        listHost.addView(TextView(context).apply {
            text = "Nascoste (spunta per rimetterle in fondo):"
            setPadding(0, context.dp(12), 0, context.dp(4))
        })

        val disabled = ScSections.DEFAULT.filter { def -> rows.none { it.id == def.id } }
        disabled.forEach { section ->
            listHost.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(2), 0, context.dp(2))
                addView(CheckBox(context).apply {
                    text = section.label
                    isChecked = false
                    setOnCheckedChangeListener { _, isCheckedNow ->
                        if (isCheckedNow) {
                            rows.add(section) // entra in fondo, poi la trascini dove vuoi
                            rebuild()
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        }

        listHost.addView(TextView(context).apply {
            text = "Le modifiche valgono premendo SALVA."
            setPadding(0, context.dp(10), 0, 0)
        })
    }

    val builder = AlertDialog.Builder(context)
        .setTitle("StreamingCommunity — Sezioni in home")
        .setView(scroll)
        .setPositiveButton("Salva") { _, _ ->
            ScSections.save(context, rows.toList())
            StreamingCommunity.invalidateSections()
        }
        .setNegativeButton("Annulla", null)
        .setNeutralButton("Ripristina") { _, _ ->
            ScSections.reset(context)
            StreamingCommunity.invalidateSections()
        }

    rebuild()
    builder.show()
}

/**
 * Riga attiva: [☰] [casella + nome]. Il trascinamento parte dalla maniglia
 * (basta un piccolo movimento) o tenendo premuta la riga; la riga su cui si
 * rilascia evidenzia la destinazione e riceve il drop.
 */
private fun dragSectionRow(
    context: Context,
    section: ScSection,
    onDropOn: (String) -> Unit,
    onHide: () -> Unit,
): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
    }

    // Maniglia ☰: piccoli movimenti avviano subito il trascinamento
    val handle = TextView(context).apply {
        text = "☰"
        textSize = 20f
        setPadding(context.dp(6), 0, context.dp(10), 0)
        var downX = 0f
        var downY = 0f
        var started = false
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.getX()
                    downY = event.getY()
                    started = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.getX() - downX)
                    val dy = Math.abs(event.getY() - downY)
                    if (!started && (dx > 14 || dy > 14)) {
                        started = true
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        startRowDrag(row, section.id)
                    }
                    started
                }
                else -> false
            }
        }
    }
    row.addView(handle)

    row.addView(CheckBox(context).apply {
        text = section.label
        isChecked = true
        setOnCheckedChangeListener { _, isCheckedNow ->
            if (!isCheckedNow) onHide()
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })

    // Tenere premuta la riga (fuori dalla casella) trascina pure
    row.isLongClickable = true
    row.setOnLongClickListener { v ->
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startRowDrag(v, section.id)
        true
    }

    row.setOnDragListener { v, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED ->
                // accetta solo i nostri drag (le etichette combaciano)
                event.clipDescription?.label == SC_DRAG_LABEL
            DragEvent.ACTION_DRAG_ENTERED -> {
                v.alpha = 0.4f
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                v.alpha = 1f
                true
            }
            DragEvent.ACTION_DROP -> {
                v.alpha = 1f
                val draggedId = event.clipData?.getItemAt(0)?.text?.toString().orEmpty()
                if (draggedId.isNotEmpty()) onDropOn(draggedId)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                v.alpha = 1f
                true
            }
            // LOCATION non gestita: "sale" al ScrollView per l'auto-scroll
            else -> false
        }
    }
    return row
}

/** Avvia il trascinamento della riga con l'id della sezione nel ClipData. */
private fun startRowDrag(row: View, sectionId: String) {
    @Suppress("DEPRECATION") // startDragAndDrop richiede API 24: startDrag va ovunque
    row.startDrag(
        ClipData.newPlainText(SC_DRAG_LABEL, sectionId),
        View.DragShadowBuilder(row),
        null,
        0,
    )
}

/** Etichetta del ClipData: i drag listener accettano solo i nostri drag. */
private const val SC_DRAG_LABEL = "sc_section_reorder"

/** Converte dp in pixel (piccoli spazi). */
private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()
