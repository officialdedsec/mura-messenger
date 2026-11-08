package com.mura.mesh

import com.mura.mesh.core.*
import com.mura.mesh.identity.TinkCurve
import com.mura.mesh.identity.TinkEd
import org.junit.BeforeClass
import org.junit.Test

class FeaturesTest {
    companion object {
        @JvmStatic @BeforeClass fun setup() { Crypto.curve = TinkCurve; Crypto.ed = TinkEd; Compression.padding = false }
    }

    @Test fun chatFeatures() = featureTests()
}


fun featureTests() {
    println("\n-- chat features --")
    fun id() = Crypto.randomBytes(16)
    val me = Crypto.randomBytes(32)

    val t = Payload.unpack(Payload.Text(id(), 1, "@Lena are you in?", false, null, null, 3600, true,
        listOf(Mention(me, "Lena Okafor"))).pack()) as Payload.Text
    check(t.forwarded && t.mentions.single().name == "Lena Okafor" && t.mentions.single().pub.contentEquals(me) && t.ttl == 3600, "text: forwarded + mentions + timer")

    // A 2.1 phone writes extras without the new fields: still readable.
    val old = Payload.unpack(Payload.Text(id(), 1, "hi", false).pack()) as Payload.Text
    check(!old.forwarded && old.mentions.isEmpty(), "plain text still decodes")

    val r = Payload.unpack(Payload.Reaction(id(), 1, id(), "❤️").pack()) as Payload.Reaction
    check(r.emoji == "❤️", "reaction codec (emoji survives)")
    val e = Payload.unpack(Payload.Edit(id(), 1, id(), "fixed typo").pack()) as Payload.Edit
    check(e.text == "fixed typo", "edit codec")
    val poll = Payload.unpack(Payload.Poll(id(), 1, "Where?", listOf("Hut", "Lake", "Summit"), true, null, 0, false).pack()) as Payload.Poll
    check(poll.options.size == 3 && poll.multi, "poll codec")
    check(runCatching { Payload.Poll(id(), 1, "x", listOf("only one"), false).pack().let { Payload.unpack(it) } }.isFailure, "poll with one option rejected")
    val v = Payload.unpack(Payload.Vote(id(), 1, id(), listOf(0, 2, 2, 99)).pack()) as Payload.Vote
    check(v.choices == listOf(0, 2), "vote codec drops duplicates and out-of-range choices")
    val pin = Payload.unpack(Payload.Pin(id(), 1, id(), true).pack()) as Payload.Pin
    check(pin.pinned, "pin codec")
    val items = List(3) { ListItem(Crypto.randomBytes(8), "item $it") }
    val cl = Payload.unpack(Payload.Checklist(id(), 1, "Packing", items, null, 0, false).pack()) as Payload.Checklist
    check(cl.items.map { it.text } == listOf("item 0", "item 1", "item 2"), "checklist codec")
    val op = Payload.unpack(Payload.ListOp(id(), 1, id(), Payload.OP_CHECK, items[0].id, "").pack()) as Payload.ListOp
    check(op.op == Payload.OP_CHECK && op.item.contentEquals(items[0].id), "list op codec")

    // Checklist CRDT: every delivery order gives the same list
    val A = "aaaa"; val B = "bbbb"
    val ops = listOf(
        Checklists.Op("1", Payload.OP_ADD, "i1", "Tent", 10, A),
        Checklists.Op("2", Payload.OP_ADD, "i2", "Stove", 11, A),
        Checklists.Op("3", Payload.OP_ADD, "i3", "Rope", 12, B),
        Checklists.Op("4", Payload.OP_CHECK, "i1", "", 20, B),
        Checklists.Op("5", Payload.OP_UNCHECK, "i1", "", 25, A),
        Checklists.Op("6", Payload.OP_CHECK, "i1", "", 25, B),      // same time: author breaks the tie
        Checklists.Op("7", Payload.OP_RENAME, "i2", "Gas stove", 30, B),
        Checklists.Op("8", Payload.OP_REMOVE, "i3", "", 31, A),
        Checklists.Op("9", Payload.OP_CHECK, "i2", "", 5, B),       // clock skew: dated before its add
    )
    val expected = Checklists.fold(ops)
    val rnd = java.util.Random(4)
    var same = true
    repeat(200) { if (Checklists.fold(ops.shuffled(rnd)) != expected) same = false }
    check(same, "checklist converges for 200 random delivery orders")
    check(expected.map { it.text } == listOf("Tent", "Gas stove") && expected[0].checked && expected[1].checked, "checklist result: rename, tie-break, removal, skew")

    // Doodle codec
    val strokes = listOf(
        DoodleCodec.Stroke(0xFFFFB547.toInt(), 0.012f, FloatArray(400) { i -> if (i % 2 == 0) i / 400f else 0.5f + 0.3f * kotlin.math.sin(i / 20f) }),
        DoodleCodec.Stroke(0xFF7CC4FF.toInt(), 0.02f, floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)),
    )
    val enc = DoodleCodec.encode(strokes)
    val dec = DoodleCodec.decode(enc)
    var maxErr = 0f
    for (k in strokes.indices) for (i in strokes[k].points.indices) maxErr = maxOf(maxErr, kotlin.math.abs(strokes[k].points[i] - dec[k].points[i]))
    check(dec.size == 2 && dec[0].color == 0xFFFFB547.toInt() && maxErr < 0.0005f, "doodle round trip (max error ${"%.5f".format(maxErr)})")
    val packed = Payload.Doodle(id(), 1, 600, 800, 0xFF0B0E13.toInt(), enc).pack()
    check(packed.size < 900, "doodle with 202 points travels in ${packed.size} bytes")
    check(DoodleCodec.simplify(FloatArray(2000) { it / 2000f }).size < 2000, "doodle simplify drops redundant points")
}
