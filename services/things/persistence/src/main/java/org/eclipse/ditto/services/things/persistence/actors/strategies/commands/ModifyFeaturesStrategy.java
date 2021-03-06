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
import org.eclipse.ditto.model.things.Features;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.signals.commands.things.ThingCommandSizeValidator;
import org.eclipse.ditto.signals.commands.things.modify.ModifyFeatures;
import org.eclipse.ditto.signals.commands.things.modify.ModifyFeaturesResponse;
import org.eclipse.ditto.signals.events.things.FeaturesCreated;
import org.eclipse.ditto.signals.events.things.FeaturesModified;

/**
 * This strategy handles the {@link org.eclipse.ditto.signals.commands.things.modify.ModifyFeatures} command.
 */
@Immutable
final class ModifyFeaturesStrategy
        extends AbstractConditionalHeadersCheckingCommandStrategy<ModifyFeatures, Features> {

    /**
     * Constructs a new {@code ModifyFeaturesStrategy} object.
     */
    ModifyFeaturesStrategy() {
        super(ModifyFeatures.class);
    }

    @Override
    protected Result doApply(final Context context, @Nullable final Thing thing,
            final long nextRevision, final ModifyFeatures command) {

        final Thing nonNullThing = getThingOrThrow(thing);

        ThingCommandSizeValidator.getInstance().ensureValidSize(() -> {
            final long lengthWithOutFeatures = nonNullThing.removeFeatures()
                    .toJsonString()
                    .length();
            final long featuresLength = command.getFeatures().toJsonString().length() + "features".length() + 5L;
            return lengthWithOutFeatures + featuresLength;
        }, command::getDittoHeaders);

        return nonNullThing.getFeatures()
                .map(features -> getModifyResult(context, nextRevision, command))
                .orElseGet(() -> getCreateResult(context, nextRevision, command));
    }

    private Result getModifyResult(final Context context, final long nextRevision,
            final ModifyFeatures command) {
        final DittoHeaders dittoHeaders = command.getDittoHeaders();

        return ResultFactory.newMutationResult(command,
                FeaturesModified.of(command.getThingEntityId(), command.getFeatures(), nextRevision,
                        getEventTimestamp(), dittoHeaders),
                ModifyFeaturesResponse.modified(context.getThingEntityId(), dittoHeaders),
                this);
    }

    private Result getCreateResult(final Context context, final long nextRevision,
            final ModifyFeatures command) {
        final Features features = command.getFeatures();
        final DittoHeaders dittoHeaders = command.getDittoHeaders();

        return ResultFactory.newMutationResult(command,
                FeaturesCreated.of(command.getThingEntityId(), features, nextRevision, getEventTimestamp(),
                        dittoHeaders),
                ModifyFeaturesResponse.created(context.getThingEntityId(), features, dittoHeaders),
                this);
    }


    @Override
    public Optional<Features> determineETagEntity(final ModifyFeatures command, @Nullable final Thing thing) {
        return getThingOrThrow(thing).getFeatures();
    }
}
