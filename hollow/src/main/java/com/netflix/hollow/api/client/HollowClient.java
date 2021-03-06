/*
 *
 *  Copyright 2016 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.api.client;

import static com.netflix.hollow.api.client.HollowAPIFactory.DEFAULT_FACTORY;
import static com.netflix.hollow.api.client.HollowClientMemoryConfig.DEFAULT_CONFIG;
import static com.netflix.hollow.api.client.HollowUpdateListener.DEFAULT_LISTENER;

import com.netflix.hollow.api.custom.HollowAPI;

import com.netflix.hollow.core.util.HollowObjectHashCodeFinder;
import com.netflix.hollow.core.util.DefaultHashCodeFinder;
import com.netflix.hollow.api.codegen.HollowAPIClassJavaGenerator;
import com.netflix.hollow.core.read.filter.HollowFilterConfig;
import com.netflix.hollow.core.read.engine.HollowReadStateEngine;

/**
 * A HollowClient is the top-level class used by consumers of HollowData to initialize and keep up-to-date a local in-memory 
 * copy of a hollow dataset.  The interactions between the "blob" transition store and announcement listener are defined by 
 * this class, and the implementations of the data retrieval, announcement mechanism are abstracted in the interfaces which 
 * are injectable to this class.
 *   
 * The following is injectable:     
 * 
 * <dl>
 *      <dt>{@link HollowBlobRetriever}</dt>
 *      <dd>Implementations of this class define how to retrieve blob data for consumption by this HollowClient.</dd>
 *      
 *      <dt>{@link HollowAnnouncementWatcher}</dt>
 *      <dd>Implementations of this class define the announcement mechanism, which is used to track the version of the 
 *          currently announced state.  It's also suggested that implementations will trigger a refresh when the current
 *          data version is updated.</dd>
 *          
 *      <dt>{@link HollowUpdateListener}</dt>
 *      <dd>Implementations of this class will define what to do when various events happen before, during, and after updating
 *          local in-memory copies of hollow data sets.</dd>
 *          
 *      <dt>{@link HollowAPIFactory}</dt>
 *      <dd>Defines how to create a {@link HollowAPI} for the dataset, useful when wrapping a dataset with an api which has 
 *          been generated (via the {@link HollowAPIClassJavaGenerator})</dd>
 *          
 *      <dt>{@link HollowObjectHashCodeFinder}</dt>
 *      <dd>Defines the record hashing behavior for elements in set and map records</dd>
 *      
 *      <dt>{@link HollowClientMemoryConfig}</dt>
 *      <dd>Defines various aspects of data access guarantees and update behavior which impact the heap footprint/GC behavior
 *          of hollow.</dd>
 *      
 * </dl>
 * 
 * Only an implementation of the HollowBlobRetriever is required to be injected, the other components may use default
 * implementations. 
 * 
 * @author dkoszewnik
 *
 */
public class HollowClient {

    protected final HollowAnnouncementWatcher announcementWatcher;
    protected final HollowClientUpdater updater;

    public HollowClient(HollowBlobRetriever blobRetriever) {
        this(blobRetriever, new HollowAnnouncementWatcher.DefaultWatcher(), DEFAULT_LISTENER, DEFAULT_FACTORY, new DefaultHashCodeFinder(), DEFAULT_CONFIG);
    }

    public HollowClient(HollowBlobRetriever blobRetriever,
            HollowAnnouncementWatcher announcementWatcher,
            HollowUpdateListener updateListener,
            HollowAPIFactory apiFactory,
            HollowClientMemoryConfig memoryConfig) {
        
        this(blobRetriever, announcementWatcher, updateListener, apiFactory, new DefaultHashCodeFinder(), memoryConfig);
    }

    
    public HollowClient(HollowBlobRetriever blobRetriever,
                        HollowAnnouncementWatcher announcementWatcher,
                        HollowUpdateListener updateListener,
                        HollowAPIFactory apiFactory,
                        HollowObjectHashCodeFinder hashCodeFinder,
                        HollowClientMemoryConfig memoryConfig) {
        this.updater = new HollowClientUpdater(blobRetriever, updateListener, apiFactory, hashCodeFinder, memoryConfig);
        this.announcementWatcher = announcementWatcher;
        announcementWatcher.setClientToNotify(this);
    }

    /**
     * Triggers a refresh to the latest version specified by the HollowAnnouncementWatcher.
     * If already on the latest version, this operation is a no-op.
     *
     * This is a blocking call.
     */
    public void triggerRefresh() {
        try {
            updater.updateTo(announcementWatcher.getLatestVersion());
        } catch(Throwable th) {
            throw new RuntimeException(th);
        }
    }

    /**
     * Triggers a refresh to the latest version specified by the HollowAnnouncementWatcher.
     * If already on the latest version, this operation is a no-op.
     *
     * This is an asynchronous call.
     */
    public void triggerAsyncRefresh() {
        announcementWatcher.triggerAsyncRefresh();
    }

    /**
     * If the HollowAnnouncementWatcher supports setting an explicit version, this method will update
     * to the specified version.
     *
     * Otherwise, an UnsupportedOperationException will be thrown.
     *
     * This is a blocking call.
     *
     * @param version
     */
    public void triggerRefreshTo(long version) {
        announcementWatcher.setLatestVersion(version);
        triggerRefresh();
    }

    /**
     * Will force a double snapshot refresh on the next update.
     */
    public void forceDoubleSnapshotNextUpdate() {
        updater.forceDoubleSnapshotNextUpdate();
    }

    /**
     * Will apply the filter (i.e. not load the excluded types and fields) on the next snapshot update.
     * Subsequent updates will also ignore the types and fields.
     */
    public void setFilter(HollowFilterConfig filter) {
        updater.setFilter(filter);
    }

    /**
     * Set the maximum number of deltas which will be followed by this client.  If an update
     * is triggered which attempts to traverse more than this number of double snapshots:
     * 
     * <ul>
     *      <li>Will do a double snapshot if enabled, otherwise</li>
     *      <li>will traverse up to the specified number of deltas towards the desired state, then stop</li>
     * </ul>
     */
    public void setMaxDeltas(int maxDeltas) {
        updater.setMaxDeltas(maxDeltas);
    }
    
    /**
     * Clear any failed transitions from the {@link FailedTransitionTracker}, so that they may be reattempted when an update is triggered.
     */
    public void clearFailedTransitions() {
        updater.clearFailedTransitions();
    }

    public StackTraceRecorder getStaleReferenceUsageStackTraceRecorder() {
        return updater.getStaleReferenceUsageStackTraceRecorder();
    }

    /**
     * @return the {@link HollowReadStateEngine} which is holding the underlying hollow dataset.
     */
    public HollowReadStateEngine getStateEngine() {
        return updater.getStateEngine();
    }

    /**
     * @return the api which wraps the underlying dataset.
     */
    public HollowAPI getAPI() {
        return updater.getAPI();
    }

    /**
     * @return the current version of the dataset.  This is the unique identifier of the data's state.
     */
    public long getCurrentVersionId() {
        return updater.getCurrentVersionId();
    }

}
