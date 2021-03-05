package de.mjksoftware.deviationreport

import java.io.File


class DamageType2(val name: String, private val description: Array<String>)
{
    private val maxValue: Int = description.size
    var current: Int = 0
    var counter = IntArray(maxValue){0}

    fun getCurrentString() = "$name${current + 1}"
    fun cycle() { if (current == counter.lastIndex) current = 0 else current++ }
    fun getDescription(): String = description[current]
    fun add() { counter[current]++ }
    fun resetCounter() { for(i in counter.indices) counter[i] = 0}

    fun getTotalCount(): Int {
        var total: Int = 0
        for (i in counter.indices) total += counter[i]
        return total
    }

    fun saveToFile(f: File) {
        for (i in counter.indices)
            if (counter[i] > 0) f.appendText("$name${i+1}\t-->\t${counter[i]}\n", Charsets.UTF_8)
    }
}