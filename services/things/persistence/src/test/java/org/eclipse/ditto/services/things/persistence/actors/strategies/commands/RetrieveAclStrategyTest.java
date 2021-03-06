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

import static org.eclipse.ditto.model.things.TestConstants.Thing.THING_V1;
import static org.eclipse.ditto.model.things.TestConstants.Thing.THING_V2;
import static org.eclipse.ditto.services.things.persistence.actors.ETagTestUtils.retrieveAclResponse;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.JsonSchemaVersion;
import org.eclipse.ditto.model.things.AccessControlList;
import org.eclipse.ditto.services.things.persistence.actors.strategies.commands.CommandStrategy.Context;
import org.eclipse.ditto.signals.commands.things.query.RetrieveAcl;
import org.eclipse.ditto.signals.commands.things.query.RetrieveAclResponse;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit test for {@link RetrieveAclStrategy}.
 */
public final class RetrieveAclStrategyTest extends AbstractCommandStrategyTest {

    private RetrieveAclStrategy underTest;

    @Before
    public void setUp() {
        underTest = new RetrieveAclStrategy();
    }

    @Test
    public void assertImmutability() {
        assertInstancesOf(RetrieveAclStrategy.class, areImmutable());
    }

    @Test
    public void resultContainsJsonOfExistingAcl() {
        final Context context = getDefaultContext();
        final RetrieveAcl command = RetrieveAcl.of(context.getThingEntityId(), DittoHeaders.empty());

        final AccessControlList expectedAcl = THING_V1.getAccessControlList().get();
        final JsonObject expectedAclJson = expectedAcl.toJson(JsonSchemaVersion.V_1);

        final RetrieveAclResponse expectedResponse =
                retrieveAclResponse(command.getThingEntityId(), expectedAcl, expectedAclJson, command.getDittoHeaders());

        assertQueryResult(underTest, THING_V1, command, expectedResponse);
    }

    @Test
    public void resultContainsEmptyJsonObject() {
        final Context context = getDefaultContext();
        final RetrieveAcl command = RetrieveAcl.of(context.getThingEntityId(), DittoHeaders.empty());
        final RetrieveAclResponse expectedResponse =
                RetrieveAclResponse.of(command.getThingEntityId(), JsonFactory.newObject(), command.getDittoHeaders());

        assertQueryResult(underTest, THING_V2, command, expectedResponse);
    }

}
