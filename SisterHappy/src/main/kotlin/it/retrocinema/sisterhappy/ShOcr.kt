package it.retrocinema.sisterhappy

/**
 * ------------------------------------------------------------------
 * OCR CIFRE per il captcha del protettore safego/clicka (v11)
 * ------------------------------------------------------------------
 * Flusso identico al prototipo convalidato offline (scripts/
 * sh_captcha_ocr_proto.py, accuratezza 97% per tentativo su captcha
 * puliti; il protettore serve cifre pulite leggibili da tesseract, come
 * dimostra il flusso MammaMia che lo risolve in produzione):
 *
 * 1. luminanza -> soglia (Otsu + punto medio fondo/2-percentile, mai
 *    sotto Otsu, mai vicino al fondo) -> inchiostro = minoranza
 * 2. rimozione righe/colonne-linea (strike-through)
 * 3. componenti connesse 8-conn sulla mask ORIGINALE (cifre nette),
 *    poi sulla CHIUSA (tratti spezzati riconnessi), poi proiezione
 *    verticale; si tiene la prima segmentazione da 4..7 box
 * 4. finestra y robusta per box (sequenza contigua non vuota piu'
 *    lunga, buchi di 1-2 righe tollerati) e scarto frammenti bassi
 * 5. normalizzazione nearest-neighbor 12x16, aspect preservato, centro
 * 6. dice 2|A^B|/(|A|+|B|) contro i template (max su 5 scorrimenti),
 *    popcount con Long.bitCount su LongArray(4) = 4 gruppi da 4 righe
 *    (48 bit usati per long)
 *
 * Nessuna dipendenza android: gira anche su JVM per i test.
 */
internal object ShOcr {

    const val TW = 12
    const val TH = 16
    private const val ROWMASK = 0xFFFL

    class Res(val text: String, val margin: Double)

    // ---------------------------------------------------------------- ingresso

    /** Legge le cifre da un'immagine in luminanza (0-255), riga-major. Null se non leggibile. */
    fun read(gray: IntArray, w: Int, h: Int): Res? {
        if (w <= 0 || h <= 0 || gray.size < w * h) return null
        val mask = binarize(gray, w, h)
        stripLines(mask, w, h)
        val orig = mask.copyOf()
        val closed = close3(mask, w, h)
        val boxes = segBoxes(orig, closed, w, h)
        if (boxes.size < 4 || boxes.size > 7) return null

        val digits = StringBuilder()
        val margins = mutableListOf<Double>()
        for ((a, y0, b, y1, _) in boxes) {
            val norm = normalizeGlyph(orig, w, h, a, y0, b, y1)
            var ink = 0
            for (v in norm) ink += v
            if (ink <= 2) return null
            val bw = b - a + 1
            val bh = y1 - y0 + 1
            if (bw <= 2 && bh >= 8) {
                digits.append('1')
                margins.add(0.5)
                continue
            }
            val nbits = toBits(norm)
            val npop = pop3(nbits)
            // punteggio per cifra: massimo dice sui 5 scorrimenti
            val perDigit = DoubleArray(10) { -1.0 }
            val shifts = ArrayList<LongArray>(5)
            shifts.add(nbits)
            shifts.add(shiftDx(nbits, -1))
            shifts.add(shiftDx(nbits, 1))
            shifts.add(shiftDy(nbits, 1))
            shifts.add(shiftDy(nbits, -1))
            for (tpl in SH_TPL_LIST) {
                val tp = pop3(tpl.bits)
                if (tp * 135 < npop * 70 || npop * 135 < tp * 70) continue
                var s = -1.0
                val den = (npop + tp).toDouble()
                for (vb in shifts) {
                    val inter = and3(vb, tpl.bits)
                    val sc = 2.0 * inter / den
                    if (sc > s) s = sc
                }
                val d = tpl.digit - '0'
                if (s > perDigit[d]) perDigit[d] = s
            }
            var bestD = -1
            var bestS = -1.0
            var secondS = -1.0
            for (d in 0 until 10) {
                if (perDigit[d] > bestS) {
                    secondS = bestS
                    bestS = perDigit[d]
                    bestD = d
                } else if (perDigit[d] > secondS) {
                    secondS = perDigit[d]
                }
            }
            if (bestD < 0) return null
            digits.append(('0' + bestD))
            margins.add(bestS - secondS)
        }
        return Res(digits.toString(), margins.minOrNull() ?: 0.0)
    }

