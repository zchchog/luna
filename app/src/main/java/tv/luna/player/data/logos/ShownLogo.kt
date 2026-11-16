package tv.luna.player.data.logos

import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.settings.LogoKind

const val TV_LOGOS_RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/"

/** Spec §4.1 with the user's order (2026-11-12): a hand-picked logo always wins, then the first source that has one. Null → the number tile. */
fun shownLogo(ch: ChannelEntity, order: List<LogoKind>): String? = shownLogos(ch, order).firstOrNull()

/** Every logo the channel has, in [shownLogo]'s order: the next is tried when one fails to load. */
fun shownLogos(ch: ChannelEntity, order: List<LogoKind>): List<String> = listOfNotNull(ch.customLogo) + order.mapNotNull { k ->
    when (k) {
        LogoKind.PLAYLIST -> ch.logo
        LogoKind.OPEN_SOURCE -> ch.builtinLogo?.let { TV_LOGOS_RAW + it }
        LogoKind.PACK -> ch.packLogo
    }
}
