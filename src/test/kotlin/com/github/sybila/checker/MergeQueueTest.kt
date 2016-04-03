package com.github.sybila.checker

import com.github.daemontus.egholm.thread.guardedThread
import com.github.daemontus.jafra.Terminator
import com.github.daemontus.jafra.Token
import org.junit.Test
import java.util.*
import java.util.concurrent.FutureTask
import kotlin.test.assertEquals


class BigMergeQueueTest : MergeQueueTest() {
    override val repetitions: Int = 2
    override val processCount: Int = 24
}

class SmallMergeQueueTest : MergeQueueTest() {
    override val repetitions: Int = 100
    override val processCount: Int = 4
}

class OneMergeQueueTest : MergeQueueTest() {
    override val repetitions: Int = 100
    override val processCount: Int = 1
}


abstract class MergeQueueTest: JobModifyingQueueTest() {

    override fun createJobQueues(
            processCount: Int,
            partitioning: List<PartitionFunction<IDNode>>,
            communicators: List<Communicator>,
            terminators: List<Terminator.Factory>
    ): List<JobQueue.Factory<IDNode, IDColors>>
            = createMergeQueues(processCount, partitioning, communicators, terminators)
}

/**
 * And abstract set of tests for your job queue implementation.
 * Just override queue constructor.
 */

private fun List<Job<IDNode, IDColors>>.merge(): List<Job<IDNode, IDColors>> {
    return this.groupBy { it.path() }.entries.map {
        Job(it.key.first, it.key.second, it.value.fold(IDColors()) { a, b -> a + b.colors })
    }
}

abstract class JobModifyingQueueTest {

    abstract val processCount: Int
    abstract val repetitions: Int

    abstract fun createJobQueues(
            processCount: Int,
            partitioning: List<PartitionFunction<IDNode>> = (1..processCount).map { UniformPartitionFunction<IDNode>(it - 1) },
            communicators: List<Communicator>,
            terminators: List<Terminator.Factory>
    ): List<JobQueue.Factory<IDNode, IDColors>>

    private val allColors = (1..5).toSet()

    private fun randomEuJob(): Job<IDNode, IDColors> = Job(
            IDNode((Math.random() * 10).toInt()),
            IDNode((Math.random() * 10).toInt()),
            IDColors(allColors.randomSubset())
    )

    //safely create and close communicators!
    private fun withQueues(
            partitioning: List<PartitionFunction<IDNode>> = (1..processCount).map { UniformPartitionFunction<IDNode>(it - 1) },
            task: (List<JobQueue.Factory<IDNode, IDColors>>) -> Unit
    ) {
        val communicators = createSharedMemoryCommunicators(processCount)
        val messengers = communicators.map { CommunicatorTokenMessenger(it.id, it.size) }
        messengers.zip(communicators).forEach {
            it.first.comm = it.second
            it.second.addListener(Token::class.java) { m -> it.first.invoke(m) }
        }
        val terminators = messengers.toFactories()

        try {
            task(createJobQueues(processCount, partitioning, communicators, terminators))
        } finally {
            messengers.map { it.close() }
            communicators.map { it.close() }
        }
    }

    @Test(timeout = 1000)
    fun noMessages() {
        withQueues {
            it.map { f ->
                guardedThread {
                    val q = f.createNew() { }
                    q.waitForTermination()
                }
            }.map { it.join() }
        }
    }

    @Test(timeout = 1000)
    fun onlyInitialTest() {
        repeat(repetitions) {
            withQueues {
                it.map { f ->
                    guardedThread {

                        val executed = ArrayList<Job<IDNode, IDColors>>()
                        val jobs = (1..10).map { randomEuJob() }


                        val q = f.createNew(jobs) {
                            synchronized(executed) {
                                executed.add(it)
                            }
                        }

                        q.waitForTermination()

                        assertEquals(
                                jobs.merge().sortedWith(jobComparator),
                                executed.merge().sortedWith(jobComparator)
                        )
                    }
                }.map { it.join() }
            }
        }
    }

    @Test(timeout = 5000)
    fun complexTest() {

        repeat(repetitions) {
            //As in messenger tests, create a flood of jobs that will jump across state space

            withQueues(
                    (1..processCount).map { i -> FunctionalPartitionFunction<IDNode>(i - 1) { it.id % processCount } }
            ) {
                val allJobs = it.map { f ->
                    FutureTask {

                        val executed = ArrayList<Job<IDNode, IDColors>>()
                        val posted = HashMap((1..processCount).associateBy({ it - 1 }, { ArrayList<Job<IDNode, IDColors>>() }))

                        val initial = (1..(processCount)).map { randomEuJob() }
                        initial.forEach { job ->
                            synchronized(posted) {
                                posted[job.target.id % processCount]!!.add(job)
                            }
                        }

                        val queue = f.createNew(initial) {
                            synchronized(executed) { executed.add(it) }
                            if (it.target.id != 0) {
                                val newNodeId = it.target.id - 1
                                val newJob = Job(it.target, IDNode(newNodeId), it.colors)
                                synchronized(posted) {
                                    posted[newNodeId % processCount]!!.add(newJob)
                                }
                                this.post(newJob)
                            }
                        }

                        queue.waitForTermination()

                        Pair(posted, executed)
                    }
                }.map { guardedThread { it.run() }; it }.map { it.get() }

                //Merge sent messages by their destinations into something that has same type as received list
                val sent = allJobs.map { it.first }.foldRight(
                        HashMap((0..(processCount - 1)).map { Pair(it, listOf<Job<IDNode, IDColors>>()) }.toMap())
                ) { value, accumulator ->
                    for ((key, list) in value) {
                        accumulator[key] = list + accumulator[key]!!
                    }
                    accumulator
                }.mapValues {
                    it.value.merge().sortedWith(jobComparator)
                }
                val received = allJobs.map { it.second }.mapIndexed { i, list -> Pair(i, list.merge().sortedWith(jobComparator)) }.toMap()

                assertEquals(received, sent)

                //For debugging:
                //System.out.println("Transferred: ${sent.values.fold(0, { f, s -> f + s.size })}")

            }

        }
    }

}