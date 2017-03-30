/*
 * Copyright 2016 Adobe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.acs.commons.fam;

import aQute.bnd.annotation.ProviderType;
import com.adobe.acs.commons.functions.*;
import com.adobe.acs.commons.workflow.synthetic.SyntheticWorkflowModel;
import com.adobe.acs.commons.workflow.synthetic.SyntheticWorkflowRunner;
import com.adobe.granite.asset.api.Asset;
import com.adobe.granite.asset.api.AssetManager;
import com.adobe.granite.asset.api.Rendition;
import com.day.cq.dam.commons.util.DamUtil;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import javax.jcr.Session;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Various deferred actions to be used with the ActionManager
 */
@Component
@Service(DeferredActions.class)
@ProviderType
public final class DeferredActions {
    public static final String ORIGINAL_RENDITION = "original";

    @Reference
    private SyntheticWorkflowRunner workflowRunner;

    @Reference
    private Replicator replicator;

    //--- Filters (for using withQueryResults)
    /**
     * Returns opposite of its input, e.g. filterMatching(glob).andThen(not)
     */
    public Function<Boolean, Boolean> not = (Boolean t) -> !t;

    /**
     * Returns true of glob matches provided path
     * @param glob Regex expression
     * @return True for matches
     */
    public BiFunction<ResourceResolver, String, Boolean> filterMatching(final String glob) {
        return (ResourceResolver r, String path) -> path.matches(glob);
    }

    /**
     * Returns false if glob matches provided path
     * Useful for things like filterOutSubassets
     * @param glob Regex expression
     * @return False for matches
     */
    public BiFunction<ResourceResolver, String, Boolean> filterNotMatching(final String glob) {
        return filterMatching(glob).andThen(not);
    }

    /**
     * Exclude subassets from processing
     * @return true if node is not a subasset
     */
    public BiFunction<ResourceResolver, String, Boolean> filterOutSubassets() {
        return filterNotMatching(".*?/subassets/.*");
    }

    /**
     * Determine if node is a valid asset, skip any non-assets
     * It's better to filter via query if possible to avoid having to use this
     * @return True if asset
     */
    public BiFunction<ResourceResolver, String, Boolean> filterNonAssets() {
        return (ResourceResolver r, String path) -> {
            nameThread("filterNonAssets-" + path);
            Resource res = r.getResource(path);
            return (DamUtil.resolveToAsset(res) != null);
        };
    }

    /**
     * This filter identifies assets where the original rendition is newer than any of the other renditions.
     * This is an especially useful function for updating assets with missing or outdated thumbnails.
     * @return True if asset has no thumbnails or outdated thumbnails
     */
    public BiFunction<ResourceResolver, String, Boolean> filterAssetsWithOutdatedRenditions() {
        return (ResourceResolver r, String path) -> {
            nameThread("filterAssetsWithOutdatedRenditions-" + path);
            Resource res = r.getResource(path);
            com.day.cq.dam.api.Asset asset = DamUtil.resolveToAsset(res);
            if (asset == null) {
                return false;
            }
            com.day.cq.dam.api.Rendition original = asset.getRendition(ORIGINAL_RENDITION);
            if (original == null) {
                return false;
            }
            long originalTime = original.getResourceMetadata().getCreationTime();
            int counter = 0;
            for (com.day.cq.dam.api.Rendition rendition : asset.getRenditions()) {
                counter++;
                long time = rendition.getResourceMetadata().getCreationTime();
                if (time < originalTime) {
                    return true;
                }
            }
            return counter <= 1;
        };    
    }
    //-- Query Result consumers (for using withQueryResults)
    /**
     * Retry provided action a given number of times before giving up and throwing an error.
     * Before each retry attempt, the resource resolver is reverted so when using
     * this it is a good idea to commit from your action directly.
     * @param retries Number of retries to attempt
     * @param pausePerRetry Milliseconds to wait between attempts
     * @param action Action to attempt
     * @return New retry wrapper around provided action
     */
    public BiConsumer<ResourceResolver, String> retryAll(final int retries, final long pausePerRetry, final BiConsumer<ResourceResolver, String> action) {
        return (ResourceResolver r, String s) -> {
            int remaining = retries;
            while (remaining > 0) {
                try {
                    action.accept(r, s);
                    return;
                } catch (Exception e) {
                    if (remaining-- <= 0) {
                        throw e;
                    } else {
                        r.revert();
                        r.refresh();
                        Thread.sleep(pausePerRetry);
                    }
                }
            }
        };        
    }