    // ---------------------------------------------------------------- bitset
    // LongArray(4): gruppo g = righe 4g..4g+3, 12 bit per riga (bit x = colonna x)

    internal fun toBits(m: IntArray): LongArray {
        val out = LongArray(4)
        for (i in m.indices) {
            if (m[i] != 0) {
                val g = i / 48
                out[g] = out[g] or (1L shl (i % 48))
            }
        }
        return out
    }

    private fun pop3(v: LongArray): Int =
        java.lang.Long.bitCount(v[0]) + java.lang.Long.bitCount(v[1]) +
            java.lang.Long.bitCount(v[2]) + java.lang.Long.bitCount(v[3])

    private fun and3(a: LongArray, b: LongArray): Int =
        java.lang.Long.bitCount(a[0] and b[0]) +
            java.lang.Long.bitCount(a[1] and b[1]) +
            java.lang.Long.bitCount(a[2] and b[2]) +
            java.lang.Long.bitCount(a[3] and b[3])

    /** Scorrimento orizzontale del contenuto: dx>0 = verso destra. */
    private fun shiftDx(v: LongArray, dx: Int): LongArray {
        val out = LongArray(4)
        for (r in 0 until TH) {
            val g = r / 4
            val off = (r % 4) * 12
            var row = (v[g] shr off) and ROWMASK
            row = if (dx > 0) (row shl dx) and ROWMASK else row shr (-dx)
            out[g] = out[g] or (row shl off)
        }
        return out
    }

    /** Scorrimento verticale: dy>0 = il contenuto scende (perde la riga in cima). */
    private fun shiftDy(v: LongArray, dy: Int): LongArray {
        val out = LongArray(4)
        val g48 = (1L shl 48) - 1
        if (dy > 0) {
            for (g in 0 until 4) {
                var nv = (v[g] shl 12) and g48
                if (g > 0) {
                    nv = nv or ((v[g - 1] ushr 36) and ROWMASK)
                }
                out[g] = nv
            }
        } else {
            for (g in 0 until 4) {
                var nv = v[g] ushr 12
                if (g < 3) {
                    nv = nv or ((v[g + 1] and ROWMASK) shl 36)
                }
                out[g] = nv
            }
        }
        return out
    }

    // ---------------------------------------------------------------- binarizzazione

