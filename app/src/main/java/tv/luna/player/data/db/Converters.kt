package tv.luna.player.data.db

import androidx.room.TypeConverter

class Converters {
    // URLs never contain a newline, so it is a safe separator.
    @TypeConverter
    fun fromStringList(list: List<String>): String = list.joinToString("\n")

    @TypeConverter
    fun toStringList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}
