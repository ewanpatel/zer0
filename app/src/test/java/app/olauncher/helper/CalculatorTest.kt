package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {

    @Test
    fun calculatesArithmetic() {
        assertEquals("4", calculate("2+2"))
        assertEquals("21.6", calculate("18*1.2"))
        assertEquals("12", calculate("3x4"))
        assertEquals("14", calculate("2+3*4"))
        assertEquals("20", calculate("(2+3)*4"))
        assertEquals("1024", calculate("2^10"))
        assertEquals("0.3333333333", calculate("1/3"))
        assertEquals("-1", calculate("-3+2"))
        assertEquals("7", calculate(" 10 - 3 "))
    }

    @Test
    fun ignoresNonSums() {
        assertNull(calculate("2024"))
        assertNull(calculate("-5"))
        assertNull(calculate("spotify"))
        assertNull(calculate("2+"))
        assertNull(calculate("1/0"))
        assertNull(calculate("(2+3"))
        assertNull(calculate("x"))
    }
}
