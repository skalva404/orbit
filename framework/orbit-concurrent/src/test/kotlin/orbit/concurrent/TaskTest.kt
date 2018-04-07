/*
 Copyright (C) 2015 - 2018 Electronic Arts Inc.  All rights reserved.
 This file is part of the Orbit Project <http://www.orbit.cloud>.
 See license in LICENSE.
 */

package orbit.concurrent

import orbit.concurrent.job.JobManagers
import orbit.concurrent.task.Promise
import orbit.concurrent.task.Task
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class TaskTest {

    @Test
    fun testBasic() {
        val success = Task { "success" }
        Assertions.assertEquals("success", success.await())

        val fail = Task { throw TestException() }.map {}
        Assertions.assertThrows(TestException::class.java, { fail.await() })
    }

    @Test
    fun testMap() {
        val success = Task { 5 }.map { it * it }
        Assertions.assertEquals(25, success.await())

        val initialFail = Task<Int> { throw TestException() }.map { it * it }
        Assertions.assertThrows(TestException::class.java, { initialFail.await() })

        val mapFail = Task { 5 }.map { throw TestException() }
        Assertions.assertThrows(TestException::class.java, { mapFail.await() })
    }

    @Test
    fun testFlatMap() {
        val success = Task { 5 }.flatMap { x -> Task { x * x } }
        Assertions.assertEquals(25, success.await())

        val initialFail = Task<Int> { throw TestException() }.flatMap { x -> Task { x * x } }
        Assertions.assertThrows(TestException::class.java, { initialFail.await() })

        @Suppress("UNREACHABLE_CODE")
        val flatMapFail = Task { 5 }.flatMap { throw TestException(); Task { 5 } }
        Assertions.assertThrows(TestException::class.java, { flatMapFail.await() })

        @Suppress("UNREACHABLE_CODE")
        val flatMapNestedFail = Task { 5 }.flatMap { Task { throw TestException(); 5 } }
        Assertions.assertThrows(TestException::class.java, { flatMapNestedFail.await() })
    }

    @Test
    fun testDoAlways() {
        var didFire: Boolean

        didFire = false
        val success = Task { "success" }.doAlways { it.onSuccess { didFire = true } }
        Assertions.assertEquals("success", success.await())
        Assertions.assertTrue(didFire)

        didFire = false
        val fail = Task { throw TestException() }.doAlways { it.onFailure { didFire = true } }
        Assertions.assertThrows(TestException::class.java, { fail.await() })
        Assertions.assertTrue(didFire)
    }

    @Test
    fun testDoOnValue() {
        var didFire: Boolean

        didFire = false
        val success = Task { "success" }.doOnValue { didFire = true }
        Assertions.assertEquals("success", success.await())
        Assertions.assertTrue(didFire)

        didFire = false
        val fail = Task { throw TestException() }.doOnValue { didFire = true }
        Assertions.assertThrows(TestException::class.java, { fail.await() })
        Assertions.assertFalse(didFire)
    }

    @Test
    fun testDoOnError() {
        var didFire: Boolean

        didFire = false
        val success = Task { "success" }
        success.doOnError { didFire = true }
        Assertions.assertEquals("success", success.await())
        Assertions.assertFalse(didFire)

        didFire = false
        val fail = Task { throw TestException() }.doOnError { didFire = true }
        Assertions.assertThrows(TestException::class.java, { fail.await() })
        Assertions.assertTrue(didFire)
    }

    @Test
    fun testRunOn() {
        val newThread = JobManagers.newSingleThread()
        val dummyThread = JobManagers.newSingleThread()
        val threadLocal = ThreadLocal<Int>()

        var shouldMatch = false
        var shouldNotMatch = true

        val task = Task(newThread) {
            threadLocal.set(42)
        }.runOn {
            newThread
        }.doOnValue {
            shouldMatch = (threadLocal.get() == 42)
        }.runOn {
            dummyThread
        }.doOnValue {
            shouldNotMatch = (threadLocal.get() == 42)
        }
        task.await()
        Assertions.assertTrue(shouldMatch)
        Assertions.assertFalse(shouldNotMatch)
    }

    @Test
    fun testDelayedExecution() {
        var didFire: Boolean

        didFire = false
        val success = Task { "success" }
        success.await()
        success.doOnValue { didFire = true }
        Assertions.assertTrue(didFire)

    }

    @Test
    fun testJust() {
        var didTrigger = false
        val just = Task.just(42).doOnValue { didTrigger = true }
        Assertions.assertEquals(42, just.await())
        Assertions.assertTrue(didTrigger)
    }

    @Test
    fun testEmpty() {
        var didTrigger = false
        val empty = Task.empty().doOnValue { didTrigger = true }
        empty.await()
        Assertions.assertTrue(didTrigger)
    }

    @Test
    fun testFail() {
        var didTrigger = false
        val empty = Task.fail<Int>(TestException()).doOnError { didTrigger = true }
        Assertions.assertThrows(TestException::class.java, { empty.await() })
        Assertions.assertTrue(didTrigger)
    }

    @Test
    fun testPromise() {
        val successPromise = Promise<Int>()
        Assertions.assertFalse(successPromise.isComplete())
        Assertions.assertFalse(successPromise.isSuccessful())
        successPromise.complete(42)
        Assertions.assertTrue(successPromise.isComplete())
        Assertions.assertTrue(successPromise.isSuccessful())
        Assertions.assertEquals(42, successPromise.await())

        val failPromise = Promise<Int>()
        Assertions.assertFalse(failPromise.isComplete())
        Assertions.assertFalse(failPromise.isError())
        failPromise.completeExceptionally(TestException())
        Assertions.assertTrue(failPromise.isComplete())
        Assertions.assertTrue(failPromise.isError())
        Assertions.assertThrows(TestException::class.java, { failPromise.await() })
    }

    @Test
    fun testAllOf() {
        // Success
        val successPromise1 = Promise<Unit>()
        val successPromise2 = Promise<Unit>()
        val successAllOf = Task.allOf(successPromise1, successPromise2)

        Assertions.assertFalse(successAllOf.isComplete())
        successPromise1.complete(Unit)
        Assertions.assertFalse(successAllOf.isComplete())
        successPromise2.complete(Unit)
        Assertions.assertTrue(successAllOf.isComplete())
        Assertions.assertTrue(successAllOf.isSuccessful())

        // Fail
        val failPromise1 = Promise<Unit>()
        val failPromise2 = Promise<Unit>()
        val failAllOf = Task.allOf(failPromise1, failPromise2)

        Assertions.assertFalse(failAllOf.isComplete())
        failPromise1.complete(Unit)
        Assertions.assertFalse(failAllOf.isComplete())
        failPromise2.completeExceptionally(TestException())
        Assertions.assertTrue(failAllOf.isComplete())
        Assertions.assertTrue(failAllOf.isError())
    }

    @Test
    fun testAnyOf() {
        // Success
        val successPromise1 = Promise<Unit>()
        val successPromise2 = Promise<Unit>()
        val successAnyOf = Task.anyOf(successPromise1, successPromise2)

        Assertions.assertFalse(successAnyOf.isComplete())
        successPromise1.complete(Unit)
        Assertions.assertTrue(successAnyOf.isComplete())
        successPromise2.complete(Unit)
        Assertions.assertTrue(successAnyOf.isComplete())
        Assertions.assertTrue(successAnyOf.isSuccessful())

        // Fail
        val failPromise1 = Promise<Unit>()
        val failPromise2 = Promise<Unit>()
        val failAnyOf = Task.anyOf(failPromise1, failPromise2)

        Assertions.assertFalse(failAnyOf.isComplete())
        failPromise1.complete(Unit)
        Assertions.assertTrue(failAnyOf.isComplete())
        failPromise2.completeExceptionally(TestException())
        Assertions.assertTrue(failAnyOf.isComplete())
        Assertions.assertTrue(failAnyOf.isSuccessful())
    }


}