/*
 * Copyright 2007-2009 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */
package net.liftweb.http

import _root_.net.liftweb.common._
import _root_.net.liftweb.util._
import Helpers._
import _root_.scala.collection.mutable.{HashMap, HashSet, ListBuffer}

/**
 * Keep session information around without the nastiness of naming session variables
 * or the type-unsafety of casting the results.
 * SessionVars are type-safe variables that map pretty directly to
 * HttpSession attributes.  Put stuff in and they are available for the
 * life of the Session.
 *
 * SessionVar's can be used even from CometActor's as now S scope in a Cometctor is
 * provided automatically.
 *
 * @param dflt - the default value of the session variable
 */
abstract class SessionVar[T](dflt: => T) extends AnyVar[T, SessionVar[T]](dflt) {
  override protected def findFunc(name: String): Box[T] = S.session.flatMap(_.get(name))

  override protected def setFunc(name: String, value: T): Unit = S.session.foreach(_.set(name, value))

  override protected def clearFunc(name: String): Unit = S.session.foreach(_.unset(name))

  override protected def wasInitialized(name: String): Boolean = {
    val bn = name + VarConstants.initedSuffix
    val old: Boolean = S.session.flatMap(_.get(bn)) openOr false
    S.session.foreach(_.set(bn, true))
    old
  }

  protected override def registerCleanupFunc(in: LiftSession => Unit): Unit =
    S.session.foreach(_.addSessionCleanup(in))

  type CleanUpParam = LiftSession
}

private[http] trait HasLogUneadVal {
  def logUnreadVal: Boolean
}


/**
 * Keep request-local information around without the nastiness of naming session variables
 * or the type-unsafety of casting the results.
 * RequestVars share their value through the scope of the current HTTP
 * request.  They have no value at the beginning of request servicing
 * and their value is discarded at the end of request processing.  They
 * are helpful to share values across many snippets.
 *
 * @param dflt - the default value of the session variable
 */
abstract class RequestVar[T](dflt: => T) extends AnyVar[T, RequestVar[T]](dflt) with HasLogUneadVal {
  type CleanUpParam = Box[LiftSession]

  override protected def findFunc(name: String): Box[T] = RequestVarHandler.get(name)

  override protected def setFunc(name: String, value: T): Unit = RequestVarHandler.set(name, this, value)

  override protected def clearFunc(name: String): Unit = RequestVarHandler.clear(name)

  override protected def wasInitialized(name: String): Boolean = {
    val bn = name + VarConstants.initedSuffix
    val old: Boolean = RequestVarHandler.get(bn) openOr false
    RequestVarHandler.set(bn, this, true)
    old
  }

  /**
   * Generate a function that will take a snapshot of the current RequestVars
   * such that they can be restored
   */
  final def generateSnapshotRestorer[T](): Function1[Function0[T], T] = RequestVarHandler.generateSnapshotRestorer()

  override protected def registerCleanupFunc(in: Box[LiftSession] => Unit): Unit =
    RequestVarHandler.addCleanupFunc(in)

  /**
   * This defines whether or not Lift will log when a RequestVar is set but then not read within
   * the same request cycle. Change this to false to turn off logging. Logging can also be turned
   * off globally via LiftRules.logUnreadRequestVars.
   *
   * @see LiftRules#logUnreadRequestVars
   */
  def logUnreadVal = true
}

/**
 * Keep request-local information around without the nastiness of naming session variables
 * or the type-unsafety of casting the results.
 * RequestVars share their value through the scope of the current HTTP
 * request.  They have no value at the beginning of request servicing
 * and their value is discarded at the end of request processing.  They
 * are helpful to share values across many snippets.
 *
 * @param dflt - the default value of the session variable
 */
private[http] abstract class TransientRequestVar[T](dflt: => T) extends AnyVar[T, TransientRequestVar[T]](dflt) with HasLogUneadVal {
  type CleanUpParam = Box[LiftSession]

  override protected def findFunc(name: String): Box[T] = TransientRequestVarHandler.get(name)

  override protected def setFunc(name: String, value: T): Unit = TransientRequestVarHandler.set(name, this, value)

  override protected def clearFunc(name: String): Unit = TransientRequestVarHandler.clear(name)

  override protected def wasInitialized(name: String): Boolean = {
    val bn = name + VarConstants.initedSuffix
    val old: Boolean = TransientRequestVarHandler.get(bn) openOr false
    TransientRequestVarHandler.set(bn, this, true)
    old
  }

  override protected def registerCleanupFunc(in: Box[LiftSession] => Unit): Unit =
    TransientRequestVarHandler.addCleanupFunc(in)

  /**
   * This defines whether or not Lift will log when a RequestVar is set but then not read within
   * the same request cycle. Change this to false to turn off logging. Logging can also be turned
   * off globally via LiftRules.logUnreadRequestVars.
   *
   * @see LiftRules#logUnreadRequestVars
   */
  def logUnreadVal = false
}

