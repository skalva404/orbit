/*
 Copyright (C) 2015 - 2018 Electronic Arts Inc.  All rights reserved.
 This file is part of the Orbit Project <http://www.orbit.cloud>.
 See license in LICENSE.
 */

package orbit.concurrent.task.operator

import orbit.util.tries.Try


internal class TaskDoOnError<T>(private val body: (Throwable) -> Unit) : TaskOperator<T, T>() {
    override fun operator(item: Try<T>) {
        val x: Try<T> = try {
            item.onFailure(body)
            item
        } catch (throwable: Throwable) {
            Try.failed(throwable)
        }
        publish(x)
    }
}