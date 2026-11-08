package com.mura.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mura.mesh.ui.theme.Mura

/**
 * Popular emoji, limited to Unicode Emoji 11 so they render on every phone from Android 9 up
 * (no emoji font download needed — keeps the app small and offline).
 */
object EmojiData {
    val categories: List<Pair<String, List<String>>> = listOf(
        "Smileys" to ("😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 " +
            "😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 " +
            "😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👹 👺 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾").split(" "),
        "People" to ("👋 🤚 🖐 ✋ 🖖 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦵 🦶 👂 👃 🧠 🦷 " +
            "👀 👁 👅 👄 💋 👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🙇 🤦 🤷 👮 🕵 💂 👷 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🤱 👼 🎅 🤶 " +
            "🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🏃 💃 🕺 👯 🧖 🧘 👫 👭 👬 💏 💑 👪").split(" "),
        "Hearts" to ("❤️ 🧡 💛 💚 💙 💜 🖤 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💌 💯 💢 💥 💫 💦 💨 🕳 💬 🗨 🗯 💭 💤 ✨ 🌟 ⭐ 🔥 🎉 🎊").split(" "),
        "Nature" to ("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🦗 🕷 " +
            "🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🐈 " +
            "🐓 🦃 🦚 🦜 🦢 🕊 🐇 🦝 🦡 🐁 🐀 🐿 🦔 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘ 🍀 🎍 🎋 🍃 🍂 🍁 🍄 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌚 🌙 🌎 " +
            "🪐 ⛅ 🌈 ☔ ❄ ⛄ 🌊").split(" ").filter { it != "🪐" },
        "Food" to ("🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶 🌽 🥕 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🥞 🥓 🥩 🍗 " +
            "🍖 🌭 🍔 🍟 🍕 🥪 🥙 🌮 🌯 🥗 🥘 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 " +
            "🍪 🌰 🥜 🍯 🥛 🍼 ☕ 🍵 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🍾 🥄 🍴 🍽").split(" "),
        "Activity" to ("⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 🛷 ⛸ 🥌 🎿 ⛷ 🏂 🏋 🤼 🤸 ⛹ 🤺 🤾 🏌 🏇 🧘 " +
            "🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖 🏵 🎗 🎫 🎟 🎪 🤹 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟ 🎯 🎳 🎮 🎰 🧩").split(" "),
        "Travel" to ("🚗 🚕 🚙 🚌 🚎 🏎 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈ " +
            "🛫 🛬 🛩 💺 🛰 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥 🛳 ⛴ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺 🗿 🗽 🗼 🏰 🏯 🏟 🎡 🎢 🎠 ⛲ ⛱ 🏖 🏝 🏜 🌋 ⛰ 🏔 🗻 🏕 ⛺ 🏠 " +
            "🏡 🏘 🏚 🏗 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛 ⛪ 🕌 🕍 🕋 ⛩ 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙 🌃 🌌 🌉 🌁").split(" "),
        "Objects" to ("⌚ 📱 📲 💻 ⌨ 🖥 🖨 🖱 🕹 💽 💾 💿 📀 📷 📸 📹 🎥 📞 ☎ 📟 📠 📺 📻 🎙 ⏱ ⏰ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯 🧯 💸 💵 💳 " +
            "💎 ⚖ 🧰 🔧 🔨 🛠 ⛏ 🔩 ⚙ 🧱 ⛓ 🧲 🔫 💣 🧨 🔪 🗡 ⚔ 🛡 🔮 📿 🧿 💈 ⚗ 🔭 🔬 💊 💉 🧬 🦠 🧪 🌡 🧹 🧺 🧻 🚽 🛁 🔑 🗝 🚪 🛋 🛏 " +
            "🧸 🖼 🛍 🛒 🎁 🎈 🎏 🎀 🎊 🎉 🎎 🏮 🎐 🧧 ✉ 📩 📨 📧 💌 📥 📤 📦 🏷 📪 📫 📬 📭 📮 📜 📃 📄 📑 🧾 📊 📈 📉 🗒 🗓 📆 📅 📇 🗃 " +
            "🗳 🗄 📋 📁 📂 🗂 🗞 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇 📐 📏 🧮 📌 📍 ✂ 🖊 🖋 ✒ 🖌 🖍 📝 ✏ 🔍 🔎 🔏 🔐 🔒 🔓").split(" "),
        "Symbols" to ("✅ ☑ ✔ ❌ ❎ ➕ ➖ ➗ ✖ ♾ ‼ ⁉ ❓ ❔ ❕ ❗ 〰 💱 💲 ⚕ ♻ ⚜ 🔱 📛 🔰 ⭕ ✳ ❇ 🆗 🆙 🆒 🆕 🆓 🆘 ⛔ 🚫 🚳 🚭 🚯 🚱 🚷 📵 🔞 " +
            "☢ ☣ ⬆ ↗ ➡ ↘ ⬇ ↙ ⬅ ↖ ↕ ↔ ↩ ↪ ⤴ ⤵ 🔃 🔄 🔙 🔚 🔛 🔜 🔝 🛐 ⚛ 🕉 ✡ ☸ ☯ ✝ ☦ ☪ ☮ 🕎 🔯 ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⛎ " +
            "🔀 🔁 🔂 ▶ ⏩ ⏭ ⏯ ◀ ⏪ ⏮ 🔼 ⏫ 🔽 ⏬ ⏸ ⏹ ⏺ ⏏ 🎦 🔅 🔆 📶 📳 📴 ♀ ♂ ⚧ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔶 🔷 🔸 🔹 🔺 🔻 💠 🔘 🔳 🔲")
            .split(" ").filter { it !in setOf("⚧", "🟠", "🟡", "🟢", "🟣", "🟤") },
        "Flags" to ("🏁 🚩 🎌 🏴 🏳 🏳️‍🌈 🇮🇳 🇺🇸 🇬🇧 🇨🇦 🇦🇺 🇩🇪 🇫🇷 🇮🇹 🇪🇸 🇵🇹 🇧🇷 🇲🇽 🇦🇷 🇯🇵 🇰🇷 🇨🇳 🇷🇺 🇺🇦 🇹🇷 🇸🇦 🇦🇪 🇪🇬 🇳🇬 " +
            "🇰🇪 🇿🇦 🇵🇰 🇧🇩 🇱🇰 🇳🇵 🇮🇩 🇲🇾 🇸🇬 🇹🇭 🇻🇳 🇵🇭 🇳🇿 🇮🇪 🇳🇱 🇧🇪 🇨🇭 🇸🇪 🇳🇴 🇩🇰 🇫🇮 🇵🇱 🇬🇷 🇮🇱 🇮🇷 🇮🇶").split(" "),
    )
}

