package com.example

import com.example.hardware.DevInputListener
import org.junit.Assert.assertEquals
import org.junit.Test

class DevInputListenerTest {

    @Test
    fun testKeyConstants() {
        assertEquals(1, DevInputListener.EV_KEY)
        assertEquals(0, DevInputListener.EV_SYN)
        assertEquals(59, DevInputListener.KEY_F1)
        assertEquals(1, DevInputListener.KEY_DOWN)
        assertEquals(0, DevInputListener.KEY_UP)
    }
}
