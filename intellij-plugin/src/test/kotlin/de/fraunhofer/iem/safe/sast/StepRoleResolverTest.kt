package de.fraunhofer.iem.safe.sast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StepRoleResolverTest {

    @Test
    fun `message prefix takes precedence over position`() {
        // First step would normally be SOURCE by position, but the message overrides.
        assertEquals(StepRole.SINK, StepRoleResolver.resolve("Sink: 'x'", 0, 5))
        assertEquals(StepRole.PROPAGATOR, StepRoleResolver.resolve("Propagator: 'x'", 0, 5))
        assertEquals(StepRole.SANITIZER, StepRoleResolver.resolve("Sanitizer: clean", 2, 5))
        assertEquals(StepRole.CALL, StepRoleResolver.resolve("Call: foo()", 1, 5))
    }

    @Test
    fun `position falls back when message has no role prefix`() {
        assertEquals(StepRole.SOURCE, StepRoleResolver.resolve(null, 0, 4))
        assertEquals(StepRole.SINK, StepRoleResolver.resolve(null, 3, 4))
        assertEquals(StepRole.PROPAGATOR, StepRoleResolver.resolve(null, 1, 4))
        assertEquals(StepRole.PROPAGATOR, StepRoleResolver.resolve(null, 2, 4))
    }

    @Test
    fun `single-step trace classifies as source`() {
        assertEquals(StepRole.SOURCE, StepRoleResolver.resolve(null, 0, 1))
    }

    @Test
    fun `unknown when total is non-positive`() {
        assertEquals(StepRole.UNKNOWN, StepRoleResolver.resolve(null, 0, 0))
    }

    @Test
    fun `case-insensitive prefix match`() {
        assertEquals(StepRole.SOURCE, StepRoleResolver.resolve("source: 'x'", 0, 3))
        assertEquals(StepRole.SOURCE, StepRoleResolver.resolve("SOURCE: 'x'", 0, 3))
        assertEquals(StepRole.SOURCE, StepRoleResolver.resolve("  Source 'x'", 0, 3))
    }
}
