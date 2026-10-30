package com.example

import com.example.matrix.MatrixVault
import org.junit.Assert.*
import org.junit.Test

class MatrixValidationTest {
    @Test
    fun httpRequiresExplicitLocalBuildAndPrivateIpv4() {
        assertEquals(
            "http://192.168.1.10:8008",
            MatrixVault.validateOrigin("http://192.168.1.10:8008/", true),
        )
        MatrixVault.validateOrigin("http://10.0.2.2:8008", true)
        for (origin in listOf("http://192.168.1.10:8008", "http://127.0.0.1:8008")) assertTrue(
            runCatching { MatrixVault.validateOrigin(origin, false) }.isFailure
        )
        for (origin in
            listOf(
                "http://example.org",
                "http://8.8.8.8",
                "http://169.254.169.254",
                "http://192.168.01.1",
                "http://user@192.168.1.10",
                "http://192.168.1.10/?token=secret",
            )) assertTrue(
            origin,
            runCatching { MatrixVault.validateOrigin(origin, true) }.isFailure,
        )
    }

    @Test
    fun httpsOriginHasNoEmbeddedCredentialsOrPath() {
        assertEquals(
            "https://matrix.example.org",
            MatrixVault.validateOrigin("https://matrix.example.org/"),
        )
        for (origin in
            listOf(
                "http://matrix.example.org",
                "https://token@matrix.example.org",
                "https://matrix.example.org/?access_token=secret",
                "https://matrix.example.org/_matrix",
                "file:///tmp/server",
            )) {
            assertTrue(origin, runCatching { MatrixVault.validateOrigin(origin) }.isFailure)
        }
    }

    @Test
    fun fullMatrixIdIsRequiredInsteadOfPhoneOrAmbiguousName() {
        MatrixVault.validateUser("@alice:matrix.example.org")
        for (user in
            listOf(
                "alice",
                "+919876543210",
                "@alice",
                "@a:evil.example/path",
                "@a:example.org\n",
            )) assertTrue(user, runCatching { MatrixVault.validateUser(user) }.isFailure)
    }
}
