package com.github.sybila.checker

import com.github.sybila.huctl.Formula


/**
 * A transition system with successor/predecessor functions and proposition evaluation.
 */
interface TransitionSystem : Solver {

    /**
     * Total number of states.
     */
    val stateCount: Int

    /**
     * Successor/Predecessors of given state.
     *
     * timeFlow == true -> Normal time flow.
     * timeFlow == false -> Reversed transition system.
     *
     * Note: predecessors/successors have inverted directions, hence we can't just
     * decide based on timeFlow.
     *
     * @Contract state \in (0 until stateCount)
     */
    fun Int.successors(timeFlow: Boolean): Sequence<Transition>
    fun Int.predecessors(timeFlow: Boolean): Sequence<Transition>

    /**
     * Proposition evaluation.
     */
    fun Formula.Atom.Float.eval(): StateMap
    fun Formula.Atom.Transition.eval(): StateMap

}