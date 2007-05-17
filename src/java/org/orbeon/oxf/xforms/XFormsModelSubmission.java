/**
 *  Copyright (C) 2005 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.apache.log4j.Logger;
import org.dom4j.*;
import org.dom4j.io.DocumentSource;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.ProcessorUtils;
import org.orbeon.oxf.util.LoggerFactory;
import org.orbeon.oxf.util.NetUtils;
import org.orbeon.oxf.xforms.action.actions.XFormsLoadAction;
import org.orbeon.oxf.xforms.control.controls.XFormsUploadControl;
import org.orbeon.oxf.xforms.event.*;
import org.orbeon.oxf.xforms.event.events.*;
import org.orbeon.oxf.xforms.mip.BooleanModelItemProperty;
import org.orbeon.oxf.xforms.mip.ValidModelItemProperty;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.oxf.xml.dom4j.LocationDocumentResult;
import org.orbeon.saxon.dom4j.NodeWrapper;
import org.orbeon.saxon.functions.FunctionLibrary;
import org.orbeon.saxon.om.DocumentInfo;
import org.orbeon.saxon.om.NodeInfo;
import org.orbeon.saxon.om.FastStringBuffer;

import javax.xml.transform.Transformer;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Represents an XForms model submission instance.
 *
 * TODO: This badly needs to be modularized instead of being a soup of "ifs"!
 */
public class XFormsModelSubmission implements XFormsEventTarget, XFormsEventHandlerContainer {

	public final static Logger logger = LoggerFactory.createLogger(XFormsModelSubmission.class);

    public static final String DEFAULT_TEXT_READING_ENCODING = "iso-8859-1";

    private final XFormsContainingDocument containingDocument;
    private final String id;
    private final XFormsModel model;
    private final Element submissionElement;
    private boolean submissionElementExtracted = false;

    // Event handlers
    private final List eventHandlers;

    private String avtActionOrResource; // required unless there is a nested xforms:resource element
    private String resolvedActionOrResource;
    private String method; // required

    private boolean validate = true;
    private boolean relevant = true;
    private boolean serialize = true;

    private String version;
    private boolean indent;
    private String mediatype;
    private String encoding;
    private boolean omitxmldeclaration;
    private Boolean standalone;
    private String cdatasectionelements;

    private String replace = XFormsConstants.XFORMS_SUBMIT_REPLACE_ALL;
    private String replaceInstanceId;
    private String xxfReplaceInstanceId;
    private String separator = ";";
    private String includenamespaceprefixes;

    private String avtXXFormsUsername;
    private String resolvedXXFormsUsername;
    private String avtXXFormsPassword;
    private String resolvedXXFormsPassword;
    private String avtXXFormsReadonly;
    private String resolvedXXFormsReadonly;
    private String avtXXFormsShared;
    private String resolvedXXFormsShared;

    private boolean xxfShowProgress;

    private boolean fURLNorewrite;

    public XFormsModelSubmission(XFormsContainingDocument containingDocument, String id, Element submissionElement, XFormsModel model) {
        this.containingDocument = containingDocument;
        this.id = id;
        this.submissionElement = submissionElement;
        this.model = model;

        // Extract event handlers
        eventHandlers = XFormsEventHandlerImpl.extractEventHandlers(containingDocument, this, submissionElement);
    }

    public XFormsContainingDocument getContainingDocument() {
        return containingDocument;
    }

    public Element getSubmissionElement() {
        return submissionElement;
    }


    public boolean isXxfShowProgress() {
        return xxfShowProgress;
    }

    public String getReplace() {
        return replace;
    }

    private void extractSubmissionElement() {
        if (!submissionElementExtracted) {

            avtActionOrResource = submissionElement.attributeValue("resource");
            if (avtActionOrResource == null) // @resource has precedence over @action
                avtActionOrResource = submissionElement.attributeValue("action");
            if (avtActionOrResource == null) {
                // TODO: For XForms 1.1, support @resource and nested xforms:resource
                throw new OXFException("xforms:submission: action attribute or resource attribute is missing.");
            }

            method = submissionElement.attributeValue("method");
            method = Dom4jUtils.qNameToexplodedQName(Dom4jUtils.extractAttributeValueQName(submissionElement, "method"));

            validate = !"false".equals(submissionElement.attributeValue("validate"));
            relevant = !"false".equals(submissionElement.attributeValue("relevant"));
            serialize = !"false".equals(submissionElement.attributeValue("serialize"));

            version = submissionElement.attributeValue("version");

            if (submissionElement.attributeValue("indent") != null) {
                indent = Boolean.valueOf(submissionElement.attributeValue("indent")).booleanValue();
            }
            mediatype = submissionElement.attributeValue("mediatype");
            encoding = submissionElement.attributeValue("encoding");
            if (submissionElement.attributeValue("omitxmldeclaration") != null) {
                omitxmldeclaration = Boolean.valueOf(submissionElement.attributeValue("omit-xml-declaration")).booleanValue();
            }
            if (submissionElement.attributeValue("standalone") != null) {
                standalone = new Boolean(submissionElement.attributeValue("standalone"));
            }

            cdatasectionelements = submissionElement.attributeValue("cdata-section-elements");
            if (submissionElement.attributeValue("replace") != null) {
                replace = submissionElement.attributeValue("replace");

                if (replace.equals("instance")) {
                    replaceInstanceId = XFormsUtils.namespaceId(containingDocument, submissionElement.attributeValue("instance"));
                    xxfReplaceInstanceId = XFormsUtils.namespaceId(containingDocument, submissionElement.attributeValue(XFormsConstants.XXFORMS_INSTANCE_QNAME));
                }
            }
            if (submissionElement.attributeValue("separator") != null) {
                separator = submissionElement.attributeValue("separator");
            }
            includenamespaceprefixes = submissionElement.attributeValue("includenamespaceprefixes");

            // Extension attributes
            avtXXFormsUsername = submissionElement.attributeValue(XFormsConstants.XXFORMS_USERNAME_QNAME);
            avtXXFormsPassword = submissionElement.attributeValue(XFormsConstants.XXFORMS_PASSWORD_QNAME);

            avtXXFormsReadonly = submissionElement.attributeValue(XFormsConstants.XXFORMS_READONLY_ATTRIBUTE_QNAME);
            avtXXFormsShared = submissionElement.attributeValue(XFormsConstants.XXFORMS_SHARED_QNAME);

            // Whether we must show progress or not
            xxfShowProgress = !"false".equals(submissionElement.attributeValue(XFormsConstants.XXFORMS_SHOW_PROGRESS_QNAME));

            // Whether or not to rewrite URLs
            fURLNorewrite = XFormsUtils.resolveUrlNorewrite(submissionElement);

            // Remember that we did this
            submissionElementExtracted = true;
        }
    }

