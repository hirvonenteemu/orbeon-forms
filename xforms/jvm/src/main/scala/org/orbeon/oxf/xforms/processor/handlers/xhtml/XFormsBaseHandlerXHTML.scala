/**
  * Copyright (C) 2017 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.oxf.xforms.processor.handlers.xhtml

import java.{lang => jl}

import org.apache.commons.lang3.StringUtils
import org.orbeon.oxf.common.ValidationException
import org.orbeon.oxf.xforms.analysis.controls.{LHHA, LHHAAnalysis, _}
import org.orbeon.oxf.xforms.analysis.model.ValidationLevel
import org.orbeon.oxf.xforms.control._
import org.orbeon.oxf.xforms.processor.handlers.{HandlerContext, XFormsBaseHandler}
import org.orbeon.oxf.xforms.{XFormsConstants, XFormsUtils}
import org.orbeon.oxf.xml.dom4j.{Dom4jUtils, LocationData}
import org.orbeon.oxf.xml.{XMLReceiverHelper, _}
import org.xml.sax.Attributes
import org.xml.sax.helpers.AttributesImpl


// Created this just so we can reuse `getContainerAttributes`. Fix this as we complete conversion of
// code to Scala.
abstract class XFormsBaseHandlerXHTML (
  uri            : String,
  localname      : String,
  qName          : String,
  localAtts      : Attributes,
  matched        : Any,
  handlerContext : Any,
  repeating      : Boolean,
  forwarding     : Boolean
) extends
  XFormsBaseHandler(
    uri,
    localname,
    qName,
    localAtts,
    matched,
    handlerContext,
    repeating,
    forwarding
  ) {

  import XFormsBaseHandlerXHTML._

  // Used by `XFormsSelect1Handler` only
  def getHandlerContext: HandlerContext = this.xformsHandlerContext

  final val getPrefixedId  = xformsHandlerContext.getPrefixedId(attributes)
  final val getEffectiveId = xformsHandlerContext.getEffectiveId(attributes)

  // May be overridden by subclasses
  protected def isDefaultIncremental                                                = false
  protected def addCustomClasses(classes: jl.StringBuilder, control: XFormsControl) = ()

  // Output MIP classes
  // TODO: Move this to to control itself, like writeMIPsAsAttributes
  final def handleMIPClasses(controlPrefixedId: String, control: XFormsControl)(implicit sb: jl.StringBuilder): Unit = {

    // xforms-disabled class
    // NOTE: We used to not output this class if the control existed and didn't have a binding. That looked either
    // like an inconsistent optimization (not done for controls with bindings), or like an oversight (likely).
    val isRelevant = control != null && control.isRelevant

    // don't output class within a template
    if (! isRelevant)
      appendWithSpace("xforms-disabled")

    // MIP classes for a concrete control
    if (isRelevant && xformsHandlerContext.getPartAnalysis.hasBinding(controlPrefixedId)) {

      if (control.visited)
        appendWithSpace("xforms-visited")

      control match {
        case singleNodeControl: XFormsSingleNodeControl =>

          addConstraintClasses(sb, singleNodeControl.alertLevel)

          if (singleNodeControl.isReadonly)
            appendWithSpace("xforms-readonly")

          if (singleNodeControl.isRequired) {
            appendWithSpace("xforms-required")
            control match {
              case valueControl: XFormsValueControl =>
                // NOTE: Test above excludes xf:group
                if (valueControl.isEmptyValue)
                  appendWithSpace("xforms-empty")
                else
                  appendWithSpace("xforms-filled")
              case _ =>
            }
          }

          val customMIPs = singleNodeControl.customMIPsClasses
          if (customMIPs.nonEmpty)
            appendWithSpace(customMIPs mkString " ")

          singleNodeControl.getBuiltinOrCustomTypeCSSClassOpt foreach
            appendWithSpace

        case _ =>
      }
    }
  }

  final protected def getInitialClasses(
    controlURI         : String,
    controlName        : String,
    controlAttributes  : Attributes,
    control            : XFormsControl,
    incrementalDefault : Boolean
  ): jl.StringBuilder = {

    implicit val sb = new jl.StringBuilder(50)

    // User-defined classes go first
    appendControlUserClasses(controlAttributes, control)

    // Component control doesn't get xforms-control, xforms-[control name], incremental, mediatype, xforms-static
    if (! elementAnalysis.isInstanceOf[ComponentControl]) {

      // We only call xforms-control the actual controls as per the spec
      // NOTE: XForms 1.1 has core and container controls but our client depends on having `xforms-control`.
      if (! XFormsControlFactory.isContainerControl(controlURI, controlName))
        appendWithSpace("xforms-control xforms-")
      else
        appendWithSpace("xforms-")

      sb.append(controlName)

      // Class for incremental mode
      val value = controlAttributes.getValue("incremental")

      // Set the class if the default is non-incremental and the user explicitly set the value to true, or the
      // default is incremental and the user did not explicitly set it to false
      if ((! incrementalDefault && value == "true") || (incrementalDefault && value != "false"))
        appendWithSpace("xforms-incremental")

      // Class for mediatype
      Option(controlAttributes.getValue("mediatype")) foreach { mediatypeValue =>

        // NOTE: We could certainly do a better check than this to make sure we have a valid mediatype
        val slashIndex = mediatypeValue.indexOf('/')
        if (slashIndex == -1)
          throw new ValidationException(s"Invalid mediatype attribute value: $mediatypeValue", xformsHandlerContext.getLocationData)

        appendWithSpace("xforms-mediatype-")

        if (mediatypeValue.endsWith("/*")) {
          // Add class with just type: "image/*" -> "xforms-mediatype-image"
          sb.append(mediatypeValue.substring(0, mediatypeValue.length - 2))
        } else {
          // Add class with type and subtype: "text/html" -> "xforms-mediatype-text-html"
          sb.append(mediatypeValue.replace('/', '-'))
          // Also add class with just type: "image/jpeg" -> "xforms-mediatype-image"
          sb.append(" xforms-mediatype-")
          sb.append(mediatypeValue.substring(0, slashIndex))
        }
      }

      // Static read-only
      if (XFormsBaseHandler.isStaticReadonly(control))
        appendWithSpace("xforms-static")
    }

    // Classes for appearances
    elementAnalysis match {
      case appearanceTrait: AppearanceTrait => appearanceTrait.encodeAndAppendAppearances(sb)
      case _ =>
    }

    sb
  }

  final protected def appendControlUserClasses(
    controlAttributes : Attributes,
    control           : XFormsControl)(implicit
    sb                : jl.StringBuilder
  ): Unit = {
    // @class
    val evaluatedClassValueOpt =
      Option(controlAttributes.getValue("class")) flatMap { classValue =>
        if (!XFormsUtils.maybeAVT(classValue)) {
          // Definitely not an AVT
          Some(classValue)
        } else {
          // Possible AVT
          if (control ne null) {
            // Ask the control if possible
            control.extensionAttributeValue(XFormsConstants.CLASS_QNAME)
          } else {
            // Otherwise we can't compute it
            None
          }
        }
      }

    evaluatedClassValueOpt foreach appendWithSpace
  }

  final protected def handleLabelHintHelpAlert(
    lhhaAnalysis             : LHHAAnalysis,
    targetControlEffectiveId : String,
    forEffectiveId           : String,
    lhha                     : LHHA,
    requestedElementNameOpt  : Option[String],
    controlOrNull            : XFormsControl,
    isExternal               : Boolean
  ): Unit = {

    val isInternal = lhhaAnalysis.appearances(XFormsConstants.XXFORMS_INTERNAL_APPEARANCE_QNAME)
    val staticLHHAAttributes = Dom4jUtils.getSAXAttributes(lhhaAnalysis.element)

    if (
      ! isInternal &&
      (
        (staticLHHAAttributes ne null) ||
        lhha == LHHA.Alert
      )) {

      // If no attributes were found, there is no such label / help / hint / alert

      val (labelHintHelpAlertValue, mustOutputHTMLFragment) =
        lhha match {
          case LHHA.Label | LHHA.Hint if controlOrNull ne null =>
            (controlOrNull.lhhaProperty(lhha).value(), lhhaAnalysis.containsHTML)
          case LHHA.Label | LHHA.Hint =>
            (null, lhhaAnalysis.containsHTML)
          case LHHA.Help if controlOrNull ne null =>
            // NOTE: Special case here where we get the escaped help to facilitate work below. Help is a special
            // case because it is stored as escaped HTML within a <label> element.
            (controlOrNull.lhhaProperty(lhha).escapedValue(), false)
          case LHHA.Help =>
            (null, false)
          case LHHA.Alert if controlOrNull ne null =>
            // Not known statically at this time because it currently depends on the number of active alerts
            (controlOrNull.lhhaProperty(lhha).value(), controlOrNull.isHTMLAlert)
          case LHHA.Alert =>
            (null, false)
        }

      val elementName =
        requestedElementNameOpt getOrElse {
          lhha match {
            case LHHA.Label => xformsHandlerContext.getLabelElementName
            case LHHA.Help  => xformsHandlerContext.getHelpElementName
            case LHHA.Hint  => xformsHandlerContext.getHintElementName
            case LHHA.Alert => xformsHandlerContext.getAlertElementName
          }
        }

      implicit val classes = new jl.StringBuilder(30)

      // Put user classes first if any
      if (staticLHHAAttributes ne null) {
        val userClass = staticLHHAAttributes.getValue("class")
        if (userClass ne null)
          appendWithSpace(userClass)
      }

      // For now only place these on label and hint as that's the ones known statically
      // AND useful for Form Builder.
      if ((lhha == LHHA.Label || lhha == LHHA.Hint) && mustOutputHTMLFragment) {
        appendWithSpace("xforms-mediatype-text-html")
      }

      // Mark alert as active if needed
      if (lhha == LHHA.Alert)
        controlOrNull match {
          case singleNodeControl: XFormsSingleNodeControl =>

            val constraintLevelOpt = singleNodeControl.alertLevel

            constraintLevelOpt foreach { _ =>
              appendWithSpace("xforms-active")
            }

            // Constraint classes are placed on the control if the alert is not external
            if (isExternal)
              addConstraintClasses(classes, constraintLevelOpt)

          case _ =>
        }

      // Handle visibility
      // TODO: It would be great to actually know about the relevance of help, hint, and label. Right now, we just look at whether the value is empty
      if (controlOrNull != null) {
        if (! controlOrNull.isRelevant)
          appendWithSpace("xforms-disabled")
      } else if (lhha == LHHA.Help) {
        // Null control outside of template OR help within template
        appendWithSpace("xforms-disabled")
      }
      // LHHA name
      appendWithSpace("xforms-")
      classes.append(lhha.entryName)

      // We handle null attributes as well because we want a placeholder for "alert" even if there is no xf:alert
      val newAttributes =
        if (staticLHHAAttributes ne null)
          staticLHHAAttributes
      else
          new AttributesImpl

      lhhaAnalysis.encodeAndAppendAppearances(classes)

      XFormsBaseHandlerXHTML.outputLabelFor(
        xformsHandlerContext,
        getIdClassXHTMLAttributes(newAttributes, classes.toString, null),
        targetControlEffectiveId,
        forEffectiveId,
        lhha,
        elementName,
        labelHintHelpAlertValue,
        mustOutputHTMLFragment,
        isExternal
      )
    }
  }

  final protected def getContainerAttributes(
    uri           : String,
    localname     : String,
    attributes    : Attributes,
    prefixedId    : String,
    effectiveId   : String,
    xformsControl : XFormsControl
  ): AttributesImpl = {

    // Get classes
    // Initial classes: `xforms-control`, `xforms-[control name]`, `incremental`, `appearance`, `mediatype`, `xforms-static`
    implicit val classes =
      getInitialClasses(uri, localname, attributes, xformsControl, isDefaultIncremental)

    // All MIP-related classes
    handleMIPClasses(prefixedId, xformsControl)

    // Static classes
    xformsHandlerContext.getPartAnalysis.appendClasses(classes, prefixedId)

    // Dynamic classes added by the control
    addCustomClasses(classes, xformsControl)

    // Get attributes
    val newAttributes = getIdClassXHTMLAttributes(attributes, classes.toString, effectiveId)

    // Add extension attributes in no namespace if possible
    if (xformsControl ne null)
      xformsControl.addExtensionAttributesExceptClassAndAcceptForHandler(newAttributes, "")

    newAttributes
  }
}

object XFormsBaseHandlerXHTML {

  val LHHACodes = Map[LHHA, String](
    LHHA.Label -> "l",
    LHHA.Help  -> "p",
    LHHA.Hint  -> "t",
    LHHA.Alert -> "a"
  )

  val ControlCode = "c"
  val LabelCode   = LHHACodes(LHHA.Label)

  def appendWithSpace(s: String)(implicit sb: jl.StringBuilder): Unit = {
    if (sb.length > 0)
      sb.append(' ')
    sb.append(s)
  }

  def addConstraintClasses(sb: jl.StringBuilder, constraintLevelOpt: Option[ValidationLevel]): Unit =
    constraintLevelOpt foreach { constraintLevel =>
      val levelName = constraintLevel.entryName
      if (sb.length > 0)
        sb.append(' ')
      sb.append("xforms-")
      // level is called "error" but we use "invalid" on the client
      sb.append(if (levelName == "error") "invalid" else levelName)
    }

  def outputLabelFor(
    handlerContext           : HandlerContext,
    attributes               : Attributes,
    targetControlEffectiveId : String,
    forEffectiveId           : String,
    lhha                     : LHHA,
    elementName              : String,
    labelValue               : String,
    mustOutputHTMLFragment   : Boolean,
    addIds: Boolean
  ): Unit = {
    outputLabelForStart(handlerContext, attributes, targetControlEffectiveId, forEffectiveId, lhha, elementName, addIds)
    outputLabelText(handlerContext.getController.getOutput, labelValue, handlerContext.findXHTMLPrefix, mustOutputHTMLFragment, None)
    outputLabelForEnd(handlerContext, elementName)
  }

  def outputLabelForStart(
    handlerContext           : HandlerContext,
    attributes               : Attributes,
    targetControlEffectiveId : String,
    forEffectiveId           : String,
    lhha                     : LHHA,
    elementName              : String,
    addIds                   : Boolean
  ): Unit = {

    require(lhha ne null)
    require(! addIds || (targetControlEffectiveId ne null))

    // Replace id attribute to be foo-label, foo-hint, foo-help, or foo-alert
    val newAttribute =
      if (addIds && (targetControlEffectiveId ne null)) {
        // Add or replace existing id attribute
        // NOTE: addIds == true for external LHHA
        SAXUtils.addOrReplaceAttribute(
          attributes,
          "",
          "",
          "id",
          XFormsBaseHandler.getLHHACId(handlerContext.getContainingDocument, targetControlEffectiveId, XFormsBaseHandlerXHTML.LHHACodes(lhha))
        )
      } else {
        // Remove existing id attribute if any
        SAXUtils.removeAttribute(attributes, "", "id")
      }

    // Add @for attribute if specified and element is a label
    if ((forEffectiveId ne null) && elementName == "label")
      newAttribute.addAttribute("", "for", "for", XMLReceiverHelper.CDATA, forEffectiveId)

    val xhtmlPrefix    = handlerContext.findXHTMLPrefix
    val labelQName     = XMLUtils.buildQName(xhtmlPrefix, elementName)
    val contentHandler = handlerContext.getController.getOutput

    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, elementName, labelQName, newAttribute)
  }

  def outputLabelForEnd(handlerContext: HandlerContext, elementName: String): Unit = {

    val xhtmlPrefix = handlerContext.findXHTMLPrefix
    val labelQName  = XMLUtils.buildQName(xhtmlPrefix, elementName)
    val xmlReceiver = handlerContext.getController.getOutput

    xmlReceiver.endElement(XMLConstants.XHTML_NAMESPACE_URI, elementName, labelQName)
  }

  def outputLabelText(
    xmlReceiver            : XMLReceiver,
    value                  : String,
    xhtmlPrefix            : String,
    mustOutputHTMLFragment : Boolean,
    locationDataOpt        : Option[LocationData]
  ): Unit =
    if (StringUtils.isNotEmpty(value)) {
      if (mustOutputHTMLFragment)
        XFormsUtils.streamHTMLFragment(
          xmlReceiver,
          value,
          locationDataOpt.orNull,
          xhtmlPrefix
        )
      else
        xmlReceiver.characters(value.toCharArray, 0, value.length)
  }

  def outputDisabledAttribute(newAttributes: AttributesImpl): Unit = {
    // @disabled="disabled"
    // HTML 4: @disabled supported on: input, button, select, optgroup, option, and textarea.
    newAttributes.addAttribute("", "disabled", "disabled", XMLReceiverHelper.CDATA, "disabled")
  }
}