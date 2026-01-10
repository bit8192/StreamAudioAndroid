package cn.bincker.stream.sound

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class KotlinTest {

    @Test
    fun emitTest() {
        runBlocking {
            val flow = MutableStateFlow(0)
            val job = launch {
                flow.filter { it > 0 }.first().let {
                    println("Collected: $it")
                }
                println("Collector ended")
            }
            launch {
                for (i in -5..10) {
                    println("Emitting: $i")
                    flow.emit(i)
                    delay(100)
                }
            }.join()
            job.join()
        }
    }
}