    private boolean isMethodOptimizedLocalSubmission() {
        return method.startsWith(XMLUtils.buildExplodedQName(XFormsConstants.XXFORMS_NAMESPACE_URI, ""))
                && (XFormsSubmissionUtils.isGet(method) || XFormsSubmissionUtils.isPost(method) || XFormsSubmissionUtils.isPut(method));
    }

    public String getEffectiveId() {
        return id;
    }

    public LocationData getLocationData() {
        return (LocationData) submissionElement.getData();
    }

    public XFormsEventHandlerContainer getParentContainer(XFormsContainingDocument containingDocument) {
        return model;
    }

    public List getEventHandlers(XFormsContainingDocument containingDocument) {
        return eventHandlers;
    }

    public void performDefaultAction(PipelineContext pipelineContext, XFormsEvent event) {
        final String eventName = event.getEventName();

        if (XFormsEvents.XFORMS_SUBMIT.equals(eventName) || XFormsEvents.XXFORMS_SUBMIT.equals(eventName)) {
            // 11.1 The xforms-submit Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            containingDocument.setGotSubmission(true);

            boolean isDeferredSubmissionSecondPass = false;
            XFormsSubmitErrorEvent submitErrorEvent = null;
            boolean submitDone = false;
            final long submissionStartTime = XFormsServer.logger.isDebugEnabled() ? System.currentTimeMillis() : 0;

            // Make sure submission element info is extracted
            extractSubmissionElement();

            try {
                final boolean isReplaceAll = replace.equals(XFormsConstants.XFORMS_SUBMIT_REPLACE_ALL);
                final boolean isReplaceInstance = replace.equals(XFormsConstants.XFORMS_SUBMIT_REPLACE_INSTANCE);

                final boolean isHandlingOptimizedGet = XFormsUtils.isOptimizeGetAllSubmission() && XFormsSubmissionUtils.isGet(method)
                        && isReplaceAll
                        && avtXXFormsUsername == null; // can't optimize if there are authentication credentials

                final XFormsControls xformsControls = containingDocument.getXFormsControls();

                // Get current node for xforms:submission
                final NodeInfo currentNodeInfo;
                {
                    // TODO FIXME: the submission element is not a control, so we shouldn't use XFormsControls.
                    // "The default value is '/'."
                    final String refAttribute = (submissionElement.attributeValue("ref") != null)
                            ? submissionElement.attributeValue("ref") : "/";
                    xformsControls.resetBindingContext();
                    xformsControls.pushBinding(pipelineContext, refAttribute, null, null, model.getEffectiveId(), null, submissionElement,
                            Dom4jUtils.getNamespaceContextNoDefault(submissionElement));

                    currentNodeInfo = xformsControls.getCurrentSingleNode();

                    // Check that we have a current node and that it is pointing to a document or an element
                    if (currentNodeInfo == null)
                        throw new OXFException("Empty single-node binding on xforms:submission for submission id: " + id);

                    if (!(currentNodeInfo instanceof DocumentInfo || currentNodeInfo.getNodeKind() == org.w3c.dom.Document.ELEMENT_NODE)) {
                        throw new OXFException("xforms:submission: single-node binding must refer to a document node or an element.");
                    }
                }

                // Get instance containing the node to submit
                final XFormsInstance currentInstance = xformsControls.getCurrentInstance();

                // Determine if the instance to submit has one or more bound and relevant upload controls
                boolean hasBoundRelevantUploadControl = false;
                if (serialize) {
                    final List uploadControls = xformsControls.getCurrentControlsState().getUploadControls();
                    if (uploadControls != null) {
                        for (Iterator i = uploadControls.iterator(); i.hasNext();) {
                            final XFormsUploadControl currentControl = (XFormsUploadControl) i.next();
                            if (currentControl.isRelevant()) {
                                final NodeInfo boundNodeInfo = currentControl.getBoundNode();
                                if (currentInstance ==  model.getInstanceForNode(boundNodeInfo)) {
                                    // Found one relevant bound control
                                    hasBoundRelevantUploadControl = true;
                                    break;
                                }
                            }
                        }
                    }
                }

                final boolean isDeferredSubmission = (isReplaceAll && !isHandlingOptimizedGet) || (!isReplaceAll && serialize && hasBoundRelevantUploadControl);
                final boolean isDeferredSubmissionFirstPass = isDeferredSubmission && XFormsEvents.XFORMS_SUBMIT.equals(eventName);
                isDeferredSubmissionSecondPass = isDeferredSubmission && !isDeferredSubmissionFirstPass; // here we get XXFORMS_SUBMIT

                // TODO: Somewhere around here, check flags and doRecalculate() if needed

                final XFormsModel modelForInstance = currentInstance.getModel(containingDocument);
                final Document initialDocumentToSubmit;
                if (serialize && !isDeferredSubmissionSecondPass) {
                    // Create document to submit
                    try {
                        initialDocumentToSubmit = createDocumentToSubmit(currentNodeInfo, currentInstance);

                        // Temporarily change instance document so that we can run validation again
                        modelForInstance.setInstanceDocument(initialDocumentToSubmit,
                                currentInstance.getModelId(), currentInstance.getEffectiveId(), currentInstance.getSourceURI(),
                                currentInstance.getUsername(), currentInstance.getPassword(),
                                currentInstance.isApplicationShared());

                        // Revalidate instance
                        modelForInstance.doRevalidate(pipelineContext);
                        // TODO: Check if the validation state can really change. If so, find a solution.
                        // "no notification events are marked for dispatching due to this operation"

                        // Check that there are no validation errors
                        final boolean instanceSatisfiesValidRequired = isDocumentSatisfiesValidRequired(initialDocumentToSubmit);
                        if (!instanceSatisfiesValidRequired) {
                            if (logger.isDebugEnabled()) {
                                final LocationDocumentResult documentResult = new LocationDocumentResult();
                                final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
                                identity.setResult(documentResult);
                                currentInstance.read(identity);
                                final String documentString = Dom4jUtils.domToString(documentResult.getDocument());

                                logger.debug("XForms - submission - instance document or subset thereof cannot be submitted:\n" + documentString);
                            }
                            throw new OXFException("xforms:submission: instance to submit does not satisfy valid and/or required model item properties.");
                        }
                    } finally {
                        // Restore instance document
                        modelForInstance.setInstance(currentInstance, false);
                    }
                } else {
                    initialDocumentToSubmit = null;
                }

                // Deferred submission: end of the first pass
                if (isDeferredSubmissionFirstPass) {
                    // When replace="all", we wait for the submission of an XXFormsSubmissionEvent from the client
                    containingDocument.setClientActiveSubmission(this);
                    return;
                }

                // Evaluate AVTs
                // TODO FIXME: the submission element is not a control, so we shouldn't use XFormsControls.
                {
                    final FunctionLibrary functionLibrary = xformsControls.getFunctionLibrary();

                    final String tempActionOrResource = XFormsUtils.resolveAttributeValueTemplates(pipelineContext, currentNodeInfo, null, functionLibrary, submissionElement, avtActionOrResource);
                    resolvedActionOrResource = XFormsUtils.encodeConvenienceCharactersForURI(tempActionOrResource);

                    resolvedXXFormsUsername = XFormsUtils.resolveAttributeValueTemplates(pipelineContext, currentNodeInfo, null, functionLibrary, submissionElement, avtXXFormsUsername);
                    resolvedXXFormsPassword = XFormsUtils.resolveAttributeValueTemplates(pipelineContext, currentNodeInfo, null, functionLibrary, submissionElement, avtXXFormsPassword);
                    resolvedXXFormsReadonly = XFormsUtils.resolveAttributeValueTemplates(pipelineContext, currentNodeInfo, null, functionLibrary, submissionElement, avtXXFormsReadonly);
                    resolvedXXFormsShared = XFormsUtils.resolveAttributeValueTemplates(pipelineContext, currentNodeInfo, null, functionLibrary, submissionElement, avtXXFormsShared);
                }

                // Check read-only and shared hints
                XFormsInstance.checkSharedHints(submissionElement, resolvedXXFormsReadonly, resolvedXXFormsShared);
                final boolean isReadonlyHint = "true".equals(resolvedXXFormsReadonly);
                final boolean isApplicationSharedHint = "application".equals(resolvedXXFormsShared);
                if (isApplicationSharedHint) {
                    if (!XFormsSubmissionUtils.isGet(method))
                        throw new OXFException("xforms:submission: xxforms:shared=\"application\" can be set only with method=\"get\".");
                    if (!isReplaceInstance)
                        throw new OXFException("xforms:submission: xxforms:shared=\"application\" can be set only with replace=\"instance\".");
                } else if (isReadonlyHint) {
                    if (!isReplaceInstance)
                        throw new OXFException("xforms:submission: xxforms:readonly=\"true\" can be \"true\" only with replace=\"instance\".");
                }

                final Document documentToSubmit;
                if (serialize && isDeferredSubmissionSecondPass) {
                    // Handle uploaded files if any
                    final Element filesElement = (event instanceof XXFormsSubmitEvent) ? ((XXFormsSubmitEvent) event).getFilesElement() : null;
                    if (filesElement != null) {
                        for (Iterator i = filesElement.elements().iterator(); i.hasNext();) {
                            final Element parameterElement = (Element) i.next();
                            final String name = parameterElement.element("name").getTextTrim();

                            final XFormsUploadControl uploadControl
                                        = (XFormsUploadControl) containingDocument.getObjectById(pipelineContext, name);

                            // In case of xforms:repeat, the name of the template will not match an existing control
                            if (uploadControl == null)
                                continue;

                            final Element valueElement = parameterElement.element("value");
                            final String value = valueElement.getTextTrim();

                            final String filename;
                            {
                                final Element filenameElement = parameterElement.element("filename");
                                filename = (filenameElement != null) ? filenameElement.getTextTrim() : "";
                            }
                            final String mediatype;
                            {
                                final Element mediatypeElement = parameterElement.element("content-type");
                                mediatype = (mediatypeElement != null) ? mediatypeElement.getTextTrim() : "";
                            }
                            final String size = parameterElement.element("content-length").getTextTrim();

                            if (size.equals("0") && filename.equals("")) {
                                // No file was selected in the UI
                            } else {
                                // A file was selected in the UI (note that the file may be empty)
                                final String paramValueType = Dom4jUtils.qNameToexplodedQName(Dom4jUtils.extractAttributeValueQName(valueElement, XMLConstants.XSI_TYPE_QNAME));

                                // Set value of uploaded file into the instance (will be xs:anyURI or xs:base64Binary)
                                uploadControl.setExternalValue(pipelineContext, value, paramValueType, !isReplaceAll);

                                // Handle filename, mediatype and size if necessary
                                uploadControl.setFilename(pipelineContext, filename);
                                uploadControl.setMediatype(pipelineContext, mediatype);
                                uploadControl.setSize(pipelineContext, size);
                            }
                        }
                    }

                    // Create document to submit
                    try {
                        documentToSubmit = createDocumentToSubmit(currentNodeInfo, currentInstance);

                        // Temporarily change instance document so that we can run validation again
                        modelForInstance.setInstanceDocument(documentToSubmit,
                                currentInstance.getModelId(), currentInstance.getEffectiveId(), currentInstance.getSourceURI(),
                                currentInstance.getUsername(), currentInstance.getPassword(),
                                currentInstance.isApplicationShared());

                        // Revalidate instance
                        modelForInstance.doRevalidate(pipelineContext);
                        // sent out. Check if the validation state can really change. If so, find a
                        // solution.
                        // "no notification events are marked for dispatching due to this operation"

                        // Check that there are no validation errors
                        final boolean instanceSatisfiesValidRequired = isDocumentSatisfiesValidRequired(documentToSubmit);
                        if (!instanceSatisfiesValidRequired) {
                            if (logger.isDebugEnabled()) {
                                final LocationDocumentResult documentResult = new LocationDocumentResult();
                                final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
                                identity.setResult(documentResult);
                                currentInstance.read(identity);
                                final String documentString = Dom4jUtils.domToString(documentResult.getDocument());

                                logger.debug("XForms - submission - instance document or subset thereof cannot be submitted:\n" + documentString);
                            }
                            throw new OXFException("xforms:submission: instance to submit does not satisfy valid and/or required model item properties.");
                        }
                    } finally {
                        // Restore instance document
                        modelForInstance.setInstance(currentInstance, false);
                    }
                } else {
                    // Don't recreate document
                    documentToSubmit = initialDocumentToSubmit;
                }

                if (serialize) {
                    // Fire xforms-submit-serialize

                    // "The event xforms-submit-serialize is dispatched. If the submission-body property of the event
                    // is changed from the initial value of empty string, then the content of the submission-body
                    // property string is used as the submission serialization. Otherwise, the submission serialization
                    // consists of a serialization of the selected instance data according to the rules stated at 11.9
                    // Submission Options."

                    // TODO: pass submission-body attribute
                    containingDocument.dispatchEvent(pipelineContext, new XFormsSubmitSerializeEvent(XFormsModelSubmission.this));
                    // TODO: look at result of submission-body attribute and see if submission body needs to be replaced
                }

                // Serialize
                // To support: application/xml, application/x-www-form-urlencoded, multipart/related, multipart/form-data
                final byte[] messageBody;
                final String queryString;
                final String defaultMediatype;
                {
                    if (method.equals("multipart-post")) {
                        // TODO
                        throw new OXFException("xforms:submission: submission method not yet implemented: " + method);
                    } else if (method.equals("form-data-post")) {
                        // TODO

//                        final MultipartFormDataBuilder builder = new MultipartFormDataBuilder(, , null);

                        throw new OXFException("xforms:submission: submission method not yet implemented: " + method);
                    } else if (method.equals("urlencoded-post")) {

                        // Perform "application/x-www-form-urlencoded" serialization
                        queryString = null;
                        messageBody = serialize? createWwwFormUrlEncoded(documentToSubmit).getBytes("UTF-8") : null;// the resulting string is already ASCII
                        defaultMediatype = "application/x-www-form-urlencoded";

                    } else if (XFormsSubmissionUtils.isPost(method) || XFormsSubmissionUtils.isPut(method)) {

                        if (serialize) {
                            // Serialize XML to a stream of bytes
                            try {
                                final Transformer identity = TransformerUtils.getIdentityTransformer();
                                TransformerUtils.applyOutputProperties(identity,
                                        "xml", version, null, null, encoding, omitxmldeclaration, standalone, indent, 4);

                                // TODO: use cdata-section-elements

                                final ByteArrayOutputStream os = new ByteArrayOutputStream();
                                identity.transform(new DocumentSource(documentToSubmit), new StreamResult(os));
                                messageBody = os.toByteArray();
                            } catch (Exception e) {
                                throw new OXFException("xforms:submission: exception while serializing instance to XML.", e);
                            }
                        } else {
                            messageBody = null;
                        }
                        queryString = null;
                        defaultMediatype = "application/xml";

                    } else if (XFormsSubmissionUtils.isGet(method) || XFormsSubmissionUtils.isDelete(method)) {

                        // Perform "application/x-www-form-urlencoded" serialization
                        queryString = serialize ? createWwwFormUrlEncoded(documentToSubmit) : null;
                        messageBody = null;
                        defaultMediatype = null;

                    } else {
                        throw new OXFException("xforms:submission: invalid submission method requested: " + method);
                    }
                }

                final ExternalContext externalContext = (ExternalContext) pipelineContext.getAttribute(PipelineContext.EXTERNAL_CONTEXT);

                // Get URL type
                final String urlType = submissionElement.attributeValue(XMLConstants.FORMATTING_URL_TYPE_QNAME);
                final ExternalContext.Request request = externalContext.getRequest();

                // Find instance to update
                final XFormsInstance replaceInstance;
                if (isReplaceInstance) {
                    if (xxfReplaceInstanceId != null)
                        replaceInstance = containingDocument.findInstance(xxfReplaceInstanceId);
                    else if (replaceInstanceId != null)
                        replaceInstance = model.getInstance(replaceInstanceId);
                    else
                        replaceInstance = currentInstance;
                } else {
                    replaceInstance = null;
                }

                // Result information
                ConnectionResult connectionResult = null;
                final long externalSubmissionStartTime = XFormsServer.logger.isDebugEnabled() ? System.currentTimeMillis() : 0;
                try {
                    if (isReplaceInstance && resolvedActionOrResource.startsWith("test:")) {
                        // Test action

                        if (messageBody == null)
                            throw new OXFException("Action 'test:': no message body.");

                        connectionResult = new ConnectionResult(null);
                        connectionResult.resultCode = 200;
                        connectionResult.resultHeaders = new HashMap();
                        connectionResult.lastModified = 0;
                        connectionResult.resultMediaType = "application/xml";
                        connectionResult.dontHandleResponse = false;
                        connectionResult.setResultInputStream(new ByteArrayInputStream(messageBody));

                    } else if (isHandlingOptimizedGet) {
                        // GET with replace="all": we can optimize and tell the client to just load the URL

                        final String actionString = (queryString == null) ? resolvedActionOrResource : resolvedActionOrResource + ((resolvedActionOrResource.indexOf('?') == -1) ? "?" : "") + queryString;
                        final String resultURL = XFormsLoadAction.resolveLoadValue(containingDocument, pipelineContext, submissionElement, true, actionString, null, null, fURLNorewrite, xxfShowProgress);
                        connectionResult = new ConnectionResult(resultURL);
                        connectionResult.dontHandleResponse = true;

                    } else if (!NetUtils.urlHasProtocol(resolvedActionOrResource)
                               && !fURLNorewrite
                               && ((request.getContainerType().equals("portlet") && !"resource".equals(urlType))
                                    || (request.getContainerType().equals("servlet")
                                        && (XFormsUtils.isOptimizeLocalSubmission() || isMethodOptimizedLocalSubmission())
                                        &&  isReplaceAll))) {

                        // This is an "optimized" submission, i.e. one that does not use an actual
                        // protocol handler to access the resource

                        // NOTE: Optimizing with include() for servlets doesn't allow detecting
                        // errors caused by the included resource, so we don't allow this for now.

                        // NOTE: For portlets, paths are served directly by the portlet, NOT as
                        // resources.

                        // Current limitations:
                        // o Portlets cannot access resources outside the portlet except by using absolute URLs (unless f:url-type="resource")
                        // o Servlets cannot access resources on the same server but not in the current application
                        //   except by using absolute URLs

                        final URI resolvedURI = XFormsUtils.resolveXMLBase(submissionElement, resolvedActionOrResource);
                        connectionResult = XFormsSubmissionUtils.doOptimized(pipelineContext, externalContext,
                                this, method, resolvedURI.toString(), (mediatype == null) ? defaultMediatype : mediatype, isReplaceAll,
                                messageBody, queryString);

                    } else {
                        // This is a regular remote submission going through a protocol handler

                        // Absolute URLs or absolute paths are allowed to a local servlet
                        String resolvedURL;

                        if (NetUtils.urlHasProtocol(resolvedActionOrResource) || fURLNorewrite) {
                            // Don't touch the URL if it is absolute or if f:url-norewrite="true"
                            resolvedURL = resolvedActionOrResource;
                        } else {
                            // Rewrite URL
                            resolvedURL= XFormsUtils.resolveResourceURL(pipelineContext, submissionElement, resolvedActionOrResource);

                            if (request.getContainerType().equals("portlet") && "resource".equals(urlType) && !NetUtils.urlHasProtocol(resolvedURL)) {
                                // In this case, we have to prepend the complete server path
                                resolvedURL = request.getScheme() + "://" + request.getServerName() + (request.getServerPort() > 0 ? ":" + request.getServerPort() : "") + resolvedURL;
                            }
                        }

                        if (isApplicationSharedHint) {
                            // Get the instance from shared instance cache
                            // This can only happen is method="get" and replace="instance" and xxforms:readonly="true" and xxforms:shared="application"

                            if (XFormsServer.logger.isDebugEnabled())
                                XFormsServer.logger.debug("XForms - submission - using instance from application shared instance cache: " + replaceInstance.getEffectiveId());

                            final URL absoluteResolvedURL = XFormsSubmissionUtils.createAbsoluteURL(resolvedURL, queryString, externalContext);
                            final String absoluteResolvedURLString = absoluteResolvedURL.toExternalForm();

                            final SharedXFormsInstance sharedInstance
                                    = XFormsServerSharedInstancesCache.instance().find(pipelineContext, replaceInstance.getEffectiveId(), replaceInstance.getModelId(), absoluteResolvedURLString);

                            // Handle new instance and associated events
                            final XFormsModel replaceModel = replaceInstance.getModel(containingDocument);

                            if (XFormsServer.logger.isDebugEnabled())
                                XFormsServer.logger.debug("XForms - submission - replacing instance with read-only instance: " + replaceInstance.getEffectiveId());

                            replaceModel.setInstance(sharedInstance, true);
                            replaceModel.handleNewInstanceDocuments(pipelineContext);

                            connectionResult = null;
                            submitDone = true;
                        } else {
                            // Perform actual submission
                            connectionResult = XFormsSubmissionUtils.doRegular(externalContext,
                                    method, resolvedURL, resolvedXXFormsUsername, resolvedXXFormsPassword, (mediatype == null) ? defaultMediatype : mediatype,
                                    messageBody, queryString);
                        }
                    }

                    if (connectionResult != null && !connectionResult.dontHandleResponse) {
                        // Handle response
                        if (connectionResult.resultCode >= 200 && connectionResult.resultCode < 300) {// accept any success code (in particular "201 Resource Created")
                            // Sucessful response
                            if (connectionResult.hasContent()) {
                                // There is a body

                                if (isReplaceAll) {
                                    // When we get here, we are in a mode where we need to send the reply
                                    // directly to an external context, if any.

                                    // "the event xforms-submit-done is dispatched"
                                    containingDocument.dispatchEvent(pipelineContext, new XFormsSubmitDoneEvent(XFormsModelSubmission.this));

                                    final ExternalContext.Response response = externalContext.getResponse();

                                    // Forward headers to response
                                    if (connectionResult.resultHeaders != null) {
                                        for (Iterator i = connectionResult.resultHeaders.entrySet().iterator(); i.hasNext();) {
                                            final Map.Entry currentEntry = (Map.Entry) i.next();
                                            final String headerName = (String) currentEntry.getKey();
                                            // We only get one header value per name
                                            final String headerValue = (String) currentEntry.getValue();

                                            /**
                                             * Filtering the Transfer-Encoding header
                                             *
                                             * We don't pass the Transfer-Encoding header, as the request body is
                                             * already decoded for us. Passing along the Transfer-Encoding causes a
                                             * problem if the server sends us chunked data and we send it in the
                                             * response not chunked but saying in the header that it is chunked.
                                             *
                                             * Non-filtering of Content-Encoding header
                                             *
                                             * The Content-Encoding has the potential of causing the same problem as
                                             * the Transfer-Encoding header. It could be an issue if we get data with
                                             * Content-Encoding: gzip, but pass it along uncompressed but still
                                             * include the Content-Encoding: gzip. However this does not happen, as
                                             * the request we send does not contain a Accept-Encoding: gzip,deflate. So
                                             * filtering the Content-Encoding header is safe here.
                                             */
                                            if (headerName != null && headerValue != null && !"transfer-encoding".equals(headerName.toLowerCase())) {
                                                response.addHeader(headerName, headerValue);
                                            }
                                        }
                                    }

                                    // Forward content to response
                                    NetUtils.copyStream(connectionResult.getResultInputStream(), response.getOutputStream());

                                    // TODO: [#306918] RFE: Must be able to do replace="all" during initialization.
                                    // http://forge.objectweb.org/tracker/index.php?func=detail&aid=306918&group_id=168&atid=350207
                                    // Suggestion is to write either binary or XML to processor output ContentHandler,
                                    // and make sure the code which would output the XHTML+XForms is disabled.

                                } else if (isReplaceInstance) {

                                    if (ProcessorUtils.isXMLContentType(connectionResult.resultMediaType)) {
                                        // Handling of XML media type
                                        try {
                                            // Set new instance document to replace the one submitted

                                            if (replaceInstance == null) {
                                                // Replacement instance was specified but not found
                                                // TODO: XForms 1.1 won't dispatch xforms-binding-exception here
                                                containingDocument.dispatchEvent(pipelineContext, new XFormsBindingExceptionEvent(XFormsModelSubmission.this));
                                            } else {

                                                // Read stream into Document
                                                final XFormsInstance newInstance;
                                                if (!isReadonlyHint) {
                                                    // Resulting instance is not read-only

                                                    if (XFormsServer.logger.isDebugEnabled())
                                                        XFormsServer.logger.debug("XForms - submission - replacing instance with mutable instance: " + replaceInstance.getEffectiveId());

                                                    final Document resultingInstanceDocument = Dom4jUtils.readDom4j(connectionResult.getResultInputStream(), connectionResult.resourceURI);
                                                    newInstance = new XFormsInstance(replaceInstance.getModelId(), replaceInstance.getEffectiveId(), resultingInstanceDocument,
                                                            connectionResult.resourceURI, resolvedXXFormsUsername, resolvedXXFormsPassword, false);
                                                } else {
                                                    // Resulting instance is read-only

                                                    if (XFormsServer.logger.isDebugEnabled())
                                                        XFormsServer.logger.debug("XForms - submission - replacing instance with read-only instance: " + replaceInstance.getEffectiveId());

                                                    final DocumentInfo resultingInstanceDocument = TransformerUtils.readTinyTree(connectionResult.getResultInputStream(), connectionResult.resourceURI);
                                                    newInstance = new SharedXFormsInstance(replaceInstance.getModelId(), replaceInstance.getEffectiveId(), resultingInstanceDocument,
                                                            connectionResult.resourceURI, resolvedXXFormsUsername, resolvedXXFormsPassword, false);
                                                }

                                                // Set new instance
                                                final XFormsModel replaceModel = replaceInstance.getModel(containingDocument);
                                                replaceModel.setInstance(newInstance, true);

                                                if (!replaceInstance.isReadOnly()) {
                                                    // Mark all values as changed so that refresh sends appropriate events
                                                    XFormsUtils.markAllValuesChanged(replaceInstance);
                                                }

                                                // Handle new instance and associated events
                                                replaceModel.handleNewInstanceDocuments(pipelineContext);

                                                // Notify that submission is done
                                                submitDone = true;
                                            }
                                        } catch (Exception e) {
                                            submitErrorEvent = createErrorEvent(connectionResult);
                                            throw new OXFException("xforms:submission: exception while serializing XML to instance.", e);
                                        }
                                    } else {
                                        // Other media type
                                        submitErrorEvent = createErrorEvent(connectionResult);
                                        throw new OXFException("Body received with non-XML media type for replace=\"instance\": " + connectionResult.resultMediaType);
                                    }
                                } else if (replace.equals(XFormsConstants.XFORMS_SUBMIT_REPLACE_NONE)) {
                                    // Just notify that processing is terminated
                                    submitDone = true;
                                } else {
                                    submitErrorEvent = createErrorEvent(connectionResult);
                                    throw new OXFException("xforms:submission: invalid replace attribute: " + replace);
                                }

                            } else {
                                // There is no body, notify that processing is terminated
                                submitDone = true;
                            }
                        } else if (connectionResult.resultCode == 302 || connectionResult.resultCode == 301) {
                            // Got a redirect

                            final ExternalContext.Response response = externalContext.getResponse();

                            // Forward headers to response
                            // TODO: this is duplicated from above
                            if (connectionResult.resultHeaders != null) {
                                for (Iterator i = connectionResult.resultHeaders.entrySet().iterator(); i.hasNext();) {
                                    final Map.Entry currentEntry = (Map.Entry) i.next();
                                    final String headerName = (String) currentEntry.getKey();
                                    final List headerValues = (List) currentEntry.getValue();

                                    if (headerName != null && headerValues != null) {
                                        for (Iterator j = headerValues.iterator(); j.hasNext();) {
                                            response.addHeader(headerName, (String) j.next());
                                        }
                                    }
                                }
                            }

                            // Forward redirect
                            response.setStatus(connectionResult.resultCode);

                        } else {
                            // Error code received
                            submitErrorEvent = createErrorEvent(connectionResult);
                            throw new OXFException("xforms:submission for submission id: " + id + ", error code received when submitting instance: " + connectionResult.resultCode);
                        }
                    }
                } finally {
                    // Clean-up
                    if (connectionResult != null) {
                        connectionResult.close();
                    }
                    // Log time spent in submission if needed
                    if (XFormsServer.logger.isDebugEnabled()) {
                        final long submissionTime = System.currentTimeMillis() - externalSubmissionStartTime;
                        XFormsServer.logger.debug("XForms - submission - external submission time (including handling returned body): " + submissionTime);
                    }
                }
            } catch (Throwable e) {
                if (isDeferredSubmissionSecondPass && XFormsUtils.isOptimizePostAllSubmission()) {
                    // It doesn't serve any purpose here to dispatch an event, so we just propagate the exception
                    throw new OXFException(e);
                } else {
                    // Any exception will cause an error event to be dispatched
                    if (submitErrorEvent == null)
                        submitErrorEvent = new XFormsSubmitErrorEvent(XFormsModelSubmission.this, resolvedActionOrResource);

                    submitErrorEvent.setThrowable(e);
                    containingDocument.dispatchEvent(pipelineContext, submitErrorEvent);
                }
            } finally {
                // If submission succeeded, dispatch success event
                if (submitDone) {
                    containingDocument.dispatchEvent(pipelineContext, new XFormsSubmitDoneEvent(XFormsModelSubmission.this));
                }
                // Log total time spent in submission if needed
                if (XFormsServer.logger.isDebugEnabled()) {
                    final long submissionTime = System.currentTimeMillis() - submissionStartTime;
                    XFormsServer.logger.debug("XForms - submission - total submission time: " + Long.toString(submissionTime));
                }
            }

        } else if (XFormsEvents.XFORMS_BINDING_EXCEPTION.equals(eventName)) {
            // The default action for this event results in the following: Fatal error.
            throw new OXFException("Binding exception.");
        }
    }

