package com.github.sybila.checker.model

import com.github.sybila.checker.*
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.huctl.Formula

class ExplicitPartition<Params : Any>(
        override val partitionId: Int,
        override val partitionCount: Int,
        override val stateCount: Int,
        private val states: List<Set<Int>>,
        private val successorMap: Map<Int, List<Transition<Params>>>,
        private val validity: Map<Formula.Atom, StateMap<Params>>,
        solver: Solver<Params>
) : Partition<Params>, Solver<Params> by solver {

    private val predecessorMap = successorMap.asSequence().flatMap {
        //direction is not flipped, because we are not going back in time
        it.value.asSequence().map { t -> t.target to Transition(it.key, t.direction, t.bound) }
    }.groupBy({ it.first }, { it.second })

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<Params>> {
        return ((if (timeFlow) successorMap[this] else predecessorMap[this]?.map {
            if (it.direction is DirectionFormula.Atom.Proposition) {
                it.copy(direction = it.direction.negate())
            } else it
        }) ?: listOf()).iterator()
    }

    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<Params>> {
        return ((if (timeFlow) predecessorMap[this] else successorMap[this]?.map {
            if (it.direction is DirectionFormula.Atom.Proposition) {
                it.copy(direction = it.direction.negate())
            } else it
        }) ?: listOf()).iterator()
    }

    override fun Formula.Atom.Float.eval(): StateMap<Params> {
        return validity[this] ?: emptyStateMap()
    }

    override fun Formula.Atom.Transition.eval(): StateMap<Params> {
        return validity[this] ?: emptyStateMap()
    }

    override fun Int.owner(): Int = states.indexOfFirst { this in it }

}