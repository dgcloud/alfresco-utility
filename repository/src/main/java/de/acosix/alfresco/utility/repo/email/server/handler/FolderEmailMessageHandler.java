/*
 * Copyright 2018 Acosix GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.acosix.alfresco.utility.repo.email.server.handler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.model.ImapModel;
import org.alfresco.repo.action.executer.ContentMetadataExtracter;
import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.email.EmailMessage;
import org.alfresco.service.cmr.email.EmailMessageException;
import org.alfresco.service.cmr.email.EmailMessagePart;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.datatype.DefaultTypeConverter;
import org.alfresco.service.namespace.QName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.extensions.surf.util.I18NUtil;

import de.acosix.alfresco.utility.repo.email.server.ImprovedEmailMessage;
import de.acosix.alfresco.utility.repo.model.EmailModel;

/**
 * @author Axel Faust
 */
public class FolderEmailMessageHandler extends AbstractEmailMessageHandler
{

    // copied from Alfresco class of same name
    protected static final String MSG_RECEIVED_BY_SMTP = "email.server.msg.received_by_smtp";

    protected static final String MSG_DEFAULT_SUBJECT = "email.server.msg.default_subject";

    protected static final String ERR_MAIL_READ_ERROR = "email.server.err.mail_read_error";

    private static final Logger LOGGER = LoggerFactory.getLogger(FolderEmailMessageHandler.class);

    private static final Set<QName> KNOWN_EMAIL_PROPERTIES = Collections
            .unmodifiableSet(new HashSet<>(Arrays.asList(ContentModel.PROP_SENTDATE, ContentModel.PROP_ORIGINATOR,
                    ContentModel.PROP_ADDRESSEE, ContentModel.PROP_ADDRESSEES, ContentModel.PROP_SUBJECT)));

    protected boolean overwriteDuplicates = false;

    protected boolean extractAttachments = false;

    protected boolean extractAttachmentsAsDirectChildren = false;

    protected boolean copyEmailMetadataToAttachments = false;

    /**
     * @param overwriteDuplicates
     *            the overwriteDuplicates to set
     */
    public void setOverwriteDuplicates(final boolean overwriteDuplicates)
    {
        this.overwriteDuplicates = overwriteDuplicates;
    }

    /**
     * @param extractAttachments
     *            the extractAttachments to set
     */
    public void setExtractAttachments(final boolean extractAttachments)
    {
        this.extractAttachments = extractAttachments;
    }

    /**
     * @param extractAttachmentsAsDirectChildren
     *            the extractAttachmentsAsDirectChildren to set
     */
    public void setExtractAttachmentsAsDirectChildren(final boolean extractAttachmentsAsDirectChildren)
    {
        this.extractAttachmentsAsDirectChildren = extractAttachmentsAsDirectChildren;
    }

