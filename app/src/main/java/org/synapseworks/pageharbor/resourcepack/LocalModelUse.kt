package org.synapseworks.pageharbor.resourcepack
/** All local consumers serialize native ownership until actual cleanup returns. */
object LocalModelUse { val running=java.util.concurrent.atomic.AtomicBoolean(false) }
