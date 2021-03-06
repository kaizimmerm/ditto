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
package org.eclipse.ditto.services.policies.persistence.actors.policy;

import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.concurrent.NotThreadSafe;

import org.eclipse.ditto.json.JsonCollectors;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.headers.WithDittoHeaders;
import org.eclipse.ditto.model.base.headers.entitytag.EntityTag;
import org.eclipse.ditto.model.policies.Label;
import org.eclipse.ditto.model.policies.PoliciesModelFactory;
import org.eclipse.ditto.model.policies.Policy;
import org.eclipse.ditto.model.policies.PolicyBuilder;
import org.eclipse.ditto.model.policies.PolicyEntry;
import org.eclipse.ditto.model.policies.PolicyId;
import org.eclipse.ditto.model.policies.PolicyLifecycle;
import org.eclipse.ditto.model.policies.PolicyTooLargeException;
import org.eclipse.ditto.model.policies.Resource;
import org.eclipse.ditto.model.policies.ResourceKey;
import org.eclipse.ditto.model.policies.Resources;
import org.eclipse.ditto.model.policies.Subject;
import org.eclipse.ditto.model.policies.SubjectId;
import org.eclipse.ditto.model.policies.Subjects;
import org.eclipse.ditto.services.models.policies.PoliciesMessagingConstants;
import org.eclipse.ditto.services.models.policies.PoliciesValidator;
import org.eclipse.ditto.services.models.policies.commands.sudo.SudoRetrievePolicy;
import org.eclipse.ditto.services.models.policies.commands.sudo.SudoRetrievePolicyResponse;
import org.eclipse.ditto.services.policies.common.config.DittoPoliciesConfig;
import org.eclipse.ditto.services.policies.common.config.PolicyConfig;
import org.eclipse.ditto.services.policies.persistence.actors.AbstractReceiveStrategy;
import org.eclipse.ditto.services.policies.persistence.actors.ReceiveStrategy;
import org.eclipse.ditto.services.policies.persistence.actors.StrategyAwareReceiveBuilder;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.services.utils.cleanup.AbstractPersistentActorWithTimersAndCleanup;
import org.eclipse.ditto.services.utils.cluster.DistPubSubAccess;
import org.eclipse.ditto.services.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.services.utils.headers.conditional.ConditionalHeadersValidator;
import org.eclipse.ditto.services.utils.persistence.SnapshotAdapter;
import org.eclipse.ditto.services.utils.persistence.mongo.config.ActivityCheckConfig;
import org.eclipse.ditto.signals.commands.base.Command;
import org.eclipse.ditto.signals.commands.base.CommandResponse;
import org.eclipse.ditto.signals.commands.policies.PolicyCommandSizeValidator;
import org.eclipse.ditto.signals.commands.policies.exceptions.PolicyConflictException;
import org.eclipse.ditto.signals.commands.policies.exceptions.PolicyEntryModificationInvalidException;
import org.eclipse.ditto.signals.commands.policies.exceptions.PolicyEntryNotAccessibleException;
import org.eclipse.ditto.signals.commands.policies.exceptions.PolicyModificationInvalidException;
import org.eclipse.ditto.signals.commands.policies.exceptions.PolicyNotAccessibleException;
import org.eclipse.ditto.signals.commands.policies.exceptions.ResourceNotAccessibleException;
import org.eclipse.ditto.signals.commands.policies.exceptions.SubjectNotAccessibleException;
import org.eclipse.ditto.signals.commands.policies.modify.CreatePolicy;
import org.eclipse.ditto.signals.commands.policies.modify.CreatePolicyResponse;
import org.eclipse.ditto.signals.commands.policies.modify.DeletePolicy;
import org.eclipse.ditto.signals.commands.policies.modify.DeletePolicyEntry;
import org.eclipse.ditto.signals.commands.policies.modify.DeletePolicyEntryResponse;
import org.eclipse.ditto.signals.commands.policies.modify.DeletePolicyResponse;
import org.eclipse.ditto.signals.commands.policies.modify.DeleteResource;
import org.eclipse.ditto.signals.commands.policies.modify.DeleteResourceResponse;
import org.eclipse.ditto.signals.commands.policies.modify.DeleteSubject;
import org.eclipse.ditto.signals.commands.policies.modify.DeleteSubjectResponse;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyPolicy;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyPolicyEntries;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyPolicyEntriesResponse;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyPolicyEntry;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyPolicyEntryResponse;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyPolicyResponse;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyResource;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyResourceResponse;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyResources;
import org.eclipse.ditto.signals.commands.policies.modify.ModifyResourcesResponse;
import org.eclipse.ditto.signals.commands.policies.modify.ModifySubject;
import org.eclipse.ditto.signals.commands.policies.modify.ModifySubjectResponse;
import org.eclipse.ditto.signals.commands.policies.modify.ModifySubjects;
import org.eclipse.ditto.signals.commands.policies.modify.ModifySubjectsResponse;
import org.eclipse.ditto.signals.commands.policies.query.RetrievePolicy;
import org.eclipse.ditto.signals.commands.policies.query.RetrievePolicyEntries;
import org.eclipse.ditto.signals.commands.policies.query.RetrievePolicyEntriesResponse;
import org.eclipse.ditto.signals.commands.policies.query.RetrievePolicyEntry;
import org.eclipse.ditto.signals.commands.policies.query.RetrievePolicyEntryResponse;
import org.eclipse.ditto.signals.commands.policies.query.RetrievePolicyResponse;
import org.eclipse.ditto.signals.commands.policies.query.RetrieveResource;
import org.eclipse.ditto.signals.commands.policies.query.RetrieveResourceResponse;
import org.eclipse.ditto.signals.commands.policies.query.RetrieveResources;
import org.eclipse.ditto.signals.commands.policies.query.RetrieveResourcesResponse;
import org.eclipse.ditto.signals.commands.policies.query.RetrieveSubject;
import org.eclipse.ditto.signals.commands.policies.query.RetrieveSubjectResponse;
import org.eclipse.ditto.signals.commands.policies.query.RetrieveSubjects;
import org.eclipse.ditto.signals.commands.policies.query.RetrieveSubjectsResponse;
import org.eclipse.ditto.signals.events.policies.PolicyCreated;
import org.eclipse.ditto.signals.events.policies.PolicyDeleted;
import org.eclipse.ditto.signals.events.policies.PolicyEntriesModified;
import org.eclipse.ditto.signals.events.policies.PolicyEntryCreated;
import org.eclipse.ditto.signals.events.policies.PolicyEntryDeleted;
import org.eclipse.ditto.signals.events.policies.PolicyEntryModified;
import org.eclipse.ditto.signals.events.policies.PolicyEvent;
import org.eclipse.ditto.signals.events.policies.PolicyModified;
import org.eclipse.ditto.signals.events.policies.ResourceCreated;
import org.eclipse.ditto.signals.events.policies.ResourceDeleted;
import org.eclipse.ditto.signals.events.policies.ResourceModified;
import org.eclipse.ditto.signals.events.policies.ResourcesModified;
import org.eclipse.ditto.signals.events.policies.SubjectCreated;
import org.eclipse.ditto.signals.events.policies.SubjectDeleted;
import org.eclipse.ditto.signals.events.policies.SubjectModified;
import org.eclipse.ditto.signals.events.policies.SubjectsModified;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.cluster.sharding.ClusterSharding;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.function.Procedure;
import akka.japi.pf.FI;
import akka.japi.pf.ReceiveBuilder;
import akka.persistence.RecoveryCompleted;
import akka.persistence.SaveSnapshotFailure;
import akka.persistence.SaveSnapshotSuccess;
import akka.persistence.SnapshotOffer;

/**
 * PersistentActor which "knows" the state of a single {@link Policy}.
 */
public final class PolicyPersistenceActor extends AbstractPersistentActorWithTimersAndCleanup {

    /**
     * The prefix of the persistenceId for Policies.
     */
    public static final String PERSISTENCE_ID_PREFIX = "policy:";

    /**
     * The ID of the journal plugin this persistence actor uses.
     */
    static final String JOURNAL_PLUGIN_ID = "akka-contrib-mongodb-persistence-policies-journal";