    private XFormsSubmitErrorEvent createErrorEvent(ConnectionResult connectionResult) throws IOException {
        final XFormsSubmitErrorEvent submitErrorEvent = new XFormsSubmitErrorEvent(XFormsModelSubmission.this, resolvedActionOrResource);
        if (connectionResult.hasContent()) {
            if (ProcessorUtils.isXMLContentType(connectionResult.resultMediaType)) {
                // XML content-type
                // TODO: XForms 1.1 may mandate that we always try to parse the body as XML first
                // Read stream into Document
                try {
                    final DocumentInfo responseBody = TransformerUtils.readTinyTree(connectionResult.getResultInputStream(), connectionResult.resourceURI);
                    submitErrorEvent.setBodyDocument(responseBody);
                } catch (Exception e) {
                    XFormsServer.logger.error("XForms - submission - error while parsing respond body ", e);
                }
            } else if (ProcessorUtils.isTextContentType(connectionResult.resultMediaType)) {
                // Text content-type
                // Read stream into String
                try {
                    final String charset;
                    {
                        final String connectionCharset = NetUtils.getContentTypeCharset(connectionResult.resultMediaType);
                        if (connectionCharset != null)
                            charset = connectionCharset;
                        else
                            charset = DEFAULT_TEXT_READING_ENCODING;
                    }
                    final Reader reader = new InputStreamReader(connectionResult.getResultInputStream(), charset);
                    final String responseBody = NetUtils.readStreamAsString(reader);
                    submitErrorEvent.setBodyString(responseBody);
                } catch (Exception e) {
                    XFormsServer.logger.error("XForms - submission - error while reading respond body ", e);
                }
            } else {
                // This is binary
                // Don't store anything for now
            }
        }
        return submitErrorEvent;
    }

