package com.github.sybila.checker.model

import com.github.sybila.checker.antlr.ModelBaseListener
import com.github.sybila.checker.antlr.ModelLexer
import com.github.sybila.checker.antlr.ModelParser
import com.github.sybila.checker.new.*
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.huctl.Formula
import com.github.sybila.huctl.HUCTLParser
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import java.util.*

fun String.asExperiment(): () -> Unit {
    val parser = ModelParser(CommonTokenStream(ModelLexer(ANTLRInputStream(this))))
    val experiment = ModelContext()
    ParseTreeWalker().walk(experiment, parser.root())
    return {
        val paramsMapping = experiment.params.toList()
        val stateMapping = experiment.states.flatMap { it }
        val fullParams = (0..Math.max(0, paramsMapping.size - 1)).toSet()
        val globalSolver = EnumeratedSolver(fullParams)
        val partitionMapping = experiment.states.mapIndexed { i, set ->
            i to set.map { stateMapping.indexOf(it) }
        }.toMap()
        val partitions = experiment.states.mapIndexed { i, set ->
            ExplicitPartitionFunction(i, inverseMapping = partitionMapping)
        }

        val solvers = partitions.map { EnumeratedSolver(fullParams) }

        fun Set<String>.readColors(solver: Solver<Set<Int>>): Set<Int> = if (this.isEmpty()) solver.tt else {
            this.map { paramsMapping.indexOf(it) }.toSet()
        }

        val fragments: List<Pair<Fragment<Set<Int>>, Solver<Set<Int>>>> = partitions.zip(solvers).map {
        val (partition, solver) = it
        val transitionFunction: Map<Int, List<Transition<Set<Int>>>>
                    = experiment.edges.groupBy { stateMapping.indexOf(it.from) }
                    .mapValues {
                        it.value.map {
                            val (from, to, dir, bound) = it
                            Transition(stateMapping.indexOf(it.to), dir, bound.readColors(solver))
                        }
                    }
        val atom: Map<Formula.Atom, Map<Int, Set<Int>>> = experiment.atom.map {
                val (atom, map) = it
                atom to map.mapKeys { stateMapping.indexOf(it.key) }.mapValues {
                    it.value.readColors(solver)
                }
            }.toMap()
            ExplicitFragment(partition, stateMapping.indices.filter {
                partition.run { it.owner() == partition.id }
            }.toSet(), transitionFunction, atom, solver) to solver
        }

        Checker(SharedMemComm(fragments.size), fragments).use { checker ->

            experiment.verify.forEach {
                println("$it -> ${checker.verify(it)}")
            }

            experiment.assert.forEach {
                println("Check assert $it")
                val (formula, input) = it


                val expected: StateMap<Set<Int>> = input.mapKeys {
                    stateMapping.indexOf(it.key)
                }.mapValues {
                    it.value.readColors(globalSolver)
                }.asStateMap(globalSolver.ff)

                val result = checker.verify(formula)

                println("Expected: $expected, got: $result")
                if (!deepEquals(expected to globalSolver, result.zip(solvers))) {
                    throw IllegalStateException("$formula error: expected $expected, but got $result")
                }
            }

        }

    }
}

private data class Edge(
        val from: String,
        val to: String,
        val direction: DirectionFormula.Atom,
        val bound: Set<String>
)

private class ModelContext : ModelBaseListener() {

    private val parser = HUCTLParser()

    internal val params: MutableSet<String> = HashSet()
    internal val states: MutableList<MutableSet<String>> = ArrayList()
    internal val edges: MutableList<Edge> = ArrayList()
    internal val verify: MutableList<Formula> = ArrayList()
    internal val assert: MutableList<Pair<Formula, Map<String, Set<String>>>> = ArrayList()
    internal val atom: MutableList<Pair<Formula.Atom, Map<String, Set<String>>>> = ArrayList()

    override fun exitParams(ctx: ModelParser.ParamsContext) {
        params.addAll(ctx.param().map { it.text })
    }

    override fun exitStates(ctx: ModelParser.StatesContext) {
        val partition = ctx.NUM()?.text?.toInt() ?: 0
        while (states.size <= partition) states.add(HashSet())
        states[partition].addAll(ctx.state().map { it.text })
    }

    override fun exitEdges(ctx: ModelParser.EdgesContext) {
        edges.addAll(ctx.edge().map {
            Edge(
                    from = it.state(0).text,
                    to = it.state(1).text,
                    direction = parser.dirAtom(it.STRING()?.readString() ?: "True"),
                    bound = it.param().map { it.text }.toSet()
            )
        })
    }

    override fun exitVerify(ctx: ModelParser.VerifyContext) {
        verify.add(parser.formula(ctx.STRING().readString()))
    }

    override fun exitAssert(ctx: ModelParser.AssertContext) {
        assert.add(parser.formula(ctx.STRING().readString()) to ctx.stateParams().map {
            it.state().text to it.param().map { it.text }.toSet()
        }.toMap())
    }

    override fun exitAtom(ctx: ModelParser.AtomContext) {
        atom.add(parser.atom(ctx.STRING().readString()) to ctx.stateParams().map {
            it.state().text to it.param().map { it.text }.toSet()
        }.toMap())
    }

    override fun visitErrorNode(node: ErrorNode) {
        throw IllegalStateException("Syntax error at '${node.text}' in ${node.symbol.line}")
    }

    private fun TerminalNode.readString() = this.text.filter { it != '"' }

}