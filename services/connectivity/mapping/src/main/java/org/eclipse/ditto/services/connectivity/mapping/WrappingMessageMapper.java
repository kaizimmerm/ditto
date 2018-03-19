/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.connectivity.mapping;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.headers.DittoHeadersBuilder;
import org.eclipse.ditto.model.connectivity.ConnectivityModelFactory;
import org.eclipse.ditto.model.connectivity.ExternalMessage;
import org.eclipse.ditto.model.connectivity.ExternalMessageBuilder;
import org.eclipse.ditto.model.connectivity.MessageMappingFailedException;
import org.eclipse.ditto.protocoladapter.Adaptable;
import org.eclipse.ditto.protocoladapter.ProtocolFactory;

/**
 * Does wrap any {@link MessageMapper} and adds content type checks
 * to mapping methods. It allows to override the mappers content type by any content type.
 * <p>
 * Also adds headers to ExternalMessage and Adaptable in mappings even when the wrapped {@link MessageMapper} does
 * forget to do so by himself.
 * </p>
 */
final class WrappingMessageMapper implements MessageMapper {


    private final MessageMapper delegate;
    @Nullable
    private final String contentTypeOverride;


    private WrappingMessageMapper(final MessageMapper delegate, @Nullable final String contentTypeOverride) {
        this.delegate = checkNotNull(delegate);
        this.contentTypeOverride = contentTypeOverride;
    }

    /**
     * Enforces content type checking for the mapper
     *
     * @param mapper the mapper
     * @return the wrapped mapper
     */
    public static MessageMapper wrap(final MessageMapper mapper) {
        return new WrappingMessageMapper(mapper, null);
    }

    /**
     * Enforces content type checking for the mapper and overrides the content type.
     *
     * @param mapper the mapper
     * @param contentTypeOverride the content type substitution
     * @return the wrapped mapper
     */
    public static MessageMapper wrap(final MessageMapper mapper, final String contentTypeOverride) {
        return new WrappingMessageMapper(mapper, contentTypeOverride);
    }

    @Override
    public String getContentType() {
        return Objects.nonNull(contentTypeOverride) ? contentTypeOverride : delegate.getContentType();
    }

    @Override
    public void configure(final MessageMapperConfiguration configuration) {
        delegate.configure(configuration);
    }

    @Override
    public Optional<Adaptable> map(final ExternalMessage message) {

        final String actualContentType = findContentType(message)
                .orElseThrow(() -> MessageMappingFailedException.newBuilder("").build());

        requireMatchingContentType(actualContentType);
        final Optional<Adaptable> mappedOpt = delegate.map(message);

        return mappedOpt.map(mapped -> {
            final DittoHeadersBuilder headersBuilder = DittoHeaders.newBuilder(message.getHeaders());

            final Optional<DittoHeaders> headersOpt = mapped.getHeaders();
            headersOpt.ifPresent(headersBuilder::putHeaders); // overwrite with mapped headers (if any)

            return ProtocolFactory.newAdaptableBuilder(mapped)
                    .withHeaders(headersBuilder.build())
                    .build();
        });
    }

    @Override
    public Optional<ExternalMessage> map(final Adaptable adaptable) {
        final String actualContentType = findContentType(adaptable)
                .orElseThrow(() -> MessageMappingFailedException.newBuilder("").build());

        requireMatchingContentType(actualContentType);
        final Optional<ExternalMessage> mappedOpt = delegate.map(adaptable);

        return mappedOpt.map(mapped -> {

            final ExternalMessageBuilder messageBuilder = ConnectivityModelFactory.newExternalMessageBuilder(mapped);
            adaptable.getHeaders().ifPresent(adaptableHeaders -> {
                messageBuilder.withAdditionalHeaders(adaptableHeaders);
                messageBuilder.withAdditionalHeaders(mapped.getHeaders());
            });
            messageBuilder.withAdditionalHeaders(ExternalMessage.CONTENT_TYPE_HEADER, getContentType());

            // TODO add topicPath if not set??

            return messageBuilder.build();
        });
    }

    private void requireMatchingContentType(final String actualContentType) {

        if (!getContentType().equalsIgnoreCase(actualContentType)) {
            throw MessageMappingFailedException.newBuilder(actualContentType).build();
        }
    }


    private static Optional<String> findContentType(final ExternalMessage internalMessage) {
        checkNotNull(internalMessage);
        return internalMessage.findHeaderIgnoreCase(ExternalMessage.CONTENT_TYPE_HEADER);
    }


    private static Optional<String> findContentType(final Adaptable adaptable) {
        checkNotNull(adaptable);
        return adaptable.getHeaders().map(h -> h.entrySet().stream()
                .filter(e -> ExternalMessage.CONTENT_TYPE_HEADER.equalsIgnoreCase(e.getKey()))
                .findFirst()
                .map(Map.Entry::getValue).orElse(null));
    }


    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final WrappingMessageMapper that = (WrappingMessageMapper) o;
        return Objects.equals(delegate, that.delegate) &&
                Objects.equals(contentTypeOverride, that.contentTypeOverride);
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate, contentTypeOverride);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "delegate=" + delegate +
                ", contentTypeOverride=" + contentTypeOverride +
                "]";
    }
}
