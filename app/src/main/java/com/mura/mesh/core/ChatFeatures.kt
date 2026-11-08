package com.mura.mesh.core

/** One checklist item: an 8-byte random id (so two people can add items offline without clashing) and its text. */
class ListItem(val id: ByteArray, val text: String)

/**
 * Shared checklists that work offline: everyone edits their own copy and the changes
 * ([Payload.ListOp]) travel through the mesh in any order. Every phone folds the same set of
 * operations in the same order (time, then author, then op id), so all copies end up identical
 * no matter when or in which order the changes arrive — a small CRDT.
 */
object Checklists {
    class Op(val opId: String, val op: Int, val itemId: String, val text: String, val at: Long, val author: String)

    data class Item(val id: String, val text: String, val checked: Boolean, val checkedBy: String?, val addedAt: Long, val addedBy: String)

    fun fold(ops: Collection<Op>): List<Item> {
        class S(var text: String, val addedAt: Long, val addedBy: String) {
            var textAt = Long.MIN_VALUE; var checked = false; var checkedBy: String? = null; var checkAt = Long.MIN_VALUE; var removed = false
        }
        val items = LinkedHashMap<String, S>()
        val sorted = ops.sortedWith(compareBy<Op>({ it.at }, { it.author }, { it.opId }))
        // Pass 1: items exist once added (phones' clocks differ, so a tick may be dated before its add).
        for (o in sorted) if (o.op == Payload.OP_ADD && o.itemId !in items) items[o.itemId] = S(o.text, o.at, o.author).also { it.textAt = o.at }
        // Pass 2: everything else, last writer wins.
        for (o in sorted) {
            when (o.op) {
                Payload.OP_ADD -> {}
                else -> {
                    val s = items[o.itemId] ?: continue          // its "add" hasn't arrived yet: ignored until it has (we re-fold every time)
                    when (o.op) {
                        Payload.OP_RENAME -> if (o.at >= s.textAt) { s.text = o.text; s.textAt = o.at }
                        Payload.OP_CHECK -> if (o.at >= s.checkAt) { s.checked = true; s.checkedBy = o.author; s.checkAt = o.at }
                        Payload.OP_UNCHECK -> if (o.at >= s.checkAt) { s.checked = false; s.checkedBy = null; s.checkAt = o.at }
                        Payload.OP_REMOVE -> s.removed = true      // removal wins: a removed item stays removed
                    }
                }
            }
        }
        return items.filterValues { !it.removed }.map { (id, s) -> Item(id, s.text, s.checked, s.checkedBy, s.addedAt, s.addedBy) }
            .sortedWith(compareBy<Item>({ it.addedAt }, { it.id }))
    }
}

/**
 * Doodles as vectors instead of pictures: each stroke is a colour, a width and a list of points
 * quantised to a 4096×4096 grid, stored as zig-zag varint deltas. A typical sketch is 1–6 KB
 * before deflate — far smaller than even a tiny photo, so it crosses Bluetooth in a moment.
 */
object DoodleCodec {
    const val MAX_BYTES = 96 * 1024
    const val GRID = 4095f

    /** [points] are x,y pairs in 0..1. [color] is ARGB, [width] is a fraction of the doodle's width. */
    class Stroke(val color: Int, val width: Float, val points: FloatArray)

    fun encode(strokes: List<Stroke>): ByteArray {
        val w = ByteWriter(4096)
        w.u16(strokes.size)
        for (s in strokes) {
            val n = s.points.size / 2
            w.i32(s.color).u8((s.width * 1000).toInt().coerceIn(1, 255)).u16(n)
            var px = 0; var py = 0
            for (i in 0 until n) {
                val x = (s.points[i * 2].coerceIn(0f, 1f) * GRID).toInt()
                val y = (s.points[i * 2 + 1].coerceIn(0f, 1f) * GRID).toInt()
                varint(w, zig(x - px)); varint(w, zig(y - py))
                px = x; py = y
            }
        }
        val out = w.toByteArray()
        if (out.size > MAX_BYTES) throw IllegalArgumentException("Doodle is too detailed")
        return out
    }

    fun decode(b: ByteArray): List<Stroke> {
        val r = ByteReader(b)
        val count = r.u16()
        if (count > 4000) throw ProtocolException("too many strokes")
        return List(count) {
            val color = r.i32(); val width = r.u8() / 1000f; val n = r.u16()
            val pts = FloatArray(n * 2)
            var x = 0; var y = 0
            for (i in 0 until n) {
                x += unzig(readVarint(r)); y += unzig(readVarint(r))
                pts[i * 2] = (x / GRID).coerceIn(0f, 1f); pts[i * 2 + 1] = (y / GRID).coerceIn(0f, 1f)
            }
            Stroke(color, width, pts)
        }
    }

    /** Drops points closer than [minDist] (in 0..1 units) to the previous kept point: smaller, smoother strokes. */
    fun simplify(points: FloatArray, minDist: Float = 0.004f): FloatArray {
        if (points.size <= 4) return points
        val out = ArrayList<Float>(points.size)
        var lx = points[0]; var ly = points[1]
        out.add(lx); out.add(ly)
        for (i in 1 until points.size / 2) {
            val x = points[i * 2]; val y = points[i * 2 + 1]
            val dx = x - lx; val dy = y - ly
            if (dx * dx + dy * dy >= minDist * minDist || i == points.size / 2 - 1) { out.add(x); out.add(y); lx = x; ly = y }
        }
        return out.toFloatArray()
    }

    private fun zig(v: Int) = (v shl 1) xor (v shr 31)
    private fun unzig(v: Int) = (v ushr 1) xor -(v and 1)
    private fun varint(w: ByteWriter, v0: Int) { var v = v0; while (v and 0x7f.inv() != 0) { w.u8((v and 0x7f) or 0x80); v = v ushr 7 }; w.u8(v) }
    private fun readVarint(r: ByteReader): Int {
        var shift = 0; var result = 0
        while (true) {
            val b = r.u8()
            result = result or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 28) throw ProtocolException("varint")
        }
    }
}
