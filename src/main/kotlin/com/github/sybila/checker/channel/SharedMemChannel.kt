package com.github.sybila.checker.channel

import com.github.sybila.checker.Channel
import com.github.sybila.checker.CheckerStats
import com.github.sybila.checker.Partition
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.CyclicBarrier

fun <Params : Any> List<Partition<Params>>.connectWithSharedMemory(): List<Channel<Params>>
        = SharedMemChannel.connect(this)

/**
 * Channel structure:
 * channel(i)(j) = data from worker j for worker i
 *
 * Send: Each worker will input one row.
 * Receive: Each worker will read it's column.
 */
class SharedMemChannel<Params : Any> private constructor(
        partition: Partition<Params>,
        private val barrier: CyclicBarrier,
        private val channels: Array<Array<ByteBuffer?>>
) : Channel<Params>, Partition<Params> by partition {

    companion object {
        fun <Params : Any> connect(list: List<Partition<Params>>): List<Channel<Params>> {
            val barrier = CyclicBarrier(list.size)
            val channels = Array(list.size) { arrayOfNulls<ByteBuffer>(list.size) }
            return list.map { SharedMemChannel(it, barrier, channels) }
        }
    }

    override fun mapReduce(outgoing: Array<List<Pair<Int, Params>>?>): List<Pair<Int, Params>>? {
        // prepare buffers
        val buffers = outgoing.map { list ->
            list?.let { list ->
                val bufferSize = list.fold(4) { a, pair ->
                    a + 4 + pair.second.byteSize()
                }
                val buffer = obtainBuffer(2*bufferSize)
                buffer.clear()
                buffer.putInt(list.size)
                list.forEach {
                    buffer.putInt(it.first)
                    buffer.putColors(it.second)
                }
                buffer.flip()
                buffer
            }
        }
        CheckerStats.mapReduce(buffers.sumBy { it?.limit() ?: 0 }.toLong())
        barrier.await()
        // everyone has a prepared transmission, now everyone will write it into channels
        buffers.forEachIndexed { i, byteBuffer ->
            channels[i][partitionId] = byteBuffer
        }
        barrier.await()
        // all transmissions are in place. Check if somebody sent something and then collect your data
        val done = channels.all { it.all { it == null } }
        val result: List<Pair<Int, Params>>?
        if (!done) {
            result = ArrayList<Pair<Int, Params>>()
            channels[partitionId].forEach {
                it?.let { buffer ->
                    repeat(buffer.int) {
                        result.add(buffer.int to buffer.getColors())
                    }
                }
            }
        } else {
            result = null
        }
        barrier.await()
        // everyone has a result, so we can recycle buffers and clear channels
        channels[partitionId].indices.forEach { channels[partitionId][it] = null }
        buffers.forEach { if (it != null) recycleBuffer(it) }
        return result
    }

}