/** Keyboard-height emoji panel. Recently used emoji appear first. */
@Composable
fun EmojiPicker(onPick: (String) -> Unit) {
    val recent = remember { RecentEmoji.list }
    val tabs = remember { listOf("Recent" to recent) + EmojiData.categories }
    var tab by remember { mutableIntStateOf(if (recent.isEmpty()) 1 else 0) }
    Column(Modifier.fillMaxWidth().height(300.dp).background(Mura.Surface)) {
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            itemsIndexed(tabs) { i, (name, list) ->
                val icon = if (name == "Recent") "🕘" else list.first()
                Box(
                    Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (i == tab) Mura.Surface2 else Mura.Surface)
                        .clickable(role = Role.Tab, onClickLabel = name) { tab = i }.padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(icon, fontSize = 20.sp) }
            }
        }
        val list = if (tab == 0) recent.toList() else tabs[tab].second
        if (list.isEmpty()) Text("Emoji you use will show up here", color = Mura.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            items(list) { e ->
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = e) {
                        RecentEmoji.use(e); onPick(e)
                    },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 26.sp) }
            }
        }
    }
}

object RecentEmoji {
    val list = mutableStateListOf<String>()
    fun use(e: String) { list.remove(e); list.add(0, e); while (list.size > 32) list.removeAt(list.size - 1) }
}
