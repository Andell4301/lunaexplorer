package com.lunaexplorer.core

object NaturalOrder : Comparator<Entry> {
    override fun compare(left: Entry, right: Entry): Int = compareNames(left.name, right.name)

    fun compareNames(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a[i]
            val y = b[j]
            // Other digit scripts must keep code-point order relative to intervening non-digits.
            if (x in '0'..'9' && y in '0'..'9') {
                var endX = i
                while (endX < a.length && a[endX] in '0'..'9') endX++
                var endY = j
                while (endY < b.length && b[endY] in '0'..'9') endY++
                // With leading zeros stripped, the longer digit run is the larger number.
                val numX = a.substring(i, endX).trimStart('0')
                val numY = b.substring(j, endY).trimStart('0')
                if (numX.length != numY.length) return numX.length - numY.length
                val byValue = numX.compareTo(numY)
                if (byValue != 0) return byValue
                i = endX
                j = endY
            } else {
                val byChar = x.lowercaseChar().compareTo(y.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
