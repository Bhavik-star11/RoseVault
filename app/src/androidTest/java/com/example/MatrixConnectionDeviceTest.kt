package com.example

import androidx.test.platform.app.InstrumentationRegistry
import com.example.matrix.MatrixConnectionCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MatrixConnectionDeviceTest {
    @Test
    fun publicHttpIsRejectedBeforeConnecting(): Unit = runBlocking {
        assertTrue(
            MatrixConnectionCheck.check("http://example.org").startsWith("Check the server address")
        )
    }

    @Test
    fun localServerCheckWorksWithoutSigningIn(): Unit = runBlocking {
        val origin = InstrumentationRegistry.getArguments().getString("matrixOrigin")
        assumeTrue("Optional test needs a local Matrix origin", !origin.isNullOrBlank())
        assertTrue(
            MatrixConnectionCheck.check(requireNotNull(origin)).startsWith("Connection works")
        )
    }
}
