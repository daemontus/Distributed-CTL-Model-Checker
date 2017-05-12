package com.github.sybila.solver

import org.junit.Test
import kotlin.test.assertTrue

class SetSolverTest {

    val f = null
    val a = setOf(true)
    val b = setOf(false)
    val ab = setOf(true, false)

    val solver = SetSolver(universe = ab)

    @Test
    fun isSatTest() {
        solver.run {
            assertTrue(universe.isSat())
            assertTrue(a.isSat())
            assertTrue(b.isSat())
            assertTrue(ab.isSat())
            assertTrue(f.isNotSat())
        }
    }

    @Test
    fun equalTest() {
        solver.run {
            assertTrue(a equal a)
            assertTrue(b equal b)
            assertTrue(ab equal universe)
            assertTrue(f equal f)
            assertTrue(a notEqual b)
            assertTrue(a notEqual ab)
            assertTrue(a notEqual f)
        }
    }

    @Test
    fun notTest() {
        solver.run {
            assertTrue(a.not() equal b)
            assertTrue(b.not() equal a)
            assertTrue(ab.not() equal f)
            assertTrue(f.not() equal ab)
        }
    }

    @Test
    fun andTest() {
        solver.run {
            assertTrue((a and b) equal f)
            assertTrue((a and ab) equal a)
            assertTrue((b and ab) equal b)
            assertTrue((f and a) equal f)
            assertTrue((b and f) equal f)
            assertTrue((a and a) equal a)
        }
    }

    @Test
    fun orTest() {
        solver.run {
            assertTrue((a or b) equal ab)
            assertTrue((a or ab) equal ab)
            assertTrue((b or ab) equal ab)
            assertTrue((f or a) equal a)
            assertTrue((b or f) equal b)
            assertTrue((a or a) equal a)
        }
    }

    @Test
    fun tryOrTest() {
        solver.run {
            assertTrue((a tryOr a) === null)
            assertTrue((b tryOr b) === null)
            assertTrue((ab tryOr a) === null)
            assertTrue((a tryOr b) equal ab)
            assertTrue((a tryOr ab) equal ab)
            assertTrue((f tryOr a) equal a)
        }
    }

}