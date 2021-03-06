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
import org.eclipse.ditto.model.things.FeatureDefinition;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.signals.commands.things.query.RetrieveFeatureDefinition;
import org.eclipse.ditto.signals.commands.things.query.RetrieveFeatureDefinitionResponse;

/**
 * This strategy handles the {@link RetrieveFeatureDefinition} command.
 */
@Immutable
public final class RetrieveFeatureDefinitionStrategy extends
        AbstractConditionalHeadersCheckingCommandStrategy<RetrieveFeatureDefinition, FeatureDefinition> {

    /**
     * Constructs a new {@code RetrieveFeatureDefinitionStrategy} object.
     */
    RetrieveFeatureDefinitionStrategy() {
        super(RetrieveFeatureDefinition.class);
    }

    @Override
    protected Result doApply(final Context context, @Nullable final Thing thing,
            final long nextRevision, final RetrieveFeatureDefinition command) {
        final ThingId thingId = context.getThingEntityId();
        final String featureId = command.getFeatureId();

        return extractFeature(command, thing)
                .map(feature -> getFeatureDefinition(feature, thingId, command, thing))
                .orElseGet(() -> ResultFactory.newErrorResult(ExceptionFactory.featureNotFound(thingId,
                        featureId, command.getDittoHeaders())));
    }

    private Optional<Feature> extractFeature(final RetrieveFeatureDefinition command, final @Nullable Thing thing) {
        return getThingOrThrow(thing).getFeatures()
                .flatMap(features -> features.getFeature(command.getFeatureId()));
    }

    private Result getFeatureDefinition(final Feature feature, final ThingId thingId,
            final RetrieveFeatureDefinition command, @Nullable final Thing thing) {

        final String featureId = feature.getId();
        final DittoHeaders dittoHeaders = command.getDittoHeaders();

        return feature.getDefinition()
                .map(featureDefinition -> RetrieveFeatureDefinitionResponse.of(thingId, featureId,
                        featureDefinition, dittoHeaders))
                .map(response -> ResultFactory.newQueryResult(command, thing, response, this))
                .orElseGet(() -> ResultFactory.newErrorResult(
                        ExceptionFactory.featureDefinitionNotFound(thingId, featureId, dittoHeaders)));
    }


    @Override
    public Optional<FeatureDefinition> determineETagEntity(final RetrieveFeatureDefinition command,
            @Nullable final Thing thing) {

        return extractFeature(command, thing)
                .flatMap(Feature::getDefinition);
    }
}