    /**
     * Run nodes through synthetic workflow
     * @param model Synthetic workflow model
     */
    public BiConsumer<ResourceResolver, String> startSyntheticWorkflows(final SyntheticWorkflowModel model) {
        return (ResourceResolver r, String path) -> {
            r.adaptTo(Session.class).getWorkspace().getObservationManager().setUserData("changedByWorkflowProcess");
            nameThread("synWf-" + path);
            workflowRunner.execute(r,
                    path,
                    model,
                    false,
                    false);
        };
    }

    public BiConsumer<ResourceResolver, String> withAllRenditions(
            final BiConsumer<ResourceResolver, String> action, 
            final BiFunction<ResourceResolver, String, Boolean>... filters) {
        return (ResourceResolver r, String path) -> {
            AssetManager assetManager = r.adaptTo(AssetManager.class);
            Asset asset = assetManager.getAsset(path);
            for (Iterator<? extends Rendition> renditions = asset.listRenditions(); renditions.hasNext();) {
                Rendition rendition = renditions.next();
                boolean skip = false;
                if (filters != null) {
                    for (BiFunction<ResourceResolver, String, Boolean> filter : filters) {
                        if (!filter.apply(r, rendition.getPath())) {
                            skip=true;
                            break;
                        }
                    }
                }
                if (!skip) {
                    action.accept(r, path);
                }                
            }
        };
    }
    
    /**
     * Remove all renditions except for the original rendition for assets
     * @return 
     */
    public BiConsumer<ResourceResolver, String> removeAllRenditions() {
        return (ResourceResolver r, String path) -> {
            nameThread("removeRenditions-" + path);
            AssetManager assetManager = r.adaptTo(AssetManager.class);
            Asset asset = assetManager.getAsset(path);
            for (Iterator<? extends Rendition> renditions = asset.listRenditions(); renditions.hasNext();) {
                Rendition rendition = renditions.next();
                if (!rendition.getName().equalsIgnoreCase("original")) {
                    asset.removeRendition(rendition.getName());
                }
            }
        };
    }

    /**
     * Remove all renditions with a given name
     * @param name
     * @return 
     */
    public BiConsumer<ResourceResolver, String> removeAllRenditionsNamed(final String name) {
        return (ResourceResolver r, String path) -> {
            nameThread("removeRenditions-" + path);
            AssetManager assetManager = r.adaptTo(AssetManager.class);
            Asset asset = assetManager.getAsset(path);
            for (Iterator<? extends Rendition> renditions = asset.listRenditions(); renditions.hasNext();) {
                Rendition rendition = renditions.next();
                if (rendition.getName().equalsIgnoreCase(name)) {
                    asset.removeRendition(rendition.getName());
                }
            }
        };
    }
    
    /**
     * Activate all nodes using default replicators
     * @return 
     */
    public BiConsumer<ResourceResolver, String> activateAll() {
        return (ResourceResolver r, String path) -> {
            nameThread("activate-" + path);
            replicator.replicate(r.adaptTo(Session.class), ReplicationActionType.ACTIVATE, path);
        };
    }

    /**
     * Activate all nodes using provided options
     * NOTE: If using large batch publishing it is highly recommended to set synchronous to true on the replication options
     * @param options
     * @return 
     */
    public BiConsumer<ResourceResolver, String> activateAllWithOptions(final ReplicationOptions options) {
        return (ResourceResolver r, String path) -> {
            nameThread("activate-" + path);
            replicator.replicate(r.adaptTo(Session.class), ReplicationActionType.ACTIVATE, path, options);
        };
    }

