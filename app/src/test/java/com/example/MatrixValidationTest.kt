package com.example

import com.example.matrix.MatrixVault
import org.junit.Assert.*
import org.junit.Test

class MatrixValidationTest {
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
