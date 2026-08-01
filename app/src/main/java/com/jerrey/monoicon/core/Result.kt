package com.jerrey.monoicon.core

/**
 * A discriminated union that represents either a successful value [T]
 * or a failure with a [Throwable].
 *
 * Unlike [kotlin.Result], this sealed class can be used as a property,
 * return type of non-inline functions, and is serializable.
 *
 * @param T The type of the success value.
 */
sealed class Result<out T> {

    /** Contains the successful value. */
    data class Success<T>(val value: T) : Result<T>()

    /** Contains the failure information. */
    data class Error(
        val throwable: Throwable,
        val message: String? = throwable.message
    ) : Result<Nothing>()

    /** Returns `true` if this instance represents a successful outcome. */
    val isSuccess: Boolean get() = this is Success

    /** Returns `true` if this instance represents a failed outcome. */
    val isError: Boolean get() = this is Error

    /**
     * Maps a [Result] of type [T] to a [Result] of type [R] by applying
     * [transform] to the success value. Errors pass through unchanged.
     */
    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> of { transform(value) }
        is Error -> this
    }

    /**
     * Chains another operation that itself returns a [Result].
     * Errors pass through unchanged.
     */
    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
        is Success -> transform(value)
        is Error -> this
    }

    /**
     * Returns the success value, or `null` if this is an [Error].
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Error -> null
    }

    /**
     * Returns the success value, or throws the underlying [Throwable]
     * if this is an [Error].
     */
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Error -> throw throwable
    }

    /**
     * Returns the success value, or [defaultValue] if this is an [Error].
     */
    fun getOrDefault(defaultValue: @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Error -> defaultValue
    }

    companion object {
        /**
         * Runs [block] and wraps its result in a [Result].
         * Any thrown exception is caught and wrapped as [Error].
         */
        inline fun <T> of(block: () -> T): Result<T> = try {
            Success(block())
        } catch (e: Throwable) {
            Error(e)
        }
    }
}