    /** Soglia Otsu + punto medio fondo/2-percentile; inchiostro = minoranza. */
    private fun binarize(gray: IntArray, w: Int, h: Int): IntArray {
        val hist = IntArray(256)
        val total = w * h
        for (v in gray) if (v in 0..255) hist[v]++
        var sumAll = 0.0
        for (t in 0 until 256) sumAll += t * hist[t]
        var sumB = 0.0
        var wB = 0L
        var best = -1.0
        var thrOtsu = 127
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                thrOtsu = t
            }
        }
        // fondo = moda; 2-percentile = nucleo cifre anche con puntini piu' scuri
        var bg = 255
        var bgCount = -1
        for (t in 0 until 256) {
            if (hist[t] > bgCount) {
                bgCount = hist[t]
                bg = t
            }
        }
        var cum = 0L
        var p2 = 0
        for (t in 0 until 256) {
            cum += hist[t]
            if (cum * 50 >= total) {
                p2 = t
                break
            }
        }
        var thr = (bg + p2) / 2
        if (thr < thrOtsu) thr = thrOtsu
        if (thr > bg - 15) thr = bg - 15
        val mask = IntArray(total)
        var ink = 0
        for (i in 0 until total) {
            mask[i] = if (gray[i] < thr) 1 else 0
            ink += mask[i]
        }
        if (ink * 2 > total) {
            for (i in 0 until total) mask[i] = 1 - mask[i]
        }
        return mask
    }

    /** Righe/colonne con inchiostro su >85% = linea: azzera. */
    private fun stripLines(mask: IntArray, w: Int, h: Int) {
        for (y in 0 until h) {
            var cnt = 0
            for (x in 0 until w) cnt += mask[y * w + x]
            if (cnt * 20 > w * 17) {
                for (x in 0 until w) mask[y * w + x] = 0
            }
        }
        for (x in 0 until w) {
            var cnt = 0
            for (y in 0 until h) cnt += mask[y * w + x]
            if (cnt * 20 > h * 17) {
                for (y in 0 until h) mask[y * w + x] = 0
            }
        }
    }

    /** Chiusura morfologica 3x3: dilata poi erodi (riconnette i tratti). */
    private fun close3(mask: IntArray, w: Int, h: Int): IntArray {
        val dil = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x] == 1) {
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h) dil[ny * w + nx] = 1
                        }
                    }
                }
            }
        }
        val ero = IntArray(w * h) { 1 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var ok = 1
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h || dil[ny * w + nx] == 0) {
                            ok = 0
                            break@loop
                        }
                    }
                }
                ero[y * w + x] = ok
            }
        }
        return ero
    }

    // ---------------------------------------------------------------- segmentazione

    private data class Box(val a: Int, val y0: Int, val b: Int, val y1: Int, val ink: Int)

    private fun components8(mask: IntArray, w: Int, h: Int): MutableList<Box> {
        val lab = IntArray(w * h)
        val comps = ArrayList<Box>()
        var cur = 0
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (mask[start] == 1 && lab[start] == 0) {
                cur++
                var sp = 0
                stack[sp++] = start
                lab[start] = cur
                var x0 = Int.MAX_VALUE
                var y0 = Int.MAX_VALUE
                var x1 = -1
                var y1 = -1
                var ink = 0
                while (sp > 0) {
                    val p = stack[--sp]
                    val px = p % w
                    val py = p / w
                    ink++
                    if (px < x0) x0 = px
                    if (px > x1) x1 = px
                    if (py < y0) y0 = py
                    if (py > y1) y1 = py
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = px + dx
                            val ny = py + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                val q = ny * w + nx
                                if (mask[q] == 1 && lab[q] == 0) {
                                    lab[q] = cur
                                    stack[sp++] = q
                                }
                            }
                        }
                    }
                }
                if (ink >= 4) comps.add(Box(x0, y0, x1, y1, ink))
            }
        }
        return comps
    }

    /** Unisce SOLO componenti con sovrapposizione x e verticale (stessa cifra inclinata). */
    private fun mergeComps(listIn: List<Box>): MutableList<Box> {
        val comps = listIn.sortedBy { it.a }.toMutableList()
        var changed = true
        while (changed && comps.size > 1) {
            changed = false
            val out = ArrayList<Box>()
            var i = 0
            while (i < comps.size) {
                var merged = comps[i]
                var j = i + 1
                while (j < comps.size) {
                    val bb = comps[j]
                    val ov = minOf(merged.b, bb.b) - maxOf(merged.a, bb.a)
                    val gy = minOf(merged.y1, bb.y1) - maxOf(merged.y0, bb.y0)
                    val minh = minOf(merged.y1 - merged.y0, bb.y1 - bb.y0) + 1
                    if (ov >= 0 && gy > 0.25 * minh) {
                        merged = Box(
                            minOf(merged.a, bb.a), minOf(merged.y0, bb.y0),
                            maxOf(merged.b, bb.b), maxOf(merged.y1, bb.y1),
                            merged.ink + bb.ink,
                        )
                        comps.removeAt(j)
                        changed = true
                    } else {
                        j++
                    }
                }
                out.add(merged)
                i++
            }
            comps.clear()
            comps.addAll(out)
        }
        return comps
    }

    /** Proiezione verticale: bande di colonne con inchiostro. */
    private fun segmentProjection(mask: IntArray, w: Int, h: Int): MutableList<Box> {
        val colcnt = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) colcnt[x] += mask[y * w + x]
        }
        val out = ArrayList<Box>()
        var x = 0
        while (x < w) {
            if (colcnt[x] > 0) {
                var x2 = x
                while (x2 + 1 < w && colcnt[x2 + 1] > 0) x2++
                var y0 = h
                var y1 = -1
                var ink = 0
                for (yy in 0 until h) {
                    for (xx in x..x2) {
                        if (mask[yy * w + xx] == 1) {
                            ink++
                            if (yy < y0) y0 = yy
                            if (yy > y1) y1 = yy
                        }
                    }
                }
                if (y1 >= 0 && ink >= 5) out.add(Box(x, y0, x2, y1, ink))
                x = x2 + 1
            } else {
                x++
            }
        }
        return out
    }

    /** Scelta della segmentazione: CC orig -> CC chiusa -> proiezione. */
    private fun segBoxes(orig: IntArray, closed: IntArray, w: Int, h: Int): List<Box> {
        val candidates = listOf(
            mergeComps(components8(orig, w, h)),
            mergeComps(components8(closed, w, h)),
            mergeComps(segmentProjection(closed, w, h)),
        )
        var boxes: List<Box> = emptyList()
        for (cands in candidates) {
            val tall = cands.filter { it.y1 - it.y0 + 1 >= 6 }
            if (tall.size in 4..7) {
                boxes = tall
                break
            }
        }
        if (boxes.isEmpty()) return emptyList()
        val maxh = boxes.maxOf { it.y1 - it.y0 + 1 }
        val sorted = boxes.sortedBy { it.a }.filter { (it.y1 - it.y0 + 1) >= 0.45 * maxh }

        val out = ArrayList<Box>()
        for (bx in sorted) {
            var y0 = bx.y0
            var y1 = bx.y1
            // finestra y robusta: sequenza contigua non vuota piu' lunga
            val xa = maxOf(0, bx.a)
            val xb = minOf(w - 1, bx.b)
            var bestLen = 0
            var bestA = bx.y0
            var curLen = 0
            var curA = bx.y0
            for (yy in 0 until h) {
                var has = false
                for (xx in xa..xb) {
                    if (orig[yy * w + xx] == 1) {
                        has = true
                        break
                    }
                }
                if (has) {
                    if (curLen == 0) curA = yy
                    curLen++
                    if (curLen > bestLen) {
                        bestLen = curLen
                        bestA = curA
                    }
                } else {
                    if (curLen >= 4) {
                        val a1 = yy + 1 < h && rowHasInk(orig, w, yy + 1, xa, xb)
                        val a2 = yy + 2 < h && rowHasInk(orig, w, yy + 2, xa, xb)
                        if (a1) {
                            curLen++
                            continue
                        }
                        if (a2) {
                            curLen += 2
                            continue
                        }
                    }
                    curLen = 0
                }
            }
            if (bestLen >= 5) {
                y0 = bestA
                y1 = bestA + bestLen - 1
            }
            var ink = 0
            var yy0 = h
            var yy1 = -1
            val yTop = maxOf(0, y0)
            val yBot = minOf(h - 1, y1)
            for (yy in yTop..yBot) {
                for (xx in xa..xb) {
                    if (orig[yy * w + xx] == 1) {
                        ink++
                        if (yy < yy0) yy0 = yy
                        if (yy > yy1) yy1 = yy
                    }
                }
            }
            if (yy1 >= 0 && ink >= 5) out.add(Box(bx.a, yy0, bx.b, yy1, ink))
        }
        return out
    }

    private fun rowHasInk(mask: IntArray, w: Int, y: Int, xa: Int, xb: Int): Boolean {
        for (xx in xa..xb) {
            if (mask[y * w + xx] == 1) return true
        }
        return false
    }

    // ---------------------------------------------------------------- normalizzazione

    /** Ritaglia il bbox, scala nearest preserving l'aspect, centra in 12x16. */
    private fun normalizeGlyph(mask: IntArray, w: Int, h: Int, a: Int, y0: Int, b: Int, y1: Int): IntArray {
        val sw = b - a + 1
        val sh = y1 - y0 + 1
        val scale = minOf(TW.toDouble() / sw, TH.toDouble() / sh)
        val dw = maxOf(1, (sw * scale + 0.5).toInt())
        val dh = maxOf(1, (sh * scale + 0.5).toInt())
        val offx = (TW - dw) / 2
        val offy = (TH - dh) / 2
        val out = IntArray(TW * TH)
        for (dy in 0 until dh) {
            val sy = y0 + (dy * sh) / dh
            for (dx in 0 until dw) {
                val sx = a + (dx * sw) / dw
                if (mask[sy * w + sx] == 1) {
                    out[(offy + dy) * TW + (offx + dx)] = 1
                }
            }
        }
        return out
    }
}
