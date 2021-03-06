/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.connectivity.mapping;

import java.util.Optional;

/**
 * Defines a collection of mappers with a fallback default mapper.
 */
public interface MessageMapperRegistry {

    /**
     * Returns a mapper with the supposed role of a fallback mapping strategy.
     * @return the default mapper
     */
    MessageMapper getDefaultMapper();

    /**
     * Returns the mapper to use for mapping.
     * @return the mapper
     */
    Optional<MessageMapper> getMapper();

}
