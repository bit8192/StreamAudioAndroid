package cn.bincker.stream.sound

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class KotlinTest {
    data class Foo(var name: String)
    @Test
    fun scopeTest() {
        runBlocking {
            val f1 = MutableStateFlow(Foo(""))
            val nf = f1.map { it.name }.filter { it.isNotBlank() }.map {
                println("map: $it")
                "hello $it"
            }

            val job = launch {
                nf.collect {
                    println("nf collect: $it")
                }
            }

            f1.value = Foo("aaa")
            delay(100)
            f1.value = Foo("aaa")
            delay(100)
            f1.value = Foo("")
            delay(100)
            f1.value = Foo("ccc")
            delay(100)
            f1.value = Foo("ddd")
            delay(100)
            job.cancelAndJoin()
        }
    }
}