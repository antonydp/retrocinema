package it.retrocinema.streamingcommunity

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Collections

/**
 * Menu impostazioni del plugin: riorganizza e disattiva le sezioni della home.
 *
 * Si apre dal tasto impostazioni del provider in home (HomeFragment chiama
 * Plugin.openSettings). UI costruita via codice, senza risorse XML.
 *
 * - Le sezioni attive sono in alto, nell'ordine scelto (frecce per spostare)
 * - Deseleziona una casella per nascondere la sezione
 * - I preset riempiono subito l'elenco (poi puoi rifinire a mano)
 * - Salva scrive la lista in DataStore e invalida la cache del provider
 */
fun showScSectionsDialog(context: Context) {
    // Stato editabile: attive (in ordine) e disattivate (ordine del catalogo)
    val rows = ScSections.loadOrder(context).toMutableList()
    val disabled = ScSections.DEFAULT
        .filter { def -> rows.none { it.id == def.id } }
        .toMutableList()

    val listHost = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    val scroll = ScrollView(context).apply {
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        addView(listHost)
    }

    fun refreshDisabledList() {
        disabled.clear()
        disabled.addAll(ScSections.DEFAULT.filter { def -> rows.none { it.id == def.id } })
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
                    refreshDisabledList()
                    rebuild()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        presets.setPadding(0, 0, 0, context.dp(6))
        listHost.addView(presets)

        listHost.addView(TextView(context).apply {
            text = "Sezioni attive (deseleziona per nascondere, frecce per ordinare):"
            setPadding(0, context.dp(8), 0, context.dp(4))
        })

        rows.forEachIndexed { index, section ->
            listHost.addView(sectionRow(
                context = context,
                label = section.label,
                checked = true,
                canUp = index > 0,
                canDown = index < rows.size - 1,
                onToggle = { nowChecked ->
                    // non dovrebbe accadere su righe attive, ma difensivo
                    if (!nowChecked) {
                        rows.removeAll { it.id == section.id }
                        refreshDisabledList()
                        rebuild()
                    }
                },
                onUp = {
                    if (index > 0) {
                        Collections.swap(rows, index, index - 1)
                        rebuild()
                    }
                },
                onDown = {
                    if (index < rows.size - 1) {
                        Collections.swap(rows, index, index + 1)
                        rebuild()
                    }
                },
            ))
        }

        listHost.addView(TextView(context).apply {
            text = "Nascoste:"
            setPadding(0, context.dp(12), 0, context.dp(4))
        })

        disabled.forEach { section ->
            listHost.addView(sectionRow(
                context = context,
                label = section.label,
                checked = false,
                canUp = false,
                canDown = false,
                onToggle = { nowChecked ->
                    if (nowChecked) {
                        disabled.removeAll { it.id == section.id }
                        rows.add(section) // entra in fondo, poi la sposti con le frecce
                        rebuild()
                    }
                },
                onUp = {},
                onDown = {},
            ))
        }
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

/** Riga: [casella + nome] [▲] [▼] */
private fun sectionRow(
    context: Context,
    label: String,
    checked: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(2), 0, context.dp(2))
    }

    row.addView(CheckBox(context).apply {
        text = label
        isChecked = checked
        setOnCheckedChangeListener { _, isCheckedNow -> onToggle(isCheckedNow) }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })

    fun arrowButton(symbol: String, enabled: Boolean, action: () -> Unit): Button =
        Button(context).apply {
            text = symbol
            isEnabled = enabled
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                context.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    row.addView(arrowButton("▲", canUp, onUp))
    row.addView(arrowButton("▼", canDown, onDown))
    return row
}

/** Converte dp in pixel (piccoli spazi). */
private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()