    private Document createDocumentToSubmit(final NodeInfo currentNodeInfo, final XFormsInstance currentInstance) {

        final Document documentToSubmit;
        if (currentNodeInfo instanceof NodeWrapper) {
            final Node currentNode = (Node) ((NodeWrapper) currentNodeInfo).getUnderlyingNode();

            // "A node from the instance data is selected, based on attributes on the submission
            // element. The indicated node and all nodes for which it is an ancestor are considered for
            // the remainder of the submit process. "
            if (currentNode instanceof Element) {
                // Create subset of document
                documentToSubmit = Dom4jUtils.createDocument((Element) currentNode);
            } else {
                // Use entire instance document
                documentToSubmit = Dom4jUtils.createDocument(currentInstance.getDocument().getRootElement());
            }

            if (relevant) {
                // "Any node which is considered not relevant as defined in 6.1.4 is removed."
                final Node[] nodeToDetach = new Node[1];
                do {
                    // NOTE: This is not very efficient, but at least we avoid NPEs that we would get by
                    // detaching elements within accept(). Should implement a more efficient algorithm to
                    // prune non-relevant nodes.
                    nodeToDetach[0] = null;
                    documentToSubmit.accept(new VisitorSupport() {

                        public final void visit(Element element) {
                            checkInstanceData(element);
                        }

                        public final void visit(Attribute attribute) {
                            checkInstanceData(attribute);
                        }

                        private final void checkInstanceData(Node node) {
                            if (nodeToDetach[0] == null) {
                                final InstanceData instanceData = XFormsUtils.getInstanceDataUpdateInherited(node);
                                // Check "relevant" MIP and remove non-relevant nodes
                                {
                                    final BooleanModelItemProperty relevantMIP = instanceData.getInheritedRelevant();
                                    if (relevantMIP != null && !relevantMIP.get())
                                        nodeToDetach[0] = node;
                                }
                            }
                        }
                    });
                    if (nodeToDetach[0] != null)
                        nodeToDetach[0].detach();

                } while (nodeToDetach[0] != null);
            }

            // TODO: handle includenamespaceprefixes
        } else {
            // Submitting read-only instance backed by TinyTree (no MIPs to check)
            if (currentNodeInfo.getNodeKind() == org.w3c.dom.Document.ELEMENT_NODE) {
                documentToSubmit = TransformerUtils.tinyTreeToDom4j2(currentNodeInfo);
            } else {
                documentToSubmit = TransformerUtils.tinyTreeToDom4j2(currentNodeInfo.getRoot());
            }
        }
        return documentToSubmit;
    }

