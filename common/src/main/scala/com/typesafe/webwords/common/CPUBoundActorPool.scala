package com.typesafe.webwords.common

import akka.actor._
import akka.routing._

/**
 * An actor pool with a bunch of reasonable defaults for actors
 * that do some kind of CPU-bound computation. General loose
 * assumptions are that having way more actors than cores is
 * counterproductive, that we only want to process each message
 * one time, and that the actors in the pool are relatively
 * lightweight.
 */
trait CPUBoundActorPool
    extends DefaultActorPool
    with SmallestMailboxSelector
    with BoundedCapacityStrategy
    with MailboxPressureCapacitor
    with Filter
    with BasicRampup
    with RunningMeanBackoff {
    self: Actor =>

    // Selector: selectionCount is how many pool members to send each message to
    override def selectionCount = 1
    // Selector: partialFill controls whether to pick less than selectionCount or
    // send the same message to duplicate delegates, when the pool is smaller
    // than selectionCount. Does not matter if lowerBound >= selectionCount.
    override def partialFill = true
    // BoundedCapacitor: create between lowerBound and upperBound delegates in the pool
    override val lowerBound = 1
    override lazy val upperBound = Runtime.getRuntime().availableProcessors() * 2
    // MailboxPressureCapacitor: pressure is number of delegates with >pressureThreshold messages queued
    override val pressureThreshold = 1
    // BasicRampup: rampupRate is percentage increase in capacity when all delegates are busy
    override def rampupRate = 0.2
    // RunningMeanBackoff: backoffThreshold is the running mean percentage-busy to drop below before
    // we reduce actor count
    override def backoffThreshold = 0.8
    // RunningMeanBackoff: backoffRate is the amount to back off when we are below backoffThreshold
    // on average
    override def backoffRate = 0.19
}
