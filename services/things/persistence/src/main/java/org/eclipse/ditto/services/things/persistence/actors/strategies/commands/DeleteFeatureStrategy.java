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
package org.eclipse.ditto.services.things.persistence.actors.strategies.commands;

import java.util.Optional;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.things.Feature;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.signals.commands.things.modify.DeleteFeature;
import org.eclipse.ditto.signals.commands.things.modify.DeleteFeatureResponse;
import org.eclipse.ditto.signals.events.things.FeatureDeleted;

/**
 * This strategy handles the {@link org.eclipse.ditto.signals.commands.things.modify.DeleteFeature} command.
 */
@Immutable
final class DeleteFeatureStrategy extends AbstractConditionalHeadersCheckingCommandStrategy<DeleteFeature, Feature> {

    /**
     * Constructs a new {@code DeleteFeatureStrategy} object.
     */
    DeleteFeatureStrategy() {
        super(DeleteFeature.class);
    }

    @Override
    protected Result doApply(final Context context, @Nullable final Thing thing,
            final long nextRevision, final DeleteFeature command) {
        final String featureId = command.getFeatureId();

        return extractFeature(command, thing)
                .map(feature -> getDeleteFeatureResult(context, nextRevision, command))
                .orElseGet(() -> ResultFactory.newErrorResult(
                        ExceptionFactory.featureNotFound(context.getThingEntityId(), featureId, command.getDittoHeaders())));
    }

    private Optional<Feature> extractFeature(final DeleteFeature command, @Nullable final Thing thing) {
        final String featureId = command.getFeatureId();

        return getThingOrThrow(thing).getFeatures()
                .flatMap(features -> features.getFeature(featureId));
    }

    private Result getDeleteFeatureResult(final Context context, final long nextRevision,
            final DeleteFeature command) {
        final ThingId thingId = context.getThingEntityId();
        final String featureId = command.getFeatureId();
        final DittoHeaders dittoHeaders = command.getDittoHeaders();

        return ResultFactory.newMutationResult(command,
                FeatureDeleted.of(thingId, featureId, nextRevision, getEventTimestamp(), dittoHeaders),
                DeleteFeatureResponse.of(thingId, featureId, dittoHeaders), this);
    }

    @Override
    public Optional<Feature> determineETagEntity(final DeleteFeature command, @Nullable final Thing thing) {
        return extractFeature(command, thing);
    }
}