    private String createWwwFormUrlEncoded(final Document document) {

        final StringBuffer sb = new StringBuffer();
        document.accept(new VisitorSupport() {
            public final void visit(Element element) {
                // We only care about elements

                final List children = element.elements();
                if (children == null || children.size() == 0) {
                    // Only consider leaves
                    final String text = element.getText();
                    if (text != null && text.length() > 0) {
                        // Got one!
                        final String localName = element.getName();

                        if (sb.length() > 0)
                            sb.append(separator);

                        try {
                            sb.append(URLEncoder.encode(localName, "utf-8"));
                            sb.append('=');
                            sb.append(URLEncoder.encode(text, "utf-8"));
                            // TODO: check if line breaks will be correcly encoded as "%0D%0A"
                        } catch (UnsupportedEncodingException e) {
                            // Should not happen: utf-8 must be supported
                            throw new OXFException(e);
                        }
                    }
                }
            }
        });

        return sb.toString();
    }

    private boolean isDocumentSatisfiesValidRequired(final Document documentToSubmit) {
        final boolean[] instanceSatisfiesValidRequired = new boolean[]{true};
        if (validate) {
            documentToSubmit.accept(new VisitorSupport() {

                public final void visit(Element element) {
                    final InstanceData instanceData = XFormsUtils.getLocalInstanceData(element);
                    final boolean valid = checkInstanceData(instanceData);

                    instanceSatisfiesValidRequired[0] &= valid;

                    if (!valid && XFormsServer.logger.isDebugEnabled()) {
                        XFormsServer.logger.debug("XForms - submission - found invalid element: " + elementToString(element));
                    }
                }

                public final void visit(Attribute attribute) {
                    final InstanceData instanceData = XFormsUtils.getLocalInstanceData(attribute);
                    final boolean valid = checkInstanceData(instanceData);

                    instanceSatisfiesValidRequired[0] &= valid;

                    if (!valid && XFormsServer.logger.isDebugEnabled()) {
                        XFormsServer.logger.debug("XForms - submission - found invalid attribute: " + attributeToString(attribute)
                                + " (parent element: " + elementToString(attribute.getParent()) + ")");
                    }
                }

                private final boolean checkInstanceData(InstanceData instanceData) {
                    // Check "valid" MIP
                    {
                        final BooleanModelItemProperty validMIP = instanceData.getValid();
                        if (validMIP != null && !validMIP.get())
                            return false;
                    }
                    // Check "required" MIP
                    {
                        final ValidModelItemProperty requiredMIP = instanceData.getRequired();
                        if (requiredMIP != null && requiredMIP.get() && requiredMIP.getStringValue().length() == 0) {
                            // Required and empty
                            return false;
                        }
                    }
                    return true;
                }
            });
        }
        return instanceSatisfiesValidRequired[0];
    }