    /**
     * @param copyEmailMetadataToAttachments
     *            the copyEmailMetadataToAttachments to set
     */
    public void setCopyEmailMetadataToAttachments(boolean copyEmailMetadataToAttachments)
    {
        this.copyEmailMetadataToAttachments = copyEmailMetadataToAttachments;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processMessage(final NodeRef nodeRef, final EmailMessage message)
    {
        LOGGER.debug("Message from {} to {} is being processed by FolderMailMessageHandler", message.getFrom(), message.getTo());
        try
        {
            final QName nodeTypeQName = this.nodeService.getType(nodeRef);

            if (this.dictionaryService.isSubClass(nodeTypeQName, ContentModel.TYPE_FOLDER))
            {
                // Add the content into the system
                this.addToFolder(nodeRef, message);
            }
            else
            {
                LOGGER.debug("Handler called on unsupported target node type {} of {}", nodeTypeQName, nodeRef);
                throw new AlfrescoRuntimeException("\n" + "Message handler " + this.getClass().getName() + " cannot handle type "
                        + nodeTypeQName + ".\n" + "Check the message handler mappings.");
            }
        }
        catch (final IOException ex)
        {
            LOGGER.error("IO exception during processing of email", ex);
            throw new EmailMessageException(ERR_MAIL_READ_ERROR, ex.getMessage());
        }
    }

    /**
     * Add content to Alfresco repository
     *
     * @param folderNode
     *            Addressed node
     * @param message
     *            Mail message
     * @throws IOException
     *             Exception can be thrown while saving a content into Alfresco repository.
     */
    protected void addToFolder(final NodeRef folderNode, final EmailMessage message) throws IOException
    {
        final Map<QName, Serializable> folderProperties = this.nodeService.getProperties(folderNode);
        final Boolean folderExtractAttachments = DefaultTypeConverter.INSTANCE.convert(Boolean.class,
                folderProperties.get(EmailModel.PROP_EXTRACT_ATTACHMENTS));
        final Boolean folderExtractAttachmentsAsDirectChildren = DefaultTypeConverter.INSTANCE.convert(Boolean.class,
                folderProperties.get(EmailModel.PROP_EXTRACT_ATTACHMENTS_AS_DIRECT_CHILDREN));
        final Boolean folderOverwriteDuplicates = DefaultTypeConverter.INSTANCE.convert(Boolean.class,
                folderProperties.get(EmailModel.PROP_OVERWRITE_DUPLICATES));

        String messageSubject = message.getSubject();
        if (messageSubject == null || messageSubject.length() == 0)
        {
            final Date now = new Date();
            final SimpleDateFormat df = new SimpleDateFormat("dd-MM-yyyy-hh-mm-ss", Locale.ENGLISH);
            df.setTimeZone(TimeZone.getDefault());
            messageSubject = I18NUtil.getMessage(MSG_DEFAULT_SUBJECT, df.format(now));
        }

        String messageFrom = message.getFrom();
        if (messageFrom == null)
        {
            messageFrom = "";
        }

        final Map<QName, Serializable> properties = new HashMap<>();
        properties.put(ContentModel.PROP_TITLE, messageSubject);
        properties.put(ContentModel.PROP_DESCRIPTION, I18NUtil.getMessage(MSG_RECEIVED_BY_SMTP, messageFrom));

        final boolean effectiveOverwriteDuplicates = folderOverwriteDuplicates != null ? Boolean.TRUE.equals(folderOverwriteDuplicates)
                : this.overwriteDuplicates;
        final NodeRef contentNode = this.getOrCreateContentNode(folderNode, messageSubject, ContentModel.ASSOC_CONTAINS,
                effectiveOverwriteDuplicates, properties);
        this.writeMailContent(contentNode, message);

        final boolean effectiveExtractAttachments = folderExtractAttachments != null ? Boolean.TRUE.equals(folderExtractAttachments)
                : this.extractAttachments;

        final Action extracterAction = this.actionService.createAction(ContentMetadataExtracter.EXECUTOR_NAME);
        this.actionService.executeAction(extracterAction, contentNode, true,
                !effectiveExtractAttachments || !copyEmailMetadataToAttachments);

        if (effectiveExtractAttachments)
        {
            final boolean effectiveExtractAttachmentsAsDirectChildren = folderExtractAttachmentsAsDirectChildren != null
                    ? Boolean.TRUE.equals(folderExtractAttachmentsAsDirectChildren)
                    : this.extractAttachmentsAsDirectChildren;
            this.extractAttachments(folderNode, contentNode, message, effectiveExtractAttachmentsAsDirectChildren);
        }

    }

    protected void writeMailContent(final NodeRef contentNode, final EmailMessage message) throws IOException
    {
        if (message instanceof ImprovedEmailMessage)
        {
            final MimeMessage mimeMessage = ((ImprovedEmailMessage) message).getMimeMessage();
            final ContentWriter writer = this.contentService.getWriter(contentNode, ContentModel.PROP_CONTENT, true);
            writer.setMimetype(MimetypeMap.MIMETYPE_RFC822);
            try (OutputStream os = writer.getContentOutputStream())
            {
                mimeMessage.writeTo(os);
            }
            catch (final MessagingException mEx)
            {
                LOGGER.error("Error writing content of mime message", mEx);
                throw new AlfrescoRuntimeException("Failure storing original RFC 822 email", mEx);
            }
        }
        else
        {
            final EmailMessagePart body = message.getBody();
            if (body.getSize() == -1)
            {
                LOGGER.debug("Writing single space as content on {} for empty email body from {}", contentNode, message.getFrom());
                final ContentWriter writer = this.contentService.getWriter(contentNode, ContentModel.PROP_CONTENT, true);
                writer.setMimetype(MimetypeMap.MIMETYPE_TEXT_PLAIN);
                writer.setEncoding("UTF-8");
                writer.putContent(" ");
            }
            else
            {
                final InputStream contentIs = message.getBody().getContent();
                String mimetype = this.mimetypeService.guessMimetype(message.getSubject());
                if (mimetype.equals(MimetypeMap.MIMETYPE_BINARY))
                {
                    mimetype = MimetypeMap.MIMETYPE_TEXT_PLAIN;
                }
                final String encoding = message.getBody().getEncoding();

                this.writeContent(contentNode, contentIs, mimetype, encoding);
            }
        }
    }

    protected void extractAttachments(final NodeRef folderRef, final NodeRef mailNodeRef, final EmailMessage message,
            final boolean extractAttachmentsAsDirectChildren)
    {
        final QName childAssocType = extractAttachmentsAsDirectChildren ? EmailModel.ASSOC_ATTACHMENTS : ContentModel.ASSOC_CONTAINS;
        final NodeRef attachmentParent = extractAttachmentsAsDirectChildren ? mailNodeRef : folderRef;

        Map<QName, Serializable> mailProperties = nodeService.getProperties(mailNodeRef);

        for (final EmailMessagePart attachment : message.getAttachments())
        {
            this.writeAttachment(mailNodeRef, mailProperties, childAssocType, EmailModel.ASSOC_ATTACHMENTS, attachmentParent, attachment);
        }
    }

    protected void writeAttachment(final NodeRef mailNodeRef, Map<QName, Serializable> mailProperties, final QName childAssocType,
            final QName mailNodeRefChildAssocType, final NodeRef attachmentParent, final EmailMessagePart attachment)
    {
        final String fileName = attachment.getFileName();
        final InputStream attachmentStream = attachment.getContent();

        final String mimetype = this.mimetypeService.guessMimetype(fileName);
        final String encoding = attachment.getEncoding();

        final Map<QName, Serializable> properties = new HashMap<>();

        if (this.copyEmailMetadataToAttachments)
        {
            mailProperties.forEach((key, value) -> {
                if (ImapModel.IMAP_MODEL_1_0_URI.equals(key.getNamespaceURI()) && KNOWN_EMAIL_PROPERTIES.contains(key))
                {
                    properties.put(key, value);
                }
            });
        }

        final NodeRef attachmentNodeRef = this.getOrCreateContentNode(attachmentParent, fileName, childAssocType, false, properties);

        this.nodeService.addAspect(mailNodeRef, ContentModel.ASPECT_ATTACHABLE, Collections.<QName, Serializable> emptyMap());
        this.nodeService.createAssociation(mailNodeRef, attachmentNodeRef, ContentModel.ASSOC_ATTACHMENTS);

        if (!childAssocType.equals(mailNodeRefChildAssocType))
        {
            this.nodeService.addChild(mailNodeRef, attachmentNodeRef, mailNodeRefChildAssocType,
                    this.nodeService.getPrimaryParent(attachmentNodeRef).getQName());
        }

        this.writeContent(attachmentNodeRef, attachmentStream, mimetype, encoding);

        final Action extracterAction = this.actionService.createAction(ContentMetadataExtracter.EXECUTOR_NAME);
        this.actionService.executeAction(extracterAction, attachmentNodeRef, true, true);
    }
}