    /**
     * The ID of the snapshot plugin this persistence actor uses.
     */
    static final String SNAPSHOT_PLUGIN_ID = "akka-contrib-mongodb-persistence-policies-snapshots";

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);
    private final PolicyId policyId;
    private final SnapshotAdapter<Policy> snapshotAdapter;
    private final ActorRef pubSubMediator;
    private final PolicyConfig policyConfig;
    private final Receive handlePolicyEvents;

    private Policy policy;
    private long accessCounter;
    private long lastSnapshotSequenceNr = 0L;
    private long confirmedSnapshotSequenceNr = 0L;

    PolicyPersistenceActor(final PolicyId policyId,
            final SnapshotAdapter<Policy> snapshotAdapter,
            final ActorRef pubSubMediator) {

        this.policyId = policyId;
        this.snapshotAdapter = snapshotAdapter;
        this.pubSubMediator = pubSubMediator;
        final DittoPoliciesConfig policiesConfig = DittoPoliciesConfig.of(
                DefaultScopedConfig.dittoScoped(getContext().getSystem().settings().config())
        );
        this.policyConfig = policiesConfig.getPolicyConfig();

        scheduleCheckForPolicyActivity(policyConfig.getActivityCheckConfig().getDeletedInterval());

        handlePolicyEvents = ReceiveBuilder.create()

                // # Policy Creation Recovery
                .match(PolicyCreated.class, pc -> policy = pc.getPolicy().toBuilder()
                        .setLifecycle(PolicyLifecycle.ACTIVE)
                        .setRevision(lastSequenceNr())
                        .setModified(pc.getTimestamp().orElse(null))
                        .build())

                // # Policy Modification Recovery
                .match(PolicyModified.class, pm -> null != policy, pm -> {
                    // we need to use the current policy as base otherwise we would loose its state
                    final PolicyBuilder copyBuilder = policy.toBuilder();
                    copyBuilder.removeAll(policy); // remove all old policyEntries!
                    copyBuilder.setAll(pm.getPolicy().getEntriesSet()); // add the new ones
                    policy = copyBuilder.setRevision(lastSequenceNr())
                            .setModified(pm.getTimestamp().orElse(null))
                            .build();
                })

                // # Policy Deletion Recovery
                .match(PolicyDeleted.class, pd -> null != policy, pd -> policy = policy.toBuilder()
                        .setLifecycle(PolicyLifecycle.DELETED)
                        .setRevision(lastSequenceNr())
                        .setModified(pd.getTimestamp().orElse(null))
                        .build())

                // # Policy Entries Modification Recovery
                .match(PolicyEntriesModified.class, pem -> null != policy, pem -> policy = policy.toBuilder()
                        .removeAll(policy.getEntriesSet())
                        .setAll(pem.getPolicyEntries())
                        .setRevision(lastSequenceNr())
                        .setModified(pem.getTimestamp().orElse(null))
                        .build())


                // # Policy Entry Creation Recovery
                .match(PolicyEntryCreated.class, pec -> null != policy, pec -> policy = policy.toBuilder()
                        .set(pec.getPolicyEntry())
                        .setRevision(lastSequenceNr())
                        .setModified(pec.getTimestamp().orElse(null))
                        .build())

                // # Policy Entry Modification Recovery
                .match(PolicyEntryModified.class, pem -> null != policy, pem -> policy = policy.toBuilder()
                        .set(pem.getPolicyEntry())
                        .setRevision(lastSequenceNr())
                        .setModified(pem.getTimestamp().orElse(null))
                        .build())

                // # Policy Entry Deletion Recovery
                .match(PolicyEntryDeleted.class, ped -> null != policy, ped -> policy = policy.toBuilder()
                        .remove(ped.getLabel())
                        .setRevision(lastSequenceNr())
                        .setModified(ped.getTimestamp().orElse(null))
                        .build())

                // # Subjects Modification Recovery
                .match(SubjectsModified.class, sm -> null != policy, sm -> policy.getEntryFor(sm.getLabel())
                        .map(policyEntry -> PoliciesModelFactory
                                .newPolicyEntry(sm.getLabel(), sm.getSubjects(), policyEntry.getResources()))
                        .ifPresent(modifiedPolicyEntry -> policy = policy.toBuilder()
                                .set(modifiedPolicyEntry)
                                .setRevision(lastSequenceNr())
                                .setModified(sm.getTimestamp().orElse(null))
                                .build()))

                // # Subject Creation Recovery
                .match(SubjectCreated.class, sc -> null != policy, sc -> policy.getEntryFor(sc.getLabel())
                        .map(policyEntry -> PoliciesModelFactory
                                .newPolicyEntry(sc.getLabel(), policyEntry.getSubjects().setSubject(sc.getSubject()),
                                        policyEntry.getResources()))
                        .ifPresent(modifiedPolicyEntry -> policy = policy.toBuilder()
                                .set(modifiedPolicyEntry)
                                .setRevision(lastSequenceNr())
                                .setModified(sc.getTimestamp().orElse(null))
                                .build()))

                // # Subject Modification Recovery
                .match(SubjectModified.class, sm -> null != policy, sm -> policy.getEntryFor(sm.getLabel())
                        .map(policyEntry -> PoliciesModelFactory
                                .newPolicyEntry(sm.getLabel(), policyEntry.getSubjects().setSubject(sm.getSubject()),
                                        policyEntry.getResources()))
                        .ifPresent(modifiedPolicyEntry -> policy = policy.toBuilder()
                                .set(modifiedPolicyEntry)
                                .setRevision(lastSequenceNr())
                                .setModified(sm.getTimestamp().orElse(null))
                                .build()))

                // # Subject Deletion Recovery
                .match(SubjectDeleted.class, sd -> null != policy, sd -> policy = policy.toBuilder()
                        .forLabel(sd.getLabel())
                        .removeSubject(sd.getSubjectId())
                        .setRevision(lastSequenceNr())
                        .setModified(sd.getTimestamp().orElse(null))
                        .build())

                // # Resources Modification Recovery
                .match(ResourcesModified.class, rm -> null != policy, rm -> policy.getEntryFor(rm.getLabel())
                        .map(policyEntry -> PoliciesModelFactory
                                .newPolicyEntry(rm.getLabel(), policyEntry.getSubjects(), rm.getResources()))
                        .ifPresent(modifiedPolicyEntry -> policy = policy.toBuilder()
                                .set(modifiedPolicyEntry)
                                .setRevision(lastSequenceNr())
                                .setModified(rm.getTimestamp().orElse(null))
                                .build()))

                // # Resource Creation Recovery
                .match(ResourceCreated.class, rc -> null != policy, rc -> policy.getEntryFor(rc.getLabel())
                        .map(policyEntry -> PoliciesModelFactory.newPolicyEntry(rc.getLabel(),
                                policyEntry.getSubjects(),
                                policyEntry.getResources().setResource(rc.getResource())))
                        .ifPresent(modifiedPolicyEntry -> policy = policy.toBuilder()
                                .set(modifiedPolicyEntry)
                                .setRevision(lastSequenceNr())
                                .setModified(rc.getTimestamp().orElse(null))
                                .build()))

                // # Resource Modification Recovery
                .match(ResourceModified.class, rm -> null != policy, rm -> policy.getEntryFor(rm.getLabel())
                        .map(policyEntry -> PoliciesModelFactory.newPolicyEntry(rm.getLabel(),
                                policyEntry.getSubjects(),
                                policyEntry.getResources().setResource(rm.getResource())))
                        .ifPresent(modifiedPolicyEntry -> policy = policy.toBuilder()
                                .set(modifiedPolicyEntry)
                                .setRevision(lastSequenceNr())
                                .setModified(rm.getTimestamp().orElse(null))
                                .build()))

                // # Resource Deletion Recovery
                .match(ResourceDeleted.class, rd -> null != policy, rd -> policy = policy.toBuilder()
                        .forLabel(rd.getLabel())
                        .removeResource(rd.getResourceKey())
                        .setRevision(lastSequenceNr())
                        .setModified(rd.getTimestamp().orElse(null))
                        .build())

                .build();
    }

    /**
     * Creates Akka configuration object {@link Props} for this PolicyPersistenceActor.
     *
     * @param policyId the ID of the Policy this Actor manages.
     * @param snapshotAdapter the adapter to serialize Policy snapshots.
     * @param pubSubMediator the PubSub mediator actor.
     * @return the Akka configuration Props object
     */
    public static Props props(final PolicyId policyId,
            final SnapshotAdapter<Policy> snapshotAdapter,
            final ActorRef pubSubMediator) {

        return Props.create(PolicyPersistenceActor.class, policyId, snapshotAdapter, pubSubMediator);
    }

    /**
     * Retrieves the ShardRegion of "Policy". PolicyCommands can be sent to this region which handles dispatching them
     * in the cluster (onto the cluster node containing the shard).
     *
     * @param system the ActorSystem in which to lookup the ShardRegion.
     * @return the ActorRef to the ShardRegion.
     */
    public static ActorRef getShardRegion(final ActorSystem system) {
        return ClusterSharding.get(system).shardRegion(PoliciesMessagingConstants.SHARD_REGION);
    }

    private static Instant getEventTimestamp() {
        return Instant.now();
    }

    private void scheduleCheckForPolicyActivity(final Duration delay) {
        final Object checkForActivity = new CheckForActivity(lastSequenceNr(), accessCounter);
        timers().startSingleTimer("activityCheck", checkForActivity, delay);
    }

    private void scheduleSnapshot() {
        final Duration interval = policyConfig.getSnapshotConfig().getInterval();
        timers().startPeriodicTimer("takeSnapshot", new TakeSnapshot(), interval);
    }

    private void cancelSnapshot() {
        timers().cancel("takeSnapshot");
    }

    @Override
    public String persistenceId() {
        return PERSISTENCE_ID_PREFIX + policyId;
    }

    @Override
    public String journalPluginId() {
        return JOURNAL_PLUGIN_ID;
    }

    @Override
    public String snapshotPluginId() {
        return SNAPSHOT_PLUGIN_ID;
    }

    @Override
    public Receive createReceive() {
        /*
         * First no Policy for the ID exists at all. Thus the only command this Actor reacts to is CreatePolicy.
         * This behaviour changes as soon as a Policy was created.
         */
        final StrategyAwareReceiveBuilder initialReceiveCommandBuilder = new StrategyAwareReceiveBuilder();
        initialReceiveCommandBuilder.match(new CreatePolicyStrategy());
        initialReceiveCommandBuilder.match(new CheckForActivityStrategy());
        initialReceiveCommandBuilder.matchAny(new MatchAnyDuringInitializeStrategy());
        return initialReceiveCommandBuilder.build();
    }

    @Override
    protected long getLatestSnapshotSequenceNumber() {
        return confirmedSnapshotSequenceNr;
    }

    @Override
    public Receive createReceiveRecover() {
        // defines how state is updated during recovery
        return handlePolicyEvents.orElse(ReceiveBuilder.create()

                // # Snapshot handling
                .match(SnapshotOffer.class, ss -> {
                    policy = snapshotAdapter.fromSnapshotStore(ss);
                    lastSnapshotSequenceNr = confirmedSnapshotSequenceNr = ss.metadata().sequenceNr();
                })

                // # Recovery handling
                .match(RecoveryCompleted.class, rc -> {
                    if (policy != null) {
                        log.debug("Policy <{}> was recovered.", policyId);

                        if (isPolicyActive()) {
                            becomePolicyCreatedHandler();
                        } else if (isPolicyDeleted()) {
                            becomePolicyDeletedHandler();
                        } else {
                            log.error("Unknown lifecycle state <{}> for Policy <{}>.", policy.getLifecycle(), policyId);
                        }

                    }
                })

                // # Handle unknown
                .matchAny(m -> log.warning("Unknown recover message: {}", m))
                .build());
    }

    /*
     * Now as the {@code policy} reference is not {@code null} the strategies which act on this reference can
     * be activated. In return the strategy for the CreatePolicy command is not needed anymore.
     */
    private void becomePolicyCreatedHandler() {
        final Collection<ReceiveStrategy<?>> policyCreatedStrategies = initPolicyCreatedStrategies();
        final StrategyAwareReceiveBuilder strategyAwareReceiveBuilder =
                new StrategyAwareReceiveBuilder().withReceiveFromSuperClass(super.createReceive());
        policyCreatedStrategies.forEach(strategyAwareReceiveBuilder::match);
        strategyAwareReceiveBuilder.matchAny(new MatchAnyAfterInitializeStrategy());

        getContext().become(strategyAwareReceiveBuilder.build(), true);
        getContext().getParent().tell(new PolicySupervisorActor.ManualReset(), getSelf());
        scheduleCheckForPolicyActivity(policyConfig.getActivityCheckConfig().getInactiveInterval());
        scheduleSnapshot();
    }

    private Collection<ReceiveStrategy<?>> initPolicyCreatedStrategies() {
        final Collection<ReceiveStrategy<?>> result = new ArrayList<>();

        // Policy level
        result.add(new PolicyConflictStrategy());
        result.add(new ModifyPolicyStrategy());
        result.add(new RetrievePolicyStrategy());
        result.add(new DeletePolicyStrategy());

        // Policy Entries
        result.add(new ModifyPolicyEntriesStrategy());
        result.add(new RetrievePolicyEntriesStrategy());

        // Policy Entry
        result.add(new ModifyPolicyEntryStrategy());
        result.add(new RetrievePolicyEntryStrategy());
        result.add(new DeletePolicyEntryStrategy());

        // Subjects
        result.add(new ModifySubjectsStrategy());
        result.add(new ModifySubjectStrategy());
        result.add(new RetrieveSubjectsStrategy());
        result.add(new RetrieveSubjectStrategy());
        result.add(new DeleteSubjectStrategy());

        // Resources
        result.add(new ModifyResourcesStrategy());
        result.add(new ModifyResourceStrategy());
        result.add(new RetrieveResourcesStrategy());
        result.add(new RetrieveResourceStrategy());
        result.add(new DeleteResourceStrategy());

        // Sudo
        result.add(new SudoRetrievePolicyStrategy());
        result.add(new CheckForActivityStrategy());

        result.addAll(getTakeSnapshotStrategies());

        return result;
    }

    private void becomePolicyDeletedHandler() {
        final Collection<ReceiveStrategy<?>> policyDeletedStrategies = initPolicyDeletedStrategies();
        final StrategyAwareReceiveBuilder strategyAwareReceiveBuilder =
                new StrategyAwareReceiveBuilder().withReceiveFromSuperClass(super.createReceive());
        policyDeletedStrategies.forEach(strategyAwareReceiveBuilder::match);
        strategyAwareReceiveBuilder.matchAny(new PolicyNotFoundStrategy());

        getContext().become(strategyAwareReceiveBuilder.build(), true);
        getContext().getParent().tell(new PolicySupervisorActor.ManualReset(), getSelf());

        /*
         * Check in the next X minutes and therefore
         * - stay in-memory for a short amount of minutes after deletion
         * - get a Snapshot when removed from memory
         */
        final ActivityCheckConfig activityCheckConfig = policyConfig.getActivityCheckConfig();
        scheduleCheckForPolicyActivity(activityCheckConfig.getDeletedInterval());
        cancelSnapshot();
    }

    private Collection<ReceiveStrategy<?>> initPolicyDeletedStrategies() {
        final Collection<ReceiveStrategy<?>> result = new ArrayList<>();
        result.add(new CreatePolicyStrategy());
        result.add(new CheckForActivityStrategy());
        result.addAll(getTakeSnapshotStrategies());
        return result;
    }

    private <E extends PolicyEvent> void processEvent(final E event, final Procedure<E> handler) {
        log.debug("About to persist Event <{}>.", event.getType());

        persist(event, persistedEvent -> {
            log.info("Successfully persisted Event <{}>.", event.getType());

            // after the event was persisted, apply the event on the current actor state
            handlePolicyEvents.onMessage().apply(persistedEvent);

            /*
             * The event has to be applied before creating the snapshot, otherwise a snapshot with new
             * sequence no (e.g. 2), but old thing revision no (e.g. 1) will be created. This can lead to serious
             * aftereffects.
             */
            handler.apply(persistedEvent);

            // save a snapshot if there were too many changes since the last snapshot
            if ((lastSequenceNr() - lastSnapshotSequenceNr) >= policyConfig.getSnapshotConfig().getThreshold()) {
                takeSnapshot("snapshot threshold is reached");
            }
            notifySubscribers(event);
        });
    }

    private long getNextRevision() {
        return lastSequenceNr() + 1;
    }

    private boolean isPolicyActive() {
        return policy != null && policy.hasLifecycle(PolicyLifecycle.ACTIVE);
    }

    private boolean isPolicyDeleted() {
        return null == policy || policy.hasLifecycle(PolicyLifecycle.DELETED);
    }

    private boolean policyExistsAsDeleted() {
        return null != policy && policy.hasLifecycle(PolicyLifecycle.DELETED);
    }

    private void takeSnapshot(final String reason) {
        final long revision = lastSequenceNr();
        if (policy != null && lastSnapshotSequenceNr != revision) {
            log.info("Taking snapshot for policy with ID <{}> and revision <{}> because {}.", policy, revision, reason);
            final Object snapshotSubject = snapshotAdapter.toSnapshotStore(policy);
            saveSnapshot(snapshotSubject);
            lastSnapshotSequenceNr = revision;
        } else if (lastSnapshotSequenceNr == revision) {
            log.info("Not taking duplicate snapshot for policy <{}> with revision <{}> even if {}.", policy, revision,
                    reason);
        } else {
            log.info("Not taking snapshot for nonexistent policy <{}> even if {}.", policyId, reason);
        }
    }

    private Collection<ReceiveStrategy<?>> getTakeSnapshotStrategies() {
        return Arrays.asList(
                SimpleStrategy.of(TakeSnapshot.class, t -> takeSnapshot("snapshot interval has passed")),
                SimpleStrategy.of(SaveSnapshotSuccess.class, s -> {
                    log.info("Got {}", s);
                    confirmedSnapshotSequenceNr = s.metadata().sequenceNr();
                }),
                SimpleStrategy.of(SaveSnapshotFailure.class, s -> log.error("Got {}", s, s.cause()))
        );
    }

    private void notifySubscribers(final PolicyEvent event) {
        pubSubMediator.tell(DistPubSubAccess.publishViaGroup(PolicyEvent.TYPE_PREFIX, event), getSelf());
    }

    private void policyEntryNotFound(final Label label, final DittoHeaders dittoHeaders) {
        notifySender(PolicyEntryNotAccessibleException.newBuilder(policyId, label).dittoHeaders(dittoHeaders).build());
    }

    private void subjectNotFound(final Label label, final CharSequence subjectId, final DittoHeaders dittoHeaders) {
        notifySender(SubjectNotAccessibleException.newBuilder(policyId, label.toString(), subjectId)
                .dittoHeaders(dittoHeaders)
                .build());
    }

    private void resourceNotFound(final Label label, final ResourceKey resourceKey, final DittoHeaders dittoHeaders) {
        notifySender(ResourceNotAccessibleException.newBuilder(policyId, label, resourceKey.toString())
                .dittoHeaders(dittoHeaders)
                .build());
    }

    private WithDittoHeaders policyNotFound(final DittoHeaders dittoHeaders) {
        return PolicyNotAccessibleException.newBuilder(policyId).dittoHeaders(dittoHeaders).build();
    }

    private void policyInvalid(final String message, final DittoHeaders dittoHeaders) {
        final PolicyModificationInvalidException exception = PolicyModificationInvalidException.newBuilder(policyId)
                .description(message)
                .dittoHeaders(dittoHeaders)
                .build();

        notifySender(exception);
    }

    private void policyEntryInvalid(final Label label, final String message, final DittoHeaders dittoHeaders) {
        final PolicyEntryModificationInvalidException exception =
                PolicyEntryModificationInvalidException.newBuilder(policyId, label)
                        .description(message)
                        .dittoHeaders(dittoHeaders)
                        .build();

        notifySender(exception);
    }

    private void notifySender(final WithDittoHeaders message) {
        accessCounter++;
        notifySender(getSender(), message);
    }

    private void notifySender(final ActorRef sender, final WithDittoHeaders message) {
        accessCounter++;
        sender.tell(message, getSelf());
    }

    /**
     * Message the PolicyPersistenceActor can send to itself to check for activity of the Actor and terminate itself
     * if there was no activity since the last check.
     */
    static final class CheckForActivity {

        private final long currentSequenceNr;
        private final long currentAccessCounter;

        /**
         * Constructs a new {@code CheckForActivity} message containing the current "lastSequenceNo" of the
         * PolicyPersistenceActor.
         *
         * @param currentSequenceNr the current {@code PoliciesModelFactory.lastSequenceNr()} of the
         * PolicyPersistenceActor.
         * @param currentAccessCounter the current {@code accessCounter} of the PolicyPersistenceActor.
         */
        CheckForActivity(final long currentSequenceNr, final long currentAccessCounter) {
            this.currentSequenceNr = currentSequenceNr;
            this.currentAccessCounter = currentAccessCounter;
        }

        /**
         * Returns the current {@code PoliciesModelFactory.lastSequenceNr()} of the PolicyPersistenceActor.
         *
         * @return the current {@code PoliciesModelFactory.lastSequenceNr()} of the PolicyPersistenceActor.
         */
        long getCurrentSequenceNr() {
            return currentSequenceNr;
        }

        /**
         * Returns the current {@code accessCounter} of the PolicyPersistenceActor.
         *
         * @return the current {@code accessCounter} of the PolicyPersistenceActor.
         */
        long getCurrentAccessCounter() {
            return currentAccessCounter;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final CheckForActivity that = (CheckForActivity) o;
            return Objects.equals(currentSequenceNr, that.currentSequenceNr) &&
                    Objects.equals(currentAccessCounter, that.currentAccessCounter);
        }

        @Override
        public int hashCode() {
            return Objects.hash(currentSequenceNr, currentAccessCounter);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + " [" + "currentSequenceNr=" + currentSequenceNr +
                    ", currentAccessCounter=" + currentAccessCounter + "]";
        }

    }

    /**
     * Message the PolicyPersistenceActor can send to itself to take a Snapshot if the Policy was modified.
     */
    private static final class TakeSnapshotInternal {

        /**
         * The single instance of this message.
         */
        public static final TakeSnapshotInternal INSTANCE = new TakeSnapshotInternal();

        private TakeSnapshotInternal() {}

    }

    /**
     * This strategy handles the {@link RetrievePolicy} command.
     */
    @NotThreadSafe
    private abstract class WithIdReceiveStrategy<T extends Command<T>>
            extends AbstractReceiveStrategy<T> {

        /**
         * Constructs a new {@code WithIdReceiveStrategy} object.
         *
         * @param theMatchingClass the class of the message this strategy reacts to.
         * @param theLogger the logger to use for logging.
         * @throws NullPointerException if {@code theMatchingClass} is {@code null}.
         */
        WithIdReceiveStrategy(final Class<T> theMatchingClass, final DiagnosticLoggingAdapter theLogger) {
            super(theMatchingClass, theLogger);
        }

        @Override
        public FI.TypedPredicate<T> getPredicate() {
            return command -> Objects.equals(policyId, command.getEntityId());
        }

    }

    /**
     * This strategy handles the {@link CreatePolicy} command for a new Policy.
     */
    @NotThreadSafe
    private final class CreatePolicyStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<CreatePolicy, Policy> {

        /**
         * Constructs a new {@code CreatePolicyStrategy} object.
         */
        CreatePolicyStrategy() {
            super(CreatePolicy.class, log);
        }

        @Override
        protected void doApply(final CreatePolicy command) {
            // Policy not yet created - do so ..
            final Policy newPolicy = command.getPolicy();
            final PolicyBuilder newPolicyBuilder = PoliciesModelFactory.newPolicyBuilder(newPolicy);
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            if (!newPolicy.getLifecycle().isPresent()) {
                newPolicyBuilder.setLifecycle(PolicyLifecycle.ACTIVE);
            }

            final Policy newPolicyWithLifecycle = newPolicyBuilder.build();
            final PoliciesValidator validator = PoliciesValidator.newInstance(newPolicyWithLifecycle);

            if (validator.isValid()) {
                final PolicyCreated policyCreated =
                        PolicyCreated.of(newPolicyWithLifecycle, getNextRevision(), getEventTimestamp(), dittoHeaders);

                processEvent(policyCreated, event -> {
                    final CreatePolicyResponse response =
                            CreatePolicyResponse.of(policyId, PolicyPersistenceActor.this.policy, dittoHeaders);
                    sendSuccessResponse(command, response);
                    log.debug("Created new Policy with ID <{}>.", policyId);
                    becomePolicyCreatedHandler();
                });
            } else {
                policyInvalid(validator.getReason().orElse(null), dittoHeaders);
            }
        }

        @Override
        public FI.UnitApply<CreatePolicy> getUnhandledFunction() {
            return command -> {
                final String msgTemplate = "This Policy Actor did not handle the requested Policy with ID <{0}>!";
                throw new IllegalArgumentException(MessageFormat.format(msgTemplate, command.getEntityId()));
            };
        }

        @Override
        protected Optional<Policy> determineETagEntity(final CreatePolicy command) {
            return Optional.ofNullable(policy);
        }
    }

    /**
     * This strategy handles the {@link CreatePolicy} command for an already existing Policy.
     */
    @NotThreadSafe
    private final class PolicyConflictStrategy extends AbstractReceiveStrategy<CreatePolicy> {

        /**
         * Constructs a new {@code PolicyConflictStrategy} object.
         */
        public PolicyConflictStrategy() {
            super(CreatePolicy.class, log);
        }

        @Override
        public FI.TypedPredicate<CreatePolicy> getPredicate() {
            return command -> Objects.equals(policyId, command.getEntityId());
        }

        @Override
        protected void doApply(final CreatePolicy command) {
            notifySender(PolicyConflictException.newBuilder(command.getEntityId())
                    .dittoHeaders(command.getDittoHeaders())
                    .build());
        }

        @Override
        public FI.UnitApply<CreatePolicy> getUnhandledFunction() {
            return command -> {
                final String msgTemplate = "This Policy Actor did not handle the requested Policy with ID <{0}>!";
                throw new IllegalArgumentException(MessageFormat.format(msgTemplate, command.getEntityId()));
            };
        }

    }

    /**
     * This strategy handles the {@link ModifyPolicy} command for an already existing Policy.
     */
    @NotThreadSafe
    private final class ModifyPolicyStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<ModifyPolicy, Policy> {

        /**
         * Constructs a new {@code ModifyPolicyStrategy} object.
         */
        ModifyPolicyStrategy() {
            super(ModifyPolicy.class, log);
        }

        @Override
        protected void doApply(final ModifyPolicy command) {
            final Policy modifiedPolicy = command.getPolicy();
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            try {
                PolicyCommandSizeValidator.getInstance().ensureValidSize(() -> modifiedPolicy.toJsonString().length(),
                        command::getDittoHeaders);
            } catch (final PolicyTooLargeException e) {
                notifySender(e);
            }

            final PoliciesValidator validator = PoliciesValidator.newInstance(modifiedPolicy);

            if (validator.isValid()) {
                final PolicyModified policyModified =
                        PolicyModified.of(modifiedPolicy, getNextRevision(), getEventTimestamp(), dittoHeaders);
                processEvent(policyModified,
                        event -> sendSuccessResponse(command, ModifyPolicyResponse.modified(policyId, dittoHeaders)));
            } else {
                policyInvalid(validator.getReason().orElse(null), dittoHeaders);
            }
        }

        @Override
        public FI.UnitApply<ModifyPolicy> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Policy> determineETagEntity(final ModifyPolicy command) {
            return Optional.ofNullable(policy);
        }
    }

    /**
     * This strategy handles the {@link RetrievePolicy} command.
     */
    @NotThreadSafe
    private final class RetrievePolicyStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<RetrievePolicy, Policy> {

        /**
         * Constructs a new {@code RetrievePolicyStrategy} object.
         */
        RetrievePolicyStrategy() {
            super(RetrievePolicy.class, log);
        }

        @Override
        protected void doApply(final RetrievePolicy command) {
            sendSuccessResponse(command, RetrievePolicyResponse.of(policyId, policy, command.getDittoHeaders()));
        }

        @Override
        public FI.UnitApply<RetrievePolicy> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Policy> determineETagEntity(final RetrievePolicy command) {
            return Optional.ofNullable(policy);
        }
    }

    /**
     * This strategy handles the {@link DeletePolicy} command.
     */
    @NotThreadSafe
    private final class DeletePolicyStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<DeletePolicy, Policy> {

        /**
         * Constructs a new {@code DeletePolicyStrategy} object.
         */
        DeletePolicyStrategy() {
            super(DeletePolicy.class, log);
        }

        @Override
        protected void doApply(final DeletePolicy command) {
            final DittoHeaders dittoHeaders = command.getDittoHeaders();
            final PolicyDeleted policyDeleted = PolicyDeleted.of(policyId, getNextRevision(), getEventTimestamp(),
                    dittoHeaders);

            processEvent(policyDeleted, event -> {
                sendSuccessResponse(command, DeletePolicyResponse.of(policyId, dittoHeaders));
                log.info("Deleted Policy with ID <{}>.", policyId);
                becomePolicyDeletedHandler();
            });
        }

        @Override
        public FI.UnitApply<DeletePolicy> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Policy> determineETagEntity(final DeletePolicy command) {
            return Optional.ofNullable(policy);
        }
    }

    /**
     * This strategy handles the {@link ModifyPolicyEntries} command.
     */
    @NotThreadSafe
    private final class ModifyPolicyEntriesStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<ModifyPolicyEntries, Set<PolicyEntry>> {

        /**
         * Constructs a new {@code ModifyPolicyEntriesStrategy} object.
         */
        ModifyPolicyEntriesStrategy() {
            super(ModifyPolicyEntries.class, log);
        }

        @Override
        protected void doApply(final ModifyPolicyEntries command) {
            final Iterable<PolicyEntry> policyEntries = command.getPolicyEntries();
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            try {
                PolicyCommandSizeValidator.getInstance().ensureValidSize(
                        () -> StreamSupport.stream(policyEntries.spliterator(), false)
                                .map(PolicyEntry::toJson)
                                .collect(JsonCollectors.valuesToArray())
                                .toString()
                                .length(),
                        command::getDittoHeaders);
            } catch (final PolicyTooLargeException e) {
                notifySender(e);
            }

            final ModifyPolicyEntriesResponse response = ModifyPolicyEntriesResponse.of(policyId, dittoHeaders);
            final PolicyEntriesModified policyEntriesModified = PolicyEntriesModified.of(policyId, policyEntries,
                    getNextRevision(), getEventTimestamp(), dittoHeaders);

            processEvent(policyEntriesModified, event -> sendSuccessResponse(command, response));
        }

        @Override
        public FI.UnitApply<ModifyPolicyEntries> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }


        @Override
        protected Optional<Set<PolicyEntry>> determineETagEntity(final ModifyPolicyEntries command) {
            return Optional.ofNullable(policy).map(Policy::getEntriesSet);
        }
    }

    /**
     * This strategy handles the {@link ModifyPolicyEntry} command.
     */
    @NotThreadSafe
    private final class ModifyPolicyEntryStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<ModifyPolicyEntry, PolicyEntry> {

        /**
         * Constructs a new {@code ModifyPolicyEntryStrategy} object.
         */
        ModifyPolicyEntryStrategy() {
            super(ModifyPolicyEntry.class, log);
        }

        @Override
        protected void doApply(final ModifyPolicyEntry command) {
            final PolicyEntry policyEntry = command.getPolicyEntry();
            final Label label = policyEntry.getLabel();
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            try {
                PolicyCommandSizeValidator.getInstance().ensureValidSize(() -> {
                    final long policyLength = policy.removeEntry(label).toJsonString().length();
                    final long entryLength =
                            policyEntry.toJsonString().length() + label.toString().length() + 5L;
                    return policyLength + entryLength;
                }, command::getDittoHeaders);
            } catch (final PolicyTooLargeException e) {
                notifySender(e);
            }

            final PoliciesValidator validator = PoliciesValidator.newInstance(policy.setEntry(policyEntry));

            if (validator.isValid()) {
                final PolicyEvent eventToPersist;
                final ModifyPolicyEntryResponse response;
                if (policy.contains(label)) {
                    eventToPersist =
                            PolicyEntryModified.of(policyId, policyEntry, getNextRevision(), getEventTimestamp(),
                                    dittoHeaders);
                    response = ModifyPolicyEntryResponse.modified(policyId, dittoHeaders);
                } else {
                    eventToPersist =
                            PolicyEntryCreated.of(policyId, policyEntry, getNextRevision(), getEventTimestamp(),
                                    dittoHeaders);
                    response = ModifyPolicyEntryResponse.created(policyId, policyEntry, dittoHeaders);
                }

                processEvent(eventToPersist, event -> sendSuccessResponse(command, response));
            } else {
                policyEntryInvalid(label, validator.getReason().orElse(null), dittoHeaders);
            }
        }

        @Override
        public FI.UnitApply<ModifyPolicyEntry> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<PolicyEntry> determineETagEntity(final ModifyPolicyEntry command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getPolicyEntry().getLabel()));
        }
    }

    /**
     * This strategy handles the {@link DeletePolicyEntry} command.
     */
    @NotThreadSafe
    private final class DeletePolicyEntryStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<DeletePolicyEntry, PolicyEntry> {

        /**
         * Constructs a new {@code DeleteAclEntryStrategy} object.
         */
        DeletePolicyEntryStrategy() {
            super(DeletePolicyEntry.class, log);
        }

        @Override
        protected void doApply(final DeletePolicyEntry command) {
            final DittoHeaders dittoHeaders = command.getDittoHeaders();
            final Label label = command.getLabel();

            if (policy.contains(label)) {
                final PoliciesValidator validator = PoliciesValidator.newInstance(policy.removeEntry(label));

                if (validator.isValid()) {
                    deletePolicyEntry(label, dittoHeaders);
                } else {
                    policyEntryInvalid(label, validator.getReason().orElse(null), dittoHeaders);
                }
            } else {
                policyEntryNotFound(label, dittoHeaders);
            }
        }

        private void deletePolicyEntry(final Label label, final DittoHeaders dittoHeaders) {
            final PolicyEntryDeleted policyEntryDeleted =
                    PolicyEntryDeleted.of(policyId, label, getNextRevision(), getEventTimestamp(), dittoHeaders);

            processEvent(policyEntryDeleted,
                    event -> notifySender(DeletePolicyEntryResponse.of(policyId, label, dittoHeaders)));
        }

        @Override
        public FI.UnitApply<DeletePolicyEntry> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<PolicyEntry> determineETagEntity(final DeletePolicyEntry command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()));
        }
    }

    /**
     * This strategy handles the {@link RetrievePolicyEntries} command.
     */
    @NotThreadSafe
    private final class RetrievePolicyEntriesStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<RetrievePolicyEntries, Set<PolicyEntry>> {

        /**
         * Constructs a new {@code RetrievePolicyEntryStrategy} object.
         */
        RetrievePolicyEntriesStrategy() {
            super(RetrievePolicyEntries.class, log);
        }

        @Override
        protected void doApply(final RetrievePolicyEntries command) {
            final RetrievePolicyEntriesResponse response =
                    RetrievePolicyEntriesResponse.of(policyId, policy.getEntriesSet(), command.getDittoHeaders());
            sendSuccessResponse(command, response);
        }

        @Override
        public FI.UnitApply<RetrievePolicyEntries> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Set<PolicyEntry>> determineETagEntity(final RetrievePolicyEntries command) {
            return Optional.ofNullable(policy).map(Policy::getEntriesSet);
        }
    }

    /**
     * This strategy handles the {@link RetrievePolicyEntry} command.
     */
    @NotThreadSafe
    private final class RetrievePolicyEntryStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<RetrievePolicyEntry, PolicyEntry> {

        /**
         * Constructs a new {@code RetrievePolicyEntryStrategy} object.
         */
        RetrievePolicyEntryStrategy() {
            super(RetrievePolicyEntry.class, log);
        }

        @Override
        protected void doApply(final RetrievePolicyEntry command) {
            final Optional<PolicyEntry> optionalEntry = policy.getEntryFor(command.getLabel());
            if (optionalEntry.isPresent()) {
                final RetrievePolicyEntryResponse response =
                        RetrievePolicyEntryResponse.of(policyId, optionalEntry.get(), command.getDittoHeaders());
                sendSuccessResponse(command, response);
            } else {
                policyEntryNotFound(command.getLabel(), command.getDittoHeaders());
            }
        }

        @Override
        public FI.UnitApply<RetrievePolicyEntry> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<PolicyEntry> determineETagEntity(final RetrievePolicyEntry command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()));
        }
    }

    /**
     * This strategy handles the {@link ModifySubjects} command.
     */
    @NotThreadSafe
    private final class ModifySubjectsStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<ModifySubjects, Subjects> {

        /**
         * Constructs a new {@code ModifySubjectsStrategy} object.
         */
        ModifySubjectsStrategy() {
            super(ModifySubjects.class, log);
        }

        @Override
        protected void doApply(final ModifySubjects command) {
            final Label label = command.getLabel();
            final Subjects subjects = command.getSubjects();
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            if (policy.getEntryFor(label).isPresent()) {
                final PoliciesValidator validator =
                        PoliciesValidator.newInstance(policy.setSubjectsFor(label, subjects));

                if (validator.isValid()) {
                    final SubjectsModified subjectsModified =
                            SubjectsModified.of(policyId, label, subjects, getNextRevision(), getEventTimestamp(),
                                    command.getDittoHeaders());
                    processEvent(subjectsModified,
                            event -> {
                                final ModifySubjectsResponse response =
                                        ModifySubjectsResponse.of(policyId, label, dittoHeaders);
                                sendSuccessResponse(command, response);
                            });
                } else {
                    policyEntryInvalid(label, validator.getReason().orElse(null), dittoHeaders);
                }
            } else {
                policyEntryNotFound(label, dittoHeaders);
            }
        }

        @Override
        public FI.UnitApply<ModifySubjects> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Subjects> determineETagEntity(final ModifySubjects command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getSubjects);
        }
    }

    /**
     * This strategy handles the {@link RetrieveSubjects} command.
     */
    @NotThreadSafe
    private final class RetrieveSubjectsStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<RetrieveSubjects, Subjects> {

        /**
         * Constructs a new {@code RetrieveSubjectsStrategy} object.
         */
        RetrieveSubjectsStrategy() {
            super(RetrieveSubjects.class, log);
        }

        @Override
        protected void doApply(final RetrieveSubjects command) {
            final Optional<PolicyEntry> optionalEntry = policy.getEntryFor(command.getLabel());
            if (optionalEntry.isPresent()) {
                final RetrieveSubjectsResponse response =
                        RetrieveSubjectsResponse.of(policyId, command.getLabel(), optionalEntry.get().getSubjects(),
                                command.getDittoHeaders());
                sendSuccessResponse(command, response);
            } else {
                policyEntryNotFound(command.getLabel(), command.getDittoHeaders());
            }
        }

        @Override
        public FI.UnitApply<RetrieveSubjects> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Subjects> determineETagEntity(final RetrieveSubjects command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getSubjects);
        }
    }

    /**
     * This strategy handles the {@link ModifySubject} command.
     */
    @NotThreadSafe
    private final class ModifySubjectStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<ModifySubject, Subject> {

        /**
         * Constructs a new {@code ModifySubjectStrategy} object.
         */
        ModifySubjectStrategy() {
            super(ModifySubject.class, log);
        }

        @Override
        protected void doApply(final ModifySubject command) {
            final Label label = command.getLabel();
            final Subject subject = command.getSubject();
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            final Optional<PolicyEntry> optionalEntry = policy.getEntryFor(label);
            if (optionalEntry.isPresent()) {
                final PolicyEntry policyEntry = optionalEntry.get();
                final PoliciesValidator validator = PoliciesValidator.newInstance(policy.setSubjectFor(label, subject));

                if (validator.isValid()) {
                    final PolicyEvent eventToPersist;
                    final ModifySubjectResponse response;

                    if (policyEntry.getSubjects().getSubject(subject.getId()).isPresent()) {
                        response = ModifySubjectResponse.modified(policyId, label, dittoHeaders);
                        eventToPersist =
                                SubjectModified.of(policyId, label, subject, getNextRevision(), getEventTimestamp(),
                                        command.getDittoHeaders());
                    } else {
                        response = ModifySubjectResponse.created(policyId, label, subject, dittoHeaders);
                        eventToPersist =
                                SubjectCreated.of(policyId, label, subject, getNextRevision(), getEventTimestamp(),
                                        command.getDittoHeaders());
                    }

                    processEvent(eventToPersist, event -> sendSuccessResponse(command, response));
                } else {
                    policyEntryInvalid(label, validator.getReason().orElse(null), dittoHeaders);
                }
            } else {
                policyEntryNotFound(label, dittoHeaders);
            }
        }

        @Override
        public FI.UnitApply<ModifySubject> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Subject> determineETagEntity(final ModifySubject command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getSubjects)
                    .flatMap(s -> s.getSubject(command.getSubject().getId()));
        }
    }

    /**
     * This strategy handles the {@link DeleteSubject} command.
     */
    @NotThreadSafe
    private final class DeleteSubjectStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<DeleteSubject, Subject> {

        /**
         * Constructs a new {@code DeleteSubjectStrategy} object.
         */
        DeleteSubjectStrategy() {
            super(DeleteSubject.class, log);
        }

        @Override
        protected void doApply(final DeleteSubject command) {
            final Label label = command.getLabel();
            final SubjectId subjectId = command.getSubjectId();
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            final Optional<PolicyEntry> optionalEntry = policy.getEntryFor(label);
            if (optionalEntry.isPresent()) {
                final PolicyEntry policyEntry = optionalEntry.get();
                if (policyEntry.getSubjects().getSubject(subjectId).isPresent()) {
                    final PoliciesValidator validator =
                            PoliciesValidator.newInstance(policy.removeSubjectFor(label, subjectId));

                    if (validator.isValid()) {
                        final SubjectDeleted subjectDeleted =
                                SubjectDeleted.of(policyId, label, subjectId, getNextRevision(), getEventTimestamp(),
                                        dittoHeaders);

                        processEvent(subjectDeleted,
                                event -> {
                                    final DeleteSubjectResponse response =
                                            DeleteSubjectResponse.of(policyId, label, subjectId,
                                                    dittoHeaders);
                                    sendSuccessResponse(command, response);
                                });
                    } else {
                        policyEntryInvalid(label, validator.getReason().orElse(null), dittoHeaders);
                    }
                } else {
                    subjectNotFound(label, subjectId, dittoHeaders);
                }
            } else {
                policyEntryNotFound(label, dittoHeaders);
            }
        }

        @Override
        public FI.UnitApply<DeleteSubject> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Subject> determineETagEntity(final DeleteSubject command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getSubjects)
                    .flatMap(s -> s.getSubject(command.getSubjectId()));
        }
    }

    /**
     * This strategy handles the {@link RetrieveSubject} command.
     */
    @NotThreadSafe
    private final class RetrieveSubjectStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<RetrieveSubject, Subject> {

        /**
         * Constructs a new {@code RetrieveSubjectStrategy} object.
         */
        RetrieveSubjectStrategy() {
            super(RetrieveSubject.class, log);
        }

        @Override
        protected void doApply(final RetrieveSubject command) {
            final Optional<PolicyEntry> optionalEntry = policy.getEntryFor(command.getLabel());
            if (optionalEntry.isPresent()) {
                final PolicyEntry policyEntry = optionalEntry.get();
                final Optional<Subject> optionalSubject = policyEntry.getSubjects().getSubject(command.getSubjectId());
                if (optionalSubject.isPresent()) {
                    final RetrieveSubjectResponse response = RetrieveSubjectResponse.of(policyId, command.getLabel(),
                            optionalSubject.get(), command.getDittoHeaders());
                    sendSuccessResponse(command, response);
                } else {
                    subjectNotFound(command.getLabel(), command.getSubjectId(), command.getDittoHeaders());
                }
            } else {
                policyEntryNotFound(command.getLabel(), command.getDittoHeaders());
            }
        }

        @Override
        public FI.UnitApply<RetrieveSubject> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Subject> determineETagEntity(final RetrieveSubject command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getSubjects)
                    .flatMap(s -> s.getSubject(command.getSubjectId()));
        }
    }

    /**
     * This strategy handles the {@link ModifyResources} command.
     */
    @NotThreadSafe
    private final class ModifyResourcesStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<ModifyResources, Resources> {

        /**
         * Constructs a new {@code ModifyResourcesStrategy} object.
         */
        ModifyResourcesStrategy() {
            super(ModifyResources.class, log);
        }

        @Override
        protected void doApply(final ModifyResources command) {
            final Label label = command.getLabel();
            final Resources resources = command.getResources();
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            try {
                PolicyCommandSizeValidator.getInstance().ensureValidSize(() -> {
                    final List<ResourceKey> rks = resources.stream()
                            .map(Resource::getResourceKey)
                            .collect(Collectors.toList());
                    Policy tmpPolicy = policy;
                    for (final ResourceKey rk : rks) {
                        tmpPolicy = tmpPolicy.removeResourceFor(label, rk);
                    }
                    final long policyLength = tmpPolicy.toJsonString().length();
                    final long resourcesLength = resources.toJsonString()
                            .length() + 5L;
                    return policyLength + resourcesLength;
                }, command::getDittoHeaders);
            } catch (final PolicyTooLargeException e) {
                notifySender(e);
            }

            if (policy.getEntryFor(label).isPresent()) {
                final PoliciesValidator validator =
                        PoliciesValidator.newInstance(policy.setResourcesFor(label, resources));

                if (validator.isValid()) {
                    final ResourcesModified resourcesModified =
                            ResourcesModified.of(policyId, label, resources, getNextRevision(), getEventTimestamp(),
                                    dittoHeaders);

                    processEvent(resourcesModified,
                            event -> {
                                final ModifyResourcesResponse response =
                                        ModifyResourcesResponse.of(policyId, label, dittoHeaders);
                                sendSuccessResponse(command, response);
                            });
                } else {
                    policyEntryInvalid(label, validator.getReason().orElse(null), dittoHeaders);
                }
            } else {
                policyEntryNotFound(label, dittoHeaders);
            }
        }

        @Override
        public FI.UnitApply<ModifyResources> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Resources> determineETagEntity(final ModifyResources command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getResources);
        }
    }

    /**
     * This strategy handles the {@link RetrieveResources} command.
     */
    @NotThreadSafe
    private final class RetrieveResourcesStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<RetrieveResources, Resources> {

        /**
         * Constructs a new {@code RetrieveResourcesStrategy} object.
         */
        RetrieveResourcesStrategy() {
            super(RetrieveResources.class, log);
        }

        @Override
        protected void doApply(final RetrieveResources command) {
            final Optional<PolicyEntry> optionalEntry = policy.getEntryFor(command.getLabel());
            if (optionalEntry.isPresent()) {
                final RetrieveResourcesResponse response = RetrieveResourcesResponse.of(policyId, command.getLabel(),
                        optionalEntry.get().getResources(), command.getDittoHeaders());
                sendSuccessResponse(command, response);
            } else {
                policyEntryNotFound(command.getLabel(), command.getDittoHeaders());
            }
        }

        @Override
        public FI.UnitApply<RetrieveResources> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Resources> determineETagEntity(final RetrieveResources command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getResources);
        }
    }

    /**
     * This strategy handles the {@link ModifyResource} command.
     */
    @NotThreadSafe
    private final class ModifyResourceStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<ModifyResource, Resource> {

        /**
         * Constructs a new {@code ModifyResourceStrategy} object.
         */
        ModifyResourceStrategy() {
            super(ModifyResource.class, log);
        }

        @Override
        protected void doApply(final ModifyResource command) {
            final Label label = command.getLabel();
            final Resource resource = command.getResource();
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            final Optional<PolicyEntry> optionalEntry = policy.getEntryFor(label);
            if (optionalEntry.isPresent()) {
                final PoliciesValidator validator =
                        PoliciesValidator.newInstance(policy.setResourceFor(label, resource));

                if (validator.isValid()) {
                    final PolicyEntry policyEntry = optionalEntry.get();
                    final PolicyEvent eventToPersist;
                    final ModifyResourceResponse response;

                    if (policyEntry.getResources().getResource(resource.getResourceKey()).isPresent()) {
                        response = ModifyResourceResponse.modified(policyId, label, dittoHeaders);
                        eventToPersist =
                                ResourceModified.of(policyId, label, resource, getNextRevision(), getEventTimestamp(),
                                        dittoHeaders);
                    } else {
                        response = ModifyResourceResponse.created(policyId, label, resource, dittoHeaders);
                        eventToPersist =
                                ResourceCreated.of(policyId, label, resource, getNextRevision(), getEventTimestamp(),
                                        dittoHeaders);
                    }

                    processEvent(eventToPersist, event -> sendSuccessResponse(command, response));
                } else {
                    policyEntryInvalid(label, validator.getReason().orElse(null), dittoHeaders);
                }
            } else {
                policyEntryNotFound(label, dittoHeaders);
            }
        }

        @Override
        public FI.UnitApply<ModifyResource> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Resource> determineETagEntity(final ModifyResource command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getResources)
                    .flatMap(r -> r.getResource(command.getResource().getResourceKey()));
        }
    }

    /**
     * This strategy handles the {@link DeleteResource} command.
     */
    @NotThreadSafe
    private final class DeleteResourceStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<DeleteResource, Resource> {

        /**
         * Constructs a new {@code DeleteResourceStrategy} object.
         */
        DeleteResourceStrategy() {
            super(DeleteResource.class, log);
        }

        @Override
        protected void doApply(final DeleteResource command) {
            final Label label = command.getLabel();
            final ResourceKey resourceKey = command.getResourceKey();
            final DittoHeaders dittoHeaders = command.getDittoHeaders();

            final Optional<PolicyEntry> optionalEntry = policy.getEntryFor(label);
            if (optionalEntry.isPresent()) {
                final PolicyEntry policyEntry = optionalEntry.get();

                if (policyEntry.getResources().getResource(resourceKey).isPresent()) {
                    final PoliciesValidator validator =
                            PoliciesValidator.newInstance(policy.removeResourceFor(label, resourceKey));

                    if (validator.isValid()) {
                        final ResourceDeleted resourceDeleted =
                                ResourceDeleted.of(policyId, label, resourceKey, getNextRevision(), getEventTimestamp(),
                                        dittoHeaders);

                        processEvent(resourceDeleted,
                                event -> {
                                    final DeleteResourceResponse response =
                                            DeleteResourceResponse.of(policyId, label, resourceKey,
                                                    dittoHeaders);
                                    sendSuccessResponse(command, response);
                                });
                    } else {
                        policyEntryInvalid(label, validator.getReason().orElse(null), dittoHeaders);
                    }
                } else {
                    resourceNotFound(label, resourceKey, dittoHeaders);
                }
            } else {
                policyEntryNotFound(label, dittoHeaders);
            }
        }

        @Override
        public FI.UnitApply<DeleteResource> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Resource> determineETagEntity(final DeleteResource command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getResources)
                    .flatMap(r -> r.getResource(command.getResourceKey()));
        }
    }

    /**
     * This strategy handles the {@link RetrieveResource} command.
     */
    @NotThreadSafe
    private final class RetrieveResourceStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<RetrieveResource, Resource> {

        /**
         * Constructs a new {@code RetrieveResourceStrategy} object.
         */
        RetrieveResourceStrategy() {
            super(RetrieveResource.class, log);
        }

        @Override
        protected void doApply(final RetrieveResource command) {
            final Optional<PolicyEntry> optionalEntry = policy.getEntryFor(command.getLabel());
            if (optionalEntry.isPresent()) {
                final PolicyEntry policyEntry = optionalEntry.get();

                final Optional<Resource> optionalResource =
                        policyEntry.getResources().getResource(command.getResourceKey());
                if (optionalResource.isPresent()) {
                    final RetrieveResourceResponse response =
                            RetrieveResourceResponse.of(policyId, command.getLabel(), optionalResource.get(),
                                    command.getDittoHeaders());
                    sendSuccessResponse(command, response);
                } else {
                    resourceNotFound(command.getLabel(), command.getResourceKey(), command.getDittoHeaders());
                }
            } else {
                policyEntryNotFound(command.getLabel(), command.getDittoHeaders());
            }
        }

        @Override
        public FI.UnitApply<RetrieveResource> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Resource> determineETagEntity(final RetrieveResource command) {
            return Optional.ofNullable(policy)
                    .flatMap(p -> p.getEntryFor(command.getLabel()))
                    .map(PolicyEntry::getResources)
                    .flatMap(r -> r.getResource(command.getResourceKey()));
        }
    }

    /**
     * This strategy handles the {@link SudoRetrievePolicy} command w/o valid authorization context.
     */
    @NotThreadSafe
    private final class SudoRetrievePolicyStrategy
            extends AbstractConditionalHeadersCheckingReceiveStrategy<SudoRetrievePolicy, Policy> {

        /**
         * Constructs a new {@code SudoRetrievePolicyStrategy} object.
         */
        SudoRetrievePolicyStrategy() {
            super(SudoRetrievePolicy.class, log);
        }

        @Override
        protected void doApply(final SudoRetrievePolicy command) {
            final SudoRetrievePolicyResponse response =
                    SudoRetrievePolicyResponse.of(policyId, policy, command.getDittoHeaders());
            sendSuccessResponse(command, response);
        }

        @Override
        public FI.UnitApply<SudoRetrievePolicy> getUnhandledFunction() {
            return command -> notifySender(policyNotFound(command.getDittoHeaders()));
        }

        @Override
        protected Optional<Policy> determineETagEntity(final SudoRetrievePolicy command) {
            return Optional.ofNullable(policy);
        }
    }

    /**
     * Responsible to check conditional (http) headers based on the policy's current eTag value.
     *
     * @param <C> The type of the handled command.
     * @param <E> The type of the addressed entity.
     */
    private abstract class AbstractConditionalHeadersCheckingReceiveStrategy<C extends Command<C>, E>
            extends WithIdReceiveStrategy<C> {

        private final ConditionalHeadersValidator validator = PoliciesConditionalHeadersValidatorProvider.getInstance();

        /**
         * Constructs a new {@code AbstractReceiveStrategy} object.
         *
         * @param theMatchingClass the class of the message this strategy reacts to.
         * @param theLogger the logger to use for logging.
         * @throws NullPointerException if {@code theMatchingClass} is {@code null}.
         */
        protected AbstractConditionalHeadersCheckingReceiveStrategy(final Class<C> theMatchingClass,
                final DiagnosticLoggingAdapter theLogger) {
            super(theMatchingClass, theLogger);
        }

        @Override
        protected void apply(final C command) {
            final EntityTag currentETagValue = determineETagEntity(command)
                    .flatMap(EntityTag::fromEntity)
                    .orElse(null);

            log.debug("Validating conditional headers with currentETagValue <{}> on command <{}>.", currentETagValue,
                    command);
            try {
                validator.checkConditionalHeaders(command, currentETagValue);
                log.debug("Validating conditional headers succeeded.");
            } catch (final DittoRuntimeException dre) {
                log.debug("Validating conditional headers failed with exception <{}>.", dre.getMessage());
                notifySender(dre);
                return;
            }

            super.apply(command);
        }

        /**
         * Sends a success-Response, which may be extended with an ETag header.
         *
         * @param command the command which caused the response
         * @param response the response, which may be extended
         */
        protected void sendSuccessResponse(final C command, final CommandResponse response) {
            final WithDittoHeaders responseWithOptionalETagHeader = appendETagHeader(command, response);

            notifySender(responseWithOptionalETagHeader);
        }

        private WithDittoHeaders appendETagHeader(final C command, final WithDittoHeaders response) {
            final DittoHeaders dittoHeaders = response.getDittoHeaders();

            final Optional<EntityTag> entityTagOpt = determineETagEntity(command)
                    .flatMap(EntityTag::fromEntity);
            if (entityTagOpt.isPresent()) {
                final DittoHeaders newDittoHeaders = dittoHeaders.toBuilder().eTag(entityTagOpt.get()).build();
                return response.setDittoHeaders(newDittoHeaders);
            }

            return response;
        }

        /**
         * Determines the value based on which an eTag will be generated.
         *
         * @param command the policy command.
         * @return An optional of the eTag header value. Optional can be empty if no eTag header should be added.
         */
        protected abstract Optional<E> determineETagEntity(final C command);
    }

    /**
     * This strategy handles all commands which were not explicitly handled beforehand. Those commands are logged as
     * unknown messages and are marked as unhandled.
     */
    @NotThreadSafe
    private final class MatchAnyAfterInitializeStrategy extends AbstractReceiveStrategy<Object> {

        /**
         * Constructs a new {@code MatchAnyAfterInitializeStrategy} object.
         */
        MatchAnyAfterInitializeStrategy() {
            super(Object.class, log);
        }

        @Override
        protected void doApply(final Object message) {
            log.warning("Unknown message: {}", message);
            unhandled(message);
        }

    }

    /**
     * This strategy handles all messages which were received before the Policy was initialized. Those messages are
     * logged
     * as unexpected messages and cause the actor to be stopped.
     */
    @NotThreadSafe
    private final class MatchAnyDuringInitializeStrategy extends AbstractReceiveStrategy<Object> {

        /**
         * Constructs a new {@code MatchAnyDuringInitializeStrategy} object.
         */
        MatchAnyDuringInitializeStrategy() {
            super(Object.class, log);
        }

        @Override
        protected void doApply(final Object message) {
            log.debug("Unexpected message after initialization of actor received: <{}> - " +
                            "Terminating this actor and sending <{}> to requester..", message,
                    PolicyNotAccessibleException.class.getName());
            final PolicyNotAccessibleException.Builder builder = PolicyNotAccessibleException.newBuilder(policyId);
            if (message instanceof WithDittoHeaders) {
                builder.dittoHeaders(((WithDittoHeaders) message).getDittoHeaders());
            }
            notifySender(builder.build());
        }

    }

    /**
     * This strategy handles any messages for a previous deleted Policy.
     */
    @NotThreadSafe
    private final class PolicyNotFoundStrategy extends AbstractReceiveStrategy<Object> {

        /**
         * Constructs a new {@code PolicyNotFoundStrategy} object.
         */
        PolicyNotFoundStrategy() {
            super(Object.class, log);
        }

        @Override
        protected void doApply(final Object message) {
            final PolicyNotAccessibleException.Builder builder = PolicyNotAccessibleException.newBuilder(policyId);
            if (message instanceof WithDittoHeaders) {
                builder.dittoHeaders(((WithDittoHeaders) message).getDittoHeaders());
            }
            notifySender(builder.build());
        }

    }

    /**
     * This strategy handles the {@link CheckForActivity} message which checks for activity of the Actor and
     * terminates
     * itself if there was no activity since the last check.
     */
    @NotThreadSafe
    private final class CheckForActivityStrategy extends AbstractReceiveStrategy<CheckForActivity> {

        /**
         * Constructs a new {@code CheckForActivityStrategy} object.
         */
        CheckForActivityStrategy() {
            super(CheckForActivity.class, log);
        }

        @Override
        protected void doApply(final CheckForActivity message) {
            if (policyExistsAsDeleted() && lastSnapshotSequenceNr < lastSequenceNr()) {
                // take a snapshot after a period of inactivity if:
                // - thing is deleted,
                // - the latest snapshot is out of date
                takeSnapshot("the policy is deleted and has no up-to-date snapshot");
                scheduleCheckForPolicyActivity(policyConfig.getActivityCheckConfig().getDeletedInterval());
            } else if (accessCounter > message.getCurrentAccessCounter()) {
                // if the Thing was accessed in any way since the last check
                scheduleCheckForPolicyActivity(policyConfig.getActivityCheckConfig().getInactiveInterval());
            } else {
                // safe to shutdown after a period of inactivity if:
                // - policy is active (and taking regular snapshots of itself), or
                // - policy is deleted and the latest snapshot is up to date
                if (isPolicyActive()) {
                    shutdown("Policy <{}> was not accessed in a while. Shutting Actor down ...", policyId);
                } else {
                    shutdown("Policy <{}> was deleted recently. Shutting Actor down ...", policyId);
                }
            }
        }

        private void shutdown(final String shutdownLogTemplate, final PolicyId policyId) {
            log.debug(shutdownLogTemplate, policyId);
            // stop the supervisor (otherwise it'd restart this actor) which causes this actor to stop, too.
            getContext().getParent().tell(PolicySupervisorActor.Control.PASSIVATE, getSelf());
        }

    }

    private static final class SimpleStrategy<T> implements ReceiveStrategy<T> {

        private final Class<T> clazz;
        private final Consumer<T> consumer;

        private SimpleStrategy(final Class<T> clazz, final Consumer<T> consumer) {
            this.clazz = clazz;
            this.consumer = consumer;
        }

        private static <T> ReceiveStrategy<T> of(final Class<T> clazz, final Consumer<T> consumer) {
            return new SimpleStrategy<>(clazz, consumer);
        }

        @Override
        public Class<T> getMatchingClass() {
            return clazz;
        }

        @Override
        public FI.UnitApply<T> getApplyFunction() {
            return consumer::accept;
        }

        @Override
        public FI.UnitApply<T> getUnhandledFunction() {
            // not used
            return x -> {};
        }
    }

    private static final class TakeSnapshot {}

}
