package com.lunaexplorer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {
    @Test fun `digit runs sort numerically without overflowing`() {
        val names = listOf("file100000000000000000000.txt", "file10.txt", "file2.txt")
        assertEquals(names.reversed(), names.sortedWith(NaturalOrder::compareNames))
        assertEquals(0, NaturalOrder.compareNames("FILE0002.txt", "file2.txt"))
    }

    @Test fun `mixed digit scripts and letters preserve comparator transitivity`() {
        val names = listOf("2", "10", "100", "a", "١١", "１１")
        for (first in names) for (second in names) for (third in names) {
            if (NaturalOrder.compareNames(first, second) < 0 && NaturalOrder.compareNames(second, third) < 0) {
                assertTrue("$first < $second < $third", NaturalOrder.compareNames(first, third) < 0)
            }
        }
    }
}