trait CleanRequestVarOnSessionTransition {
  self: RequestVar[_] =>
}

private[http] object RequestVarHandler extends CoreRequestVarHandler {
type MyType = RequestVar[_]
}

private[http] object TransientRequestVarHandler extends CoreRequestVarHandler {
type MyType = TransientRequestVar[_]
}

private[http] trait CoreRequestVarHandler {
  type MyType <: HasLogUneadVal
  // This maps from the RV name to (RV instance, value, set-but-not-read flag)
  private val vals: ThreadGlobal[HashMap[String, (MyType, Any, Boolean)]] = new ThreadGlobal
  private val cleanup: ThreadGlobal[ListBuffer[Box[LiftSession] => Unit]] = new ThreadGlobal
  private val isIn: ThreadGlobal[String] = new ThreadGlobal
  private val sessionThing: ThreadGlobal[Box[LiftSession]] = new ThreadGlobal


  /**
   * Generate a function that will take a snapshot of the current RequestVars
   * such that they can be restored
   */
  final def generateSnapshotRestorer[T](): Function1[Function0[T], T] =
    {
      val myVals = vals.value
      val mySessionThing = sessionThing.value

      f => isIn.doWith("in")(
        vals.doWith(myVals)(
          cleanup.doWith(new ListBuffer) {
            sessionThing.doWith(mySessionThing) {
              val ret: T = f()

              cleanup.value.toList.foreach(clean => Helpers.tryo(clean(sessionThing.value)))

              ret
            }
          }
          ))
    }

  private[http] def get[T](name: String): Box[T] =
    for (ht <- Box.legacyNullTest(vals.value);
         (rvInstance,value,unread) <- ht.get(name)) yield {
           if (unread) {
             // Flag the variable as no longer being set-but-unread
             ht(name) = (rvInstance : MyType, value.asInstanceOf[T], false)
           }
           value.asInstanceOf[T]
         }

  private[http] def set[T](name: String, from: MyType, value: T): Unit =
  for (ht <- Box.legacyNullTest(vals.value))
  ht(name) = (from, value, true)

  private[http] def clear(name: String): Unit =
    for (ht <- Box.legacyNullTest(vals.value))
      ht -= name

  private[http] def addCleanupFunc(f: Box[LiftSession] => Unit): Unit =
    for (cu <- Box.legacyNullTest(cleanup.value))
      cu += f

  def apply[T](session: Box[LiftSession], f: => T): T = {
    if ("in" == isIn.value) {
      val tv = vals.value

      // remove all the session variables that are CleanRequestVarOnSessionTransition
      val toRemove: Iterable[String] = tv.flatMap {
        case (name, (it: CleanRequestVarOnSessionTransition, _, _)) => List(name)
        case _ => Nil
      }

      toRemove.foreach(n => tv -= n)


      sessionThing.set(session)
      f
    } else
    isIn.doWith("in") (
      vals.doWith(new HashMap) (
        cleanup.doWith(new ListBuffer) {
          sessionThing.doWith(session) {
            val ret: T = f

            cleanup.value.toList.foreach(clean => Helpers.tryo(clean(sessionThing.value)))

            if (Props.devMode && LiftRules.logUnreadRequestVars) {
              vals.value.keys.filter(! _.startsWith(VarConstants.varPrefix+"net.liftweb"))
                .filter(! _.endsWith(VarConstants.initedSuffix))
                .foreach(key => vals.value(key) match {
                  case (rv,_,true) if rv.logUnreadVal => Log.warn("RequestVar %s was set but not read".format(key.replace(VarConstants.varPrefix,"")))
                  case _ =>
                })
            }

            ret
          }
        }
      )
    )
  }
}


object AnyVar {
  implicit def whatSessionVarIs[T](in: SessionVar[T]): T = in.is

  implicit def whatRequestVarIs[T](in: RequestVar[T]): T = in.is
}

