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
package org.eclipse.ditto.services.concierge.actors.batch;

import org.eclipse.ditto.services.utils.persistence.mongo.AbstractMongoEventAdapter;
import org.eclipse.ditto.signals.events.base.Event;
import org.eclipse.ditto.signals.events.base.GlobalEventRegistry;
import org.eclipse.ditto.signals.events.batch.BatchEvent;

import akka.actor.ExtendedActorSystem;

/**
 * EventAdapter for {@link BatchEvent}s persisted into akka-persistence event-journal.
 * Converts Events to MongoDB BSON objects and vice versa.
 */
public final class MongoBatchEventAdapter extends AbstractMongoEventAdapter<Event> {

    public MongoBatchEventAdapter(final ExtendedActorSystem system) {
        super(system, GlobalEventRegistry.getInstance());
    }

}
