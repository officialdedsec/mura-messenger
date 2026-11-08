package com.mura.mesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mura.mesh.ui.Disguise
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The disguise: a plain, working calculator. Typing the secret code and pressing "=" opens Mura.
 * Its colours are deliberately generic (not Mura's), so nothing about it looks like a messenger.
 */
@Composable
fun CalculatorScreen(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var expr by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bg = Color(0xFF101114); val key = Color(0xFF26282D); val op = Color(0xFF3A3D44); val eq = Color(0xFFFF9F0A)

    fun press(k: String) {
        when (k) {
            "C" -> { expr = ""; result = "" }
            "⌫" -> expr = expr.dropLast(1)
            "=" -> {
                val typed = expr
                // A plain number of 4–12 digits might be the code: check it in the background (it's a slow hash),
                // so the screen never freezes. Anything else is just a sum.
                if (!checking && typed.length in 4..12 && typed.all { it in '0'..'9' }) {
                    checking = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { try { Disguise.codeMatches(ctx, typed) } catch (e: Exception) { false } }
                        checking = false
                        if (ok) { expr = ""; result = ""; onUnlock() } else result = Calc.eval(typed) ?: "Error"
                    }
                    return
                }
                result = Calc.eval(expr) ?: "Error"
            }
            else -> { if (expr.length < 40) expr += k; result = Calc.eval(expr) ?: "" }
        }
    }

    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
        Text(expr.ifEmpty { "0" }, color = Color.White, fontSize = 44.sp, textAlign = TextAlign.End, maxLines = 2, modifier = Modifier.fillMaxWidth().padding(8.dp))
        Text(result, color = Color(0xFF9BA0A8), fontSize = 26.sp, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
        val rows = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("00", "0", ".", "="),
        )
        for (r in rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (k in r) {
                    val color = when (k) { "=" -> eq; "÷", "×", "−", "+", "%", "C", "⌫" -> op; else -> key }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(color).clickable(role = Role.Button, onClickLabel = k) { press(k) },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/** A small, safe calculator: + − × ÷ %, decimals, operator precedence. Returns null for incomplete input. */
object Calc {
    private val mc = MathContext(16, RoundingMode.HALF_EVEN)

    fun eval(s: String): String? {
        if (s.isBlank()) return null
        return try {
            val tokens = tokenize(s) ?: return null
            val v = parse(tokens) ?: return null
            v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        } catch (e: ArithmeticException) { null } catch (e: Exception) { null }
    }

    private fun tokenize(s: String): List<String>? {
        val out = ArrayList<String>()
        val num = StringBuilder()
        for (c in s) {
            when {
                c.isDigit() || c == '.' -> num.append(c)
                c in "+−×÷%" -> {
                    if (num.isEmpty()) { if (c == '−' && out.isEmpty()) { num.append('-'); continue } else return null }
                    out.add(num.toString()); num.clear(); out.add(c.toString())
                }
                else -> return null
            }
        }
        if (num.isEmpty() || num.toString() == "-") return null
        out.add(num.toString())
        return out
    }

    private fun parse(t: List<String>): BigDecimal? {
        // Two passes: × ÷ % first, then + −.
        val terms = ArrayList<BigDecimal>()
        val ops = ArrayList<String>()
        var cur = t[0].toBigDecimal()
        var i = 1
        while (i < t.size) {
            val o = t[i]; val n = t[i + 1].toBigDecimal()
            when (o) {
                "×" -> cur = cur.multiply(n, mc)
                "÷" -> { if (n.signum() == 0) return null; cur = cur.divide(n, mc) }
                "%" -> { if (n.signum() == 0) return null; cur = cur.remainder(n, mc) }
                else -> { terms.add(cur); ops.add(o); cur = n }
            }
            i += 2
        }
        terms.add(cur)
        var acc = terms[0]
        for (k in ops.indices) acc = if (ops[k] == "+") acc.add(terms[k + 1], mc) else acc.subtract(terms[k + 1], mc)
        return acc
    }
}
