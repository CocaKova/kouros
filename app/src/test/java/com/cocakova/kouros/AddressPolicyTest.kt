package com.cocakova.kouros

import com.cocakova.kouros.net.AddressPolicy
import com.cocakova.kouros.net.AddressPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class AddressPolicyTest {
    @Test fun httpsIsAlwaysFine() = assertEquals(Verdict.OK_TLS, AddressPolicy.check("https://comfy.example.com"))
    @Test fun lanIsPrivate() {
        listOf("http://192.168.1.20:8188", "http://10.0.0.5:8188", "http://172.20.1.1", "http://127.0.0.1:8188",
            "http://100.101.102.103:8188", "http://my-box.local:8188", "http://gpu.tail1234.ts.net:8188", "http://[fd12::1]:8188")
            .forEach { assertEquals(it, Verdict.OK_PRIVATE, AddressPolicy.check(it)) }
    }
    @Test fun publicHttpNeedsOptIn() {
        listOf("http://8.8.8.8:8188", "http://comfy.example.com", "http://172.32.0.1", "http://100.128.0.1")
            .forEach { assertEquals(it, Verdict.NEEDS_OPT_IN, AddressPolicy.check(it)) }
    }
    @Test fun garbage() = assertEquals(Verdict.INVALID, AddressPolicy.check("ftp://x"))
    @Test fun normalize() {
        assertEquals("http://192.168.1.2:8188", AddressPolicy.normalize(" 192.168.1.2:8188/ "))
        assertEquals("https://a.b", AddressPolicy.normalize("https://a.b/"))
    }
}