    /**
     * Activate all nodes using provided options
     * NOTE: If using large batch publishing it is highly recommended to set synchronous to true on the replication options
     * @param options
     * @return 
     */
    public BiConsumer<ResourceResolver, String> activateAllWithRoundRobin(final ReplicationOptions... options) {
        final List<ReplicationOptions> allTheOptions = Arrays.asList(options);
        final Iterator<ReplicationOptions> roundRobin = new RoundRobin(allTheOptions).iterator();
        return (ResourceResolver r, String path) -> {
            nameThread("activate-" + path);
            replicator.replicate(r.adaptTo(Session.class), ReplicationActionType.ACTIVATE, path, roundRobin.next());
        };
    }    

    /**
     * Deactivate all nodes using default replicators
     * @return 
     */
    public BiConsumer<ResourceResolver, String> deactivateAll() {
        return (ResourceResolver r, String path) -> {
            nameThread("deactivate-" + path);
            replicator.replicate(r.adaptTo(Session.class), ReplicationActionType.DEACTIVATE, path);
        };
    }

    /**
     * Deactivate all nodes using provided options
     * 
     * @param options
     * @return 
     */
    public BiConsumer<ResourceResolver, String> deactivateAllWithOptions(final ReplicationOptions options) {
        return (ResourceResolver r, String path) -> {
            nameThread("deactivate-" + path);
            replicator.replicate(r.adaptTo(Session.class), ReplicationActionType.DEACTIVATE, path, options);
        };
    }

    //-- Single work consumers (for use for single invocation using deferredWithResolver)
    /**
     * Retry a single action
     * @param retries Number of retries to attempt
     * @param pausePerRetry Milliseconds to wait between attempts
     * @param action Action to attempt
     * @return New retry wrapper around provided action
     */
    public Consumer<ResourceResolver> retry(final int retries, final long pausePerRetry, final Consumer<ResourceResolver> action) {
        return (ResourceResolver r) -> {
            int remaining = retries;
            while (remaining > 0) {
                try {
                    action.accept(r);
                    return;
                } catch (Exception e) {
                    if (remaining-- <= 0) {
                        throw e;
                    } else {
                        r.revert();
                        r.refresh();
                        Thread.sleep(pausePerRetry);
                    }
                }
            }
        };        
    }
    
    /**
     * Run a synthetic workflow on a single node
     * @param model
     * @param path
     * @return 
     */
    final public Consumer<ResourceResolver> startSyntheticWorkflow(SyntheticWorkflowModel model, String path) {
        return res -> startSyntheticWorkflows(model).accept(res, path);
    }
    
    /**
     * Remove all non-original renditions from an asset.
     * @param path
     * @return 
     */
    final public Consumer<ResourceResolver> removeRenditions(String path) {
        return res -> removeAllRenditions().accept(res, path);
    }
    
    /**
     * Remove all renditions with a given name
     * @param path
     * @param name
     * @return 
     */
    final public Consumer<ResourceResolver> removeRenditionsNamed(String path, String name) {
        return res -> removeAllRenditionsNamed(name).accept(res, path);
    }
    
    /**
     * Activate a single node.
     * @param path
     * @return 
     */
    final public Consumer<ResourceResolver> activate(String path) {
        return res -> activateAll().accept(res, path);
    }

    /**
     * Activate a single node using provided replication options.
     * @param path
     * @param options
     * @return 
     */
    final public Consumer<ResourceResolver> activateWithOptions(String path, ReplicationOptions options) {
        return res -> activateAllWithOptions(options).accept(res, path);
    }

    /**
     * Deactivate a single node.
     * @param path
     * @return 
     */
    final public Consumer<ResourceResolver> deactivate(String path) {
        return res -> deactivateAll().accept(res, path);
    }

    /**
     * Deactivate a single node using provided replication options.
     * @param path
     * @param options
     * @return 
     */
    final public Consumer<ResourceResolver> deactivateWithOptions(String path, ReplicationOptions options) {
        return res -> deactivateAllWithOptions(options).accept(res, path);
    }

    private void nameThread(String string) {
        Thread.currentThread().setName(string);
    }

    @Activate
    private void dsActivate() {}

    @Deactivate
    private void dsDeactivate() {}
}
