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
package net.liftweb.builtin.snippet

import _root_.scala.xml._
import _root_.net.liftweb.http._
import _root_.net.liftweb.util._
import _root_.net.liftweb.common._

import Helpers._

/**
 * This object is the default handler for the &lt;lift:form&gt; tag, which
 * is used to perform AJAX submission of form contents. If the "onsubmit"
 * attribute is set on this tag, then the contents there will be run prior
 * to the actual AJAX call. If a "postsubmit" attribute is present on the
 * tag, then its contents will be executed after successful submission of
 * the form.
 */
object Form extends DispatchSnippet {

  def dispatch : DispatchIt = {
    case "render" => render _
    case "post" => post _
  }

  def post(kids: NodeSeq): NodeSeq =
  <form method="post" action={S.uri}>{kids}</form>

  def render(kids: NodeSeq) : NodeSeq = Elem(null, "form", addAjaxForm, TopScope, kids : _*)

  private def addAjaxForm: MetaData = {
    val id = Helpers.nextFuncName

    val attr = S.currentAttrsToMetaData(name => name != "id" && name != "onsubmit" && name != "action")

    val pre = S.attr.~("onsubmit").map(_.text + ";") getOrElse ""

    val post = S.attr.~("postsubmit").map("function() { " + _.text + "; }")

    val ajax: String = pre + SHtml.makeAjaxCall(LiftRules.jsArtifacts.serialize(id), AjaxContext.js(post)).toJsCmd + ";" + "return false;"

    new UnprefixedAttribute("id", Text(id),
                            new UnprefixedAttribute("action", Text("javascript://"),
                                                    new UnprefixedAttribute("onsubmit", Text(ajax), attr)))
  }
}