    private static String elementToString(Element element) {
        // Open start tag
        final FastStringBuffer sb = new FastStringBuffer("<");
        sb.append(element.getQualifiedName());

        // Attributes if any
        for (Iterator i = element.attributeIterator(); i.hasNext();) {
            final Attribute currentAttribute = (Attribute) i.next();

            sb.append(' ');
            sb.append(currentAttribute.getQualifiedName());
            sb.append("=\"");
            sb.append(currentAttribute.getValue());
            sb.append('\"');
        }

        // Close start tag
        sb.append('>');

        if (!element.elements().isEmpty()) {
            // Mixed content
            final Object firstChild = element.content().get(0);
            if (firstChild instanceof Text) {
                sb.append(((Text) firstChild).getText());
            }
            sb.append("[...]");
        } else {
            // Not mixed content
            sb.append(element.getText());
        }

        // Close element with end tag
        sb.append("</");
        sb.append(element.getQualifiedName());
        sb.append('>');

        return sb.toString();
    }

    private static String attributeToString(Attribute attribute) {
        final FastStringBuffer sb = new FastStringBuffer(attribute.getQualifiedName());
        sb.append("=\"");
        sb.append(attribute.getValue());
        sb.append('\"');
        return sb.toString();
    }

    public static class ConnectionResult {
        public boolean dontHandleResponse;
        public int resultCode;
        public String resultMediaType;
        public Map resultHeaders;
        public long lastModified;
        public String resourceURI;

        private InputStream resultInputStream;
        private boolean hasContent;

        public ConnectionResult(String resourceURI) {
            this.resourceURI = resourceURI;
        }

        public InputStream getResultInputStream() {
        	return resultInputStream;
        }

        public boolean hasContent() {
            return hasContent;
        }

        public void setResultInputStream(final InputStream resultInputStream) throws IOException {
        	this.resultInputStream = resultInputStream;
        	setHasContentFlag();

        }

        private void setHasContentFlag() throws IOException {
            if (resultInputStream == null) {
                hasContent = false;
            } else {
                if (!resultInputStream.markSupported())
                    this.resultInputStream = new BufferedInputStream(resultInputStream);

                resultInputStream.mark(1);
                hasContent = resultInputStream.read() != -1;
                resultInputStream.reset();
            }
        }

        public void close() {}
    }
}

